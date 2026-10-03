# Run through start-workspace.cmd, or directly from Windows PowerShell 5.1+.
# Builds skip tests. No model downloads, automatic pairing, or Memory resets.
[CmdletBinding()]
param(
    [string]$DataDirectory = $env:WORKSPACE_DATA_DIRECTORY,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $repo 'target\personal-ai-workspace-0.1.0.jar'
$desktopProject = Join-Path $repo 'desktop\src\PersonalAiWorkspace.Desktop\PersonalAiWorkspace.Desktop.csproj'
$desktopExe = Join-Path $repo 'desktop\src\PersonalAiWorkspace.Desktop\bin\Debug\net10.0-windows\PersonalAiWorkspace.Desktop.exe'
$tokenFile = Join-Path $repo '.runtime\client-token'
$logDirectory = Join-Path $repo '.runtime\launcher'
$account = [Security.Principal.WindowsIdentity]::GetCurrent().User
$runtimeProcess = $null
$runtimeReady = $false
$launchLock = $null
$lockOwned = $false
$http = $null

function Get-LocalJson([string]$Url, [string]$Token = '') {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $Url)
    $response = $null
    try {
        if ($Token) { $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token) }
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        $response.EnsureSuccessStatusCode() | Out-Null
        return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Test-RuntimeReady {
    try { return (Get-LocalJson 'http://127.0.0.1:8765/actuator/health/readiness').status -eq 'UP' }
    catch { return $false }
}

function Test-OllamaReady {
    try { Get-LocalJson 'http://127.0.0.1:11434/api/tags' | Out-Null; return $true }
    catch { return $false }
}

function Test-PortOccupied([int]$Port) {
    return @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object Port -eq $Port).Count -gt 0
}

function Test-AssistantRunning {
    $activation = $null
    try {
        $activation = [Threading.EventWaitHandle]::OpenExisting("Local\PersonalAiWorkspace.Desktop.$($account.Value).Open")
        return $true
    } catch [Threading.WaitHandleCannotBeOpenedException] { return $false }
    finally { if ($null -ne $activation) { $activation.Dispose() } }
}

