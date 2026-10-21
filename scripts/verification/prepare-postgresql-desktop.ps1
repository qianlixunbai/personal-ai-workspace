#Requires -Version 7.4
[CmdletBinding()]
param([switch]$PrepareOnly, [switch]$CheckOnly, [string]$RuntimeManifest = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User

function Assert-Private([string]$Path) {
    for ($part = $Path; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'LINK_REJECTED' }
    }
    $acl = Get-Acl -LiteralPath $Path
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if (!$acl.AreAccessRulesProtected -or $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value -or
        $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $sid.Value -or $rules[0].IsInherited -or
        $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
        throw 'PRIVATE_ACL_REJECTED'
    }
}
function Protect-File([string]$Path) {
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
    [IO.FileSystemAclExtensions]::SetAccessControl([IO.FileInfo]::new($Path), $acl)
    Assert-Private $Path
}
function Write-New([string]$Path, [byte[]]$Bytes) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { Protect-File $Path; $stream.Write($Bytes); $stream.Flush($true) } finally { $stream.Dispose() }
}
function Write-Json([string]$Path, $Value) {
    Write-New $Path ([Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 5 -Compress)))
}

if ($PrepareOnly) {
    # Stage reviewed code and dependencies before asking for an administrator password.
    $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $private = Join-Path $repo '.runtime/workspace'
    Assert-Private $private
    if (!$RuntimeManifest) { throw 'EXPLICIT_REVIEWED_RUNTIME_MANIFEST_REQUIRED' }
    $sources = Get-Content -LiteralPath $RuntimeManifest -Raw | ConvertFrom-Json
    if ($sources.Format -ne 2) { throw 'ABSOLUTE_LOCAL_RUNTIME_MANIFEST_REQUIRED' }
    foreach ($file in $sources.Files) {
        if ((Get-FileHash -LiteralPath $file.Path -Algorithm SHA256).Hash -ine $file.Sha256) { throw 'RUNTIME_SOURCE_CHANGED' }
    }
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $session = Join-Path $private ('pgdesktop-' + $suffix)
    if (Test-Path -LiteralPath $session) { throw 'SESSION_EXISTS' }
    [IO.Directory]::CreateDirectory($session) | Out-Null
    # Only this new child is protected; existing ACLs are never repaired.
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    [IO.FileSystemAclExtensions]::SetAccessControl([IO.DirectoryInfo]::new($session), $acl)
    Assert-Private $session
    Write-New (Join-Path $session 'prepare.ps1') ([IO.File]::ReadAllBytes($PSCommandPath))
    Write-New (Join-Path $session 'PostgresqlDesktopMigration.java') ([IO.File]::ReadAllBytes((Join-Path $repo 'bootstrap/tests/PostgresqlDesktopMigration.java')))
    # Use precisely the libraries and production classes in the previously pinned Runtime JAR.
    $archive = [IO.Compression.ZipFile]::OpenRead($sources.RuntimeJar)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '^BOOT-INF/(classes/.+|lib/[^/]+\.jar)$' -and !$entry.FullName.EndsWith('/')) {
                $relative = $entry.FullName.Substring(9)
                $destination = [IO.Path]::GetFullPath((Join-Path $session $relative))
                if (!$destination.StartsWith($session + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'ARCHIVE_PATH_REJECTED' }
                [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $false)
                Protect-File $destination
            }
        }
    } finally { $archive.Dispose() }
    $java = Join-Path $sources.JavaHome 'bin/java.exe'
    $javac = Join-Path $sources.JavaHome 'bin/javac.exe'
    & $javac -encoding UTF-8 -cp "$session/classes;$session/lib/*" -d $session (Join-Path $session 'PostgresqlDesktopMigration.java')
    if ($LASTEXITCODE -ne 0) { throw 'MIGRATION_HELPER_COMPILE_FAILED' }
    Protect-File (Join-Path $session 'PostgresqlDesktopMigration.class')
    $pins = @(Get-ChildItem -LiteralPath $session -File -Recurse | ForEach-Object {
        @{ path = $_.FullName; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash }
    })
    $pins += @{ path = $java; sha256 = (Get-FileHash -LiteralPath $java).Hash }
    Write-Json (Join-Path $session 'pins.json') $pins
    Write-Json (Join-Path $session 'target.json') @{
        suffix = $suffix; database = 'paw_pgdesktop_' + $suffix; owner = 'paw_pgd_owner_' + $suffix
        app = 'paw_pgd_app_' + $suffix; java = $java; phase = 'STAGED_NO_SQL'; sourceHead = (& git -C $repo rev-parse HEAD).Trim()
    }
    Write-Output ('PG_DESKTOP_STAGED ' + $session)
    return
}

