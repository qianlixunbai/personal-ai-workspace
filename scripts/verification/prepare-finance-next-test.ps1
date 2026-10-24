#Requires -Version 7.4
[CmdletBinding()]
param([string]$PostgresqlBin = 'D:\PostgreSQL\bin', [string]$JavaHome = 'D:\MC\JAVA\JAVA21', [int]$Port = 55422, [switch]$Transactions, [switch]$Assets, [switch]$Investments, [switch]$Portfolio, [switch]$History)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ($Port -lt 55000 -or $Port -gt 55999 -or @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count) { throw 'NEW_UNUSED_TEST_PORT_REQUIRED' }
$suffix = [Guid]::NewGuid().ToString('N').Substring(0,12)
# PostgreSQL WAL hard links cannot use this machine's EFS-encrypted LocalAppData.
$root = Join-Path 'D:\PersonalAiWorkspace-Fnext1' $suffix
[IO.Directory]::CreateDirectory($root) | Out-Null
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
if ((Get-Acl -LiteralPath $root).GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value) { throw 'TEST_DIRECTORY_OWNER_REJECTED' }
$acl.SetAccessRuleProtection($true,$false)
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
[IO.FileSystemAclExtensions]::SetAccessControl([IO.DirectoryInfo]::new($root),$acl)
$admin = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$reader = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$writer = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$passwordFile = Join-Path $root 'init-password'
[IO.File]::WriteAllText($passwordFile,$admin,[Text.UTF8Encoding]::new($false))
try {
    & (Join-Path $PostgresqlBin 'initdb.exe') -D (Join-Path $root 'pgdata') -U fnext_admin --encoding=UTF8 --locale=C --auth=scram-sha-256 --pwfile=$passwordFile > (Join-Path $root 'initdb.log') 2>&1
    if ($LASTEXITCODE) { throw 'ISOLATED_INITDB_FAILED' }
} finally { Remove-Item -LiteralPath $passwordFile }
$options = "-D `"$(Join-Path $root 'pgdata')`" -l `"$(Join-Path $root 'postgres.log')`" -o `"-h 127.0.0.1 -p $Port`" -w start"
$server = Start-Process -FilePath (Join-Path $PostgresqlBin 'pg_ctl.exe') -ArgumentList $options -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'start.log') -RedirectStandardError (Join-Path $root 'start.stderr')
if (!$server.WaitForExit(30000) -or $server.ExitCode) { throw 'ISOLATED_CLUSTER_START_FAILED' }
$tools = Join-Path $root 'tooling'; [IO.Directory]::CreateDirectory($tools) | Out-Null
$archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $repo 'target/personal-ai-workspace-0.1.0.jar'))
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -match '^BOOT-INF/(classes/.+|lib/[^/]+\.jar)$' -and !$entry.FullName.EndsWith('/')) {
            $path = [IO.Path]::GetFullPath((Join-Path $tools $entry.FullName.Substring(9)))
            if (!$path.StartsWith($tools + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'INVALID_ARCHIVE_PATH' }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$path,$false)
        }
    }
} finally { $archive.Dispose() }
$classpath = (Join-Path $tools 'classes') + ';' + (Join-Path $tools 'lib/*')
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d (Join-Path $tools 'classes') (Join-Path $repo 'bootstrap/tests/FinanceNextPreparation.java')
if ($LASTEXITCODE) { throw 'FIXTURE_COMPILATION_FAILED' }
$fixture = @{suffix=$suffix;port=$Port;adminPassword=$admin;readerPassword=$reader;writerPassword=$writer;transactions=([bool]$Transactions -or [bool]$Assets -or [bool]$Investments -or [bool]$Portfolio -or [bool]$History);assets=([bool]$Assets -or [bool]$Investments -or [bool]$Portfolio -or [bool]$History);investments=([bool]$Investments -or [bool]$Portfolio -or [bool]$History);portfolio=[bool]$Portfolio;history=[bool]$History}
function Protect-Json([string]$Name,$Value) {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Compress))
    try { [IO.File]::WriteAllBytes((Join-Path $root $Name),[Security.Cryptography.ProtectedData]::Protect($bytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)) }
    finally { [Security.Cryptography.CryptographicOperations]::ZeroMemory($bytes) }
}
Protect-Json 'fixture.dpapi' $fixture
Protect-Json 'finance.dpapi' @{format=1;backend='postgresql';host='127.0.0.1';port=$Port;database="fnext1_finance_$suffix";username="fnext1_reader_$suffix";password=$reader}
Protect-Json 'workspace.dpapi' @{format=1;backend='postgresql';host='127.0.0.1';port=$Port;database="fnext1_workspace_$suffix";username="fnext1_writer_$suffix";password=$writer}
$start = [Diagnostics.ProcessStartInfo]::new((Join-Path $JavaHome 'bin/java.exe'))
$start.UseShellExecute=$false; $start.CreateNoWindow=$true; $start.RedirectStandardInput=$true; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
foreach ($argument in @('-cp',$classpath,'FinanceNextPreparation','prepare')) { $start.ArgumentList.Add($argument) }
$process = [Diagnostics.Process]::Start($start)
$process.StandardInput.Write(($fixture | ConvertTo-Json -Compress)); $process.StandardInput.Close()
$response = $process.StandardOutput.ReadToEnd(); $errors = $process.StandardError.ReadToEnd(); $process.WaitForExit()
if ($process.ExitCode -ne 0 -or $response.Trim() -ne 'FNEXT_FIXTURE_PASS_prepare') { throw $response.Trim() }
$receipt = @{suffix=$suffix;port=$Port;root=$root;finance="fnext1_finance_$suffix";workspace="fnext1_workspace_$suffix";financeMigrations='V1-V21';workspaceMigrations='V1-V2';productionGo=$false}
$receiptName = if ($History) { '.verification/fnext8-target.json' } elseif ($Portfolio) { '.verification/fnext7-target.json' } elseif ($Investments) { '.verification/fnext6-target.json' } elseif ($Assets) { '.verification/fnext5-target.json' } elseif ($Transactions) { '.verification/fnext3-target.json' } else { '.verification/fnext1-target.json' }
$receipt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $repo $receiptName) -Encoding utf8NoBOM
$receipt | ConvertTo-Json
