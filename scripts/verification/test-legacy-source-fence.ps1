#Requires -Version 7.4
# Manual synthetic-only experiment. Never accepts a database/Profile/credential supplied by a user.
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidence=Join-Path $repo '.verification/recovery-legacy-enrollment'
[IO.Directory]::CreateDirectory($evidence) | Out-Null
$classes=Join-Path $evidence 'classes'
[IO.Directory]::CreateDirectory($classes) | Out-Null
$classpath=[IO.File]::ReadAllText((Join-Path $repo '.verification/recovery-v3-classpath.txt')).Trim()
& 'D:\MC\JAVA\JAVA21\bin\javac.exe' -encoding UTF-8 -cp $classpath -d $classes (Join-Path $repo 'bootstrap/tests/LegacySourceFenceProbe.java') 2> (Join-Path $evidence 'javac.log')
if($LASTEXITCODE -ne 0){throw 'Synthetic fence compilation failed'}
$root=Join-Path ([IO.Path]::GetTempPath()) ('paw-legacy-fence-'+[Guid]::NewGuid().ToString('N'))
if([IO.Path]::GetFullPath($root) -cne $root -or (Test-Path -LiteralPath $root)){throw 'Fresh absolute fixture root required'}
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
[IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($root),$acl)
for($part=$root;$part;$part=[IO.Path]::GetDirectoryName($part)){
    if((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked fixture ancestor rejected'}
}
$socket=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$socket.Start();$port=$socket.LocalEndpoint.Port;$socket.Stop()
if($port -eq 5432){throw 'Shared service port rejected'}
$password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$passwordFile=Join-Path $root 'init-password';$data=Join-Path $root 'pgdata';$fixture=Join-Path $root 'fixture.json'
[IO.File]::WriteAllText($passwordFile,$password,[Text.UTF8Encoding]::new($false))
$postgresPid=0;$postgresStartTicks=0;$result=1;$stopped=$false
try {
    & 'D:\PostgreSQL\bin\initdb.exe' -D $data -U legacy_fixture_admin --pwfile=$passwordFile --auth=scram-sha-256 --encoding=UTF8 --locale=C 1> (Join-Path $root 'initdb.log') 2> (Join-Path $root 'initdb.err')
    if($LASTEXITCODE -ne 0){throw 'Isolated initdb failed'}
    Remove-Item -LiteralPath $passwordFile
    $start=Start-Process -FilePath 'D:\PostgreSQL\bin\pg_ctl.exe' -ArgumentList ('-D "'+$data+'" -l "'+(Join-Path $root 'postgres.log')+'" -o "-h 127.0.0.1 -p '+$port+' -c shared_buffers=16MB -c max_connections=12" -w -t 20 start') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'start.log') -RedirectStandardError (Join-Path $root 'start.err')
    if(!$start.WaitForExit(25000) -or $start.ExitCode -ne 0){throw 'Owned startup outcome unconfirmed'}
    $pidLines=[IO.File]::ReadAllLines((Join-Path $data 'postmaster.pid'))
    if($pidLines[1] -cne $data.Replace('\','/') -or [int]$pidLines[3] -ne $port){throw 'Owned cluster identity rejected'}
    $postgresPid=[int]$pidLines[0];$process=Get-Process -Id $postgresPid
    if($process.Path -ine 'D:\PostgreSQL\bin\postgres.exe'){throw 'Owned executable rejected'}
    $postgresStartTicks=$process.StartTime.ToUniversalTime().Ticks
    [ordered]@{root=$root;port=$port;processId=$postgresPid;adminUsername='legacy_fixture_admin';adminPassword=$password;tools='D:\PostgreSQL\bin'} | ConvertTo-Json | Set-Content -Encoding utf8NoBOM -LiteralPath $fixture
    $password=$null
    [ordered]@{root=$root;port=$port;processId=$postgresPid;startUtcTicks=$postgresStartTicks;owned=$true} | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $evidence 'owned-fixture.json')
    & 'D:\MC\JAVA\JAVA21\bin\java.exe' '-Xmx256m' '-XX:ActiveProcessorCount=2' -cp ($classes+';'+$classpath) io.github.qianlixunbai.workspace.backup.LegacySourceFenceProbe $fixture 1> (Join-Path $evidence 'probe.log') 2> (Join-Path $evidence 'probe-errors.log')
    $result=$LASTEXITCODE
    if(Test-Path -LiteralPath (Join-Path $root 'probe-result.json')){Copy-Item -LiteralPath (Join-Path $root 'probe-result.json') -Destination (Join-Path $evidence 'probe-result.json')}
} finally {
    if(Test-Path -LiteralPath $passwordFile){Remove-Item -LiteralPath $passwordFile}
    if($postgresPid -gt 0){
        $pidLines=[IO.File]::ReadAllLines((Join-Path $data 'postmaster.pid'));$process=Get-Process -Id $postgresPid
        if([int]$pidLines[0] -ne $postgresPid -or $pidLines[1] -cne $data.Replace('\','/') -or [int]$pidLines[3] -ne $port -or $process.StartTime.ToUniversalTime().Ticks -ne $postgresStartTicks -or $process.Path -ine 'D:\PostgreSQL\bin\postgres.exe'){throw 'Refusing stop: owned instance identity unknown'}
        & 'D:\PostgreSQL\bin\pg_ctl.exe' stop -D $data -m fast -w -t 20 1> (Join-Path $root 'stop.log') 2> (Join-Path $root 'stop.err')
        if($LASTEXITCODE -ne 0){throw 'Owned stop outcome unconfirmed'}
        $stopped=$true
        if(Test-Path -LiteralPath $fixture){Remove-Item -LiteralPath $fixture}
    }
    [ordered]@{root=$root;port=$port;processId=$postgresPid;testExitCode=$result;ownedInstanceStopped=$stopped;existingServicesTouched=$false;personalProfileAccessed=$false} | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $evidence 'fixture-result.json')
}
Get-Content -LiteralPath (Join-Path $evidence 'probe.log')
exit $result