$session = $PSScriptRoot
$stage = 'LOCAL_PREFLIGHT'; $process = $null; $payload = $null; $sql = $null
try {
    Assert-Private $session
    foreach ($name in @('prepare.ps1', 'pins.json', 'target.json')) { Assert-Private (Join-Path $session $name) }
    foreach ($pin in (Get-Content -LiteralPath (Join-Path $session 'pins.json') -Raw | ConvertFrom-Json)) {
        if ((Get-FileHash -LiteralPath $pin.path).Hash -ine $pin.sha256) { throw 'PIN_CHANGED' }
    }
    $target = Get-Content -LiteralPath (Join-Path $session 'target.json') -Raw | ConvertFrom-Json
    $suffix = $target.suffix
    if ($target.phase -cne 'STAGED_NO_SQL' -or $suffix -cnotmatch '^[a-f0-9]{12}$' -or
        $target.database -cne ('paw_pgdesktop_' + $suffix) -or $target.owner -cne ('paw_pgd_owner_' + $suffix) -or
        $target.app -cne ('paw_pgd_app_' + $suffix)) { throw 'TARGET_REJECTED' }
    if (Test-Path -LiteralPath (Join-Path $session 'attempt.json')) { throw 'ATTEMPT_EXISTS_INSPECT_BEFORE_RETRY' }
    $psql = 'D:\PostgreSQL\bin\psql.exe'
    if ((Get-FileHash -LiteralPath $psql).Hash -cne 'C290C7F0A3335F290F8C18011A1E5AFA03EE3CDD628697A5F3E767E5F1D3D10D') { throw 'PSQL_CHANGED' }
    if ((Get-Service postgresql-x64-17).Status -ne 'Running') { throw 'SERVER_NOT_RUNNING' }
    if ($CheckOnly) { Write-Output 'PG_DESKTOP_LOCAL_PREFLIGHT_PASS_NO_SQL'; return }
    if ([Console]::IsInputRedirected -or [Console]::IsOutputRedirected -or $env:OSTYPE -eq 'msys') { throw 'NATIVE_CONSOLE_REQUIRED' }
    Write-Json (Join-Path $session 'attempt.json') @{ phase = 'NATIVE_LOGIN_STARTED'; time = [DateTime]::UtcNow.ToString('o') }
    $database = $target.database; $owner = $target.owner; $app = $target.app
    $start = [Diagnostics.ProcessStartInfo]::new($psql)
    foreach ($argument in @('-X','-n','-W','-qAt','-h','127.0.0.1','-p','5432','-U','postgres','-d','postgres',
        '-v','ON_ERROR_STOP=1','-v','VERBOSITY=sqlstate','-P','pager=off','-f','-')) { $start.ArgumentList.Add($argument) }
    $start.UseShellExecute = $false; $start.WorkingDirectory = 'D:\PostgreSQL\bin'
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($key in @($start.Environment.Keys)) { if ($key -match '^(PG|PSQL)' -or $key -eq 'OSTYPE') { $start.Environment.Remove($key) | Out-Null } }
    $start.Environment['PGCONNECT_TIMEOUT'] = '10'
    $start.Environment['PGCLIENTENCODING'] = 'UTF8'
    Write-Host "Synthetic target: $database / $owner / $app"
    Write-Host 'Enter the existing postgres password ONLY at the native psql prompt. No administrator password is stored.'
    $stage = 'ADMIN_LOGIN_AND_READ_ONLY_IDENTITY'
    $process = [Diagnostics.Process]::Start($start)
    $errors = $process.StandardError.ReadToEndAsync()
    $preflight = @'
BEGIN READ ONLY;
DO $check$
BEGIN
 IF current_database() <> 'postgres' OR session_user <> 'postgres' OR current_user <> 'postgres'
 OR inet_server_addr() <> '127.0.0.1'::inet OR inet_server_port() <> 5432
 OR current_setting('server_version_num')::int <> 170011
 OR lower(replace(current_setting('data_directory'),chr(92),'/')) <> 'd:/postgresql/data'
 OR NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user)
 OR EXISTS (SELECT FROM pg_database WHERE datname='__DATABASE__')
 OR EXISTS (SELECT FROM pg_roles WHERE rolname IN ('__OWNER__','__APP__'))
 THEN RAISE EXCEPTION 'PG_DESKTOP_IDENTITY_OR_NAMES_REJECTED'; END IF;
