# Local interactive secret entry only. No SQL, password output or administrator plaintext file.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSEdition -ne 'Desktop') { throw '请使用 Windows PowerShell 5.1。' }
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$directory = Join-Path $repo '.runtime\workspace'
$file = Join-Path $directory 'b1-admin.dpapi'
. (Join-Path $repo 'scripts\release\release-functions.ps1')
for ($part = $directory; $part; $part = [IO.Path]::GetDirectoryName($part)) {
    if (((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw '已停止：本地凭据目录包含链接。'
    }
}
$account = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$acl = Get-Acl -LiteralPath $directory
$rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
if (-not $acl.AreAccessRulesProtected -or $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $account -or
    $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $account -or $rules[0].IsInherited -or
    $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
    throw '已停止：需要当前用户专用的受保护凭据目录。'
}
if (Test-Path -LiteralPath $file) { throw '已停止：本次授权文件已存在，不会覆盖。' }
Write-Host '请在这个本机窗口输入 PostgreSQL 管理员 postgres 的现有密码。' -ForegroundColor Cyan
Write-Host '密码不会显示、不会发到聊天；仅供本轮测试库检查和准备，使用结束后删除加密暂存文件。'
$secure = Read-Host '管理员密码' -AsSecureString
try {
    if ($secure.Length -eq 0) { throw '密码不能为空。' }
    # DPAPI binds this temporary encrypted value to the current Windows user and machine.
    $encrypted = ConvertFrom-SecureString $secure
    $stream = [IO.File]::Open($file, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        Set-ReleasePrivateAcl $file $false
        $bytes = [Text.Encoding]::ASCII.GetBytes($encrypted)
        try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) }
        finally { [Array]::Clear($bytes, 0, $bytes.Length) }
    } finally { $stream.Dispose() }
    Write-Host '本机授权已就绪，后续检查和测试由助手继续。可以关闭此窗口。' -ForegroundColor Green
    Start-Sleep -Seconds 3
} finally { $secure.Dispose(); $encrypted = $null }
