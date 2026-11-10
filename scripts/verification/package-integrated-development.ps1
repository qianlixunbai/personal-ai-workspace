# Run under the existing HeavyRun mutex / owned Job / deadline wrapper.
# Offline assembly from an audited development bundle and current tracked product sources.
# Never consumes a Profile or installs/replaces an existing application.
#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaselineDirectory,
    [Parameter(Mandatory)][string]$OutputName,
    [Parameter(Mandatory)][string]$EvidenceDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $repo
$artifacts = Join-Path $repo 'artifacts'
$BaselineDirectory = [IO.Path]::GetFullPath($BaselineDirectory)
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
if ([IO.Path]::GetDirectoryName($BaselineDirectory) -cne $artifacts -or
    [IO.Path]::GetDirectoryName($EvidenceDirectory) -cne (Join-Path $repo '.verification') -or
    $OutputName -cnotmatch '^PersonalAiWorkspace-[A-Za-z0-9-]+$') { throw 'Repository-local bundle/evidence paths required.' }
$destination = Join-Path $artifacts $OutputName
$stage = Join-Path $artifacts ('.' + $OutputName + '-stage')
foreach ($path in @($destination, ($destination + '.zip'), $stage, (Join-Path $EvidenceDirectory 'compiled'), (Join-Path $EvidenceDirectory 'libraries'))) {
    if (Test-Path -LiteralPath $path) { throw 'Fresh output required; inspect and preserve previous attempts.' }
}
foreach ($path in @($artifacts, $EvidenceDirectory)) {
    for ($part = $path; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked output rejected.' }
    }
}
. (Join-Path $repo 'scripts/distribution/development-bundle.ps1')
$baseline = Assert-DevelopmentBundle $BaselineDirectory
$commit = (& git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -cnotmatch '^[a-f0-9]{40}$' -or $baseline.commit -cnotmatch '^[a-f0-9]{40}$') { throw 'Source provenance required.' }
# This method reuses only unchanged resources, dependency JARs, Java, Bootstrap and notices.
& git diff --quiet $baseline.commit HEAD -- pom.xml workspace-application/pom.xml finance-domain src/main/resources bootstrap/PersonalAiWorkspace.NativeBootstrap bootstrap/Shared desktop/frontend/package.json desktop/frontend/package-lock.json desktop/Directory.Build.props desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj desktop/src/PersonalAiWorkspace.Core/PersonalAiWorkspace.Core.csproj
if ($LASTEXITCODE -ne 0) { throw 'Dependency/resource contract changed; use a fully reviewed rebuild.' }
& git diff --quiet HEAD -- src/main finance-domain pom.xml workspace-application desktop bootstrap scripts/package-windows.ps1 scripts/distribution/development-bundle.ps1
if ($LASTEXITCODE -ne 0) { throw 'Product sources must be committed before packaging.' }
$sourcePaths = @(& git ls-files -- src/main/java desktop/frontend/src desktop/frontend/scripts desktop/frontend/index.html desktop/frontend/vite.config.ts desktop/frontend/tsconfig.json desktop/src bootstrap scripts/package-windows.ps1)
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate tracked product sources.' }
$sources = @($sourcePaths | ForEach-Object { [ordered]@{path=$_; sha256=(Get-FileHash -LiteralPath (Join-Path $repo $_)).Hash} })
$sources | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'product-sources.json') -Encoding utf8NoBOM
$javaSources = @(& git ls-files -- 'src/main/java/*.java')
if ($LASTEXITCODE -ne 0 -or $javaSources.Count -lt 20) { throw 'Complete production Java source list required.' }
$compiled = Join-Path $EvidenceDirectory 'compiled'
$libraries = Join-Path $EvidenceDirectory 'libraries'
[void][IO.Directory]::CreateDirectory($compiled)
[void][IO.Directory]::CreateDirectory($libraries)
$originalJar = Join-Path $BaselineDirectory 'runtime/personal-ai-workspace.jar'
$archive = [IO.Compression.ZipFile]::OpenRead($originalJar)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -cmatch '^BOOT-INF/lib/([A-Za-z0-9_.+-]+\.jar)$') {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $libraries $Matches[1]), $false)
        }
    }
} finally { $archive.Dispose() }
$argsFile = Join-Path $EvidenceDirectory 'java-sources.txt'
[IO.File]::WriteAllLines($argsFile, $javaSources, [Text.UTF8Encoding]::new($false))
$javaBin = Join-Path $BaselineDirectory 'runtime/java/bin'
& (Join-Path $javaBin 'javac.exe') -J-Xmx384m -J-XX:ActiveProcessorCount=2 --release 21 -parameters -encoding UTF-8 -cp (Join-Path $libraries '*') -d $compiled ('@' + $argsFile) *> (Join-Path $EvidenceDirectory 'javac.log')
if ($LASTEXITCODE -ne 0) { throw 'Complete product Java compilation failed; inspect javac.log.' }
# The existing audited provenance writer accepts only its canonical generated output.
foreach ($owner in @('provider/ollama/OllamaProvider', 'provider/ollama/OllamaVisionEnvironment', 'security/WindowsNativeGate')) {
    $relative = 'io/github/qianlixunbai/workspace/' + $owner + '.class'
    $target = Join-Path $repo ('target/classes/' + $relative)
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
    [IO.File]::Copy((Join-Path $compiled $relative), $target, $true)
}
& (Join-Path $repo 'scripts/package-windows.ps1') -WriteRuntimeIdentity (Join-Path $repo 'target/classes') -ExpectedRuntimeKind default
$identity = Join-Path $repo 'target/classes/META-INF/workspace-runtime.properties'
$runtimeJar = Join-Path $EvidenceDirectory 'integrated-runtime.jar'
$archive = [IO.Compression.ZipFile]::Open($runtimeJar, [IO.Compression.ZipArchiveMode]::Create)
$originalArchive = [IO.Compression.ZipFile]::OpenRead($originalJar)
try {
    # Build a fresh archive. Updating a JAR in place can retain invalid local-header offsets.
    # Nested Boot dependency JARs must be STORED for the executable loader.
    foreach ($entry in $originalArchive.Entries) {
        if (($entry.FullName.StartsWith('BOOT-INF/classes/') -and $entry.FullName.EndsWith('.class')) -or
            $entry.FullName -ceq 'META-INF/workspace-runtime.properties') { continue }
        $copy = $archive.CreateEntry($entry.FullName, [IO.Compression.CompressionLevel]::NoCompression)
        $inputStream = $entry.Open(); $outputStream = $copy.Open()
        try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
    }
    $classes = @(Get-ChildItem -LiteralPath $compiled -Recurse -File -Filter '*.class')
    foreach ($file in $classes) {
        $relative = $file.FullName.Substring($compiled.Length + 1).Replace('\', '/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, ('BOOT-INF/classes/' + $relative))
    }
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $identity, 'META-INF/workspace-runtime.properties')
} finally { $archive.Dispose(); $originalArchive.Dispose() }
# Reopen every actual entry before Desktop embeds the resulting JAR pin.
$archive = [IO.Compression.ZipFile]::OpenRead($runtimeJar)
$originalArchive = [IO.Compression.ZipFile]::OpenRead($originalJar)
$verifiedClasses = 0; $verifiedResources = 0
try {
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $archive.Entries) {
        if (-not $names.Add($entry.FullName)) { throw 'Duplicate Runtime entry.' }
        $stream = $entry.Open()
        try { $actualHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) } finally { $stream.Dispose() }
        if ($entry.FullName.StartsWith('BOOT-INF/classes/') -and $entry.FullName.EndsWith('.class')) {
            $file = Join-Path $compiled $entry.FullName.Substring('BOOT-INF/classes/'.Length)
            if ($actualHash -cne (Get-FileHash -LiteralPath $file).Hash) { throw 'Compiled class differs.' }
            $verifiedClasses++
        } elseif ($entry.FullName -ceq 'META-INF/workspace-runtime.properties') {
            if ($actualHash -cne (Get-FileHash -LiteralPath $identity).Hash) { throw 'Build identity differs.' }
        } else {
            $originalEntry = $originalArchive.GetEntry($entry.FullName)
            if (-not $originalEntry) { throw 'Unexpected Runtime resource.' }
            $stream = $originalEntry.Open()
            try { $expectedHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) } finally { $stream.Dispose() }
            if ($actualHash -cne $expectedHash) { throw 'Dependency/resource bytes changed.' }
            if ($entry.FullName.StartsWith('BOOT-INF/lib/') -and $entry.FullName.EndsWith('.jar') -and $entry.CompressedLength -ne $entry.Length) { throw 'Nested dependency must be stored.' }
            $verifiedResources++
        }
    }
    $originalResources = @($originalArchive.Entries | Where-Object {
        -not ($_.FullName.StartsWith('BOOT-INF/classes/') -and $_.FullName.EndsWith('.class')) -and $_.FullName -cne 'META-INF/workspace-runtime.properties'
    }).Count
    if ($verifiedClasses -ne $classes.Count -or $verifiedResources -ne $originalResources) { throw 'Runtime inventory differs.' }
} finally { $archive.Dispose(); $originalArchive.Dispose() }
[ordered]@{passed=$true;compiledClasses=$verifiedClasses;unchangedEntries=$verifiedResources;noObsoleteApplicationClasses=$true;allLocalHeadersReadable=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'runtime-content-audit.json') -Encoding utf8NoBOM
& (Join-Path $repo 'scripts/package-windows.ps1') -AuditRuntimeJar $runtimeJar -ExpectedRuntimeKind default
$env:NODE_OPTIONS = '--max-old-space-size=384'
& npm.cmd --prefix desktop/frontend run build *> (Join-Path $EvidenceDirectory 'frontend.log')
if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
[void][IO.Directory]::CreateDirectory($stage)
# Copy only manifest-listed files. New frontend files come exclusively from this build.
foreach ($file in $baseline.files) {
    if ($file.path.StartsWith('MainWorkspace/')) { continue }
    $target = Join-Path $stage $file.path
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))
    [IO.File]::Copy((Join-Path $BaselineDirectory $file.path), $target, $false)
}
[IO.File]::Copy($runtimeJar, (Join-Path $stage 'runtime/personal-ai-workspace.jar'), $true)
$pins = @(Get-ChildItem -LiteralPath (Join-Path $stage 'runtime') -Recurse -File | Sort-Object FullName | ForEach-Object {
    [ordered]@{Path=$_.FullName.Substring($stage.Length+1).Replace('\','/');Sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
$manifest = Join-Path $EvidenceDirectory 'integrated-sources.local.json'
[ordered]@{Format=3;JavaHome='runtime/java';RuntimeJar='runtime/personal-ai-workspace.jar';JnaJar='runtime/jna-5.17.0.jar';Files=$pins} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifest -Encoding utf8NoBOM
$env:DOTNET_PROCESSOR_COUNT = '2'
$env:DOTNET_GCHeapHardLimit = '0x30000000'
$env:MSBUILDDISABLENODEREUSE = '1'
& dotnet publish desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj --no-restore -c Debug -r win-x64 --self-contained true '-p:Mmf3ProfileAcceptanceBuild=true' '-p:Mmf3AcceptanceBuild=false' '-p:FrontendSkipBuild=true' "-p:TrustedDevelopmentManifest=$manifest" '-p:RuntimeFrameworkVersion=10.0.14' '-p:PublishSingleFile=false' '-p:PublishTrimmed=false' '-p:DebugType=None' '-p:DebugSymbols=false' '-p:UseSharedCompilation=false' '-m:1' '-nodeReuse:false' -o $stage -v minimal *> (Join-Path $EvidenceDirectory 'desktop.log')
if ($LASTEXITCODE -ne 0) { throw 'Desktop publish failed.' }
[IO.File]::Copy((Join-Path $repo 'scripts/distribution/README-development.txt'), (Join-Path $stage 'README.txt'), $true)
foreach ($source in $sources) {
    if ((Get-FileHash -LiteralPath (Join-Path $repo $source.path)).Hash -cne $source.sha256) { throw 'Product source changed during build.' }
}
if ((& git rev-parse HEAD).Trim() -cne $commit) { throw 'Source commit changed during build.' }
$payload = @(Get-ChildItem -LiteralPath $stage -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
    [ordered]@{path=$_.FullName.Substring($stage.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant();bytes=$_.Length}
})
[ordered]@{format='WORKSPACE_DEVELOPMENT_BUNDLE_V1';trust='DEVELOPMENT_ONLY';productionGo=$false;installer=$false;updatesEnabled=$false;rid='win-x64';commit=$commit;sourceDirty=[bool]((& git status --porcelain)|Out-String).Trim();requiredNativeHelperRuntime='Microsoft.NETCore.App 10.0.x x64';requiredWebView2='Microsoft Edge WebView2 Evergreen Runtime';files=$payload} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $stage 'development-bundle.json') -Encoding utf8NoBOM
[void](Assert-DevelopmentBundle $stage)
$assembly = [Reflection.Assembly]::Load([IO.File]::ReadAllBytes((Join-Path $stage 'PersonalAiWorkspace.Desktop.dll')))
$reader = [IO.StreamReader]::new($assembly.GetManifestResourceStream('TrustedDevelopmentRuntimeSources'))
try { $embedded = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
if ($embedded.Files.Count -ne $pins.Count) { throw 'Embedded pin count differs.' }
foreach ($file in $embedded.Files) {
    if ((Get-FileHash -LiteralPath (Join-Path $stage $file.Path)).Hash.ToLowerInvariant() -cne $file.Sha256) { throw 'Embedded Runtime pin differs.' }
}
# Both move endpoints are fresh direct children of artifacts; never move an old bundle.
if ([IO.Path]::GetDirectoryName($stage) -cne $artifacts -or [IO.Path]::GetDirectoryName($destination) -cne $artifacts) { throw 'Unsafe delivery move.' }
[IO.Directory]::Move($stage, $destination)
[IO.Compression.ZipFile]::CreateFromDirectory($destination, ($destination + '.zip'), [IO.Compression.CompressionLevel]::Fastest, $false)
$zip = [IO.Compression.ZipFile]::OpenRead($destination + '.zip')
try {
    if ($zip.Entries.Count -ne $payload.Count + 1) { throw 'Archive count differs.' }
    foreach ($entry in $zip.Entries) {
        $stream = $entry.Open()
        try {
            if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) -cne (Get-FileHash -LiteralPath (Join-Path $destination $entry.FullName)).Hash) { throw 'Archive content differs.' }
        } finally { $stream.Dispose() }
    }
} finally { $zip.Dispose() }
[ordered]@{passed=$true;sourceCommit=$commit;baselineCommit=$baseline.commit;directory=$destination;zip=($destination+'.zip');zipSha256=(Get-FileHash -LiteralPath ($destination+'.zip')).Hash;runtimeSha256=(Get-FileHash -LiteralPath $runtimeJar).Hash;desktopSha256=(Get-FileHash -LiteralPath (Join-Path $destination 'PersonalAiWorkspace.Desktop.dll')).Hash;javaSources=$javaSources.Count;compiledClasses=$classes.Count;payload=$payload.Count;zipEntries=($payload.Count+1);embeddedPins=$pins.Count;allProductSourcesStable=$true;productionGo=$false;fullDesktopAcceptance=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'delivery-result.json') -Encoding utf8NoBOM
