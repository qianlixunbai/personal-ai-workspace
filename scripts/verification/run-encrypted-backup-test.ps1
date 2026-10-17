# Explicit B3E synthetic flow only; no normal Runtime startup and no old database access.
[CmdletBinding()]
param([switch]$KeepCredentialsForDiagnosis)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($PSVersionTable.PSEdition -ne 'Desktop'){throw 'Use Windows PowerShell 5.1.'}
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$credential=Join-Path $repo '.runtime\workspace\b3e-test-credentials.json'
if(-not (Test-Path -LiteralPath $credential -PathType Leaf)){throw 'Fresh B3E target preparation is required.'}
$output=Join-Path $repo '.verification\f2.3-b3e-encrypted-backup'
[IO.Directory]::CreateDirectory($output) | Out-Null
Push-Location $repo
try {
    & .\mvnw.cmd -B -ntp -pl workspace-application -am '-Dtest=PortableEncryptedBackupIntegrationTest' `
        '-Dencrypted.backup.test=true' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 |
        Tee-Object -FilePath (Join-Path $output 'maven-focused.log')
    $code=$LASTEXITCODE
} finally {
    try {
        if(-not $KeepCredentialsForDiagnosis) {
            & powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'prepare-encrypted-backup-test.ps1') -Finish
            if($LASTEXITCODE -ne 0){throw 'B3E credential cleanup incomplete; retain private evidence and finish locally.'}
        }
    } finally { Pop-Location }
}
exit $code
