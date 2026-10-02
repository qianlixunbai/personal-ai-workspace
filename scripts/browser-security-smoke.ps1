param([int]$Port = 18766)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$runDirectory = Join-Path $repo '.verification'
$jar = Join-Path $repo 'target/personal-ai-workspace-0.1.0.jar'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$tokenFile = Join-Path $runDirectory 'browser-smoke-auth/client-token'
$outLog = Join-Path $runDirectory 'browser-smoke.stdout.log'
$errLog = Join-Path $runDirectory 'browser-smoke.stderr.log'
$javaProperties = & (Get-Command java).Source -XshowSettings:properties -version 2>&1
$javaHomeLine = $javaProperties | Where-Object { "$_" -match '^\s+java.home = ' } | Select-Object -First 1
if (-not $javaHomeLine) { throw 'Cannot resolve JVM.' }
$javaHome = ("$javaHomeLine" -replace '^\s+java.home = ', '').Trim()
$javaCommand = Join-Path $javaHome 'bin/java.exe'
$base = "http://127.0.0.1:$Port"
$process = $null
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false; $handler.AllowAutoRedirect = $false
$http = [System.Net.Http.HttpClient]::new($handler)
$http.Timeout = [TimeSpan]::FromSeconds(8)
$credentialA = $null; $credentialB = $null; $sessionA = $null; $sessionB = $null
$runtimeLogs = @(); $restartIndex = 0
function Start-SmokeRuntime {
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Build Runtime first.' }
    if (@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count -gt 0) { throw 'Smoke port already in use.' }
    $script:outLog = Join-Path $runDirectory "browser-smoke-$restartIndex.stdout.log"
    $script:errLog = Join-Path $runDirectory "browser-smoke-$restartIndex.stderr.log"
    $script:runtimeLogs += @($outLog,$errLog); $script:restartIndex++
    $script:process = Start-Process -FilePath $javaCommand -ArgumentList @('-jar', ('"' + $jar + '"'), "--server.port=$Port", ('"--workspace.security.token-file=' + $tokenFile + '"')) -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        if ($process.HasExited) { throw 'Runtime exited before readiness.' }
        try {
            $health = Invoke-RestMethod "$base/actuator/health/readiness" -TimeoutSec 1
            if ($health.status -eq 'UP') {
                $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port | Where-Object OwningProcess -eq $process.Id)
                if ($listeners.Count -eq 0 -or @($listeners | Where-Object LocalAddress -ne '127.0.0.1').Count -gt 0) { throw 'Loopback assertion failed.' }
                return
            }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw 'Runtime readiness deadline exceeded.'
}
function Stop-SmokeRuntime {
    if ($null -ne $process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    if ($null -ne $process) { $process.WaitForExit(5000) | Out-Null }
}
function Request([string]$Method, [string]$Path, $Body, [string]$Credential, [string]$Origin, [int]$Expected) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$base$Path")
    try {
        if ($Credential) { $request.Headers.TryAddWithoutValidation('Authorization', 'Bearer ' + $Credential) | Out-Null }
        if ($Origin) {
            $request.Headers.TryAddWithoutValidation('Origin', $Origin) | Out-Null
            $request.Headers.TryAddWithoutValidation('Sec-Fetch-Site', 'none') | Out-Null
            $request.Headers.TryAddWithoutValidation('Sec-Fetch-Mode', 'cors') | Out-Null
            $request.Headers.TryAddWithoutValidation('Sec-Fetch-Dest', 'empty') | Out-Null
        }
        if ($null -ne $Body) { $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json') }
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        try {
            if ([int]$response.StatusCode -ne $Expected) { throw 'Security smoke HTTP assertion failed.' }
            if ($response.Headers.Contains('Access-Control-Allow-Origin')) {
                $allowed = @($response.Headers.GetValues('Access-Control-Allow-Origin'))
                if ($allowed.Count -ne 1 -or $allowed[0] -ne $Origin -or $allowed[0] -eq '*') { throw 'CORS assertion failed.' }
            }
            $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($Expected -eq 204) { return }
            return ($raw | ConvertFrom-Json)
        } finally { $response.Dispose() }
    } catch { throw 'Security smoke request failed; response bodies and credentials suppressed.' }
    finally { $request.Dispose() }
}
function Poll-Success($Task, [string]$Credential, [string]$Origin) {
    $deadline = [DateTime]::UtcNow.AddSeconds(160)
    do {
        Start-Sleep -Milliseconds 250
        $result = Request 'GET' "/api/v1/tasks/$($Task.taskId)" $null $Credential $Origin 200
    } while ($result.status -in @('QUEUED', 'RUNNING') -and [DateTime]::UtcNow -lt $deadline)
    if ($result.status -ne 'SUCCEEDED' -or [string]::IsNullOrWhiteSpace($result.result)) { throw 'Real Ollama task did not succeed.' }
    return $result
}
try {
    Start-SmokeRuntime
    $native = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
    $provider = Request 'GET' '/api/v1/providers/readiness' $null $native '' 200
    if (-not $provider.available -or -not $provider.modelAvailable) { throw 'Real Ollama/model unavailable.' }
    $body = @{ text='Hello, world!'; sourceLanguage='en'; targetLanguage='zh-CN' }
    $nativeTask = Request 'POST' '/api/v1/translate/tasks' $body $native '' 202
    $nativeResult = Poll-Success $nativeTask $native ''
    $originA = 'chrome-extension://' + ('a' * 32); $originB = 'chrome-extension://' + ('b' * 32)
    $sessionA = Request 'POST' '/api/v1/security/pairings' @{ origin=$originA; displayName='Synthetic A'; userApproved=$true } $native '' 200
    $sessionB = Request 'POST' '/api/v1/security/pairings' @{ origin=$originB; displayName='Synthetic B'; userApproved=$true } $native '' 200
    $exchangeA = @{ pairingId=$sessionA.pairingId; pairingSecret=$sessionA.pairingSecret }
    $a = Request 'POST' '/api/v1/security/pairings/exchange' $exchangeA '' $originA 200
    $b = Request 'POST' '/api/v1/security/pairings/exchange' @{ pairingId=$sessionB.pairingId; pairingSecret=$sessionB.pairingSecret } '' $originB 200
    $credentialA = $a.credential; $credentialB = $b.credential
    Request 'POST' '/api/v1/security/pairings/exchange' $exchangeA '' $originA 401 | Out-Null
    $taskA = Request 'POST' '/api/v1/translate/tasks' $body $credentialA $originA 202
    Request 'POST' '/api/v1/translate/tasks' $body $credentialA $originB 401 | Out-Null
    Request 'POST' '/api/v1/translate/tasks' $body $credentialA 'https://example.com' 401 | Out-Null
    foreach ($method in @('GET','DELETE')) {
        $denied = Request $method "/api/v1/tasks/$($taskA.taskId)" $null $credentialB $originB 404
        if ($denied.code -ne 'TASK_NOT_FOUND') { throw 'Ownership error contract failed.' }
        Request $method "/api/v1/tasks/$($taskA.taskId)" $null $native '' 404 | Out-Null
        Request $method "/api/v1/tasks/$($nativeTask.taskId)" $null $credentialA $originA 404 | Out-Null
    }
    $browserResult = Poll-Success $taskA $credentialA $originA
    foreach ($capability in @('ask','summarize')) { Request 'POST' "/api/v1/$capability/tasks" @{} $credentialA $originA 403 | Out-Null }
    Stop-SmokeRuntime; Start-SmokeRuntime
    $afterRestart = Request 'POST' '/api/v1/translate/tasks' $body $credentialA $originA 202
    $restartResult = Poll-Success $afterRestart $credentialA $originA
    Request 'DELETE' "/api/v1/security/clients/$($a.client.clientId)" $null $native '' 204
    Request 'POST' '/api/v1/translate/tasks' $body $credentialA $originA 401 | Out-Null
    Stop-SmokeRuntime; Start-SmokeRuntime
    Request 'POST' '/api/v1/translate/tasks' $body $credentialA $originA 401 | Out-Null
    Request 'DELETE' "/api/v1/security/clients/$($b.client.clientId)" $null $native '' 204
    Stop-SmokeRuntime
    $registry = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $tokenFile) 'browser-clients.json') -Raw
    foreach ($secret in @($native,$credentialA,$credentialB,$sessionA.pairingSecret,$sessionB.pairingSecret)) {
        if ($registry.Contains($secret)) { throw 'Secret persistence assertion failed.' }
        foreach ($log in $runtimeLogs) { if ([IO.File]::ReadAllText($log).Contains($secret)) { throw 'Secret logging assertion failed.' } }
    }
    foreach ($log in $runtimeLogs) {
        $content = [IO.File]::ReadAllText($log)
        foreach ($origin in @($originA,$originB)) { if ($content.Contains($origin)) { throw 'Origin logging assertion failed.' } }
    }
    # Check current source, build artifacts and metadata evidence using the actual ephemeral secrets, without printing them.
    $auditPaths = @(& git -C $repo ls-files --cached --others --exclude-standard | ForEach-Object { Join-Path $repo $_ })
    $auditPaths += @(Get-ChildItem -LiteralPath (Join-Path $repo 'target/classes'),(Join-Path $repo 'target/test-classes'),(Join-Path $repo 'desktop') -Recurse -File | Where-Object { $_.FullName -match '[\\/](bin|obj|classes|test-classes)[\\/]' } | ForEach-Object FullName)
    $auditPaths += @(Get-ChildItem -LiteralPath $runDirectory -Filter '*evidence*' -File | ForEach-Object FullName)
    foreach ($path in $auditPaths) {
        $content = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($path))
        foreach ($secret in @($native,$credentialA,$credentialB,$sessionA.pairingSecret,$sessionB.pairingSecret)) {
            if ($content.Contains($secret)) { throw 'Source/build/evidence secret audit failed.' }
        }
    }
    $evidence = [ordered]@{ result='REAL PASS'; timestampUtc=[DateTime]::UtcNow.ToString('o'); bindAddress='127.0.0.1'; native='PASS'; pairing='PASS'; browserTranslate='PASS'; wrongOrigin=401; ordinaryWebOrigin=401; crossOwner=404; capabilities='Translate only'; credentialRestart='PASS'; revokeRestart='PASS'; clientA=$a.client.clientId; clientB=$b.client.clientId; secretAudit='PASS' }
    $evidence | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDirectory 'browser-smoke-evidence.json') -Encoding utf8
    $evidence | ConvertTo-Json
} finally { Stop-SmokeRuntime; $http.Dispose(); $handler.Dispose() }
