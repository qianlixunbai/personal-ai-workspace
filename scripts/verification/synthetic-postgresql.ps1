#Requires -Version 7.4
# Development-only library. The generated launcher embeds reviewed identities and hashes.
Set-StrictMode -Version Latest

function Get-SyntheticHash($Stream) {
    $Stream.Position = 0
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Stream)).ToLowerInvariant()
    $Stream.Position = 0
    return $hash
}

function Read-SyntheticText($Stream, [int]$Limit = 65536) {
    if ($Stream.Length -gt $Limit) { throw 'BOUNDED_FILE_REJECTED' }
    $Stream.Position = 0
    $reader = [IO.StreamReader]::new($Stream, [Text.Encoding]::UTF8, $true, 1024, $true)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose(); $Stream.Position = 0 }
}

function Read-SyntheticSecret($Pins, [string]$Path) {
    $stream = $Pins.File($Path)
    if ($stream.Length -gt 16384) { throw 'CREDENTIAL_SIZE_REJECTED' }
    $cipher = [byte[]]::new($stream.Length)
    $stream.ReadExactly($cipher)
    $plain = $null
    try {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect($cipher, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        return [Text.Encoding]::UTF8.GetString($plain) | ConvertFrom-Json
    } finally {
        [Security.Cryptography.CryptographicOperations]::ZeroMemory($cipher)
        if ($null -ne $plain) { [Security.Cryptography.CryptographicOperations]::ZeroMemory($plain) }
    }
}

function Invoke-SyntheticTool([string]$Path, [string[]]$Arguments, [string]$Password = '', [int]$TimeoutSeconds = 10, [switch]$DiscardOutput) {
    $info = [Diagnostics.ProcessStartInfo]::new($Path)
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.WorkingDirectory = [IO.Path]::GetDirectoryName($Path)
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    # Do not inherit libpq/server options, loader hooks, service files or passwords.
    $info.Environment.Clear()
    foreach ($name in @('SystemRoot', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value) { $info.Environment[$name] = $value }
    }
    $info.Environment['PATH'] = [IO.Path]::GetDirectoryName($Path) + ';' + (Join-Path $env:SystemRoot 'System32')
    $info.Environment['LC_ALL'] = 'C'; $info.Environment['PGCONNECT_TIMEOUT'] = '5'
    $info.Environment['PGCLIENTENCODING'] = 'UTF8'
    $info.Environment['PGOPTIONS'] = '-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=3000'
    if ($Password) { $info.Environment['PGPASSWORD'] = $Password }
    $process = $null
    try {
        $process = [Diagnostics.Process]::Start($info)
        # Windows postgres may retain inherited pg_ctl pipe handles after pg_ctl exits.
        # Start has bounded, non-secret output and needs only its exit status; never wait for pipe EOF.
        if (!$DiscardOutput) { $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync() }
        if (!$process.WaitForExit($TimeoutSeconds * 1000)) { throw 'TOOL_TIMEOUT_NO_RETRY_OR_STOP' }
        if ($DiscardOutput) { return @{ ExitCode=$process.ExitCode; Text=''; HasError=$false } }
        if (!$stdout.Wait(1000) -or !$stderr.Wait(1000)) { throw 'TOOL_OUTPUT_TIMEOUT' }
        # Raw stderr is never displayed; SQL/credentials can appear in libpq errors.
        return @{ ExitCode = $process.ExitCode; Text = $stdout.Result.Trim(); HasError = [bool]$stderr.Result.Trim() }
    } finally {
        $info.Environment.Remove('PGPASSWORD') | Out-Null
        if ($process) { $process.Dispose() }
    }
}

function Get-SyntheticListeners([int]$Port) {
    # Query the whole provider: an empty result differs from a provider/query failure.
    return @(Get-CimInstance -Namespace root/StandardCimv2 -ClassName MSFT_NetTCPConnection -Filter 'State=2' -OperationTimeoutSec 5 -ErrorAction Stop | Where-Object LocalPort -eq $Port)
}

function Get-SyntheticServiceProcessIds($Trust, $Processes) {
    # Read-only SCM evidence for the separately installed service; never control it.
    $excluded = [Collections.Generic.HashSet[uint32]]::new()
    $service = Get-CimInstance Win32_Service -Filter "Name='postgresql-x64-17'" -OperationTimeoutSec 5 -ErrorAction Stop
    if ($service -and $service.State -ceq 'Running' -and $service.PathName -ceq $Trust.SeparateServicePath -and
        $service.StartName -ceq 'NT AUTHORITY\NetworkService') {
        $root = @($Processes | Where-Object ProcessId -eq $service.ProcessId)
        if ($root.Count -ne 1 -or $root[0].Name -cne 'pg_ctl.exe') { throw 'SEPARATE_SERVICE_PROCESS_UNKNOWN' }
        $parent = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $root[0].ParentProcessId) -OperationTimeoutSec 5 -ErrorAction Stop
        if (!$parent -or $parent.Name -cne 'services.exe') { throw 'SEPARATE_SERVICE_PARENT_UNKNOWN' }
        $excluded.Add([uint32]$service.ProcessId) | Out-Null
        do {
            $changed = $false
            foreach ($process in $Processes) {
                if ($excluded.Contains([uint32]$process.ParentProcessId) -and !$excluded.Contains([uint32]$process.ProcessId)) {
                    $ancestor = @($Processes | Where-Object ProcessId -eq $process.ParentProcessId)
                    if ($ancestor.Count -ne 1 -or $process.CreationDate -lt $ancestor[0].CreationDate) { throw 'SEPARATE_SERVICE_GENERATION_UNKNOWN' }
                    $excluded.Add([uint32]$process.ProcessId) | Out-Null; $changed = $true
                }
            }
        } while ($changed)
    }
    return ,$excluded
}

