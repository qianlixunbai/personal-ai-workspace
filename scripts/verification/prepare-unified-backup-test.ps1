# Explicit B3 synthetic test preparation. Does not run migrations or reset B1/B2 roles/data.
[CmdletBinding()]
param([switch]$Finish, [switch]$KeepLocalAuthorization, [string]$BootstrapBackup, [switch]$RenewRestoreTargets)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSEdition -ne 'Desktop') { throw 'Use Windows PowerShell 5.1.' }
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $repo 'scripts\release\release-functions.ps1')
$private = Join-Path $repo '.runtime\workspace'
$adminFile = Join-Path $private 'b1-admin.dpapi'
$credentialFile = Join-Path $private 'b3-test-credentials.json'
$psql = 'D:\PostgreSQL\bin\psql.exe'
$account = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function Assert-Private([string]$Path) {
    for ($part = $Path; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if (((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked maintenance path rejected.' }
    }
    $acl = Get-Acl -LiteralPath $Path
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if (-not $acl.AreAccessRulesProtected -or $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $account -or
        $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $account -or $rules[0].IsInherited -or
        $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) { throw 'Current-user-only protected ACL required.' }
}
function Invoke-Admin([string]$Database, [string]$Sql) {
    $info = [Diagnostics.ProcessStartInfo]::new($psql)
    $info.Arguments = '-X -w -A -t -h 127.0.0.1 -p 5432 -U postgres -d ' + $Database + ' -v ON_ERROR_STOP=1'
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $info.EnvironmentVariables['PGPASSWORD'] = $script:adminPassword
    $info.EnvironmentVariables['PGCONNECT_TIMEOUT'] = '5'; $info.EnvironmentVariables['PGCLIENTENCODING'] = 'UTF8'
    $process = $null
    try {
        $process = [Diagnostics.Process]::Start($info)
        $process.StandardInput.WriteLine($Sql); $process.StandardInput.Close()
        $output = $process.StandardOutput.ReadToEnd(); $process.StandardError.ReadToEnd() | Out-Null
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'B3 administrator operation rejected; inspect target ownership/state locally. No raw SQL/secret output.' }
        return $output.Trim()
    } finally { $info.EnvironmentVariables.Remove('PGPASSWORD'); if ($null -ne $process) { $process.Dispose() } }
}
function New-TaskPassword {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return [Convert]::ToBase64String($bytes) }
    finally { $rng.Dispose(); [Array]::Clear($bytes,0,$bytes.Length) }
}
Assert-Private $private; Assert-Private $adminFile
if (-not $Finish -and -not $RenewRestoreTargets -and (Test-Path -LiteralPath $credentialFile)) { throw 'B3 credentials already exist; refusing overwrite.' }
$secure = $null; $buffer = [IntPtr]::Zero; $script:adminPassword = $null
try {
    $secure = ConvertTo-SecureString ([IO.File]::ReadAllText($adminFile))
    $buffer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $script:adminPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($buffer)
    if ($RenewRestoreTargets) {
        Assert-Private $credentialFile
        $renew = [IO.File]::ReadAllText($credentialFile) | ConvertFrom-Json
        if ($renew.ownerUsername -cnotmatch '^workspace_b3_owner_[0-9a-f]{12}$' -or $renew.interruptedDatabase -cnotmatch '^personal_ai_workspace_b3_[0-9a-f]{12}$' -or
            $renew.ownershipReceipt -cnotmatch '^workspace-b3:[0-9a-f-]{36}$') { throw 'Unknown target ownership; stopped.' }
        $empty = Invoke-Admin $renew.interruptedDatabase "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','workspace');"
        if ($empty -ne '0') { throw 'Spare target is not empty; no reuse/reset.' }
        $database = 'personal_ai_workspace_b3_' + [Guid]::NewGuid().ToString('N').Substring(0,12)
        Invoke-Admin 'postgres' "CREATE DATABASE $database WITH OWNER=$($renew.ownerUsername) TEMPLATE=template0 ENCODING='UTF8';" | Out-Null
        Invoke-Admin $database "COMMENT ON DATABASE $database IS '$($renew.ownershipReceipt)'; REVOKE ALL ON DATABASE $database FROM PUBLIC; GRANT CONNECT ON DATABASE $database TO $($renew.appUsername); REVOKE ALL ON SCHEMA public FROM PUBLIC;" | Out-Null
        $renew.targetDatabase=$renew.interruptedDatabase; $renew.interruptedDatabase=$database
        $bytes=[Text.Encoding]::UTF8.GetBytes(($renew | ConvertTo-Json -Compress))
        try {
            $stream=[IO.File]::Open($credentialFile,[IO.FileMode]::Truncate,[IO.FileAccess]::Write,[IO.FileShare]::None)
            try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        } finally { [Array]::Clear($bytes,0,$bytes.Length); $renew=$null }
        Write-Host 'Fresh spare target selected and one new interruption target created; failed DB/files retained.'
        return
    }
    if ($Finish) {
        Assert-Private $credentialFile
        $completed = [IO.File]::ReadAllText($credentialFile) | ConvertFrom-Json
        foreach ($role in @($completed.sourceUsername,$completed.ownerUsername,$completed.appUsername)) {
            if ($role -cnotmatch '^workspace_b3_(backup|owner|app)_[0-9a-f]{12}$') { throw 'Unknown role; cleanup stopped.' }
            Invoke-Admin 'postgres' "ALTER ROLE $role NOLOGIN PASSWORD NULL;" | Out-Null
        }
        $sourceRole = $completed.sourceUsername
        Invoke-Admin 'personal_ai_workspace_test' @"
REVOKE ALL ON ALL TABLES IN SCHEMA public,workspace FROM $sourceRole;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public,workspace FROM $sourceRole;
REVOKE ALL ON SCHEMA public,workspace FROM $sourceRole;
REVOKE ALL ON DATABASE personal_ai_workspace_test FROM $sourceRole;
"@ | Out-Null
        if ($completed.PSObject.Properties.Name -contains 'seedDatabase') {
            if ($completed.seedDatabase -cnotmatch '^personal_ai_workspace_b3_[0-9a-f]{12}$') { throw 'Unknown seed database; cleanup stopped.' }
            Invoke-Admin $completed.seedDatabase "REVOKE ALL ON ALL TABLES IN SCHEMA public,workspace FROM $sourceRole; REVOKE ALL ON ALL SEQUENCES IN SCHEMA public,workspace FROM $sourceRole; REVOKE ALL ON SCHEMA public,workspace FROM $sourceRole; REVOKE ALL ON DATABASE $($completed.seedDatabase) FROM $sourceRole;" | Out-Null
        }
        $cleanup = Invoke-Admin 'personal_ai_workspace_test' @"
SELECT json_build_object(
 'taskRolesDisabled',NOT EXISTS(SELECT FROM pg_roles WHERE rolname IN ('$($completed.sourceUsername)','$($completed.ownerUsername)','$($completed.appUsername)') AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)),
 'taskPasswordsRemoved',NOT EXISTS(SELECT FROM pg_authid WHERE rolname IN ('$($completed.sourceUsername)','$($completed.ownerUsername)','$($completed.appUsername)') AND rolpassword IS NOT NULL),
 'activeTaskClients',(SELECT count(*) FROM pg_stat_activity WHERE usename IN ('$($completed.sourceUsername)','$($completed.ownerUsername)','$($completed.appUsername)')),
 'financeHistory',(SELECT count(*) FROM public.flyway_schema_history WHERE success),
 'workspaceHistory',(SELECT count(*) FROM workspace.flyway_schema_history WHERE success),
 'retainedB3Databases',(SELECT json_agg(datname ORDER BY datname) FROM pg_database WHERE datname ~ '^personal_ai_workspace_b3_[0-9a-f]{12}$' AND shobj_description(oid,'pg_database') LIKE 'workspace-b3:%'));
"@ | ConvertFrom-Json
        if (-not $cleanup.taskRolesDisabled -or -not $cleanup.taskPasswordsRemoved -or $cleanup.activeTaskClients -ne 0 -or
            $cleanup.financeHistory -ne 21 -or $cleanup.workspaceHistory -ne 2) { throw 'Task cleanup audit failed; credentials retained for local diagnosis.' }
        $cleanupFile = Join-Path $completed.maintenanceRoot 'cleanup-evidence.json'
        if (Test-Path -LiteralPath $cleanupFile) { throw 'Cleanup evidence already exists; refusing overwrite.' }
        [IO.File]::WriteAllText($cleanupFile,($cleanup | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
        Set-ReleasePrivateAcl $cleanupFile $false
        Remove-Item -LiteralPath $credentialFile -Force
        if (-not $KeepLocalAuthorization) { Remove-Item -LiteralPath $adminFile -Force }
        Write-Host 'B3 task roles disabled, passwords removed, source grants revoked; credentials deleted. Databases/files retained.'
        return
    }
    $facts = Invoke-Admin 'personal_ai_workspace_test' @'
SELECT json_build_object('database',current_database(),'host',host(inet_server_addr()),'port',inet_server_port(),
 'major',current_setting('server_version_num')::int/10000,'data',current_setting('data_directory'),
 'finance',(SELECT count(*) FROM public.flyway_schema_history WHERE success),
 'workspace',(SELECT count(*) FROM workspace.flyway_schema_history WHERE success),
 'clients',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'),
 'jobs',(SELECT count(*) FROM workspace.knowledge_jobs WHERE state IN ('PENDING','PARSING')),
 'deletes',(SELECT count(*) FROM workspace.knowledge_deletes));
'@ | ConvertFrom-Json
    if ($facts.database -ne 'personal_ai_workspace_test' -or $facts.host -ne '127.0.0.1' -or $facts.port -ne 5432 -or
        $facts.major -ne 17 -or $facts.data.Replace('\','/').TrimEnd('/') -ine 'D:/PostgreSQL/data' -or
        $facts.finance -ne 21 -or $facts.workspace -ne 2 -or $facts.clients -ne 0 -or $facts.jobs -ne 0 -or $facts.deletes -ne 0) {
        throw 'Existing synthetic source state is not ready for B3; no initialization/reset performed.'
    }
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0,12)
    $sourceRole = 'workspace_b3_backup_' + $suffix
    $ownerRole = 'workspace_b3_owner_' + $suffix
    $appRole = 'workspace_b3_app_' + $suffix
    $sourcePassword = New-TaskPassword; $ownerPassword = New-TaskPassword; $appPassword = New-TaskPassword
    $receipt = 'workspace-b3:' + [Guid]::NewGuid().ToString()
    $databases = @(('personal_ai_workspace_b3_' + [Guid]::NewGuid().ToString('N').Substring(0,12)),
                   ('personal_ai_workspace_b3_' + [Guid]::NewGuid().ToString('N').Substring(0,12)))
    if ($BootstrapBackup) {
        $databases += ('personal_ai_workspace_b3_' + [Guid]::NewGuid().ToString('N').Substring(0,12))
    }
    $roles = @"
CREATE ROLE $sourceRole LOGIN PASSWORD '$sourcePassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE $ownerRole LOGIN PASSWORD '$ownerPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE $appRole LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
"@
    # Do not let server statement/error logging record CREATE ROLE PASSWORD statements.
    Invoke-Admin 'postgres' ("SET log_statement='none'; SET log_min_error_statement='panic'; SET log_min_duration_statement=-1;`n" + $roles) | Out-Null
    Invoke-Admin 'personal_ai_workspace_test' @"
GRANT CONNECT ON DATABASE personal_ai_workspace_test TO $sourceRole;
GRANT USAGE ON SCHEMA public,workspace TO $sourceRole;
GRANT SELECT,UPDATE ON ALL TABLES IN SCHEMA public,workspace TO $sourceRole;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public,workspace TO $sourceRole;
"@ | Out-Null
    foreach ($database in $databases) {
        if ((Invoke-Admin 'postgres' "SELECT count(*) FROM pg_database WHERE datname='$database';") -ne '0') { throw 'Target already exists; never reuse/drop it.' }
        Invoke-Admin 'postgres' "CREATE DATABASE $database WITH OWNER=$ownerRole TEMPLATE=template0 ENCODING='UTF8';" | Out-Null
        Invoke-Admin $database @"
COMMENT ON DATABASE $database IS '$receipt';
REVOKE ALL ON DATABASE $database FROM PUBLIC;
GRANT CONNECT ON DATABASE $database TO $appRole;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
"@ | Out-Null
    }
    $root = Join-Path $private ('b3-' + $suffix)
    [IO.Directory]::CreateDirectory($root) | Out-Null
    Set-ReleasePrivateAcl $root $true; Assert-Private $root
    $configuration = @{
        sourceDatabase='personal_ai_workspace_test'; sourceUsername=$sourceRole; sourcePassword=$sourcePassword
        ownerUsername=$ownerRole; ownerPassword=$ownerPassword; appUsername=$appRole; appPassword=$appPassword
        targetDatabase=$databases[0]; interruptedDatabase=$databases[1]; ownershipReceipt=$receipt
        maintenanceRoot=$root; tools='D:\PostgreSQL\bin'
        knowledgeRoot=(Join-Path $repo '.verification\f2.3-b2-knowledge-postgresql\synthetic-knowledge')
    }
    if ($BootstrapBackup) { $configuration.seedDatabase=$databases[2]; $configuration.bootstrapBackup=[IO.Path]::GetFullPath($BootstrapBackup) }
    $config = $configuration | ConvertTo-Json -Compress
    $stream = [IO.File]::Open($credentialFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        Set-ReleasePrivateAcl $credentialFile $false; Assert-Private $credentialFile
        $bytes = [Text.Encoding]::UTF8.GetBytes($config)
        try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) }
        finally { [Array]::Clear($bytes,0,$bytes.Length) }
    } finally { $stream.Dispose() }
    Write-Host ('B3 preparation ready: ' + $databases.Count + ' new isolated databases; maintenance root ' + $root)
} finally {
    if ($buffer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($buffer) }
    if ($null -ne $secure) { $secure.Dispose() }
    $script:adminPassword=$null; $sourcePassword=$null; $ownerPassword=$null; $appPassword=$null; $roles=$null; $config=$null
}
