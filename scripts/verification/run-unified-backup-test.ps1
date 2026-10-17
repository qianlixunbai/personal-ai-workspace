# Explicit synthetic integration only. Preparation creates task-owned DBs; no daily Runtime startup.
[CmdletBinding()]
param([switch]$KeepCredentialsForDiagnosis)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSEdition -ne 'Desktop') { throw 'Use Windows PowerShell 5.1.' }
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$credential = Join-Path $repo '.runtime\workspace\b3-test-credentials.json'
$output = Join-Path $repo '.verification\f2.3-b3-unified-backup'
if (-not (Test-Path -LiteralPath $credential -PathType Leaf)) { throw 'Fresh local B3 preparation is required.' }
[IO.Directory]::CreateDirectory($output) | Out-Null
Push-Location $repo
try {
    & .\mvnw.cmd -B -ntp -pl workspace-application -am '-Dtest=UnifiedPostgresqlBackupIntegrationTest' `
        '-Dunified.backup.test=true' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 |
        Tee-Object -FilePath (Join-Path $output 'maven-focused.log')
    $code = $LASTEXITCODE
} finally {
    if (-not $KeepCredentialsForDiagnosis) {
        # Disables only the generated task roles, revokes source grants and removes both task secrets.
        # Databases, incomplete candidates and backups remain for review; no DROP/cleanup of data.
        & powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'prepare-unified-backup-test.ps1') -Finish
        if ($LASTEXITCODE -ne 0) { throw 'B3 credential cleanup incomplete; retain private evidence and finish locally.' }
    }
    Pop-Location
}
exit $code