function Assert-SyntheticProcess($Trust, $Listeners) {
    if ($Listeners.Count -ne 1 -or $Listeners[0].LocalAddress -cne '127.0.0.1') { throw 'PORT_OWNERSHIP_REJECTED' }
    $pidFile = Join-Path $Trust.Data 'postmaster.pid'
    if (!(Test-Path -LiteralPath $pidFile -PathType Leaf)) { throw 'PORT_OCCUPIED_BY_UNVERIFIED_PROCESS' }
    $lines = [IO.File]::ReadAllLines($pidFile)
    if ($lines.Count -lt 8 -or $lines[0] -cnotmatch '^[1-9][0-9]*$' -or
        $lines[1].Replace('/', '\') -ine $Trust.Data -or $lines[3] -cne [string]$Trust.Port -or
        $lines[5] -cne '127.0.0.1' -or $lines[7].Trim() -cne 'ready' -or
        [int]$lines[0] -ne $Listeners[0].OwningProcess) { throw 'POSTMASTER_IDENTITY_REJECTED' }
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $lines[0]) -OperationTimeoutSec 5 -ErrorAction Stop
    if (!$process -or $process.ExecutablePath -ine (Join-Path $Trust.Bin 'postgres.exe')) { throw 'PROCESS_SOURCE_REJECTED' }
    $owner = Invoke-CimMethod -InputObject $process -MethodName GetOwnerSid -OperationTimeoutSec 5 -ErrorAction Stop
    if ($owner.ReturnValue -ne 0 -or $owner.Sid -cne $Trust.UserSid) { throw 'PROCESS_OWNER_REJECTED' }
    $command = $process.CommandLine.Replace('/', '\')
    $data = [regex]::Escape($Trust.Data); $exe = [regex]::Escape((Join-Path $Trust.Bin 'postgres.exe'))
    if ($command -notmatch ('^"?' + $exe + '"?\s+"?-D"?\s+"?' + $data + '"?\s+"?-h"?\s+"?127\.0\.0\.1"?\s+"?-p"?\s+"?' + $Trust.Port + '"?\s*$')) {
        throw 'PROCESS_ARGUMENTS_REJECTED'
    }
    $start = [DateTimeOffset]$process.CreationDate
    if ([Math]::Abs($start.ToUnixTimeSeconds() - [long]$lines[2]) -gt 5) { throw 'PROCESS_GENERATION_REJECTED' }
}

function Get-SyntheticDatabaseIdentity($Trust, [string]$Password) {
    $sql = @'
SELECT json_build_object('system', system_identifier::text, 'data', current_setting('data_directory'),
 'config', current_setting('config_file'), 'hba', current_setting('hba_file'), 'ident', current_setting('ident_file'),
 'address', host(inet_server_addr()), 'port', inet_server_port(), 'version', current_setting('server_version_num')::int,
 'user', current_user, 'databases', (SELECT json_object_agg(datname, json_build_object('oid', oid::text,
 'marker', shobj_description(oid, 'pg_database'))) FROM pg_database WHERE datname IN
 ('fnext1_finance_SUFFIX','fnext1_workspace_SUFFIX'))) FROM pg_control_system()
'@
    $result = Invoke-SyntheticTool (Join-Path $Trust.Bin 'psql.exe') @('-X','-w','-A','-t','-v','ON_ERROR_STOP=1',
        '-h','127.0.0.1','-p',[string]$Trust.Port,'-U','fnext_admin','-d','postgres','-c',$sql.Replace('SUFFIX',$Trust.Suffix)) $Password
    if ($result.ExitCode -ne 0) { throw 'DATABASE_IDENTITY_QUERY_FAILED' }
    try { return $result.Text | ConvertFrom-Json } catch { throw 'DATABASE_IDENTITY_RESPONSE_REJECTED' }
}

function Assert-SyntheticDatabaseIdentity($Trust, $Actual) {
    if ($Actual.system -cne $Trust.SystemId -or $Actual.data.Replace('/', '\') -ine $Trust.Data -or
        $Actual.config.Replace('/', '\') -ine (Join-Path $Trust.Data 'postgresql.conf') -or
        $Actual.hba.Replace('/', '\') -ine (Join-Path $Trust.Data 'pg_hba.conf') -or
        $Actual.ident.Replace('/', '\') -ine (Join-Path $Trust.Data 'pg_ident.conf') -or
        $Actual.address -cne '127.0.0.1' -or $Actual.port -ne $Trust.Port -or
        $Actual.version -lt 170000 -or $Actual.version -ge 180000 -or $Actual.user -cne 'fnext_admin') { throw 'DATABASE_INSTANCE_MISMATCH' }
    foreach ($database in $Trust.Databases.PSObject.Properties) {
        $actualDb = $Actual.databases.($database.Name)
        if ($actualDb.oid -cne $database.Value -or $actualDb.marker -cne ('FNEXT1_SYNTHETIC_' + $Trust.Suffix)) { throw 'DATABASE_DATASET_MISMATCH' }
    }
}

function Start-SyntheticPostgresql($Trust, [switch]$CheckOnly) {
    if (!$IsWindows -or $Trust.Format -cne 'FNEXT2_SYNTHETIC_START_V1' -or
        $Trust.Suffix -cnotmatch '^[a-f0-9]{12}$' -or $Trust.Port -lt 55000 -or $Trust.Port -gt 55999 -or
        $Trust.Root -cne ('D:\PersonalAiWorkspace-Fnext1\' + $Trust.Suffix) -or
        $Trust.Data -cne (Join-Path $Trust.Root 'pgdata') -or $Trust.Bin -cne 'D:\PostgreSQL\bin' -or
        $Trust.UserSid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) { throw 'FIXED_SYNTHETIC_TARGET_REJECTED' }
    $pins = [SyntheticPostgresqlFiles]::new(); $lock = $null; $fixture = $null
    try {
        if (!(Test-Path -LiteralPath $Trust.Root -PathType Container) -or !(Test-Path -LiteralPath $Trust.Data -PathType Container)) { throw 'DATA_DIRECTORY_MISSING' }
        if ($pins.Directory($Trust.Root, $true) -cne $Trust.RootIdentity -or
            $pins.Directory($Trust.Data) -cne $Trust.DataIdentity) { throw 'DATA_DIRECTORY_IDENTITY_REJECTED' }
        # Same private root lock across every package/process; no receipt or credentials are overwritten.
        $lockPath = Join-Path $Trust.Root 'synthetic-start.lock'
        try {
            if (Test-Path -LiteralPath $lockPath) {
                $pins.File($lockPath, $false).Dispose()
                $lock = [IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
            } else {
                $lock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            }
        }
        catch { throw 'START_ALREADY_IN_PROGRESS' }
        foreach ($name in @('base','global','pg_wal','pg_xact','pg_multixact','pg_tblspc')) {
            if (!(Test-Path -LiteralPath (Join-Path $Trust.Data $name) -PathType Container)) { throw 'CLUSTER_STRUCTURE_INCOMPLETE' }
        }
        $pins.Tree($Trust.Data)
        foreach ($entry in $Trust.Config) {
            if ((Get-SyntheticHash ($pins.File((Join-Path $Trust.Data $entry.Name)))) -cne $entry.Hash) { throw 'CLUSTER_CONFIGURATION_MISMATCH' }
        }
        if ((Read-SyntheticText ($pins.File((Join-Path $Trust.Data 'PG_VERSION')))).Trim() -cne '17') { throw 'POSTGRESQL_MAJOR_REJECTED' }
        foreach ($name in @('standby.signal','recovery.signal','backup_label')) {
            if (Test-Path -LiteralPath (Join-Path $Trust.Data $name)) { throw 'RECOVERY_CONFIGURATION_REJECTED' }
        }
        if (@(Get-ChildItem -LiteralPath (Join-Path $Trust.Data 'pg_tblspc') -Force -ErrorAction Stop).Count) { throw 'EXTERNAL_TABLESPACE_REJECTED' }
        foreach ($entry in $Trust.Sources) {
            if ((Get-SyntheticHash ($pins.Source($entry.Path))) -cne $entry.Hash) { throw 'POSTGRESQL_SOURCE_HASH_MISMATCH' }
        }
        $inventory = @(foreach ($folder in @('bin','lib','share')) {
            Get-ChildItem -LiteralPath (Join-Path (Split-Path $Trust.Bin -Parent) $folder) -Recurse -File -Force -ErrorAction Stop | Select-Object -ExpandProperty FullName
        })
        if (@(Compare-Object ($inventory | Sort-Object) ($Trust.Sources.Path | Sort-Object)).Count) { throw 'POSTGRESQL_SOURCE_INVENTORY_MISMATCH' }
        $version = Invoke-SyntheticTool (Join-Path $Trust.Bin 'pg_ctl.exe') @('--version')
        if ($version.ExitCode -ne 0 -or $version.Text -notmatch '^pg_ctl \(PostgreSQL\) 17\.') { throw 'POSTGRESQL_VERSION_REJECTED' }
        $control = Invoke-SyntheticTool (Join-Path $Trust.Bin 'pg_controldata.exe') @('-D',$Trust.Data)
        if ($control.ExitCode -ne 0 -or $control.HasError -or $control.Text -notmatch ('(?m)^Database system identifier:\s+' + $Trust.SystemId + '\s*$')) { throw 'CONTROL_IDENTITY_REJECTED' }
        $fixture = Read-SyntheticSecret $pins (Join-Path $Trust.Root 'fixture.dpapi')
        if ($fixture.suffix -cne $Trust.Suffix -or $fixture.port -ne $Trust.Port -or !$fixture.adminPassword) { throw 'FIXTURE_CREDENTIAL_IDENTITY_REJECTED' }
        $listeners = @(Get-SyntheticListeners $Trust.Port)
        if (!$listeners.Count) {
            if ($CheckOnly) { throw 'DATABASE_NOT_RUNNING_CHECK_ONLY' }
            if (Test-Path -LiteralPath (Join-Path $Trust.Data 'postmaster.pid')) {
                $pidStream = $pins.File((Join-Path $Trust.Data 'postmaster.pid'), $false)
                $oldPid = (Read-SyntheticText $pidStream) -split '\r?\n'
                if ($oldPid.Count -lt 8 -or $oldPid[0] -cnotmatch '^[1-9][0-9]*$' -or
                    $oldPid[1].Replace('/', '\') -ine $Trust.Data -or $oldPid[3] -cne [string]$Trust.Port -or
                    $oldPid[5] -cne '127.0.0.1' -or
                    (Get-CimInstance Win32_Process -Filter ('ProcessId=' + $oldPid[0]) -OperationTimeoutSec 5 -ErrorAction Stop)) { throw 'POSTMASTER_PID_STATE_UNSAFE' }
                # Leave the stale file untouched. Only normal PostgreSQL start may handle it.
                $pidStream.Dispose()
            }
            $processes = @(Get-CimInstance Win32_Process -Filter "name='postgres.exe' or name='pg_ctl.exe'" -OperationTimeoutSec 5 -ErrorAction Stop)
            $separateService = Get-SyntheticServiceProcessIds $Trust $processes
            foreach ($process in $processes) {
                if ($separateService.Contains([uint32]$process.ProcessId)) { continue }
                $owner = Invoke-CimMethod -InputObject $process -MethodName GetOwnerSid -OperationTimeoutSec 5 -ErrorAction Stop
                if ($owner.ReturnValue -ne 0) { throw 'PROCESS_STATE_UNKNOWN' }
                if ($owner.Sid -ceq $Trust.UserSid -and (!$process.CommandLine -or
                    $process.CommandLine.Replace('/', '\').Contains($Trust.Data, [StringComparison]::OrdinalIgnoreCase))) { throw 'CLUSTER_PROCESS_PRESENT_OR_UNKNOWN' }
            }
            $status = Invoke-SyntheticTool (Join-Path $Trust.Bin 'pg_ctl.exe') @('status','-D',$Trust.Data)
            if ($status.ExitCode -ne 3) { throw 'STOPPED_STATE_NOT_CONFIRMED' }
            # Reserve only a new private log; never append to an unverified existing pathname.
            $log = Join-Path $Trust.Root ('synthetic-start-' + [Guid]::NewGuid().ToString('N') + '.log')
            $reservation = [IO.File]::Open($log, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
            $reservation.Dispose()
            $pins.File($log, $false) | Out-Null
            if (@(Get-SyntheticListeners $Trust.Port).Count) { throw 'PORT_CHANGED_BEFORE_START' }
            Write-Host 'SYNTHETIC_DATABASE_STARTING (timeout 30s)'
            $result = Invoke-SyntheticTool (Join-Path $Trust.Bin 'pg_ctl.exe') @('start','-D',$Trust.Data,'-p',
                (Join-Path $Trust.Bin 'postgres.exe'),'-l',$log,'-o',('-h 127.0.0.1 -p ' + $Trust.Port),'-w','-t','30','-s') -TimeoutSeconds 35 -DiscardOutput
            if ($result.ExitCode -ne 0) { throw 'POSTGRESQL_START_FAILED_OR_TIMEOUT_NO_AUTOMATIC_RETRY' }
            $listeners = @(Get-SyntheticListeners $Trust.Port)
        }
        Assert-SyntheticProcess $Trust $listeners
        Assert-SyntheticDatabaseIdentity $Trust (Get-SyntheticDatabaseIdentity $Trust $fixture.adminPassword)
        Assert-SyntheticProcess $Trust @(Get-SyntheticListeners $Trust.Port)
        Write-Host 'SYNTHETIC_DATABASE_IDENTITY_VERIFIED'
    } finally {
        $fixture = $null
        if ($lock) { $lock.Dispose() }
        $pins.Dispose()
    }
}
