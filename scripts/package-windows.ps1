# Build-time tooling only. The produced release launcher never builds source.
[CmdletBinding()]
param([string]$OutputName = 'PersonalAiWorkspace-win-x64', [switch]$FrontendPrebuilt, [switch]$RuntimePrebuilt,
    [string]$AuditRuntimeJar, [string]$WriteRuntimeIdentity,
    [ValidateSet('default', 'v1-isolated-development')][string]$ExpectedRuntimeKind = 'default')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot

# These two modes only write build metadata or inspect an artifact. They never publish Desktop.
$instanceClass = 'io/github/qianlixunbai/workspace/provider/ollama/V1IsolatedOllamaInstance'
$buildIdentityEntry = 'META-INF/workspace-runtime.properties'
$instanceId = 'V1_OLLAMA_0401_ISOLATED'
$pinsSha = '17b14bc0c4390b94ebf4aaedc684291eea02531821c0633e087bfd470c3e2d21'
$owners = @('provider/ollama/OllamaProvider', 'provider/ollama/OllamaVisionEnvironment', 'security/WindowsNativeGate')

function Assert-PlainPath([string]$Path) {
    for ($part = [IO.Path]::GetFullPath($Path); $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if (((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked Runtime artifact path.' }
    }
}
function Get-StreamSha([IO.Stream]$Stream) {
    $Stream.Position = 0
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToHexString($sha.ComputeHash($Stream)).ToLowerInvariant() }
    finally { $sha.Dispose(); $Stream.Position = 0 }
}
function Read-EntryText($Entry) {
    if ($Entry.Length -gt 16384) { throw 'Oversized Runtime identity.' }
    $stream = $Entry.Open()
    $reader = [IO.StreamReader]::new($stream, [Text.UTF8Encoding]::new($false, $true))
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Read-Identity([string]$Text) {
    $result = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($line in $Text.Split("`n")) {
        if ($line -eq '') { continue }
        if ($line -cnotmatch '^([a-z][a-zA-Z]*)=([A-Za-z0-9_.:/-]+)$' -or -not $result.TryAdd($Matches[1], $Matches[2])) { throw 'Ambiguous Runtime identity.' }
    }
    return ,$result
}
function Assert-JarContent([IO.Stream]$Stream, [string]$Kind) {
    $zip = [IO.Compression.ZipArchive]::new($Stream, [IO.Compression.ZipArchiveMode]::Read, $true)
    try {
        $entries = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::OrdinalIgnoreCase)
        $devClasses = 0
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            if ($name.Contains('\') -or $name.StartsWith('/') -or $name.Contains(':') -or $name -match '(^|/)\.{1,2}(/|$)|//|[\x00-\x20%]' -or
                -not $entries.TryAdd($name, $entry)) { throw 'Ambiguous Runtime ZIP entry.' }
            if ($name -match '^io/github/qianlixunbai/workspace/' -or
                $name.StartsWith('BOOT-INF/', [StringComparison]::OrdinalIgnoreCase) -and -not $name.StartsWith('BOOT-INF/', [StringComparison]::Ordinal)) { throw 'Shadowed Runtime application content.' }
            $dev = $name -cmatch ('(^|/)' + [regex]::Escape($instanceClass) + '(\$[^/]+)?\.class$')
            if ($dev) { $devClasses++ }
            if ($Kind -eq 'default' -and ($dev -or $name -match '(?i)v1.?isolated|development.only|artifact-pins|static-distribution-manifest|META-INF/services/.*V1Isolated')) { throw 'Development instance in production Runtime.' }
            if ($name.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase)) {
                if ($entry.Length -gt 128MB) { throw 'Oversized nested Runtime dependency.' }
                $nested = $entry.Open(); $memory = [IO.MemoryStream]::new()
                try {
                    $nested.CopyTo($memory); $memory.Position = 0
                    Assert-NestedJar $memory
                } finally { $nested.Dispose(); $memory.Dispose() }
            }
        }
        $manifest = $entries['META-INF/MANIFEST.MF']
        if ($null -eq $manifest -or $manifest.FullName -cne 'META-INF/MANIFEST.MF') { throw 'Runtime manifest missing.' }
        $manifestText = (Read-EntryText $manifest) -replace '\r?\n ', ''
        $attributes = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($line in ($manifestText -split '\r?\n')) {
            if ($line -eq '') { continue }
            if ($line -cnotmatch '^([A-Za-z0-9-]+): (.+)$' -or -not $attributes.TryAdd($Matches[1], $Matches[2])) { throw 'Ambiguous Runtime manifest.' }
        }
        if ($attributes['Main-Class'] -cne 'org.springframework.boot.loader.launch.JarLauncher' -or
            $attributes['Start-Class'] -cne 'io.github.qianlixunbai.workspace.PersonalAiWorkspaceApplication' -or
            $attributes['Spring-Boot-Classes'] -cne 'BOOT-INF/classes/' -or $attributes['Spring-Boot-Lib'] -cne 'BOOT-INF/lib/') { throw 'Runtime executable owner mismatch.' }
        $marker = $entries[$buildIdentityEntry]
        if ($null -eq $marker -or $marker.FullName -cne $buildIdentityEntry -or $entries.ContainsKey('BOOT-INF/classes/' + $buildIdentityEntry)) { throw 'Runtime build identity missing or shadowed.' }
        $identity = Read-Identity (Read-EntryText $marker)
        $expectedKeys = @('format', 'kind', 'sourceSha', 'providerSha', 'environmentSha', 'gateSha')
        if ($Kind -eq 'v1-isolated-development') { $expectedKeys += @('instance', 'pinsSha', 'contractSha', 'classSha') }
        if ($identity.Count -ne $expectedKeys.Count -or @($expectedKeys | Where-Object { -not $identity.ContainsKey($_) }).Count -ne 0 -or
            $identity['format'] -cne 'WORKSPACE_RUNTIME_V1' -or $identity['kind'] -cne $Kind -or $identity['sourceSha'] -cnotmatch '^[a-f0-9]{64}$') { throw 'Runtime build identity mismatch.' }
        $hashKeys = @('providerSha', 'environmentSha', 'gateSha')
        for ($i = 0; $i -lt $owners.Count; $i++) {
            $entryName = 'BOOT-INF/classes/io/github/qianlixunbai/workspace/' + $owners[$i] + '.class'
            if (-not $entries.ContainsKey($entryName) -or $entries[$entryName].FullName -cne $entryName) { throw 'Runtime owning class missing.' }
            $s = $entries[$entryName].Open()
            try {
                $sha = [Security.Cryptography.SHA256]::Create()
                try { $actual = [Convert]::ToHexString($sha.ComputeHash($s)).ToLowerInvariant() } finally { $sha.Dispose() }
                if ($actual -cne $identity[$hashKeys[$i]]) { throw 'Runtime owner bytes mismatch.' }
            } finally { $s.Dispose() }
        }
        if ($Kind -eq 'v1-isolated-development') {
            if ($devClasses -lt 1 -or $identity['instance'] -cne $instanceId -or $identity['pinsSha'] -cne $pinsSha -or $identity['contractSha'] -cnotmatch '^[a-f0-9]{64}$') { throw 'Incomplete development Runtime.' }
            $s = $entries['BOOT-INF/classes/' + $instanceClass + '.class'].Open()
            try {
                $sha = [Security.Cryptography.SHA256]::Create()
                try { $actual = [Convert]::ToHexString($sha.ComputeHash($s)).ToLowerInvariant() } finally { $sha.Dispose() }
                if ($actual -cne $identity['classSha']) { throw 'Development class bytes mismatch.' }
            } finally { $s.Dispose() }
        } elseif ($devClasses -ne 0) { throw 'Development class in default Runtime.' }
        return $identity
    } finally { $zip.Dispose(); $Stream.Position = 0 }
}
function Assert-NestedJar([IO.Stream]$Stream) {
    $zip = [IO.Compression.ZipArchive]::new($Stream, [IO.Compression.ZipArchiveMode]::Read, $true)
    try {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            if (-not $names.Add($name) -or $name -match '\\|(^|/)\.{1,2}(/|$)|//|[\x00-\x20%:]' -or $name.StartsWith('/') -or
                $name -match 'V1Isolated|v1.?isolated|workspace-runtime\.properties|artifact-pins|static-distribution-manifest' -or
                $name -match '^io/github/qianlixunbai/workspace/' -or $name.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase)) { throw 'Shadowed or ambiguous nested Runtime content.' }
        }
    } finally { $zip.Dispose() }
}
function Open-AuditedRuntime([string]$Path, [string]$Kind) {
    Assert-PlainPath $Path
    $stream = [IO.FileStream]::new([IO.Path]::GetFullPath($Path), [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $fileId = Get-RuntimeFileIdentity $stream
        $identity = Assert-JarContent $stream $Kind
        $hash = Get-StreamSha $stream
        if ((Get-RuntimeFileIdentity $stream) -cne $fileId) { throw 'Runtime file identity changed.' }
        return [pscustomobject]@{Stream=$stream; Sha256=$hash; FileId=$fileId; Build=$identity}
    } catch { $stream.Dispose(); throw }
}
function Get-RuntimeFileIdentity([IO.FileStream]$Stream) {
    if (-not ('WorkspacePackagingFileId' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class WorkspacePackagingFileId {
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool GetFileInformationByHandle(SafeFileHandle handle, IntPtr buffer);
    public static string Read(SafeFileHandle handle) {
        IntPtr p=Marshal.AllocHGlobal(52);
        try {
            if(!GetFileInformationByHandle(handle,p) || Marshal.ReadInt32(p,40)!=1 || (Marshal.ReadInt32(p,0)&0x400)!=0)
                throw new System.IO.IOException("Runtime file identity unavailable");
            return ((uint)Marshal.ReadInt32(p,28)).ToString("x8")+":"+((uint)Marshal.ReadInt32(p,44)).ToString("x8")+((uint)Marshal.ReadInt32(p,48)).ToString("x8");
        } finally { Marshal.FreeHGlobal(p); }
    }
}
'@
    }
    return [WorkspacePackagingFileId]::Read($Stream.SafeFileHandle)
}
if ($WriteRuntimeIdentity) {
    if ($AuditRuntimeJar -or $RuntimePrebuilt -or $FrontendPrebuilt) { throw 'Mixed Runtime build modes.' }
    $classes = [IO.Path]::GetFullPath($WriteRuntimeIdentity)
    $expectedClasses = Join-Path $repo $(if ($ExpectedRuntimeKind -eq 'default') { 'target/classes' } else { 'target/v1-isolated-development/classes' })
    if ($classes -cne [IO.Path]::GetFullPath($expectedClasses)) { throw 'Unexpected Runtime output directory.' }
    $paths = @(& git -C $repo ls-files --cached --others --exclude-standard | Sort-Object -CaseSensitive -Unique)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify Runtime sources.' }
    $sources = @($paths | ForEach-Object { [ordered]@{Path=$_;Sha256=(Get-FileHash -LiteralPath (Join-Path $repo $_)).Hash.ToLowerInvariant()} })
    $sourceBytes = [Text.Encoding]::UTF8.GetBytes(($sources | ConvertTo-Json -Depth 4 -Compress) + "`n")
    $lines = @('format=WORKSPACE_RUNTIME_V1', ('kind=' + $ExpectedRuntimeKind), ('sourceSha=' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($sourceBytes)).ToLowerInvariant()))
    $hashKeys = @('providerSha', 'environmentSha', 'gateSha')
    for ($i = 0; $i -lt $owners.Count; $i++) { $lines += $hashKeys[$i] + '=' + (Get-FileHash -LiteralPath (Join-Path $classes ('io/github/qianlixunbai/workspace/' + $owners[$i] + '.class'))).Hash.ToLowerInvariant() }
    if ($ExpectedRuntimeKind -eq 'v1-isolated-development') {
        $lines += 'instance=' + $instanceId; $lines += 'pinsSha=' + $pinsSha
        $lines += 'contractSha=' + (Get-FileHash -LiteralPath (Join-Path $repo ('src/development/java/' + $instanceClass + '.java'))).Hash.ToLowerInvariant()
        $lines += 'classSha=' + (Get-FileHash -LiteralPath (Join-Path $classes ($instanceClass + '.class'))).Hash.ToLowerInvariant()
    }
    $markerPath = Join-Path $classes $buildIdentityEntry
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($markerPath)) | Out-Null
    [IO.File]::WriteAllText($markerPath, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllBytes((Join-Path ([IO.Path]::GetDirectoryName($classes)) 'source-manifest.json'), $sourceBytes)
    return
}
if ($AuditRuntimeJar) {
    if ($RuntimePrebuilt -or $FrontendPrebuilt) { throw 'Mixed Runtime audit modes.' }
    $audited = Open-AuditedRuntime $AuditRuntimeJar $ExpectedRuntimeKind
    try { Write-Output ('PASS Runtime JAR audit: ' + $ExpectedRuntimeKind + ' sha256=' + $audited.Sha256 + ' fileId=' + $audited.FileId) }
    finally { $audited.Stream.Dispose() }
    return
}
if ($ExpectedRuntimeKind -cne 'default') { throw 'Production packaging requires default Runtime.' }
if ($OutputName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$' -or $OutputName.Contains('..')) { throw 'OutputName must be a simple folder name.' }
$artifacts = Join-Path $repo 'artifacts'
$destination = Join-Path $artifacts $OutputName
if (Test-Path -LiteralPath $destination) { throw 'Output already exists. Preserve it or choose a new OutputName; no candidate is deleted.' }
for ($part = $artifacts; $part; $part = [IO.Path]::GetDirectoryName($part)) {
    if ((Test-Path -LiteralPath $part) -and ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked package output is not supported.' }
}
if (-not (Test-Path -LiteralPath $artifacts)) { New-Item -ItemType Directory -Path $artifacts | Out-Null }
$stage = Join-Path $artifacts ('.package-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
Push-Location -LiteralPath $repo
try {
    $commit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -cnotmatch '^[a-f0-9]{40}$') { throw 'Cannot identify build commit.' }
    $sourceDirty = [bool]((& git status --porcelain --untracked-files=normal) | Out-String).Trim()
    if (-not $FrontendPrebuilt) {
        & npm.cmd --prefix desktop/frontend ci --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency restore failed.' }
        & npm.cmd --prefix desktop/frontend run build
        if ($LASTEXITCODE -ne 0) { throw 'Production frontend build failed.' }
    }
    if (-not $RuntimePrebuilt) {
        & .\mvnw.cmd --batch-mode --no-transfer-progress package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Runtime packaging failed.' }
    }
    [xml]$pom = Get-Content -LiteralPath (Join-Path $repo 'pom.xml') -Raw
    $runtimeVersion = $pom.project.version
    $jarName = 'personal-ai-workspace-' + $runtimeVersion + '.jar'
    $jar = Join-Path $repo ('target\' + $jarName)
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Application Runtime JAR missing.' }
    $auditedRuntime = Open-AuditedRuntime $jar 'default'
    try {
    & dotnet publish desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj -c Release -r win-x64 --self-contained true `
        -p:FrontendSkipBuild=true -p:PublishSingleFile=false -p:PublishTrimmed=false -p:DebugType=None -p:DebugSymbols=false -o (Join-Path $stage 'desktop') --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Self-contained Desktop publish failed. No framework-dependent downgrade was attempted.' }
    $desktop = Join-Path $stage 'desktop'
    # NuGet's WebView SDK ships API documentation alongside its runtime DLLs.
    # Exclude these exact build-time documentation files, keeping runtime payload intact.
    foreach ($name in @('Microsoft.Web.WebView2.Core.xml', 'Microsoft.Web.WebView2.Wpf.xml', 'Microsoft.Web.WebView2.WinForms.xml')) {
        $documentation = Join-Path $desktop $name
        if (Test-Path -LiteralPath $documentation -PathType Leaf) { Remove-Item -LiteralPath $documentation }
    }
    $runtimeConfig = Get-Content -LiteralPath (Join-Path $desktop 'PersonalAiWorkspace.Desktop.runtimeconfig.json') -Raw | ConvertFrom-Json
    if ($runtimeConfig.runtimeOptions.PSObject.Properties['frameworks'] -or -not $runtimeConfig.runtimeOptions.PSObject.Properties['includedFrameworks'] -or
        -not (Test-Path -LiteralPath (Join-Path $desktop 'coreclr.dll')) -or -not (Test-Path -LiteralPath (Join-Path $desktop 'PresentationFramework.dll')) -or
        -not (Test-Path -LiteralPath (Join-Path $desktop 'WebView2Loader.dll'))) { throw 'Desktop publish is not complete self-contained Windows WPF/WebView2 output.' }
    New-Item -ItemType Directory -Path (Join-Path $stage 'runtime'), (Join-Path $stage 'release') | Out-Null
    $stagedJar = Join-Path $stage ('runtime\' + $jarName)
    $targetStream = [IO.FileStream]::new($stagedJar, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::Read)
    try {
        $auditedRuntime.Stream.Position = 0
        $auditedRuntime.Stream.CopyTo($targetStream); $targetStream.Flush($true)
        $null = Assert-JarContent $targetStream 'default'
        if ((Get-StreamSha $targetStream) -cne $auditedRuntime.Sha256 -or (Get-RuntimeFileIdentity $auditedRuntime.Stream) -cne $auditedRuntime.FileId) { throw 'Staged Runtime identity mismatch.' }
    } finally { $targetStream.Dispose() }
    } finally { $auditedRuntime.Stream.Dispose() }
    foreach ($name in @('start-release.ps1', 'release-functions.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('release\' + $name)) -Destination (Join-Path $stage 'release') }
    foreach ($name in @('start-workspace.cmd', 'README.txt')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('release\' + $name)) -Destination $stage }
    # A fresh whitelist-built staging tree is audited before it becomes a candidate.
    $files = @()
    foreach ($file in Get-ChildItem -LiteralPath $stage -Recurse -File -Force | Sort-Object FullName) {
        $name = $file.FullName.Substring($stage.Length + 1).Replace('\', '/')
        if ($name -match '(^|/)(\.runtime|\.git|node_modules|src|test|tests|backups?|logs?|\.verification|TestResults|MainWorkspaceWebView2)(/|$)|client-token|browser-clients|\.(db|sqlite|sqlite3|log|pdb|map|png|zip|trx)(-(wal|shm|journal))?$|backup.*\.json$|screenshot|capture|fixture|evidence' -or
            ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Forbidden personal/development artifact in package.' }
        if ($file.Extension -notin @('.exe', '.dll', '.json', '.dat', '.bin', '.txt', '.html', '.css', '.js', '.svg', '.woff2', '.jar', '.ps1', '.cmd')) { throw 'Unexpected package file type.' }
        if ($file.Extension -in @('.html', '.js', '.css', '.ps1', '.cmd', '.txt', '.json')) {
            $text = [IO.File]::ReadAllText($file.FullName)
            if ($text -match 'http://(?:localhost|127\.0\.0\.1):5173|sourceMappingURL|br1\.[a-f0-9-]{36}\.[A-Za-z0-9_-]{43}|-----BEGIN .*PRIVATE KEY-----') { throw 'Development frontend or secret material found in package.' }
        }
        $files += [ordered]@{ path = $name; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = $file.Length }
    }
    $desktopVersion = [Reflection.AssemblyName]::GetAssemblyName((Join-Path $desktop 'PersonalAiWorkspace.Desktop.dll')).Version.ToString()
    $frontendManifest = Get-Content -LiteralPath (Join-Path $desktop 'MainWorkspace\workspace-assets.json') -Raw | ConvertFrom-Json
    foreach ($entry in $frontendManifest.files.PSObject.Properties) {
        if ((Get-FileHash -LiteralPath (Join-Path $desktop ('MainWorkspace\' + $entry.Name.Replace('/', '\'))) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.Value) { throw 'Bundled frontend hash mismatch.' }
    }
    if (-not ([IO.File]::ReadAllText((Join-Path $desktop 'MainWorkspace\index.html')).Contains("connect-src 'none'"))) { throw 'Production CSP missing.' }
    $manifest = [ordered]@{ formatVersion = 1; product = 'Personal AI Workspace'; desktopVersion = $desktopVersion; runtimeVersion = $runtimeVersion;
        frontendVersion = (Get-Content -LiteralPath 'desktop/frontend/package.json' -Raw | ConvertFrom-Json).version;
        commitSha = $commit; sourceDirty = $sourceDirty; targetRid = 'win-x64'; selfContainedDesktop = $true;
        builtAtUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'); requiredJavaMajor = 21; requiredOllama = $true;
        requiredWebView2 = 'Microsoft Edge WebView2 Evergreen Runtime'; runtimeArtifact = 'runtime/' + $jarName; files = $files }
    $utf8 = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText((Join-Path $stage 'package-manifest.json'), ($manifest | ConvertTo-Json -Depth 6) + "`n", $utf8)
    $checksums = @($files | ForEach-Object { $_.sha256 + '  ' + $_.path })
    $checksums += (Get-FileHash -LiteralPath (Join-Path $stage 'package-manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant() + '  package-manifest.json'
    [IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS.txt'), ($checksums -join "`n") + "`n", $utf8)
    # System.IO avoids wildcard interpretation. Destination was checked absent.
    [IO.Directory]::Move($stage, $destination)
    Write-Host "PASS: portable win-x64 Release candidate: $destination"
    Write-Host "Desktop $desktopVersion / Runtime $runtimeVersion / commit $commit / sourceDirty $sourceDirty"
} finally { Pop-Location }
