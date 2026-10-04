# Windows PowerShell 5.1 compatible. No builds, downloads or credential imports.
Set-StrictMode -Version Latest

function Assert-ReleaseLocation([string]$Path, [string]$PackageRoot) {
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if ($resolved.StartsWith('\\') -or (New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($resolved))).DriveType -ne 'Fixed') {
        throw 'State must use a local fixed drive.'
    }
    $payload = [IO.Path]::GetFullPath($PackageRoot).TrimEnd('\')
    if ($resolved -eq $payload -or $resolved.StartsWith($payload + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $payload.StartsWith($resolved + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'State must be separate from the package.' }
    for ($part = $resolved; $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ([IO.Path]::GetFileName($part) -in @('.git', 'build', 'target', 'logs', '.runtime') -or
            (Test-Path -LiteralPath (Join-Path $part '.git')) -or
            (Test-Path -LiteralPath (Join-Path $part 'pom.xml')) -or
            (Test-Path -LiteralPath (Join-Path $part 'build.gradle'))) { throw 'State must be outside repository/build/log/token trees.' }
        if (Test-Path -LiteralPath $part) {
            if (((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked state paths are not allowed.' }
        }
    }
    if ((Test-Path -LiteralPath $resolved) -and -not (Test-Path -LiteralPath $resolved -PathType Container)) { throw 'State location must be a directory.' }
    return $resolved
}

function Set-ReleasePrivateAcl([string]$Path, [bool]$Directory) {
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.PSIsContainer -ne $Directory) { throw 'Invalid private state path.' }
    $account = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $actual = Get-Acl -LiteralPath $Path
    $owner = $actual.GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($owner -ne $account.Value -and $owner -ne 'S-1-5-32-544') { throw 'State belongs to another account; no data was changed.' }
    if ($Directory) {
        $acl = [Security.AccessControl.DirectorySecurity]::new()
        $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    } else {
        $acl = [Security.AccessControl.FileSecurity]::new()
        $inheritance = [Security.AccessControl.InheritanceFlags]::None
    }
    $acl.SetOwner($account); $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($account, 'FullControl', $inheritance,
        [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
    # Persist only the explicitly changed owner/DACL. Windows PowerShell Set-Acl
    # can request SACL privileges on files even though this policy never edits SACL.
    if ($Directory) { [IO.DirectoryInfo]::new($Path).SetAccessControl($acl) }
    else { [IO.FileInfo]::new($Path).SetAccessControl($acl) }
    if ((Get-Acl -LiteralPath $Path).GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $account.Value) { throw 'Could not establish account-private state.' }
}

function Read-ReleaseToken([string]$Path) {
    # Same bounded, account-owned, no-links file contract as native explicit import.
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or $Path.StartsWith('\\')) { throw 'Existing Runtime private credential file is unavailable.' }
    for ($part = [IO.Path]::GetFullPath($Path); $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if (((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked credential paths are not allowed.' }
    }
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $user = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl = $stream.GetAccessControl()
        if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $user.Value) { throw 'Runtime credential is not account-owned.' }
        foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -ne $user.Value -and
                ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]'ReadData,WriteData,ChangePermissions,TakeOwnership') -ne 0) {
                throw 'Runtime credential is not account-private.'
            }
        }
        if ($stream.Length -gt 128) { throw 'Runtime credential has an invalid format.' }
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false, 128, $true)
        try { $value = $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
        if ($value -cnotmatch '^[A-Za-z0-9_-]{43}$') { throw 'Runtime credential has an invalid format.' }
        return $value
    } finally { $stream.Dispose() }
}

function Get-ReleaseJava {
    $command = Get-Command java -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw 'Java 21 runtime is required on PATH.' }
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $properties = & $command.Source -XshowSettings:properties -version 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($code -ne 0) { throw 'Java 21 runtime could not start.' }
    $version = $properties | Where-Object { "$_" -match '^\s+java.specification.version = ' } | Select-Object -First 1
    $javaLocation = $properties | Where-Object { "$_" -match '^\s+java.home = ' } | Select-Object -First 1
    $userLocation = $properties | Where-Object { "$_" -match '^\s+user.home = ' } | Select-Object -First 1
    if (-not $version -or ("$version" -replace '^\s+java.specification.version = ', '').Trim() -ne '21') { throw 'Java major must be 21; select Java 21 on PATH.' }
    if (-not $javaLocation -or -not $userLocation) { throw 'Java home resolution failed.' }
    $exe = Join-Path (("$javaLocation" -replace '^\s+java.home = ', '').Trim()) 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Java 21 executable is unavailable.' }
    return @{ Executable = $exe; UserDirectory = (("$userLocation" -replace '^\s+user.home = ', '').Trim()) }
}

function Get-ReleaseJson([Net.Http.HttpClient]$Http, [string]$Url, [string]$Token = '') {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $Url)
    $response = $null
    try {
        if ($Token) { $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token) }
        $response = $Http.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw 'Local service check failed.' }
        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $buffer = [byte[]]::new(65537); $count = 0
        $deadline = [Threading.CancellationTokenSource]::new(); $deadline.CancelAfter(3000)
        try {
        while ($count -lt $buffer.Length) {
            $read = $stream.ReadAsync($buffer, $count, $buffer.Length - $count, $deadline.Token).GetAwaiter().GetResult()
            if ($read -eq 0) { break }; $count += $read
        }
        if ($count -gt 65536) { throw 'Local service response exceeded its bound.' }
        return ([Text.Encoding]::UTF8.GetString($buffer, 0, $count) | ConvertFrom-Json)
        } finally { $deadline.Dispose() }
    } finally { if ($null -ne $response) { $response.Dispose() }; $request.Dispose() }
}

