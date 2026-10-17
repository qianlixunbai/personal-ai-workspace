#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('\A[0-9a-f]{32}\z')][string]$RunId,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$JnaJar,
    [Parameter(Mandatory)][string]$JnaPlatformJar
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
foreach ($name in [Environment]::GetEnvironmentVariables().Keys) {
    if ($name -match '\A(JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS)\z|\A(DOTNET_|COREHOST_|COMPLUS_|COR_|SPRING_|WORKSPACE_|SERVER_)') {
        throw 'External toolchain/runtime override rejected before Java execution.'
    }
}
# Read-only state admission. Prepare and Provision must be separate explicit actions on a NEW run.
# This script NEVER starts Workspace main, Spring, Desktop, Chrome or any HTTP/model operation.
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state = & (Join-Path $PSScriptRoot 'mmf3-profile-state.ps1') -Mode Verify -RunId $RunId | ConvertFrom-Json
$helper = Join-Path $repo 'bootstrap/PersonalAiWorkspace.NativeBootstrap/bin/Release/net10.0-windows/PersonalAiWorkspace.NativeBootstrap.exe'
$native = Join-Path $state.Root 'jna-5.17.0-win32-x86-64'
foreach ($path in @($JavaHome, $JnaJar, $JnaPlatformJar)) {
    if (![IO.Path]::IsPathFullyQualified($path) -or $path.StartsWith('\\') -or !(Test-Path -LiteralPath $path)) {
        throw 'Explicit existing local development toolchain/artifacts required.'
    }
}
if ((Get-FileHash -LiteralPath $JnaJar).Hash.ToLowerInvariant() -ne 'b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0') {
    throw 'Fixed JNA artifact required.'
}
if ((Get-FileHash -LiteralPath $JnaPlatformJar).Hash.ToLowerInvariant() -ne 'b7e3d46c87bad2eb409b0e704916bcd81206168e357312dfddd0e253679cd9e0') {
    throw 'Fixed JNA-platform artifact required.'
}
if (@(Get-NetTCPConnection -State Listen -LocalPort 18765 -ErrorAction SilentlyContinue).Count -ne 0) {
    throw '18765 occupied; no process was stopped or reused.'
}
$classes = Join-Path $state.Evidence ('preflight-classes-' + [Guid]::NewGuid().ToString('N'))
# Harness bytecode only, in the already private fixture; not a native extraction/fallback directory.
[IO.Directory]::CreateDirectory($classes) | Out-Null
$classpath = (Join-Path $repo 'target/classes') + ";$JnaJar;$JnaPlatformJar"
& (Join-Path $JavaHome 'bin/javac.exe') '-cp' $classpath '-d' $classes (Join-Path $repo 'bootstrap/tests/ProfileNativeGateProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Preflight probe compile failed.' }
$options = @("-Dworkspace.native.development-profile=$RunId", "-Dworkspace.native.bootstrap=$helper",
    "-Djna.boot.library.path=$native", '-Djna.boot.library.name=jnidispatch', '-Djna.nosys=true',
    '-Djna.noclasspath=true', '-Djna.nounpack=true', "-Djava.io.tmpdir=$($state.Evidence)",
    "-Djna.tmpdir=$($state.Evidence)", '-cp', "$classes;$classpath", 'ProfileNativeGateProbe', 'preflight')
$start = [Diagnostics.ProcessStartInfo]::new((Join-Path $JavaHome 'bin/java.exe'))
$start.UseShellExecute = $false; $start.CreateNoWindow = $true
$start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
foreach ($option in $options) { $start.ArgumentList.Add($option) }
$process = [Diagnostics.Process]::Start($start)
try {
    # Supply both barriers without a Workspace launch or network request.
    $process.StandardInput.Write('12'); $process.StandardInput.Close()
    if (!$process.WaitForExit(30000) -or $process.ExitCode -ne 0) { throw 'Development-only preflight STOP.' }
    if ($process.StandardOutput.ReadToEnd().Trim() -ne "PRELOAD_READY`nADMITTED_DEVELOPMENT_ONLY`nREADY".Replace("`n", [Environment]::NewLine)) {
        throw 'Unexpected preflight response.'
    }
} finally {
    if (!$process.HasExited) { $process.Kill(); $process.WaitForExit(5000) | Out-Null }
    $process.Dispose()
}
@{ result='PASS'; trust='Development-only'; runId=$RunId; runtimeEndpoint=$state.RuntimeEndpoint;
    desktopArguments=$state.DesktopArguments; runtimeStarted=$false; guiStarted=$false;
    chromeStarted=$false; modelRequests=0; credentialInitialization='not performed in preflight' } | ConvertTo-Json -Depth 3
