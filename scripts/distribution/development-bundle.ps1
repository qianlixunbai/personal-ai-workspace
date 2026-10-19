# Build/inspection tooling only. This manifest proves completeness, not publisher identity.
Set-StrictMode -Version Latest

function Assert-DevelopmentBootstrap([string]$Directory) {
    $helper = Join-Path $Directory 'PersonalAiWorkspace.NativeBootstrap'
    foreach ($pin in @(
        @('.exe', '0e7fc8765413aff6568b788023e705897721c1cb3365b7996b75a3188edfcbe4'),
        @('.dll', 'e434a3653f23774dc5b1a98b253ed413504d4e91db137aab14f41a5464deb50d'),
        @('.deps.json', '27af3a8da9f3b6155d86236c59339053f077e3ad33724e8f5e40281fb0375885'),
        @('.runtimeconfig.json', '3e965cd7cfd553c2ff4e842d8d8019aaae2da0a21d92ff85a87573dd8d1b95c6'))) {
        $path = $helper + $pin[0]
        for ($part = $path; $part; $part = [IO.Path]::GetDirectoryName($part)) {
            if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked NativeBootstrap rejected.' }
        }
        if ((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $pin[1]) { throw 'Reviewed Development-only NativeBootstrap pin mismatch.' }
    }
}

function Assert-DevelopmentBundle([string]$Root) {
    $Root = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    for ($part = $Root; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked bundle path rejected.' }
    }
    $items = @(Get-ChildItem -LiteralPath $Root -Recurse -Force)
    if (@($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'Linked bundle content rejected.' }
    $metadata = Join-Path $Root 'development-bundle.json'
    if ((Get-Item -LiteralPath $metadata).Length -gt 1MB) { throw 'Oversized bundle manifest.' }
    $manifest = Get-Content -LiteralPath $metadata -Raw | ConvertFrom-Json
    if ($manifest.format -cne 'WORKSPACE_DEVELOPMENT_BUNDLE_V1' -or $manifest.trust -cne 'DEVELOPMENT_ONLY' -or
        $manifest.productionGo -isnot [bool] -or $manifest.productionGo -or $manifest.installer -isnot [bool] -or $manifest.installer -or
        $manifest.updatesEnabled -isnot [bool] -or $manifest.updatesEnabled -or $manifest.rid -cne 'win-x64' -or
        $manifest.files.Count -lt 10 -or $manifest.files.Count -gt 4096) { throw 'Invalid development bundle manifest.' }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest.files) {
        if ($entry.path -cnotmatch '^[A-Za-z0-9_./+-]+$' -or $entry.path.Split('/') -contains '..' -or
            $entry.path.Split('/') -contains '.' -or $entry.path.StartsWith('/') -or $entry.path.Contains('//') -or
            -not $names.Add($entry.path) -or $entry.sha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid bundle file path/hash.' }
        $file = Join-Path $Root $entry.path
        if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).Length -ne $entry.bytes -or
            (Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -cne $entry.sha256) { throw 'Bundle incomplete or modified.' }
    }
    foreach ($item in $items) {
        $path = $item.FullName.Substring($Root.Length + 1).Replace('\', '/')
        if ($path -match '(^|/)(data|credentials|model-state|webview2-profile|logs?|backups?|evidence|\.git|\.verification|node_modules)(/|$)|client-token|database\.dpapi|\.(db|sqlite|sqlite3|pdb|log|trx)(-|$)') { throw 'User/build state must stay outside the bundle.' }
        if (-not $item.PSIsContainer -and $path -cne 'development-bundle.json' -and -not $names.Contains($path)) { throw 'Unlisted bundle content.' }
    }
    foreach ($path in @('PersonalAiWorkspace.Desktop.exe', 'PersonalAiWorkspace.Desktop.dll', 'coreclr.dll', 'PresentationFramework.dll',
        'WebView2Loader.dll', 'MainWorkspace/workspace-assets.json', 'NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.exe',
        'NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.dll', 'NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.deps.json',
        'NativeBootstrap/PersonalAiWorkspace.NativeBootstrap.runtimeconfig.json', 'runtime/personal-ai-workspace.jar', 'runtime/jna-5.17.0.jar',
        'runtime/java/bin/java.exe', 'runtime/java/bin/server/jvm.dll', 'runtime/java/lib/modules', 'runtime/java/release', 'runtime/java/LICENSE', 'README.txt')) {
        if (-not $names.Contains($path)) { throw 'Required bundle payload missing.' }
    }
    $config = Get-Content -LiteralPath (Join-Path $Root 'PersonalAiWorkspace.Desktop.runtimeconfig.json') -Raw | ConvertFrom-Json
    if ($config.runtimeOptions.PSObject.Properties['frameworks'] -or -not $config.runtimeOptions.PSObject.Properties['includedFrameworks']) { throw 'Self-contained Desktop required.' }
    # Native Gate pins the reviewed Development-only framework-dependent helper.
    # Its explicit .NET prerequisite is retained; sidecar manifests cannot repin it.
    Assert-DevelopmentBootstrap (Join-Path $Root 'NativeBootstrap')
    $assets = Get-Content -LiteralPath (Join-Path $Root 'MainWorkspace/workspace-assets.json') -Raw | ConvertFrom-Json
    foreach ($asset in $assets.files.PSObject.Properties) {
        $name = 'MainWorkspace/' + $asset.Name
        if (-not $names.Contains($name) -or (Get-FileHash -LiteralPath (Join-Path $Root $name)).Hash.ToLowerInvariant() -cne $asset.Value) { throw 'Frontend integrity mismatch.' }
    }
    if (-not ([IO.File]::ReadAllText((Join-Path $Root 'MainWorkspace/index.html')).Contains("connect-src 'none'"))) { throw 'Bundled production CSP required.' }
    return $manifest
}
