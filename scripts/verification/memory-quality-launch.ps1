# Used by the opt-in direct-provider quality fixture, never by product admission.
# Dot-sourcing defines helpers only; no process, network or identity query runs.
function Set-MemoryQualityOfflineEnvironment {
    param([Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$Start)
    # Call after the fixture removes inherited OLLAMA_* values. Child-only setting.
    $Start.Environment['OLLAMA_NO_CLOUD'] = '1'
}

function Get-MemoryQualityListenerObservation {
    param(
        [Parameter(Mandatory)][ValidateRange(1,65535)][int]$Port,
        [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$ExpectedProcessId
    )
    $queryErrors = @()
    $rows = @()
    try {
        # Same bounded, port-specific command as the original fixture. Capture
        # nonterminating errors instead of turning them into an empty success.
        $rows = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue -ErrorVariable +queryErrors)
    }
    catch { $queryErrors += $_ }
    $errors = @($queryErrors | ForEach-Object {
        [ordered]@{
            id = $_.FullyQualifiedErrorId
            category = [string]$_.CategoryInfo.Category
            exceptionType = $_.Exception.GetType().FullName
            message = $_.Exception.Message
        }
    })
    $state = if ($errors.Count) { 'QUERY_ERROR' }
        elseif (!$rows.Count) { 'EMPTY' }
        elseif ($rows.Count -eq 1 -and $rows[0].OwningProcess -eq $ExpectedProcessId -and $rows[0].LocalPort -eq $Port -and $rows[0].LocalAddress -ceq '127.0.0.1') { 'OWNED' }
        else { 'MISMATCH' }
    [pscustomobject][ordered]@{
        observedUtc = [DateTime]::UtcNow
        port = $Port
        expectedProcessId = $ExpectedProcessId
        state = $state
        rows = @($rows | ForEach-Object {
            [ordered]@{ address = $_.LocalAddress; port = $_.LocalPort; processId = $_.OwningProcess }
        })
        errors = $errors
    }
}
