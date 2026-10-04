[CmdletBinding()]
param([string]$DataDirectory = '', [string]$StateDirectory = '', [string]$TokenFile = '', [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Load the Windows PowerShell built-ins explicitly even with a minimal PATH.
# Do not rely on an inherited PowerShell 7 module-discovery environment.
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1')
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Management\Microsoft.PowerShell.Management.psd1')
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Security\Microsoft.PowerShell.Security.psd1')
. (Join-Path $PSScriptRoot 'release-functions.ps1')
$bundle = Split-Path -Parent $PSScriptRoot
$runtimeProcess = $null; $runtimeReady = $false; $lockOwned = $false; $launchLock = $null; $http = $null
$startupGate = 'package integrity'
try {
    Add-Type -AssemblyName System.Net.Http
    $handler = [Net.Http.HttpClientHandler]::new(); $handler.UseProxy = $false; $handler.AllowAutoRedirect = $false
    $http = [Net.Http.HttpClient]::new($handler); $http.Timeout = [TimeSpan]::FromSeconds(3)
    $manifest = Assert-ReleaseBundle $bundle
    $startupGate = 'Java 21'
    $java = Get-ReleaseJava
    $startupGate = 'private state paths'
    if (-not $StateDirectory) { $StateDirectory = Join-Path $env:LOCALAPPDATA 'PersonalAiWorkspace\RuntimeState' }
    if (-not $DataDirectory) { $DataDirectory = Join-Path $java.UserDirectory '.personal-ai-workspace\data' }
    $StateDirectory = Assert-ReleaseLocation $StateDirectory $bundle
    $DataDirectory = Assert-ReleaseLocation $DataDirectory $bundle
    if ($StateDirectory -eq $DataDirectory -or $StateDirectory.StartsWith($DataDirectory + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $DataDirectory.StartsWith($StateDirectory + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Workspace data must be separate from auth/log state.' }
    if (-not $TokenFile) { $TokenFile = Join-Path $StateDirectory 'Auth\client-token' }
    $TokenFile = [IO.Path]::GetFullPath($TokenFile)
    $authDirectory = [IO.Path]::GetDirectoryName($TokenFile)
    foreach ($separate in @($DataDirectory, $bundle)) {
        if ($authDirectory -eq $separate -or $authDirectory.StartsWith($separate + '\', [StringComparison]::OrdinalIgnoreCase) -or
            $separate.StartsWith($authDirectory + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Credentials must be separate from Workspace data and package.' }
    }
    $runtimeReady = Test-ReleaseReady $http
    $startupGate = 'existing Runtime authentication'
    if (Test-ReleasePort 8765) {
        if (-not $runtimeReady) { throw 'Port 8765 is occupied by an invalid/unready service; no process was stopped.' }
        $provider = Get-ReleaseProvider $http $TokenFile
    }
    $ollamaReady = Test-ReleaseOllama $http
    $startupGate = 'Ollama preflight'
    if (-not $ollamaReady -and (Test-ReleasePort 11434)) { throw 'Port 11434 is occupied by a service that is not ready Ollama.' }
    $ollamaExe = Resolve-ReleaseOllama $ollamaReady
    if ($CheckOnly) { Write-Host 'PASS: package integrity / Java 21 / safe state paths / service preflight. No services started.'; return }
    $account = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $launchLock = [Threading.Mutex]::new($false, "Local\PersonalAiWorkspace.Launcher.$($account.Value)")
    try { $lockOwned = $launchLock.WaitOne(0) } catch [Threading.AbandonedMutexException] { $lockOwned = $true }
    if (-not $lockOwned) { Write-Host 'Another launcher is starting Personal AI Workspace.'; return }
    # Repeat the port gate while holding the shared developer/release launcher lock.
    $runtimeReady = Test-ReleaseReady $http
    if ((Test-ReleasePort 8765) -and -not $runtimeReady) { throw 'Port 8765 is occupied by an invalid/unready service; no process was stopped.' }
    $startupGate = 'private state permissions'
    foreach ($dir in @($StateDirectory, (Join-Path $StateDirectory 'Logs'))) {
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
        Set-ReleasePrivateAcl $dir $true
    }
    $logs = Join-Path $StateDirectory 'Logs'
    $startupGate = 'Ollama startup'
    if (-not $ollamaReady) {
        Write-Host 'Starting Ollama; no model download will be performed.'
        $ollamaProcess = Start-Process -FilePath $ollamaExe -ArgumentList 'serve' -WindowStyle Hidden -PassThru -WorkingDirectory $StateDirectory `
            -RedirectStandardOutput (Join-Path $logs 'ollama.stdout.log') -RedirectStandardError (Join-Path $logs 'ollama.stderr.log')
        Wait-ReleaseService { Test-ReleaseOllama $http } $ollamaProcess 'Ollama'
    }
    if (-not $runtimeReady) {
        $startupGate = 'Runtime private state'
        # Explicit external token reuse is read-only; new tokens use the private default Auth tree.
        if ($authDirectory -ne (Join-Path $StateDirectory 'Auth')) { throw 'For a new Runtime, use the default private Auth token location.' }
        foreach ($dir in @($authDirectory, $DataDirectory)) {
            if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
            Set-ReleasePrivateAcl $dir $true
        }
        foreach ($name in @('memory.db', 'memory.db-wal', 'memory.db-shm', 'memory.db-journal')) {
            $file = Join-Path $DataDirectory $name
            if (Test-Path -LiteralPath $file) { Set-ReleasePrivateAcl $file $false }
        }
        $jar = Join-Path $bundle $manifest.runtimeArtifact
        Write-Host 'Starting packaged Runtime.'
        $startupGate = 'packaged Runtime startup'
        $runtimeProcess = Start-Process -FilePath $java.Executable -WorkingDirectory $StateDirectory -WindowStyle Hidden -PassThru `
            -ArgumentList @('-jar', ('"' + $jar + '"'), ('"--workspace.data-directory=' + $DataDirectory + '"'), ('"--workspace.security.token-file=' + $TokenFile + '"')) `
            -RedirectStandardOutput (Join-Path $logs 'runtime.stdout.log') -RedirectStandardError (Join-Path $logs 'runtime.stderr.log')
        Wait-ReleaseService { Test-ReleaseReady $http } $runtimeProcess 'Runtime'
    }
    $provider = Get-ReleaseProvider $http $TokenFile
    $runtimeReady = $true
    if (-not $provider.available -or -not $provider.modelAvailable) {
        Write-Host 'Ollama/configured model is unavailable. Main Workspace will open; install the configured model yourself before AI operations.'
    }
    $activationName = "Local\PersonalAiWorkspace.Desktop.$($account.Value).Open"
    $startupGate = 'Desktop activation'
    $activation = $null
    try { $activation = [Threading.EventWaitHandle]::OpenExisting($activationName); $activation.Set() | Out-Null }
    catch [Threading.WaitHandleCannotBeOpenedException] {
        Start-Process -FilePath (Join-Path $bundle 'desktop\PersonalAiWorkspace.Desktop.exe') -WorkingDirectory $StateDirectory -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logs 'desktop.stdout.log') -RedirectStandardError (Join-Path $logs 'desktop.stderr.log') | Out-Null
    } finally { if ($null -ne $activation) { $activation.Dispose() } }
    $deadline = [DateTime]::UtcNow.AddSeconds(15); $opened = $false
    do {
        $activation = $null
        try { $activation = [Threading.EventWaitHandle]::OpenExisting($activationName); $opened = $activation.Set() }
        catch [Threading.WaitHandleCannotBeOpenedException] { }
        finally { if ($null -ne $activation) { $activation.Dispose() } }
        if (-not $opened) { Start-Sleep -Milliseconds 200 }
    } while (-not $opened -and [DateTime]::UtcNow -lt $deadline)
    if (-not $opened) { throw 'Desktop did not start; check its native error dialog.' }
    Write-Host 'Ready: Personal AI Workspace requested. Use Settings for explicit native credential import.'
    Write-Host 'Tray Exit stops Desktop. Runtime and Ollama remain externally managed.'
} catch {
    if ($null -ne $runtimeProcess -and -not $runtimeReady -and -not $runtimeProcess.HasExited) { $runtimeProcess.Kill() }
    # Deliberately do not reflect exception text (HTTP, paths, process or credential diagnostics).
    $safe = @('Java 21 runtime is required on PATH.', 'Java 21 runtime could not start.', 'Java major must be 21; select Java 21 on PATH.',
        'Ollama is unavailable. Install Ollama and the configured model, then retry.',
        'Port 8765 is occupied by an invalid/unready service; no process was stopped.',
        'Port 11434 is occupied by a service that is not ready Ollama.',
        'Runtime could not be authenticated and validated; check its private credential and port 8765.',
        'Package integrity check failed; use a fresh complete package.', 'Desktop did not start; check its native error dialog.')
    $message = if ($_.Exception.Message -in $safe) { $_.Exception.Message } else { 'Package/startup preflight failed. Check package integrity, private state locations and RuntimeState Logs.' }
    Write-Host "Startup failed at $startupGate`: $message No Workspace reset or automatic credential import was performed."
    exit 1
} finally {
    if ($null -ne $http) { $http.Dispose() }
    if ($lockOwned) { $launchLock.ReleaseMutex() }
    if ($null -ne $launchLock) { $launchLock.Dispose() }
}