function Test-ReleasePort([int]$Port) {
    return @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq $Port).Count -gt 0
}

function Test-ReleaseReady([Net.Http.HttpClient]$Http) {
    try { return (Get-ReleaseJson $Http 'http://127.0.0.1:8765/actuator/health/readiness').status -ceq 'UP' } catch { return $false }
}

function Test-ReleaseOllama([Net.Http.HttpClient]$Http) {
    try {
        $tags = Get-ReleaseJson $Http 'http://127.0.0.1:11434/api/tags'
        return $null -ne $tags.PSObject.Properties['models'] -and $tags.models -is [Array]
    } catch { return $false }
}

function Resolve-ReleaseOllama([bool]$Ready) {
    $command = Get-Command ollama -ErrorAction SilentlyContinue
    $executable = if ($null -ne $command) { $command.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe' }
    if (-not $Ready -and -not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw 'Ollama is unavailable. Install Ollama and the configured model, then retry.'
    }
    return $executable
}

function Get-ReleaseProvider([Net.Http.HttpClient]$Http, [string]$TokenFile) {
    $token = Read-ReleaseToken $TokenFile
    try {
        $result = Get-ReleaseJson $Http 'http://127.0.0.1:8765/api/v1/providers/readiness' $token
        if ($result.provider -cne 'ollama' -or $result.profile -cne 'translate.fast' -or
            $result.available -isnot [bool] -or $result.modelAvailable -isnot [bool]) { throw 'Invalid Runtime response.' }
        return $result
    } catch { throw 'Runtime could not be authenticated and validated; check its private credential and port 8765.' }
    finally { $token = $null }
}

function Wait-ReleaseService([scriptblock]$Ready, [Diagnostics.Process]$Process, [string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        if (& $Ready) { return }
        if ($null -ne $Process -and $Process.HasExited) { throw "$Name exited during startup; check private RuntimeState Logs." }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Name did not become ready within 45 seconds; check private RuntimeState Logs."
}

function Assert-ReleaseBundle([string]$Root) {
    $manifest = Get-Content -LiteralPath (Join-Path $Root 'package-manifest.json') -Raw | ConvertFrom-Json
    if ($manifest.formatVersion -ne 1 -or $manifest.targetRid -cne 'win-x64' -or $manifest.requiredJavaMajor -ne 21 -or
        $manifest.selfContainedDesktop -isnot [bool] -or -not $manifest.selfContainedDesktop -or $manifest.files.Count -lt 10 -or
        $manifest.runtimeArtifact -cnotmatch '^runtime/personal-ai-workspace-[0-9.]+\.jar$') { throw 'Invalid portable package manifest.' }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest.files) {
        if ($entry.path -notmatch '^(desktop|runtime|release)/[A-Za-z0-9_./-]+$|^(README.txt|start-workspace.cmd)$' -or
            $entry.path.Contains('..') -or $entry.path.Contains('//') -or -not $names.Add($entry.path) -or
            $entry.sha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid package artifact metadata.' }
        $file = Join-Path $Root $entry.path.Replace('/', '\')
        for ($part = $file; $part; $part = [IO.Path]::GetDirectoryName($part)) {
            if (((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked package artifacts are not supported.' }
        }
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256) { throw 'Package integrity check failed; use a fresh complete package.' }
    }
    foreach ($file in Get-ChildItem -LiteralPath $Root -Recurse -File -Force) {
        $relative = $file.FullName.Substring($Root.TrimEnd('\').Length + 1).Replace('\', '/')
        if ($relative -notin @('package-manifest.json', 'SHA256SUMS.txt') -and -not $names.Contains($relative)) { throw 'Unexpected package file; keep user state outside the package.' }
    }
    if (-not $names.Contains($manifest.runtimeArtifact) -or -not $names.Contains('desktop/PersonalAiWorkspace.Desktop.exe')) { throw 'Required package artifacts missing.' }
    return $manifest
}
