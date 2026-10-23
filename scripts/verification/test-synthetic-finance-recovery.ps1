#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$PackageDirectory,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{12}$')][string]$StoppedFixtureSuffix)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($StoppedFixtureSuffix -ceq '939355624cdf') { throw 'ACTIVE_DELIVERY_CLUSTER_CANNOT_BE_STOPPED_FIXTURE' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'synthetic-postgresql.ps1')
Add-Type -CompilerOptions '/nullable:enable' -Path @((Join-Path $repo 'bootstrap/Shared/WindowsProfileSecurity.cs'),(Join-Path $PSScriptRoot 'SyntheticPostgresqlFiles.cs'))
$launcher = Get-Content -LiteralPath (Join-Path $PackageDirectory 'Open-Synthetic-Finance.ps1') -Raw
if ($launcher -notmatch "FromBase64String\('([A-Za-z0-9+/=]+)'\)") { throw 'LAUNCHER_SETTINGS_MISSING' }
$settings = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1])) | ConvertFrom-Json
function Copy-Trust($Value) { $Value | ConvertTo-Json -Depth 10 -Compress | ConvertFrom-Json }
function Expect-Stop([scriptblock]$Action, [string]$Code) {
    $caught = $null
    try { & $Action } catch { $caught=$_.Exception.GetBaseException().Message }
    if ($caught -cne $Code) { throw "EXPECTED_$Code" }
    Write-Host "PASS rejection=$Code"
}
$trust = $settings.Trust
Start-SyntheticPostgresql $trust -CheckOnly
$runningPid = (Get-SyntheticListeners $trust.Port)[0].OwningProcess
$bad = Copy-Trust $trust; $bad.Suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$bad.Root='D:\PersonalAiWorkspace-Fnext1\'+$bad.Suffix; $bad.Data=Join-Path $bad.Root 'pgdata'
if (Test-Path -LiteralPath $bad.Root) { throw 'MISSING_PATH_FIXTURE_COLLISION' }
Expect-Stop { Start-SyntheticPostgresql $bad } 'DATA_DIRECTORY_MISSING'
$bad = Copy-Trust $trust; $bad.RootIdentity='00000000:0000000000000000'
Expect-Stop { Start-SyntheticPostgresql $bad } 'DATA_DIRECTORY_IDENTITY_REJECTED'
$bad = Copy-Trust $trust; $bad.Config[0].Hash='0' * 64
Expect-Stop { Start-SyntheticPostgresql $bad } 'CLUSTER_CONFIGURATION_MISMATCH'
$bad = Copy-Trust $trust; $bad.SystemId='1'
Expect-Stop { Start-SyntheticPostgresql $bad } 'CONTROL_IDENTITY_REJECTED'
$bad = Copy-Trust $trust; $bad.Sources[0].Hash='0' * 64
Expect-Stop { Start-SyntheticPostgresql $bad } 'POSTGRESQL_SOURCE_HASH_MISMATCH'
$bad = Copy-Trust $trust; $bad.Databases.fnext1_finance_939355624cdf='1'
Expect-Stop { Start-SyntheticPostgresql $bad } 'DATABASE_DATASET_MISMATCH'
if ((Get-SyntheticListeners $trust.Port)[0].OwningProcess -ne $runningPid) { throw 'ACTIVE_CLUSTER_CHANGED' }

