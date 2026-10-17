# B3E: only postgres catalog and a newly created target. Never connect to test/dev/retained B3 DBs.
[CmdletBinding()]
param([switch]$Finish)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSEdition -ne 'Desktop') { throw 'Use Windows PowerShell 5.1.' }
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Management/Microsoft.PowerShell.Management.psd1') -ErrorAction Stop
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $repo 'scripts\release\release-functions.ps1')
$private=Join-Path $repo '.runtime\workspace'
$adminFile=Join-Path $private 'b1-admin.dpapi'
$credentialFile=Join-Path $private 'b3e-test-credentials.json'
$psql='D:\PostgreSQL\bin\psql.exe'
$account=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function Assert-Private([string]$Path) {
    for($part=$Path;$part;$part=[IO.Path]::GetDirectoryName($part)) {
        if(((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked private path rejected.' }
    }
    $acl=Get-Acl -LiteralPath $Path
    $rules=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if(-not $acl.AreAccessRulesProtected -or $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $account -or
       $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $account -or $rules[0].IsInherited -or
       $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) { throw 'Current-user-only protected ACL required.' }
}
function Invoke-Admin([string]$Database,[string]$Sql) {
    $info=[Diagnostics.ProcessStartInfo]::new($psql)
    $info.Arguments='-X -w -A -t -h 127.0.0.1 -p 5432 -U postgres -d '+$Database+' -v ON_ERROR_STOP=1'
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardInput=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.EnvironmentVariables['PGPASSWORD']=$script:adminPassword
    $info.EnvironmentVariables['PGCONNECT_TIMEOUT']='5'; $info.EnvironmentVariables['PGCLIENTENCODING']='UTF8'
    $process=$null
    try {
        $process=[Diagnostics.Process]::Start($info)
        $process.StandardInput.WriteLine($Sql); $process.StandardInput.Close()
        $output=$process.StandardOutput.ReadToEnd(); $process.StandardError.ReadToEnd() | Out-Null
        $process.WaitForExit()
        if($process.ExitCode -ne 0) { throw 'B3E administrator operation rejected; no raw SQL or secret diagnostics.' }
        return $output.Trim()
    } finally { $info.EnvironmentVariables.Remove('PGPASSWORD'); if($null -ne $process){$process.Dispose()} }
}
function New-TaskPassword {
    $bytes=New-Object byte[] 32; $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return [Convert]::ToBase64String($bytes) }
    finally { $rng.Dispose(); [Array]::Clear($bytes,0,$bytes.Length) }
}
Assert-Private $private; Assert-Private $adminFile
if(-not $Finish -and (Test-Path -LiteralPath $credentialFile)) { throw 'Task credentials exist; refusing overwrite.' }
$secure=$null; $buffer=[IntPtr]::Zero; $script:adminPassword=$null
try {
    $secure=ConvertTo-SecureString ([IO.File]::ReadAllText($adminFile))
    $buffer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $script:adminPassword=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($buffer)
    if($Finish) {
        Assert-Private $credentialFile
        $config=[IO.File]::ReadAllText($credentialFile) | ConvertFrom-Json
        foreach($role in @($config.ownerUsername,$config.appUsername)) {
            if($role -cnotmatch '^workspace_b3_(owner|app)_[0-9a-f]{12}$') { throw 'Unknown generated role; stopped.' }
            Invoke-Admin 'postgres' "ALTER ROLE $role NOLOGIN PASSWORD NULL;" | Out-Null
        }
        $audit=Invoke-Admin 'postgres' @"
SELECT json_build_object('rolesDisabled',NOT EXISTS(SELECT FROM pg_roles WHERE rolname IN ('$($config.ownerUsername)','$($config.appUsername)') AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)),
 'passwordsRemoved',NOT EXISTS(SELECT FROM pg_authid WHERE rolname IN ('$($config.ownerUsername)','$($config.appUsername)') AND rolpassword IS NOT NULL),
 'activeClients',(SELECT count(*) FROM pg_stat_activity WHERE usename IN ('$($config.ownerUsername)','$($config.appUsername)')));
"@ | ConvertFrom-Json
        if(-not $audit.rolesDisabled -or -not $audit.passwordsRemoved -or $audit.activeClients -ne 0) { throw 'Task cleanup audit failed.' }
        $evidence=Join-Path $config.maintenanceRoot 'cleanup-evidence.json'
        if(Test-Path -LiteralPath $evidence){throw 'Cleanup evidence exists; refusing overwrite.'}
        [IO.File]::WriteAllText($evidence,($audit | ConvertTo-Json),[Text.UTF8Encoding]::new($false)); Set-ReleasePrivateAcl $evidence $false
        Remove-Item -LiteralPath $credentialFile -Force; Remove-Item -LiteralPath $adminFile -Force
        Write-Host 'B3E roles disabled, passwords removed and temporary credentials deleted; new target/files retained.'
        return
    }
    $facts=Invoke-Admin 'postgres' @'
SELECT json_build_object('database',current_database(),'host',host(inet_server_addr()),'port',inet_server_port(),
 'major',current_setting('server_version_num')::int/10000,'data',current_setting('data_directory'));
'@ | ConvertFrom-Json
    if($facts.database -ne 'postgres' -or $facts.host -ne '127.0.0.1' -or $facts.port -ne 5432 -or
       $facts.major -ne 17 -or $facts.data.Replace('\','/').TrimEnd('/') -ine 'D:/PostgreSQL/data') { throw 'Unexpected local PostgreSQL instance.' }
    $suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
    # Newly created synthetic staging, with a private DACL from creation; no existing ACL repair.
    $root=Join-Path 'C:\' ('PAW-B3E-'+$suffix)
    if(Test-Path -LiteralPath $root){throw 'Maintenance root exists.'}
    $rootAcl=[Security.AccessControl.DirectorySecurity]::new()
    $rootAcl.SetAccessRuleProtection($true,$false)
    $rootAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.WindowsIdentity]::GetCurrent().User,'FullControl',[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
    [IO.DirectoryInfo]::new($root).Create($rootAcl); Assert-Private $root
    $owner='workspace_b3_owner_'+$suffix; $app='workspace_b3_app_'+$suffix
    $database='personal_ai_workspace_b3_'+$suffix; $receipt='workspace-b3:'+([Guid]::NewGuid().ToString())
    $ownerPassword=New-TaskPassword; $appPassword=New-TaskPassword
    # All role names are new; never ALTER LOGIN/re-enable a preserved B3 role.
    Invoke-Admin 'postgres' @"
SET log_statement='none'; SET log_min_error_statement='panic'; SET log_min_duration_statement=-1;
CREATE ROLE $owner LOGIN PASSWORD '$ownerPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE $app LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
"@ | Out-Null
    Invoke-Admin 'postgres' "CREATE DATABASE $database WITH OWNER=$owner TEMPLATE=template0 ENCODING='UTF8';" | Out-Null
    Invoke-Admin $database "COMMENT ON DATABASE $database IS '$receipt'; REVOKE ALL ON DATABASE $database FROM PUBLIC; GRANT CONNECT ON DATABASE $database TO $app; REVOKE ALL ON SCHEMA public FROM PUBLIC;" | Out-Null
    $json=@{targetDatabase=$database;ownerUsername=$owner;ownerPassword=$ownerPassword;appUsername=$app;appPassword=$appPassword
        ownershipReceipt=$receipt;maintenanceRoot=$root;tools='D:\PostgreSQL\bin'
        backupRoot=(Join-Path $private 'b3-ffb4fd708e51\backup')} | ConvertTo-Json -Compress
    $stream=[IO.File]::Open($credentialFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        Set-ReleasePrivateAcl $credentialFile $false; Assert-Private $credentialFile
        $bytes=[Text.Encoding]::UTF8.GetBytes($json)
        try{$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{[Array]::Clear($bytes,0,$bytes.Length)}
    }finally{$stream.Dispose()}
    Write-Host ('B3E new isolated target ready: '+$database+'; private root '+$root)
} finally {
    if($buffer -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($buffer)}
    if($null -ne $secure){$secure.Dispose()}
    $script:adminPassword=$null;$ownerPassword=$null;$appPassword=$null;$json=$null
}
