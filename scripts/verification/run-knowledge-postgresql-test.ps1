# Explicit local integration only. No passwords in Maven parameters/environment or build logs.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$file = Join-Path $repo '.runtime\workspace\b1-test-credentials.json'
$account = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
foreach ($path in @((Split-Path -Parent $file), $file)) {
    for ($part = $path; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if (((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked credential path rejected.' }
    }
    $acl = Get-Acl -LiteralPath $path
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if (-not $acl.AreAccessRulesProtected -or $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $account -or
        $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $account -or $rules[0].IsInherited -or
        $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
        throw 'Current-user-only protected credential ACL required.'
    }
}
$output = Join-Path $repo '.verification\f2.3-b2-knowledge-postgresql'
[IO.Directory]::CreateDirectory($output) | Out-Null
Push-Location $repo
try {
    & .\mvnw.cmd -B -ntp -pl workspace-application -am '-Dtest=KnowledgePostgresqlIntegrationTest' `
        '-Dknowledge.postgresql.test=true' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 |
        Tee-Object -FilePath (Join-Path $output 'maven-focused.log')
    $code = $LASTEXITCODE
} finally {
    # Fixed, previously verified task-owned file; no directory or other credential is removed.
    Remove-Item -LiteralPath $file -Force
    Pop-Location
}
exit $code
