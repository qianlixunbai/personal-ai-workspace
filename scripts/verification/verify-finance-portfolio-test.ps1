#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$FixtureReceipt, [string]$JavaHome = 'D:\MC\JAVA\JAVA21', [switch]$ExistingPositionsOnly, [string]$ToolingReceipt = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$target = Get-Content -LiteralPath $FixtureReceipt -Raw | ConvertFrom-Json
$tooling = if ($ToolingReceipt) { Get-Content -LiteralPath $ToolingReceipt -Raw | ConvertFrom-Json } else { $target }
foreach ($selected in @($target, $tooling)) {
    if ($selected.suffix -cnotmatch '^[a-f0-9]{12}$' -or $selected.root -cne (Join-Path 'D:\PersonalAiWorkspace-Fnext1' $selected.suffix) -or $selected.port -lt 55000 -or $selected.port -gt 55999) { throw 'ISOLATED_FIXTURE_REQUIRED' }
}
$classpath = (Join-Path $tooling.root 'tooling/classes') + ';' + (Join-Path $tooling.root 'tooling/lib/*')
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d (Join-Path $tooling.root 'tooling/classes') (Join-Path $repo 'bootstrap/tests/FinancePortfolioVerification.java')
if ($LASTEXITCODE) { throw 'PROBE_COMPILATION_FAILED' }
$bytes = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes((Join-Path $target.root 'fixture.dpapi')), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
try {
    $start = [Diagnostics.ProcessStartInfo]::new((Join-Path $JavaHome 'bin/java.exe'))
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in @('-cp', $classpath, 'FinancePortfolioVerification')) { $start.ArgumentList.Add($argument) }
    if ($ExistingPositionsOnly) { $start.ArgumentList.Add('--existing-positions-only') }
    $process = [Diagnostics.Process]::Start($start)
    $process.StandardInput.BaseStream.Write($bytes); $process.StandardInput.Close()
    $result = $process.StandardOutput.ReadToEnd(); $errors = $process.StandardError.ReadToEnd()
    if (!$process.WaitForExit(30000)) { throw 'SOURCE_PROBE_TIMEOUT' }
    Write-Output $result.Trim()
    if ($process.ExitCode -or $errors) { throw 'SOURCE_PROBE_FAILED' }
} finally { [Security.Cryptography.CryptographicOperations]::ZeroMemory($bytes) }
