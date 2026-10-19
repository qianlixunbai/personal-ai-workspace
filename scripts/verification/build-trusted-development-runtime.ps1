#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaHome, [string]$DistributionName = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $repo
$JavaHome = [IO.Path]::GetFullPath($JavaHome).TrimEnd('\')
if (!$IsWindows -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Explicit local JDK 21 required.' }
$release = Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
if ($release -notmatch '(?m)^JAVA_VERSION="21(?:\.|\")') { throw 'JDK 21 required.' }
. (Join-Path $repo 'scripts/distribution/development-bundle.ps1')
Assert-DevelopmentBootstrap (Join-Path $repo 'bootstrap/PersonalAiWorkspace.NativeBootstrap/bin/Release/net10.0-windows')
$stage = $null
if ($DistributionName) {
    if ($DistributionName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$' -or $DistributionName.Contains('..')) { throw 'Simple DistributionName required.' }
    $artifacts = Join-Path $repo 'artifacts'
    $destination = Join-Path $artifacts $DistributionName
    if ((Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath ($destination + '.zip'))) { throw 'Distribution already exists; choose a new name. Nothing is overwritten.' }
    for ($part = $artifacts; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ((Test-Path -LiteralPath $part) -and ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked output rejected.' }
    }
    $stage = Join-Path $artifacts ('.development-package-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($stage) | Out-Null
}
# Explicit local-source/toolchain trust at build time. No runtime-supplied expected hashes.
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    & (Join-Path $repo 'mvnw.cmd') -B -ntp -pl workspace-application -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Runtime package failed.' }
} finally { $env:JAVA_HOME = $previousJavaHome }
$jar = Join-Path $repo 'target/personal-ai-workspace-0.1.0.jar'
& (Join-Path $repo 'scripts/package-windows.ps1') -AuditRuntimeJar $jar -ExpectedRuntimeKind default
$jnaJar = Join-Path $env:USERPROFILE '.m2/repository/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar'
Push-Location (Join-Path $repo 'desktop/frontend')
try { & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' } } finally { Pop-Location }
$manifest = Join-Path $repo '.verification/trusted-development-runtime-sources.local.json'
$format = 2
$sourceJava = $JavaHome; $sourceJar = $jar; $sourceJna = $jnaJar
if ($stage) {
    # Explicitly reviewed local JDK only; no binary download, jlink mutation or installer execution.
    # Include upstream legal notices and sources in the local prototype.
    foreach ($inputPath in @($JavaHome, $jnaJar)) {
        for ($part = $inputPath; $part; $part = [IO.Path]::GetDirectoryName($part)) {
            if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked distribution input rejected.' }
        }
    }
    foreach ($item in Get-ChildItem -LiteralPath $JavaHome -Recurse -Force) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked JDK source rejected.' }
    }
    $runtime = Join-Path $stage 'runtime'
    $sourceJava = Join-Path $runtime 'java'
    [IO.Directory]::CreateDirectory($sourceJava) | Out-Null
    foreach ($name in @('bin', 'lib', 'conf', 'legal', 'jmods', 'include', 'release', 'LICENSE', 'README')) {
        Copy-Item -LiteralPath (Join-Path $JavaHome $name) -Destination $sourceJava -Recurse
    }
    $sourceJar = Join-Path $runtime 'personal-ai-workspace.jar'; $sourceJna = Join-Path $runtime 'jna-5.17.0.jar'
    Copy-Item -LiteralPath $jar -Destination $sourceJar
    Copy-Item -LiteralPath $jnaJar -Destination $sourceJna
    & (Join-Path $repo 'scripts/package-windows.ps1') -AuditRuntimeJar $sourceJar -ExpectedRuntimeKind default
    $manifest = Join-Path $repo ('.verification/' + $DistributionName + '-sources.local.json')
    $format = 3
}
$paths = @((Join-Path $sourceJava 'release'), $sourceJar, $sourceJna)
if ($stage) { $paths = @(Get-ChildItem -LiteralPath (Join-Path $stage 'runtime') -File -Recurse | Select-Object -ExpandProperty FullName) }
else {
    foreach ($folder in @('bin', 'lib', 'conf')) {
        $paths += @(Get-ChildItem -LiteralPath (Join-Path $JavaHome $folder) -File -Recurse | Where-Object Name -ne 'src.zip' | Select-Object -ExpandProperty FullName)
    }
}
$files = @(foreach ($path in ($paths | Sort-Object -Unique)) {
    $absolute = [IO.Path]::GetFullPath($path)
    for ($part = $absolute; $null -ne $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked development source rejected.' }
    }
    $name = if ($stage) { $absolute.Substring($stage.Length + 1).Replace('\', '/') } else { $absolute }
    [ordered]@{ Path=$name; Sha256=(Get-FileHash -LiteralPath $absolute).Hash.ToLowerInvariant() }
})
[IO.Directory]::CreateDirectory((Split-Path -Parent $manifest)) | Out-Null
if ($stage) { $sourceJava = 'runtime/java'; $sourceJar = 'runtime/personal-ai-workspace.jar'; $sourceJna = 'runtime/jna-5.17.0.jar' }
[ordered]@{Format=$format; JavaHome=$sourceJava; RuntimeJar=$sourceJar; JnaJar=$sourceJna; Files=$files} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifest -Encoding utf8NoBOM
if ((Get-Item -LiteralPath $manifest).Length -gt 131072) { throw 'Source manifest exceeds Desktop bound.' }
if ($stage) {
    # Keep the exact original Gate/helper bytes and its external .NET 10 prerequisite.
    & dotnet publish (Join-Path $repo 'desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj') -c Debug -r win-x64 --self-contained true `
        '-p:Mmf3ProfileAcceptanceBuild=true' '-p:Mmf3AcceptanceBuild=false' '-p:FrontendSkipBuild=true' "-p:TrustedDevelopmentManifest=$manifest" `
        '-p:PublishSingleFile=false' '-p:PublishTrimmed=false' '-p:DebugType=None' '-p:DebugSymbols=false' -o $stage -v minimal
    if ($LASTEXITCODE -ne 0) { throw 'Development Desktop publish failed.' }
    Copy-Item -LiteralPath (Join-Path $repo 'scripts/distribution/README-development.txt') -Destination (Join-Path $stage 'README.txt')
    $payload = @(Get-ChildItem -LiteralPath $stage -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
        [ordered]@{path=$_.FullName.Substring($stage.Length + 1).Replace('\', '/'); sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant(); bytes=$_.Length}
    })
    [ordered]@{format='WORKSPACE_DEVELOPMENT_BUNDLE_V1'; trust='DEVELOPMENT_ONLY'; productionGo=$false; installer=$false; updatesEnabled=$false;
        rid='win-x64'; commit=(& git rev-parse HEAD).Trim(); sourceDirty=[bool]((& git status --porcelain) | Out-String).Trim();
        requiredNativeHelperRuntime='Microsoft.NETCore.App 10.0.x x64'; requiredWebView2='Microsoft Edge WebView2 Evergreen Runtime'; files=$payload
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $stage 'development-bundle.json') -Encoding utf8NoBOM
    Assert-DevelopmentBundle $stage | Out-Null
    # Both resolved paths are task-owned children of artifacts; no existing directory is moved/deleted.
    if ([IO.Path]::GetDirectoryName($stage) -cne $artifacts -or [IO.Path]::GetDirectoryName($destination) -cne $artifacts) { throw 'Unsafe bundle destination.' }
    [IO.Directory]::Move($stage, $destination)
    [IO.Compression.ZipFile]::CreateFromDirectory($destination, $destination + '.zip', [IO.Compression.CompressionLevel]::Fastest, $false)
    [ordered]@{result='BUILT_DEVELOPMENT_BUNDLE'; directory=$destination; archive=$destination + '.zip'; archiveSha256=(Get-FileHash -LiteralPath ($destination + '.zip')).Hash.ToLowerInvariant(); productionGo=$false; installer=$false} | ConvertTo-Json
    return
}
& dotnet build (Join-Path $repo 'desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj') --no-restore '-p:Mmf3ProfileAcceptanceBuild=true' '-p:Mmf3AcceptanceBuild=false' '-p:FrontendSkipBuild=true' "-p:TrustedDevelopmentManifest=$manifest" -v minimal
if ($LASTEXITCODE -ne 0) { throw 'Profile Desktop build failed.' }
[ordered]@{result='BUILT_DEVELOPMENT_ONLY'; head=(& git rev-parse HEAD);manifestSha256=(Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant();jarSha256=(Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant();sources=$files.Count;productionGo=$false} | ConvertTo-Json
