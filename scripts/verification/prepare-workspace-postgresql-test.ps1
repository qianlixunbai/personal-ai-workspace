# Run locally in Windows PowerShell 5.1. Never pass passwords as arguments.
[CmdletBinding()]
param([switch]$CheckOnly, [switch]$CredentialsOnly)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtime = Join-Path $repo '.runtime'
$private = Join-Path $runtime 'workspace'
$credentialFile = Join-Path $private 'b1-test-credentials.json'
$psql = 'D:\PostgreSQL\bin\psql.exe'
$account = [Security.Principal.WindowsIdentity]::GetCurrent().User
. (Join-Path $repo 'scripts\release\release-functions.ps1')

function Assert-PrivateTestPath([string]$Path, [bool]$Directory) {
    for ($part = $Path; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ((Test-Path -LiteralPath $part) -and
            ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw '已停止：凭据路径包含链接或重解析点。'
        }
    }
    $item = Get-Item -LiteralPath $Path -Force
    $acl = Get-Acl -LiteralPath $Path
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($item.PSIsContainer -ne $Directory -or -not $acl.AreAccessRulesProtected -or
        $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $account.Value -or
        $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $account.Value -or
        $rules[0].AccessControlType -ne 'Allow' -or $rules[0].IsInherited -or
        $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
        throw '已停止：凭据目录和文件必须启用受保护的 ACL，且仅允许当前用户完全控制。'
    }
}

function Read-TestPassword([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $buffer = [IntPtr]::Zero
    try {
        $buffer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        $text = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($buffer)
        if ([string]::IsNullOrWhiteSpace($text)) { throw '已停止：密码不能为空。' }
        return $text
    } finally {
        if ($buffer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($buffer) }
        $secure.Dispose()
    }
}

function Assert-TestLogin([string]$Role, [string]$Password) {
    # Scope libpq credentials to this one child process, never the parent environment.
    $info = [Diagnostics.ProcessStartInfo]::new($psql)
    $info.Arguments = '-X -w -h 127.0.0.1 -p 5432 -d personal_ai_workspace_test -U ' + $Role + ' -c "SELECT 1"'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardErrorEncoding = [Text.Encoding]::UTF8
    $info.EnvironmentVariables['PGPASSWORD'] = $Password
    $info.EnvironmentVariables['PGCONNECT_TIMEOUT'] = '5'
    $info.EnvironmentVariables['PGCLIENTENCODING'] = 'UTF8'
    $process = $null
    try {
        $process = [Diagnostics.Process]::Start($info)
        $process.StandardOutput.ReadToEnd() | Out-Null
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            # Classify locally; never display raw child output or credentials.
            if ($errorText -match '(?i)28P01|password.*(failed|失败|失敗)|密码.*(错误|失败)|authentication failed') {
                throw 'TEST_AUTH_REJECTED'
            }
            throw 'TEST_CONNECTION_FAILED'
        }
    } finally {
        $info.EnvironmentVariables.Remove('PGPASSWORD')
        if ($null -ne $process) { $process.Dispose() }
    }
}

if ($PSVersionTable.PSEdition -ne 'Desktop') { throw '请使用 Windows PowerShell 5.1（powershell.exe）运行此脚本。' }
if ((Get-Service postgresql-x64-17).Status -ne 'Running') { throw '已停止：PostgreSQL 服务尚未运行。' }
if (-not (Test-Path -LiteralPath $psql -PathType Leaf)) { throw '已停止：找不到 psql 程序。' }
Assert-PrivateTestPath $runtime $true
if (-not (Test-Path -LiteralPath $private)) {
    # The parent has already been verified to inherit only the current user's access.
    [IO.Directory]::CreateDirectory($private) | Out-Null
    Set-ReleasePrivateAcl $private $true
}
Assert-PrivateTestPath $private $true
if (Test-Path -LiteralPath $credentialFile) { throw '已停止：测试凭据文件已存在，不会覆盖。' }
if ($CheckOnly) { Write-Host '检查通过：服务和私有凭据目录符合要求；未执行 SQL，也未要求输入密码。'; return }

