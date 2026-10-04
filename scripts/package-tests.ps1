[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PackageDirectory)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1')
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Management\Microsoft.PowerShell.Management.psd1')
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Security\Microsoft.PowerShell.Security.psd1')
Add-Type -AssemblyName System.Net.Http
. (Join-Path $PackageDirectory 'release\release-functions.ps1')
$checks = [Collections.Generic.List[string]]::new()
function Expect-Rejected([scriptblock]$Action, [string]$Name) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "Gate failed: $Name" }; $checks.Add($Name)
}
$owned = Join-Path ([IO.Path]::GetTempPath()) ('workspace-m5e-package-tests-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $owned | Out-Null
try {
    $bundle = Join-Path $owned 'Package 产品 with spaces'
    Copy-Item -LiteralPath $PackageDirectory -Destination $bundle -Recurse
    Assert-ReleaseBundle $bundle | Out-Null; $checks.Add('relocated-full-manifest-valid')
    $asset = Join-Path $bundle 'desktop\MainWorkspace\index.html'
    $original = [IO.File]::ReadAllBytes($asset)
    try { [IO.File]::AppendAllText($asset, 'changed'); Expect-Rejected { Assert-ReleaseBundle $bundle } 'modified-frontend-fails-closed' }
    finally { [IO.File]::WriteAllBytes($asset, $original) }
    $extra = Join-Path $bundle 'client-token'; [IO.File]::WriteAllText($extra, 'not-a-credential')
    try { Expect-Rejected { Assert-ReleaseBundle $bundle } 'unlisted-private-payload-rejected' } finally { Remove-Item -LiteralPath $extra }
    $manifestFile = Join-Path $bundle 'package-manifest.json'; $manifestBytes = [IO.File]::ReadAllBytes($manifestFile)
    try {
        $manifest = [Text.Encoding]::UTF8.GetString($manifestBytes) | ConvertFrom-Json; $manifest.runtimeArtifact = '../outside.jar'
        [IO.File]::WriteAllText($manifestFile, ($manifest | ConvertTo-Json -Depth 6))
        Expect-Rejected { Assert-ReleaseBundle $bundle } 'runtime-artifact-traversal-rejected'
    } finally { [IO.File]::WriteAllBytes($manifestFile, $manifestBytes) }
    Expect-Rejected { Assert-ReleaseLocation (Join-Path $bundle 'state') $bundle } 'package-state-rejected'
    Expect-Rejected { Assert-ReleaseLocation $owned $bundle } 'package-ancestor-state-rejected'
    Expect-Rejected { Assert-ReleaseLocation (Join-Path $owned 'logs\data') $bundle } 'data-in-logs-rejected'
    $project = Join-Path $owned 'source-repo'; New-Item -ItemType Directory -Path $project | Out-Null
    [IO.File]::WriteAllText((Join-Path $project 'pom.xml'), '<project/>')
    Expect-Rejected { Assert-ReleaseLocation (Join-Path $project 'data') $bundle } 'repository-state-rejected'
    $private = Join-Path $owned 'private-state'; New-Item -ItemType Directory -Path $private | Out-Null; Set-ReleasePrivateAcl $private $true
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $private
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $user.Value -or
        @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]) | Where-Object { $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -ne $user.Value }).Count -gt 0) { throw 'Private ACL gate failed.' }
    $checks.Add('owner-only-state-acl')
    $token = Join-Path $private 'client-token'; [IO.File]::WriteAllText($token, 'bad'); Set-ReleasePrivateAcl $token $false
    Expect-Rejected { Read-ReleaseToken $token } 'malformed-private-credential-rejected'
    [IO.File]::WriteAllText($token, ('x' * 43)); Set-ReleasePrivateAcl $token $false
    if ((Read-ReleaseToken $token).Length -ne 43) { throw 'Private token read gate failed.' }; $checks.Add('bounded-owner-only-token-read')
    $publicAcl = Get-Acl -LiteralPath $token
    $publicAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'), 'Read', 'Allow'))
    [IO.FileInfo]::new($token).SetAccessControl($publicAcl)
    Expect-Rejected { Read-ReleaseToken $token } 'other-account-token-read-permission-rejected'
    Assert-ReleaseBundle $bundle | Out-Null; $checks.Add('all-test-mutations-restored')
    [ordered]@{ result='PASS'; tests=$checks.Count; checks=$checks } | ConvertTo-Json -Depth 4
} finally {
    $absolute = [IO.Path]::GetFullPath($owned)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ([IO.Path]::GetDirectoryName($absolute) -ne $temporaryRoot -or [IO.Path]::GetFileName($absolute) -notlike 'workspace-m5e-package-tests-*') { throw 'Refusing unsafe fixture cleanup.' }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