function Wait-Service([scriptblock]$Ready, [Diagnostics.Process]$Process, [string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        if (& $Ready) { return }
        if ($null -ne $Process -and $Process.HasExited) {
            throw "$Name exited during startup. See $logDirectory."
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Name did not become ready within 45 seconds. See $logDirectory."
}

function Assert-MemoryLocation {
    $data = [IO.Path]::GetFullPath($DataDirectory)
    $auth = [IO.Path]::GetDirectoryName($tokenFile)
    if ($data.TrimEnd('\') -eq $auth -or $data.StartsWith($auth + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $auth.StartsWith($data.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Memory must be separate from the credential directory.'
    }
    for ($part = $data; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ([IO.Path]::GetFileName($part) -in @('build', 'target', 'logs', '.git', '.runtime') -or
            (Test-Path -LiteralPath (Join-Path $part '.git')) -or
            (Test-Path -LiteralPath (Join-Path $part 'pom.xml')) -or
            (Test-Path -LiteralPath (Join-Path $part 'build.gradle'))) {
            throw 'Memory must be outside project, build, logs, and credential directories.'
        }
        if (Test-Path -LiteralPath $part) {
            $item = Get-Item -LiteralPath $part -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Memory paths cannot contain links or reparse points.'
            }
        }
    }
    if ((Test-Path -LiteralPath $data) -and -not (Test-Path -LiteralPath $data -PathType Container)) {
        throw 'Memory data location is not a directory.'
    }
    return $data
}

function Set-PrivateMemoryPermissions([string]$Path, [bool]$Directory) {
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.PSIsContainer -ne $Directory) {
        throw 'Invalid Memory directory or SQLite file; no data was deleted.'
    }
    $acl = Get-Acl -LiteralPath $Path
    $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($owner -ne $account.Value -and $owner -ne 'S-1-5-32-544') {
        throw 'Memory belongs to another account. Select that account or a different data directory.'
    }
    # Repair the Administrators owner left by an elevated first launch. Only
    # this directory and the four known SQLite files are touched, never recursively.
    if ($Directory) {
        $privateAcl = [Security.AccessControl.DirectorySecurity]::new()
        $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    } else {
        $privateAcl = [Security.AccessControl.FileSecurity]::new()
        $inheritance = [Security.AccessControl.InheritanceFlags]::None
    }
    $privateAcl.SetOwner($account)
    $privateAcl.SetAccessRuleProtection($true, $false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($account, 'FullControl',
        $inheritance, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
    $privateAcl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $privateAcl
    if ((Get-Acl -LiteralPath $Path).GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $account.Value) {
        throw 'Could not assign Memory ownership to the current account.'
    }
}

function Test-NeedsBuild([string]$Artifact, [string[]]$Inputs) {
    if (-not (Test-Path -LiteralPath $Artifact -PathType Leaf)) { return $true }
    $builtAt = (Get-Item -LiteralPath $Artifact).LastWriteTimeUtc
    foreach ($inputPath in $Inputs) {
        foreach ($file in Get-ChildItem -LiteralPath $inputPath -Recurse -File) {
            if ($file.FullName -match '[\\/](bin|obj)[\\/]') { continue }
            if ($file.LastWriteTimeUtc -gt $builtAt) { return $true }
        }
    }
    return $false
}

try {
    Add-Type -AssemblyName System.Net.Http
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $handler.AllowAutoRedirect = $false
    $http = [Net.Http.HttpClient]::new($handler)
    $http.Timeout = [TimeSpan]::FromSeconds(2)

    $javaLauncher = (Get-Command java -ErrorAction Stop).Source
    # Java writes even successful version output to stderr. Windows PowerShell
    # 5.1 treats those redirected lines as errors under the Stop preference.
    $propertyReadPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaProperties = & $javaLauncher -XshowSettings:properties -version 2>&1
        $javaExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $propertyReadPreference }
    if ($javaExitCode -ne 0) { throw 'Java could not start.' }
    $javaHomeLine = $javaProperties | Where-Object { "$_" -match '^\s+java.home = ' } | Select-Object -First 1
    $userHomeLine = $javaProperties | Where-Object { "$_" -match '^\s+user.home = ' } | Select-Object -First 1
    if (-not $javaHomeLine -or -not $userHomeLine) { throw 'Could not resolve Java home directories.' }
    $javaHome = ("$javaHomeLine" -replace '^\s+java.home = ', '').Trim()
    $javaExe = Join-Path $javaHome 'bin\java.exe'
    if (-not $DataDirectory) {
        $javaUserHome = ("$userHomeLine" -replace '^\s+user.home = ', '').Trim()
        $DataDirectory = Join-Path $javaUserHome '.personal-ai-workspace\data'
    }
    $DataDirectory = Assert-MemoryLocation
    $runtimeReady = Test-RuntimeReady

    Write-Host 'Personal AI Workspace launcher'
    Write-Host "Memory: $DataDirectory"
    if ($CheckOnly) {
        Write-Host "Runtime ready: $runtimeReady"
        Write-Host "Ollama ready: $(Test-OllamaReady)"
        Write-Host "Runtime JAR exists: $(Test-Path -LiteralPath $jar)"
        Write-Host "Assistant EXE exists: $(Test-Path -LiteralPath $desktopExe)"
        if (Test-Path -LiteralPath $DataDirectory) {
            Write-Host "Memory owner: $((Get-Acl -LiteralPath $DataDirectory).Owner)"
        }
        return
    }

    $launchLock = [Threading.Mutex]::new($false, "Local\PersonalAiWorkspace.Launcher.$($account.Value)")
    try { $lockOwned = $launchLock.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $lockOwned = $true }
    if (-not $lockOwned) { Write-Host 'Another launcher is already starting the workspace.'; return }
    if (-not $runtimeReady -and (Test-PortOccupied 8765)) {
        throw 'Port 8765 is occupied by an unready service. Check its window before retrying.'
    }
    if (-not (Test-Path -LiteralPath $logDirectory)) { New-Item -ItemType Directory -Path $logDirectory | Out-Null }

    Push-Location -LiteralPath $repo
    try {
        if (-not $runtimeReady -and (Test-NeedsBuild $jar @((Join-Path $repo 'pom.xml'), (Join-Path $repo 'src\main')))) {
            Write-Host 'Building Runtime (tests skipped)...'
            & (Join-Path $repo 'mvnw.cmd') package -DskipTests
            if ($LASTEXITCODE -ne 0) { throw 'Runtime build failed.' }
        }
        if (Test-AssistantRunning) {
            Write-Host 'Assistant is already running; reusing it without rebuilding locked files.'
        } elseif (Test-NeedsBuild $desktopExe @((Join-Path $repo 'desktop\src'), (Join-Path $repo 'desktop\Directory.Build.props'), (Join-Path $repo 'global.json'))) {
            Write-Host 'Building Assistant (no tests)...'
            & dotnet build $desktopProject -c Debug --nologo
            if ($LASTEXITCODE -ne 0) { throw 'Assistant build failed. If it is running, exit it from the tray before retrying.' }
        }
    } finally { Pop-Location }

    if (-not (Test-OllamaReady)) {
        if (Test-PortOccupied 11434) { throw 'Port 11434 is occupied but Ollama is not responding.' }
        $ollamaCommand = Get-Command ollama -ErrorAction SilentlyContinue
        $ollamaExe = if ($null -ne $ollamaCommand) { $ollamaCommand.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe' }
        if (-not (Test-Path -LiteralPath $ollamaExe)) { throw 'Ollama is not installed. Install it, then retry.' }
        Write-Host 'Starting Ollama...'
        $ollamaProcess = Start-Process -FilePath $ollamaExe -ArgumentList 'serve' -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logDirectory 'ollama.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'ollama.stderr.log')
        Wait-Service { Test-OllamaReady } $ollamaProcess 'Ollama'
    }

    if (-not $runtimeReady) {
        if (-not (Test-Path -LiteralPath $DataDirectory)) { New-Item -ItemType Directory -Path $DataDirectory | Out-Null }
        Set-PrivateMemoryPermissions $DataDirectory $true
        foreach ($name in @('memory.db', 'memory.db-wal', 'memory.db-shm', 'memory.db-journal')) {
            $file = Join-Path $DataDirectory $name
            if (Test-Path -LiteralPath $file) { Set-PrivateMemoryPermissions $file $false }
        }
        Write-Host 'Starting Runtime...'
        $runtimeProcess = Start-Process -FilePath $javaExe -WorkingDirectory $repo -WindowStyle Hidden -PassThru `
            -ArgumentList @('-jar', ('"' + $jar + '"'), ('"--workspace.data-directory=' + $DataDirectory + '"')) `
            -RedirectStandardOutput (Join-Path $logDirectory 'runtime.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'runtime.stderr.log')
        Wait-Service { Test-RuntimeReady } $runtimeProcess 'Runtime'
        $runtimeReady = $true
    } else { Write-Host 'Runtime is already ready; reusing it.' }

    if (-not (Test-Path -LiteralPath $tokenFile -PathType Leaf)) { throw 'Runtime token is missing. Check the existing Runtime configuration.' }
    $token = [IO.File]::ReadAllText($tokenFile).Trim()
    try { $provider = Get-LocalJson 'http://127.0.0.1:8765/api/v1/providers/readiness' $token }
    finally { $token = $null }
    if (-not $provider.available -or -not $provider.modelAvailable) {
        Write-Warning 'Ollama/configured model is unavailable. Translation will work after the configured model is installed; no download was started.'
    }

    # The product starts in the tray when credentials already exist. Its existing
    # per-account event opens the window without changing Desktop implementation.
    if (-not (Test-AssistantRunning)) {
        Start-Process -FilePath $desktopExe -WorkingDirectory $repo -WindowStyle Hidden | Out-Null
    }
    $activationName = "Local\PersonalAiWorkspace.Desktop.$($account.Value).Open"
    $opened = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $activation = $null
        try {
            $activation = [Threading.EventWaitHandle]::OpenExisting($activationName)
            $opened = $activation.Set()
        } catch [Threading.WaitHandleCannotBeOpenedException] { }
        finally { if ($null -ne $activation) { $activation.Dispose() } }
        if (-not $opened) { Start-Sleep -Milliseconds 200 }
    } while (-not $opened -and [DateTime]::UtcNow -lt $deadline)
    if (-not $opened) { throw 'Assistant did not start. Check for its error dialog and retry.' }

    Write-Host 'Ready: Runtime online; Assistant window requested.' -ForegroundColor Green
    Write-Host "First use: import Runtime credential from $tokenFile"
    Write-Host 'Browser pairing: Copy Origin in extension -> Pair Browser in Assistant -> paste Pairing ID / Secret -> Pair.'
    Write-Host 'Closing this launcher leaves Runtime / Ollama / Assistant running.'
    Write-Host 'To stop Runtime, use Task Manager to end the Java process listening on port 8765.'
} catch {
    if ($null -ne $runtimeProcess -and -not $runtimeReady -and -not $runtimeProcess.HasExited) {
        $runtimeProcess.Kill()
    }
    Write-Host "Startup failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'Existing Memory was not reset. Fix the reported issue, then double-click start-workspace.cmd again.'
    exit 1
} finally {
    if ($null -ne $http) { $http.Dispose() }
    if ($lockOwned) { $launchLock.ReleaseMutex() }
    if ($null -ne $launchLock) { $launchLock.Dispose() }
}
