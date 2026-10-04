# Build-time tooling only. The produced release launcher never builds source.
[CmdletBinding()]
param([string]$OutputName = 'PersonalAiWorkspace-win-x64', [switch]$FrontendPrebuilt, [switch]$RuntimePrebuilt)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot
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
    Copy-Item -LiteralPath $jar -Destination (Join-Path $stage ('runtime\' + $jarName))
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