if (-not $CredentialsOnly) {
    Write-Host '接下来请先输入 PostgreSQL 管理员 postgres 的现有密码，再为两个新 Workspace 账号分别设置密码。'
    Write-Host '设置新密码时需要各输入两次；密码输入不会显示在终端中。'
    Write-Host '仅操作 personal_ai_workspace_test 测试库；如果数据库状态与预期不符，将停止准备。'
    & $psql -X -W -h 127.0.0.1 -p 5432 -U postgres -d personal_ai_workspace_test `
        -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'workspace-postgresql-test-role.sql')
    if ($LASTEXITCODE -ne 0) { throw '已停止：数据库准备失败，未写入凭据文件。' }
} else {
    Write-Host '仅验证已有账号并暂存凭据：不会设置密码、创建角色或修改表和权限。'
}

$created = $false
$appPassword = $null
$migrationPassword = $null
$stage = '应用账号 personal_ai_workspace_b1_app'
try {
    Write-Host '第 1 步：请输入应用账号的现有密码；首次准备时，就是刚才为该账号设置的密码。此处只验证登录，不会更改密码。'
    $appPassword = Read-TestPassword 'personal_ai_workspace_b1_app 的已有密码'
    Assert-TestLogin 'personal_ai_workspace_b1_app' $appPassword
    Write-Host '应用账号登录成功。' -ForegroundColor Green
    $stage = '迁移账号 personal_ai_workspace_b1_migrator'
    Write-Host '第 2 步：请输入迁移账号当前的密码，即你通过 psql 的 \password 设置的密码。'
    $migrationPassword = Read-TestPassword 'personal_ai_workspace_b1_migrator 的已有密码'
    Assert-TestLogin 'personal_ai_workspace_b1_migrator' $migrationPassword
    Write-Host '迁移账号登录成功。' -ForegroundColor Green
    $stage = '验证私有目录权限'
    Assert-PrivateTestPath $private $true
    $stage = '创建私有凭据文件'
    $stream = [IO.File]::Open($credentialFile, [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write, [IO.FileShare]::None)
    $created = $true
    try {
        # Establish and verify protected file ACL before writing any password bytes.
        $stage = '验证私有文件权限'
        Set-ReleasePrivateAcl $credentialFile $false
        Assert-PrivateTestPath $credentialFile $false
        $stage = '写入临时凭据'
        $json = @{
            url = 'jdbc:postgresql://127.0.0.1:5432/personal_ai_workspace_test'
            appUsername = 'personal_ai_workspace_b1_app'
            appPassword = $appPassword
            migrationUsername = 'personal_ai_workspace_b1_migrator'
            migrationPassword = $migrationPassword
        } | ConvertTo-Json -Compress
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) }
        finally { [Array]::Clear($bytes, 0, $bytes.Length); $json = $null }
    } finally { $stream.Dispose() }
    Write-Host '已就绪：两个账号均已真实登录成功，凭据已私有暂存；未保存管理员密码。' -ForegroundColor Green
    Write-Host '测试结束（含失败）后删除临时文件；若测试开始前取消，请手动删除 .runtime\workspace\b1-test-credentials.json。'
} catch {
    if ($created) { Remove-Item -LiteralPath $credentialFile -Force }
    if ($_.Exception.Message -eq 'TEST_AUTH_REJECTED') {
        Write-Host "已停止：数据库拒绝了【$stage】的密码。另一个账号的密码不会因此改变。" -ForegroundColor Red
    } elseif ($_.Exception.Message -eq 'TEST_CONNECTION_FAILED') {
        Write-Host "已停止：【$stage】连接失败，原因尚未确认，不能直接认定密码错误。" -ForegroundColor Red
    } else {
        Write-Host "已停止：未完成【$stage】。请检查本地输入或权限；未输出密码详情。" -ForegroundColor Red
    }
    Write-Host '没有保存临时凭据。'
    exit 1
} finally {
    $appPassword = $null
    $migrationPassword = $null
}
