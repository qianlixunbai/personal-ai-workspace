#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$PackageDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$PackageDirectory = [IO.Path]::GetFullPath($PackageDirectory).TrimEnd('\')
. (Join-Path $PSScriptRoot 'development-bundle.ps1')
Assert-DevelopmentBundle $PackageDirectory | Out-Null
$archive = $PackageDirectory + '.zip'
$originalHash = (Get-FileHash -LiteralPath $archive).Hash
$owned = Join-Path $repo ('.verification/distribution-contract-' + [Guid]::NewGuid().ToString('N'))
$bundle = Join-Path $owned '搬迁验收包 with spaces'
$probe = Join-Path $owned 'probe-build'
$checks = [Collections.Generic.List[string]]::new()
function Expect-Rejected([scriptblock]$Action, [string]$Name) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "Expected rejection: $Name" }
    $checks.Add($Name)
}
function Assert-SourceProbe([int]$Code) {
    # The fixture contains self-contained Desktop's hostfxr. Use the explicit
    # developer dotnet host for this framework-dependent test harness only.
    $output = & dotnet exec (Join-Path $bundle 'PersonalAiWorkspace.Desktop.Tests.dll') --verify-package-sources
    if ($LASTEXITCODE -ne $Code -or ($Code -eq 0 -and $output -cne 'DEVELOPMENT_PACKAGE_SOURCES_PASS') -or
        ($Code -ne 0 -and $output -cne 'DEVELOPMENT_PACKAGE_SOURCES_STOP')) { throw 'Unexpected source probe result.' }
}
try {
    [IO.Directory]::CreateDirectory($owned) | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $bundle)
    Assert-DevelopmentBundle $bundle | Out-Null
    $checks.Add('actual-zip-extract-relocate-full-integrity')
    $extra = Join-Path $bundle 'data'
    [IO.Directory]::CreateDirectory($extra) | Out-Null
    Expect-Rejected { Assert-DevelopmentBundle $bundle } 'even-empty-user-data-directory-rejected'
    [IO.Directory]::Delete($extra)
    $metadata = Join-Path $bundle 'development-bundle.json'
    $original = [IO.File]::ReadAllBytes($metadata)
    try {
        $manifest = [Text.Encoding]::UTF8.GetString($original) | ConvertFrom-Json
        $manifest.files[0].path = '../outside'
        $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadata
        Expect-Rejected { Assert-DevelopmentBundle $bundle } 'manifest-traversal-rejected'
        $manifest = [Text.Encoding]::UTF8.GetString($original) | ConvertFrom-Json
        $manifest.productionGo = $true
        $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadata
        Expect-Rejected { Assert-DevelopmentBundle $bundle } 'production-relabel-rejected'
    } finally { [IO.File]::WriteAllBytes($metadata, $original) }
    $jar = Join-Path $bundle 'runtime/personal-ai-workspace.jar'
    $stream = [IO.File]::Open($jar, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try { $originalByte = $stream.ReadByte(); $stream.Position = 0; $stream.WriteByte($originalByte -bxor 1) }
    finally { $stream.Dispose() }
    Expect-Rejected { Assert-DevelopmentBundle $bundle } 'modified-runtime-rejected-by-completeness-check'
    $stream = [IO.File]::OpenWrite($jar)
    try { $stream.WriteByte($originalByte) } finally { $stream.Dispose() }
    $sourceManifest = Join-Path $repo ('.verification/' + [IO.Path]::GetFileName($PackageDirectory) + '-sources.local.json')
    & dotnet build (Join-Path $repo 'desktop/tests/PersonalAiWorkspace.Desktop.Tests/PersonalAiWorkspace.Desktop.Tests.csproj') --no-restore `
        '-p:RuntimeLifecycleProbe=true' '-p:Mmf3ProfileAcceptanceBuild=true' '-p:Mmf3AcceptanceBuild=false' '-p:FrontendSkipBuild=true' `
        "-p:TrustedDevelopmentManifest=$sourceManifest" -o $probe -v minimal
    if ($LASTEXITCODE -ne 0) { throw 'Packaging source probe build failed.' }
    # Add test tooling only to this disposable extracted copy. Keep the actual
    # packaged Desktop assembly/resource and dependencies, never replace them with test builds.
    foreach ($file in Get-ChildItem -LiteralPath $probe -File) {
        $target = Join-Path $bundle $file.Name
        if (-not (Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath $file.FullName -Destination $target }
    }
    Assert-SourceProbe 0
    $checks.Add('actual-embedded-relative-sources-open-after-relocation')
    # The real Open() holds deny-write/delete handles, and refuses the changed JAR
    # even after an attacker rewrites the unsigned completeness sidecar.
    $stream = [IO.File]::Open($jar, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try { $originalByte = $stream.ReadByte(); $stream.Position = 0; $stream.WriteByte($originalByte -bxor 1) }
    finally { $stream.Dispose() }
    $manifest = [Text.Encoding]::UTF8.GetString($original) | ConvertFrom-Json
    ($manifest.files | Where-Object path -CEQ 'runtime/personal-ai-workspace.jar').sha256 = (Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant()
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $metadata
    Assert-SourceProbe 10
    $checks.Add('modified-runtime-still-rejected-by-embedded-pin-after-sidecar-rehash')
    if ((Get-FileHash -LiteralPath $archive).Hash -cne $originalHash) { throw 'Original archive changed.' }
    [ordered]@{result='PASS'; scope='PACKAGING_CONTRACT_ONLY_NO_WINDOWS_E2E'; checks=$checks} | ConvertTo-Json -Depth 3
} finally {
    $absolute = [IO.Path]::GetFullPath($owned)
    $verification = [IO.Path]::GetFullPath((Join-Path $repo '.verification'))
    if ([IO.Path]::GetDirectoryName($absolute) -cne $verification -or [IO.Path]::GetFileName($absolute) -notlike 'distribution-contract-*') { throw 'Unsafe fixture cleanup.' }
    if (Test-Path -LiteralPath $absolute) { Remove-Item -LiteralPath $absolute -Recurse -Force }
}