END $check$;
ROLLBACK;
\echo PG_DESKTOP_PREFLIGHT_PASS
'@
    $process.StandardInput.WriteLine($preflight.Replace('__DATABASE__',$database).Replace('__OWNER__',$owner).Replace('__APP__',$app))
    $process.StandardInput.Flush()
    $line = $process.StandardOutput.ReadLineAsync()
    if (!$line.Wait(600000) -or $line.Result -cne 'PG_DESKTOP_PREFLIGHT_PASS') { throw 'PREFLIGHT_FAILED_NO_DDL' }
    Write-Host 'Native login, instance identity, superuser and unused target names verified.'
    $stage = 'SYNTHETIC_CREDENTIALS'
    $ownerPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $appPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $payload = [Text.Encoding]::UTF8.GetBytes((@{suffix=$suffix;ownerPassword=$ownerPassword;appPassword=$appPassword} | ConvertTo-Json -Compress))
    Write-New (Join-Path $session 'maintenance.dpapi') ([Security.Cryptography.ProtectedData]::Protect($payload,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))
    function Invoke-MigrationHelper([string]$Mode) {
        $info = [Diagnostics.ProcessStartInfo]::new($target.java)
        foreach ($argument in @('-cp',"$session;$session/classes;$session/lib/*",'PostgresqlDesktopMigration',$Mode)) { $info.ArgumentList.Add($argument) }
        $info.UseShellExecute=$false; $info.CreateNoWindow=$true
        foreach ($key in @($info.Environment.Keys)) { if ($key -match '^(PG|PSQL)') { $info.Environment.Remove($key) | Out-Null } }
        $info.RedirectStandardInput=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
        $child = [Diagnostics.Process]::Start($info)
        try {
            $stdout=$child.StandardOutput.ReadToEndAsync(); $stderr=$child.StandardError.ReadToEndAsync()
            $child.StandardInput.BaseStream.Write($payload); $child.StandardInput.Close()
            if (!$child.WaitForExit(120000)) { $child.Kill(); throw 'HELPER_TIMEOUT' }
            $stderr.GetAwaiter().GetResult() | Out-Null
            if ($child.ExitCode -ne 0) {
                $safe = $stdout.GetAwaiter().GetResult().Trim()
                if ($safe -cmatch '^PG_DESKTOP_STOP_[A-Z0-9_]+$') { Write-Host $safe }
                throw 'HELPER_FAILED_INSPECT_FIXED_RESULT'
            }
            return $stdout.GetAwaiter().GetResult().Trim()
        } finally { $child.Dispose() }
    }
    $verifiers = (Invoke-MigrationHelper '--verifiers') -split '\r?\n'
    if ($verifiers.Count -ne 2) { throw 'VERIFIERS_REJECTED' }
    foreach ($verifier in $verifiers) { if ($verifier -cnotmatch '^SCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$') { throw 'VERIFIER_REJECTED' } }
    $stage = 'CREATE_SYNTHETIC_TARGET'
    # Only SCRAM verifiers traverse the SQL pipe; no plaintext password is SQL or a file.
    $sql = @"
