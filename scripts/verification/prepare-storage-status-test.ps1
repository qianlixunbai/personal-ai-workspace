# B4A metadata status only. New empty database/roles; no old test/dev/user databases.
[CmdletBinding()]
param([switch]$Finish)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($PSVersionTable.PSEdition -ne 'Desktop'){throw 'Use Windows PowerShell 5.1.'}
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Management/Microsoft.PowerShell.Management.psd1') -ErrorAction Stop
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $repo 'scripts/release/release-functions.ps1')
$private=Join-Path $repo '.runtime/workspace'
$authorization=Join-Path $private 'b1-admin.dpapi'
$credentials=Join-Path $private 'b4a-test-credentials.json'
$account=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function Assert-Private([string]$Path) {
    for($part=$Path;$part;$part=[IO.Path]::GetDirectoryName($part)) {
        if((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked private path rejected.'}
    }
    $acl=Get-Acl -LiteralPath $Path
    if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $account){throw 'Private owner required.'}
    $rules=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if($rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $account -or $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl){throw 'Current-user-only ACL required.'}
}
function Invoke-Admin([string]$Database,[string]$Sql) {
    $info=[Diagnostics.ProcessStartInfo]::new('D:\PostgreSQL\bin\psql.exe')
    $info.Arguments='-X -w -A -t -h 127.0.0.1 -p 5432 -U postgres -d '+$Database+' -v ON_ERROR_STOP=1'
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardInput=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.EnvironmentVariables['PGPASSWORD']=$script:adminPassword
    $info.EnvironmentVariables['PGCONNECT_TIMEOUT']='5'
    $process=$null
    try {
        $process=[Diagnostics.Process]::Start($info); $process.StandardInput.WriteLine($Sql); $process.StandardInput.Close()
        $output=$process.StandardOutput.ReadToEnd(); $process.StandardError.ReadToEnd() | Out-Null; $process.WaitForExit()
        if($process.ExitCode -ne 0){throw 'B4A preparation rejected; no raw SQL or secret diagnostics.'}
        return $output.Trim()
    }finally{$info.EnvironmentVariables.Remove('PGPASSWORD'); if($null -ne $process){$process.Dispose()}}
}
function New-TaskPassword {
    $bytes=New-Object byte[] 32; $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($bytes);return [Convert]::ToBase64String($bytes)}finally{$rng.Dispose();[Array]::Clear($bytes,0,$bytes.Length)}
}
Assert-Private $private; Assert-Private $authorization
if(-not $Finish -and (Test-Path -LiteralPath $credentials)){throw 'B4A configuration exists; refusing overwrite.'}
$secure=$null; $buffer=[IntPtr]::Zero; $script:adminPassword=$null
try {
    $secure=ConvertTo-SecureString ([IO.File]::ReadAllText($authorization)); $buffer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $script:adminPassword=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($buffer)
    if($Finish) {
        Assert-Private $credentials; $c=[IO.File]::ReadAllText($credentials) | ConvertFrom-Json
        foreach($role in @($c.ownerUsername,$c.appUsername)) {
            if($role -cnotmatch '^workspace_b4a_(owner|app)_[0-9a-f]{12}$'){throw 'Unexpected role name.'}
            Invoke-Admin 'postgres' "ALTER ROLE $role NOLOGIN PASSWORD NULL;" | Out-Null
        }
        $audit=Invoke-Admin 'postgres' "SELECT json_build_object('rolesDisabled',NOT EXISTS(SELECT FROM pg_roles WHERE rolname IN ('$($c.ownerUsername)','$($c.appUsername)') AND rolcanlogin),'passwordsRemoved',NOT EXISTS(SELECT FROM pg_authid WHERE rolname IN ('$($c.ownerUsername)','$($c.appUsername)') AND rolpassword IS NOT NULL),'activeClients',(SELECT count(*) FROM pg_stat_activity WHERE usename IN ('$($c.ownerUsername)','$($c.appUsername)')));" | ConvertFrom-Json
        if(-not $audit.rolesDisabled -or -not $audit.passwordsRemoved -or $audit.activeClients -ne 0){throw 'B4A cleanup audit failed.'}
        $evidence=Join-Path $c.maintenanceRoot 'cleanup-evidence.json'
        if(Test-Path -LiteralPath $evidence){throw 'Cleanup evidence already exists.'}
        [IO.File]::WriteAllText($evidence,($audit | ConvertTo-Json),[Text.UTF8Encoding]::new($false)); Set-ReleasePrivateAcl $evidence $false
        Remove-Item -LiteralPath $credentials -Force; Remove-Item -LiteralPath $authorization -Force
        Write-Host 'B4A roles disabled/passwords removed; temporary credentials deleted; isolated database retained.'
        return
    }
    $facts=Invoke-Admin 'postgres' "SELECT json_build_object('database',current_database(),'host',host(inet_server_addr()),'port',inet_server_port(),'major',current_setting('server_version_num')::int/10000,'data',current_setting('data_directory'));" | ConvertFrom-Json
    if($facts.database -ne 'postgres' -or $facts.host -ne '127.0.0.1' -or $facts.port -ne 5432 -or $facts.major -ne 17 -or $facts.data.Replace('\','/').TrimEnd('/') -ine 'D:/PostgreSQL/data'){throw 'Unexpected PostgreSQL instance.'}
    $suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
    $owner='workspace_b4a_owner_'+$suffix; $app='workspace_b4a_app_'+$suffix; $database='personal_ai_workspace_b4a_'+$suffix
    $ownerPassword=New-TaskPassword; $appPassword=New-TaskPassword; $receipt='workspace-b4a:'+([Guid]::NewGuid().ToString())
    $root=Join-Path $private ('b4a-'+$suffix); [IO.Directory]::CreateDirectory($root) | Out-Null; Set-ReleasePrivateAcl $root $true
    $json=@{database=$database;ownerUsername=$owner;ownerPassword=$ownerPassword;appUsername=$app;appPassword=$appPassword;ownershipReceipt=$receipt;maintenanceRoot=$root} | ConvertTo-Json -Compress
    # Persist recovery credentials before creating any server objects, never overwrite a previous attempt.
    $stream=[IO.File]::Open($credentials,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {Set-ReleasePrivateAcl $credentials $false; Assert-Private $credentials; $bytes=[Text.Encoding]::UTF8.GetBytes($json)
        try{$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{[Array]::Clear($bytes,0,$bytes.Length)}
    }finally{$stream.Dispose()}
    Invoke-Admin 'postgres' @"
SET log_statement='none'; SET log_min_error_statement='panic'; SET log_min_duration_statement=-1;
CREATE ROLE $owner LOGIN PASSWORD '$ownerPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE $app LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
"@ | Out-Null
    Invoke-Admin 'postgres' "CREATE DATABASE $database WITH OWNER=$owner TEMPLATE=template0 ENCODING='UTF8';" | Out-Null
    Invoke-Admin $database "COMMENT ON DATABASE $database IS '$receipt'; REVOKE ALL ON DATABASE $database FROM PUBLIC; GRANT CONNECT ON DATABASE $database TO $app; REVOKE ALL ON SCHEMA public FROM PUBLIC; CREATE SCHEMA workspace AUTHORIZATION $owner; GRANT USAGE ON SCHEMA workspace TO $app;" | Out-Null
    Write-Host ('B4A fresh isolated metadata target ready: '+$database)
}finally{
    if($buffer -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($buffer)}
    if($null -ne $secure){$secure.Dispose()}
    $script:adminPassword=$null; $ownerPassword=$null; $appPassword=$null; $json=$null
}
