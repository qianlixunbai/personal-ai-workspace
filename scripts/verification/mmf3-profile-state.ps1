#Requires -Version 7.4
[CmdletBinding()]
param(
    [ValidateSet('Inspect', 'Prepare', 'Verify')][string]$Mode = 'Inspect',
    [string]$RunId,
    [switch]$OptIn
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'Prepare' -and !$OptIn) { throw 'Prepare requires explicit -OptIn; nothing created.' }
if ($Mode -ne 'Prepare' -and $OptIn) { throw '-OptIn is only valid for Prepare.' }
if (!$IsWindows) { throw 'MMF3 Profile state requires Windows.' }
if ($RunId -cnotmatch '\A[0-9a-f]{32}\z') { throw 'A fresh 32-character lowercase hexadecimal RunId is required.' }

# Never reuse a process-loaded contract, even from an earlier invocation of this script.
# A fresh pwsh -NoProfile -File process is required for each call.
$typeName = 'PersonalAiWorkspace.Desktop.Mmf3ProfileState'
foreach ($assembly in [AppDomain]::CurrentDomain.GetAssemblies()) {
    if ($null -ne $assembly.GetType($typeName, $false, $false)) {
        throw 'MMF3 Profile contract already loaded; use a fresh pwsh -NoProfile -File process. Nothing created or modified.'
    }
}

# Compile ONLY the marked narrow contract, verbatim from the owning source. No Desktop assembly
# loading, Bootstrap framework, arbitrary root input, launch, credentials or token-file operation.
$sourcePath = Join-Path $PSScriptRoot '../../desktop/src/PersonalAiWorkspace.Desktop/Mmf3AcceptanceLaunch.cs'
$source = Get-Content -LiteralPath $sourcePath -Raw
$begin = '// BEGIN MMF3 PROFILE STATE'
$end = '// END MMF3 PROFILE STATE'
$start = $source.IndexOf($begin, [StringComparison]::Ordinal)
$finish = $source.IndexOf($end, [StringComparison]::Ordinal)
if ($start -lt 0 -or $finish -le $start) { throw 'Profile contract source markers missing.' }
$contract = $source.Substring($start, $finish - $start)
$header = @'
#nullable enable
using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.Win32.SafeHandles;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
namespace PersonalAiWorkspace.Desktop;
'@
$assemblyTypes = Add-Type -TypeDefinition ($header + "`n" + $contract) -PassThru
$type = $assemblyTypes | Where-Object FullName -eq $typeName
$method = switch ($Mode) { 'Prepare' { 'Prepare' }; 'Verify' { 'Verify' }; default { 'Verify' } }
# Inspect defaults to a fully verified read-only snapshot, including rejection of missing state.
try { $root = $type.GetMethod($method, [Reflection.BindingFlags]'Static, NonPublic').Invoke($null, @($RunId)) }
catch [Reflection.TargetInvocationException] { throw $_.Exception.InnerException }
[pscustomobject]@{
    Mode = $Mode
    RunId = $RunId
    Root = $root
    RuntimeEndpoint = 'http://127.0.0.1:18765'
    CredentialTarget = "PersonalAiWorkspace/Desktop/MMF3/$RunId/Runtime/127.0.0.1:18765"
    TokenFile = Join-Path $root 'credentials/client-token'
    ModelState = Join-Path $root 'model-state'
    Data = Join-Path $root 'data'
    WebView2Profile = Join-Path $root 'webview2-profile'
    ChromeProfile = Join-Path $root 'chrome-profile'
    BrowserTestExtension = Join-Path $root 'browser-test-extension'
    Evidence = Join-Path $root 'evidence'
    Logs = Join-Path $root 'logs'
    DesktopArguments = @('--mmf3-profile-acceptance', $RunId)
    InstanceSuffix = ".MMF3.$RunId"
} | ConvertTo-Json -Depth 3