SET log_statement='none';
SET log_min_error_statement='panic';
SET log_min_duration_statement=-1;
BEGIN;
CREATE ROLE $owner LOGIN PASSWORD '$($verifiers[0])' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE $app LOGIN PASSWORD '$($verifiers[1])' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
COMMIT;
CREATE DATABASE $database WITH OWNER=$owner TEMPLATE=template0 ENCODING='UTF8' ALLOW_CONNECTIONS=false;
REVOKE ALL ON DATABASE $database FROM PUBLIC;
GRANT CONNECT ON DATABASE $database TO $owner, $app;
COMMENT ON DATABASE $database IS 'PG_DESKTOP_SYNTHETIC_$suffix';
ALTER DATABASE $database ALLOW_CONNECTIONS=true;
\echo PG_DESKTOP_CREATED
"@
    $process.StandardInput.WriteLine($sql); $process.StandardInput.Close()
    $remaining = $process.StandardOutput.ReadToEndAsync()
    if (!$process.WaitForExit(30000)) { throw 'DDL_TIMEOUT_INSPECT_PARTIAL_STATE' }
    if ($process.ExitCode -ne 0 -or $remaining.GetAwaiter().GetResult().Trim() -cne 'PG_DESKTOP_CREATED') { throw 'DDL_FAILED_INSPECT_PARTIAL_STATE' }
    $errors.GetAwaiter().GetResult() | Out-Null
    Write-Json (Join-Path $session 'created.json') @{ phase='DATABASE_CREATED'; database=$database; owner=$owner; app=$app }
    $stage = 'FLYWAY_AND_APP_PREFLIGHT'
    $result = Invoke-MigrationHelper '--migrate'
    if ($result -cne 'PG_DESKTOP_MIGRATION_PASS') { throw 'MIGRATION_RESULT_REJECTED' }
    $configuration = [Text.Encoding]::UTF8.GetBytes((@{format=1;backend='postgresql';host='127.0.0.1';port=5432;database=$database;username=$app;password=$appPassword}|ConvertTo-Json -Compress))
    try { Write-New (Join-Path $session 'application.dpapi') ([Security.Cryptography.ProtectedData]::Protect($configuration,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)) }
    finally { [Array]::Clear($configuration,0,$configuration.Length) }
    Write-Json (Join-Path $session 'ready.json') @{ phase='WORKSPACE_V1_V2_APP_READY'; database=$database; owner=$owner; app=$app }
    Write-Host 'PG_DESKTOP_DATABASE_AND_MIGRATION_PASS. Close this window; desktop acceptance continues in Codex.' -ForegroundColor Green
} catch {
    # Raw SQL/JDBC errors, invocation text and secrets never reach console or ordinary logs.
    Write-Host ('PG_DESKTOP_STOP_' + $stage) -ForegroundColor Red
    if ($null -ne $process -and $process.HasExited -and $errors.IsCompletedSuccessfully) {
        $raw = $errors.GetAwaiter().GetResult()
        if ($raw -match '(?m)(?:ERROR|FATAL):\s+([0-9A-Z]{5})(?:\s|$)') { Write-Host ('SQLSTATE_' + $Matches[1]) }
        $raw = $null
    }
    Write-Host 'Preserve this session. Do not rerun or delete partially created objects.'
    exit 10
} finally {
    if ($null -ne $process) { if (!$process.HasExited) { $process.Kill(); $process.WaitForExit() }; $process.Dispose() }
    if ($null -ne $payload) { [Array]::Clear($payload,0,$payload.Length) }
    $sql=$null; $verifiers=$null; $ownerPassword=$null; $appPassword=$null
}
