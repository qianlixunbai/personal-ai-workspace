#Requires -Version 7.4
# Run from a normal, non-elevated desktop host. Always creates a new private temporary cluster.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Probe,
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$Jna,
    [Parameter(Mandatory)][string]$Classpath,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [string]$PostgresqlBin = 'D:\PostgreSQL\bin'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repo 'scripts/verification/synthetic-postgresql.ps1')
Add-Type -CompilerOptions '/nullable:enable' -Path @((Join-Path $repo 'bootstrap/Shared/WindowsProfileSecurity.cs'), (Join-Path $repo 'scripts/verification/SyntheticPostgresqlFiles.cs'))
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
try {
    if (([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Non-elevated desktop host required' }
    $sid = $identity.User
} finally { $identity.Dispose() }
foreach ($inputPath in @($Probe,$Java,$Jna,$Classpath)) {
    if (![IO.Path]::IsPathFullyQualified($inputPath) -or !(Test-Path -LiteralPath $inputPath -PathType Leaf)) { throw 'Absolute existing test input required' }
}
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $evidence) { throw 'New evidence directory required' }
[void][IO.Directory]::CreateDirectory($evidence)
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('paw-memory-postgresql-' + [Guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $fixtureRoot) { throw 'New fixture root required' }
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false)
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
[IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($fixtureRoot),$acl)
$pins = [SyntheticPostgresqlFiles]::new()
$pgData = Join-Path $fixtureRoot 'pgdata'
$passwordFile = Join-Path $fixtureRoot 'init-password'
$fixtureFile = Join-Path $fixtureRoot 'fixture.json'
$pgProcessId = 0; $pgStartTicks = 0; $stopped = $false; $testExit = 10; $stage = 'INPUT'; $failure = $null
$port = 0; $probeProcess = $null; $secretPin = $null
try {
    [void]$pins.Directory($fixtureRoot,$true)
    $toolHashes = @(foreach ($name in @('initdb.exe','pg_ctl.exe','pg_controldata.exe','postgres.exe')) {
        $source = $pins.Source((Join-Path $PostgresqlBin $name))
        [pscustomobject]@{Name=$name;SHA256=(Get-SyntheticHash $source)}
    })
    $toolHashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'tool-identities.json') -Encoding UTF8
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try { $listener.Start(); $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    if ($port -eq 5432) { throw 'Shared service port rejected' }
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    [IO.File]::WriteAllText($passwordFile,$password,[Text.UTF8Encoding]::new($false))
    $stage = 'INITDB'
    $init = Invoke-SyntheticTool (Join-Path $PostgresqlBin 'initdb.exe') @('-D',$pgData,'-U','memory_fixture_admin',('--pwfile='+$passwordFile),'--auth=scram-sha-256','--encoding=UTF8','--locale=C') -TimeoutSeconds 30
    if ($init.ExitCode -ne 0) { throw 'Fresh initdb failed' }
    Remove-Item -LiteralPath $passwordFile
    $control = Invoke-SyntheticTool (Join-Path $PostgresqlBin 'pg_controldata.exe') @('-D',$pgData)
    if ($control.ExitCode -ne 0 -or $control.Text -notmatch '(?m)^Database system identifier:\s+(\d+)\s*$') { throw 'Fresh cluster identity unavailable' }
    $systemId = $Matches[1]
    if (@(Get-SyntheticListeners $port).Count) { throw 'Selected port no longer available' }
    $stage = 'START'
    $start = Invoke-SyntheticTool (Join-Path $PostgresqlBin 'pg_ctl.exe') @('start','-D',$pgData,'-p',(Join-Path $PostgresqlBin 'postgres.exe'),'-l',(Join-Path $fixtureRoot 'postgres.log'),'-o',('-h 127.0.0.1 -p '+$port+' -c shared_buffers=16MB -c max_connections=12'),'-w','-t','20','-s') -TimeoutSeconds 25 -DiscardOutput
    if ($start.ExitCode -ne 0) { throw 'Fresh cluster start failed' }
    $pidLines = [IO.File]::ReadAllLines((Join-Path $pgData 'postmaster.pid'))
    if ($pidLines[1].Replace('/','\') -ine $pgData -or [int]$pidLines[3] -ne $port -or $pidLines[5] -cne '127.0.0.1') { throw 'Owned cluster identity rejected' }
    $pgProcessId = [int]$pidLines[0]; $pgProcess = Get-Process -Id $pgProcessId
    if ($pgProcess.Path -ine (Join-Path $PostgresqlBin 'postgres.exe')) { throw 'Owned executable rejected' }
    $pgStartTicks = $pgProcess.StartTime.ToUniversalTime().Ticks
    $listeners = @(Get-SyntheticListeners $port)
    if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -cne '127.0.0.1' -or $listeners[0].OwningProcess -ne $pgProcessId) { throw 'Owned listener rejected' }
    $fixture = @{root=$fixtureRoot;port=$port;processId=$pgProcessId;startEpochMillis=([DateTimeOffset]$pgProcess.StartTime).ToUnixTimeMilliseconds();systemId=$systemId;postgresExecutable=(Join-Path $PostgresqlBin 'postgres.exe');adminUsername='memory_fixture_admin';adminPassword=$password}
    [IO.File]::WriteAllText($fixtureFile,($fixture | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $fixture.adminPassword=$null; $password=$null
    $secretPin = $pins.File($fixtureFile)
    @{root=$fixtureRoot;port=$port;processId=$pgProcessId;startUtcTicks=$pgStartTicks;synthetic=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'owned-fixture.json') -Encoding UTF8
    $nativeEvidence = Join-Path $evidence 'native'
    [void][IO.Directory]::CreateDirectory($nativeEvidence)
    $info = [Diagnostics.ProcessStartInfo]::new($Probe)
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach ($argument in @($Java,$Jna,$Classpath,$nativeEvidence,$fixtureFile)) { $info.ArgumentList.Add($argument) }
    $stage = 'NATIVE_TEST'
    $probeProcess = [Diagnostics.Process]::Start($info)
    $stdout=$probeProcess.StandardOutput.ReadToEndAsync(); $stderr=$probeProcess.StandardError.ReadToEndAsync()
    if (!$probeProcess.WaitForExit(150000)) { $probeProcess.Kill($true); $probeProcess.WaitForExit(5000) | Out-Null; throw 'Owned probe timed out' }
    if (!$stdout.Wait(2000) -or !$stderr.Wait(2000)) { throw 'Owned probe output incomplete' }
    [IO.File]::WriteAllText((Join-Path $evidence 'host.log'),$stdout.Result + $stderr.Result)
    $testExit = $probeProcess.ExitCode
    $resultFile = Join-Path $fixtureRoot 'memory-postgresql-evidence.json'
    if (Test-Path -LiteralPath $resultFile) { Copy-Item -LiteralPath $resultFile -Destination (Join-Path $evidence 'database-evidence.json') }
} catch { $failure = $_.Exception.GetType().Name; $testExit=10 }
finally {
    if ($probeProcess) { $probeProcess.Dispose() }
    if ($secretPin) { $secretPin.Dispose() }
    foreach ($secretFile in @($passwordFile,$fixtureFile)) { if (Test-Path -LiteralPath $secretFile) { Remove-Item -LiteralPath $secretFile } }
    try {
        if ($pgProcessId -gt 0) {
            $pidLines=[IO.File]::ReadAllLines((Join-Path $pgData 'postmaster.pid')); $pgProcess=Get-Process -Id $pgProcessId
            if ([int]$pidLines[0] -ne $pgProcessId -or $pidLines[1].Replace('/','\') -ine $pgData -or [int]$pidLines[3] -ne $port -or $pgProcess.StartTime.ToUniversalTime().Ticks -ne $pgStartTicks -or $pgProcess.Path -ine (Join-Path $PostgresqlBin 'postgres.exe')) { throw 'Refusing unverified stop' }
            $stop = Invoke-SyntheticTool (Join-Path $PostgresqlBin 'pg_ctl.exe') @('stop','-D',$pgData,'-m','fast','-w','-t','20') -TimeoutSeconds 25
            if ($stop.ExitCode -ne 0 -or @(Get-SyntheticListeners $port).Count -ne 0) { throw 'Owned cluster stop incomplete' }
            $stopped=$true
        }
    } catch { $failure='OwnedStopIncomplete'; $testExit=10 }
    $pins.Dispose()
    @{root=$fixtureRoot;port=$port;processId=$pgProcessId;testExitCode=$testExit;ownedInstanceStopped=$stopped;existingServicesTouched=$false;stage=$stage;failure=$failure;temporarySecretsRemoved=(!(Test-Path -LiteralPath $passwordFile) -and !(Test-Path -LiteralPath $fixtureFile))} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'fixture-result.json') -Encoding UTF8
}
exit $testExit
