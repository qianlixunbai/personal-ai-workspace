# Build/inspection tooling only. This manifest proves completeness, not publisher identity.
Set-StrictMode -Version Latest

function Copy-DevelopmentNotices([string]$Root, [string]$NuGetRoot, [string]$FrontendRoot) {
    # Read the exact runtime/SDK package versions in the published payload.
    $config = Get-Content -LiteralPath (Join-Path $Root 'PersonalAiWorkspace.Desktop.runtimeconfig.json') -Raw | ConvertFrom-Json
    $deps = Get-Content -LiteralPath (Join-Path $Root 'PersonalAiWorkspace.Desktop.deps.json') -Raw | ConvertFrom-Json
    $web = @($deps.libraries.PSObject.Properties.Name | Where-Object { $_ -cmatch '^Microsoft.Web.WebView2/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' })
    if ($web.Count -ne 1) { throw 'Published WebView2 package identity missing.' }
    $sources = [ordered]@{
        'WebView2-LICENSE.txt' = Join-Path $NuGetRoot ($web[0].ToLowerInvariant() + '/LICENSE.txt')
        'WebView2-NOTICE.txt' = Join-Path $NuGetRoot ($web[0].ToLowerInvariant() + '/NOTICE.txt')
        'react-LICENSE.txt' = Join-Path $FrontendRoot 'node_modules/react/LICENSE'
        'react-dom-LICENSE.txt' = Join-Path $FrontendRoot 'node_modules/react-dom/LICENSE'
    }
    foreach ($runtime in @(@('Microsoft.NETCore.App', 'microsoft.netcore.app.runtime.win-x64', 'dotnet-runtime-LICENSE.txt', 'LICENSE.TXT'),
                           @('Microsoft.WindowsDesktop.App', 'microsoft.windowsdesktop.app.runtime.win-x64', 'dotnet-desktop-LICENSE.txt', 'LICENSE'))) {
        $framework = @($config.runtimeOptions.includedFrameworks | Where-Object name -CEQ $runtime[0])
        if ($framework.Count -ne 1 -or $framework[0].version -cnotmatch '^10\.0\.[0-9]+$') { throw 'Published runtime identity missing.' }
        $package = Join-Path $NuGetRoot ($runtime[1] + '/' + $framework[0].version)
        $sources[$runtime[2]] = Join-Path $package $runtime[3]
        if ($runtime[0] -ceq 'Microsoft.NETCore.App') { $sources['dotnet-runtime-NOTICES.txt'] = Join-Path $package 'THIRD-PARTY-NOTICES.TXT' }
    }
    $notices = Join-Path $Root 'ThirdPartyNotices'
    [IO.Directory]::CreateDirectory($notices) | Out-Null
    foreach ($source in $sources.GetEnumerator()) {
        for ($part = [IO.Path]::GetFullPath($source.Value); $part; $part = [IO.Path]::GetDirectoryName($part)) {
            if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked notice source rejected.' }
        }
        $target = Join-Path $notices $source.Key
        if (Test-Path -LiteralPath $target) { throw 'Notice destination already exists.' }
        Copy-Item -LiteralPath $source.Value -Destination $target
    }
}

function Assert-DevelopmentBootstrap([string]$Directory) {
    $helper = Join-Path $Directory 'PersonalAiWorkspace.NativeBootstrap'
    foreach ($pin in @(
        # Match the already-reviewed WindowsNativeGate BOOTSTRAP constants (2d417c6).
        # These are source-controlled pins, never computed from the candidate binary.
        @('.exe', '816d253e5f707b1813eabc25a29a8ee5ba3da366fcf27e679cc7964a6c2abcf2'),
        @('.dll', '4a8536eca1f7c85e893003c3079a7bd3a7e4dffd6f9bcfbf37cd3b8bad6134fb'),
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
        'runtime/java/bin/java.exe', 'runtime/java/bin/server/jvm.dll', 'runtime/java/lib/modules', 'runtime/java/release', 'runtime/java/LICENSE', 'README.txt',
        'ThirdPartyNotices/dotnet-runtime-LICENSE.txt', 'ThirdPartyNotices/dotnet-runtime-NOTICES.txt', 'ThirdPartyNotices/dotnet-desktop-LICENSE.txt',
        'ThirdPartyNotices/WebView2-LICENSE.txt', 'ThirdPartyNotices/WebView2-NOTICE.txt', 'ThirdPartyNotices/react-LICENSE.txt', 'ThirdPartyNotices/react-dom-LICENSE.txt',
        'ThirdPartyNotices/Open-XML-SDK.txt', 'ThirdPartyNotices/jsdiff.txt', 'ThirdPartyNotices/react-error-boundary.txt')) {
        if (-not $names.Contains($path)) { throw 'Required bundle payload missing.' }
    }
    $config = Get-Content -LiteralPath (Join-Path $Root 'PersonalAiWorkspace.Desktop.runtimeconfig.json') -Raw | ConvertFrom-Json
    if ($config.runtimeOptions.PSObject.Properties['frameworks'] -or -not $config.runtimeOptions.PSObject.Properties['includedFrameworks']) { throw 'Self-contained Desktop required.' }
    # CVE-2026-111497 is fixed in .NET 10.0.14. Do not silently repackage the SDK's older default.
    foreach ($name in @('Microsoft.NETCore.App', 'Microsoft.WindowsDesktop.App')) {
        $framework = @($config.runtimeOptions.includedFrameworks | Where-Object name -CEQ $name)
        if ($framework.Count -ne 1 -or $framework[0].version -cnotmatch '^10\.0\.[0-9]+$' -or
            [version]$framework[0].version -lt [version]'10.0.14') { throw 'Bundle requires .NET 10.0.14 or a later .NET 10 servicing runtime.' }
    }
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