$fixture = Copy-Trust $trust
$fixture.Suffix=$StoppedFixtureSuffix; $fixture.Root='D:\PersonalAiWorkspace-Fnext1\' + $StoppedFixtureSuffix
$fixture.Data=Join-Path $fixture.Root 'pgdata'; $fixture.Port=55422
if ((Test-Path -LiteralPath (Join-Path $fixture.Data 'postmaster.pid')) -or @(Get-SyntheticListeners $fixture.Port).Count) { throw 'FIXTURE_NOT_SAFELY_STOPPED' }
$pins=[SyntheticPostgresqlFiles]::new(); $startAttempt=$false; $fixturePid=$null
try {
    $fixture.RootIdentity=$pins.Directory($fixture.Root,$true); $fixture.DataIdentity=$pins.Directory($fixture.Data)
    $pins.Tree($fixture.Data)
    foreach ($entry in $fixture.Config) {
        # Do not bless arbitrary fixture config: require the already reviewed initdb defaults.
        if ((Get-SyntheticHash ($pins.File((Join-Path $fixture.Data $entry.Name)))) -cne $entry.Hash) { throw 'FIXTURE_CONFIG_DIFFERS_FROM_REVIEWED_DEFAULTS' }
    }
    $secret=Read-SyntheticSecret $pins (Join-Path $fixture.Root 'fixture.dpapi')
    if ($secret.suffix -cne $fixture.Suffix -or $secret.port -ne $fixture.Port) { throw 'FIXTURE_PROVENANCE_REJECTED' }
    $control=Invoke-SyntheticTool (Join-Path $fixture.Bin 'pg_controldata.exe') @('-D',$fixture.Data)
    if ($control.ExitCode -ne 0 -or $control.HasError -or $control.Text -notmatch '(?m)^Database cluster state:\s+shut down\s*$' -or
        $control.Text -notmatch '(?m)^Database system identifier:\s+([0-9]+)\s*$') { throw 'FIXTURE_CONTROL_REJECTED' }
    $fixture.SystemId=$Matches[1]
    $fixture.Databases=[pscustomobject]@{("fnext1_finance_"+$fixture.Suffix)='16386';("fnext1_workspace_"+$fixture.Suffix)='16387'}
    $occupier=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$fixture.Port)
    try {
        $occupier.Start()
        Expect-Stop { Start-SyntheticPostgresql $fixture } 'PORT_OCCUPIED_BY_UNVERIFIED_PROCESS'
        if (Test-Path -LiteralPath (Join-Path $fixture.Data 'postmaster.pid')) { throw 'OCCUPIED_PORT_STARTED_SERVER' }
    } finally { $occupier.Stop() }
    # No initdb/CREATE DATABASE and no stop of the active delivery cluster.
    $startAttempt=$true
    Start-SyntheticPostgresql $fixture
    $fixturePid=(Get-SyntheticListeners $fixture.Port)[0].OwningProcess
    Start-SyntheticPostgresql $fixture
    if ((Get-SyntheticListeners $fixture.Port)[0].OwningProcess -ne $fixturePid) { throw 'DUPLICATE_SERVER_STARTED' }
    $lock=[IO.File]::Open((Join-Path $fixture.Root 'synthetic-start.lock'),'Open','Read','None')
    try { Expect-Stop { Start-SyntheticPostgresql $fixture } 'START_ALREADY_IN_PROGRESS' } finally { $lock.Dispose() }
    Write-Host 'PASS isolated-stopped-fixture-recovery-and-repeat-same-postmaster'
} finally {
    $secret=$null
    $pins.Dispose()
    if ($startAttempt -and @(Get-SyntheticListeners $fixture.Port).Count) {
        # Only this test's initially stopped, now identity-verified fixture process may be stopped.
        $listeners=@(Get-SyntheticListeners $fixture.Port)
        Assert-SyntheticProcess $fixture $listeners
        if ($null -ne $fixturePid -and $listeners[0].OwningProcess -ne $fixturePid) { throw 'FIXTURE_CLEANUP_IDENTITY_CHANGED' }
        $cleanupPins=[SyntheticPostgresqlFiles]::new()
        try {
            $cleanupSecret=Read-SyntheticSecret $cleanupPins (Join-Path $fixture.Root 'fixture.dpapi')
            Assert-SyntheticDatabaseIdentity $fixture (Get-SyntheticDatabaseIdentity $fixture $cleanupSecret.adminPassword)
        } finally { $cleanupSecret=$null; $cleanupPins.Dispose() }
        $stop=Invoke-SyntheticTool (Join-Path $fixture.Bin 'pg_ctl.exe') @('stop','-D',$fixture.Data,'-m','fast','-w','-t','20') -TimeoutSeconds 25
        if ($stop.ExitCode -ne 0) { throw 'OWNED_FIXTURE_CLEANUP_FAILED' }
    }
}
if ((Get-SyntheticListeners $trust.Port)[0].OwningProcess -ne $runningPid) { throw 'ACTIVE_CLUSTER_CHANGED' }
Write-Host 'PASS recovery-harness; real-Windows-reboot=NOT_EXECUTED'
