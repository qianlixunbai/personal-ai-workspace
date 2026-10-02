param([int]$Port = 18765)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$runDirectory = Join-Path $repo '.verification'
$jar = Join-Path $repo 'target/personal-ai-workspace-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the Runtime with mvnw.cmd clean verify first.' }
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$tokenFile = Join-Path $runDirectory 'local-smoke-auth/client-token'
$outLog = Join-Path $runDirectory 'real-smoke.stdout.log'
$errLog = Join-Path $runDirectory 'real-smoke.stderr.log'
$javaLauncher = (Get-Command java).Source
# Oracle javapath can be a launcher shim. Start the actual JVM so cleanup owns its PID.
$javaProperties = & $javaLauncher -XshowSettings:properties -version 2>&1
$javaHomeLine = $javaProperties | Where-Object { "$_" -match '^\s+java.home = ' } | Select-Object -First 1
if (-not $javaHomeLine) { throw 'Cannot resolve Java runtime location.' }
$javaHome = ("$javaHomeLine" -replace '^\s+java.home = ', '').Trim()
$javaCommand = Join-Path $javaHome 'bin/java.exe'
if (@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count -gt 0) {
    throw 'Smoke port is already in use. Select another port.'
}
$process = Start-Process -FilePath $javaCommand -ArgumentList @('-jar', ('"' + $jar + '"'), "--server.port=$Port", ('"--workspace.security.token-file=' + $tokenFile + '"')) -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
try {
    $base = "http://127.0.0.1:$Port"
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($process.HasExited) { throw 'Runtime exited before readiness; inspect private verification logs.' }
        try {
            $health = Invoke-RestMethod "$base/actuator/health/readiness" -TimeoutSec 1
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Runtime readiness deadline exceeded.' }
    $headers = @{ Authorization = 'Bearer ' + (Get-Content -LiteralPath $tokenFile -Raw).Trim() }
    $unauthorized = $false
    try { Invoke-RestMethod "$base/api/v1/providers/readiness" -TimeoutSec 3 | Out-Null }
    catch { if ([int]$_.Exception.Response.StatusCode -eq 401) { $unauthorized = $true } }
    if (-not $unauthorized) { throw 'Unauthorized client was not rejected.' }
    $provider = Invoke-RestMethod "$base/api/v1/providers/readiness" -Headers $headers -TimeoutSec 5
    if (-not $provider.available -or -not $provider.modelAvailable) { throw 'REAL LOCAL SMOKE failed: Ollama/model unavailable.' }
    $cases = @(
        @{ capability = 'translate'; profile = 'translate.fast'; inputLength = 13; request = @{ text = 'Hello, world!'; sourceLanguage = 'en'; targetLanguage = 'zh-CN' } },
        @{ capability = 'summarize'; profile = 'summarize.fast'; request = @{ text = 'A library opens at nine in the morning. It lends books for two weeks. On Sundays it is closed.' } },
        @{ capability = 'ask'; profile = 'chat.balanced'; request = @{ question = 'What is 2 plus 3? Give a brief plain text answer.' } }
    )
    $tasks = @()
    foreach ($case in $cases) {
        $case.request.profile = $case.profile
        $inputText = if ($case.capability -eq 'ask') { $case.request.question } else { $case.request.text }
        $body = $case.request | ConvertTo-Json
        $task = Invoke-RestMethod "$base/api/v1/$($case.capability)/tasks" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 5
        $deadline = [DateTime]::UtcNow.AddSeconds(160)
        do {
            Start-Sleep -Milliseconds 250
            $result = Invoke-RestMethod "$base/api/v1/tasks/$($task.taskId)" -Headers $headers -TimeoutSec 5
        } while ($result.status -in @('QUEUED', 'RUNNING') -and [DateTime]::UtcNow -lt $deadline)
        if ($result.status -ne 'SUCCEEDED' -or [string]::IsNullOrWhiteSpace($result.result) -or $result.profile.id -ne $case.profile -or $result.capability -ne $case.capability) {
            throw "REAL LOCAL SMOKE failed: $($case.capability) did not succeed."
        }
        if ($case.capability -eq 'translate' -and $result.result -notmatch '你好') { throw 'Translate regression assertion failed.' }
        if ($case.capability -eq 'ask' -and $result.result -notmatch '5|five|五') { throw 'Ask arithmetic assertion failed.' }
        $tasks += [ordered]@{
            capability = $result.capability; taskId = $result.taskId; status = $result.status
            inputLength = $inputText.Length; outputLength = $result.result.Length
            profile = $result.profile.id; profileVersion = $result.profile.version; promptVersion = $result.promptVersion
        }
    }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop | Where-Object OwningProcess -eq $process.Id)
    if ($listeners.Count -eq 0 -or @($listeners | Where-Object LocalAddress -ne '127.0.0.1').Count -gt 0) {
        throw 'Runtime listener is not restricted to loopback.'
    }
    $evidence = [ordered]@{
        result = 'REAL PASS'; timestampUtc = [DateTime]::UtcNow.ToString('o'); runtime = 'UP'
        unauthorizedStatus = 401; bindAddress = '127.0.0.1'; provider = 'ollama'
        tasks = $tasks
    }
    $evidence | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDirectory 'real-smoke-evidence.json') -Encoding utf8
    $evidence | ConvertTo-Json
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    $process.WaitForExit(5000) | Out-Null
}
