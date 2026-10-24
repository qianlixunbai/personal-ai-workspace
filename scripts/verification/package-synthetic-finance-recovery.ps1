#Requires -Version 7.4
[CmdletBinding()]
param([string]$DistributionName = 'PersonalAiWorkspace-finance-fnext2-recovery-development-20261023',
    [string]$RuntimeJar = '', [string]$FixtureReceipt = '', [string]$Profile = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$baseline = Join-Path $repo 'artifacts/PersonalAiWorkspace-finance-fnext2-development-20261023'
if ($DistributionName -cnotmatch '^PersonalAiWorkspace-finance-(fnext2-recovery|fnext3|fnext4|fnext5|fnext6|fnext7|fnext8)-development-[A-Za-z0-9-]+$') { throw 'INVALID_DISTRIBUTION_NAME' }
$destination = Join-Path $repo ('artifacts/' + $DistributionName)
if (Test-Path -LiteralPath $destination) { throw 'NEW_OUTPUT_REQUIRED' }
. (Join-Path $PSScriptRoot 'synthetic-postgresql.ps1')
Add-Type -CompilerOptions '/nullable:enable' -Path @((Join-Path $repo 'bootstrap/Shared/WindowsProfileSecurity.cs'), (Join-Path $PSScriptRoot 'SyntheticPostgresqlFiles.cs'))
$pins = [SyntheticPostgresqlFiles]::new()
try {
    $old = Get-Content -LiteralPath (Join-Path $baseline 'BUILD-INFO.json') -Raw | ConvertFrom-Json
    if ($old.commit -cne 'fe331f35ed18766c06468ff01b62252d91e68f31' -or $old.format -cne 'FINANCE_F_NEXT_2_DEVELOPMENT') { throw 'DELIVERED_BASELINE_REQUIRED' }
    foreach ($entry in $old.files) {
        if ((Get-SyntheticHash ($pins.Source((Join-Path $baseline $entry.path)))) -cne $entry.sha256) { throw 'BASELINE_PAYLOAD_MISMATCH' }
    }
    $suffix = '939355624cdf'; $port = 55423
    if ($FixtureReceipt) {
        $selected = Get-Content -LiteralPath $FixtureReceipt -Raw | ConvertFrom-Json
        if ($selected.suffix -cnotmatch '^[a-f0-9]{12}$' -or $selected.port -lt 55000 -or $selected.port -gt 55999 -or
            $selected.root -cne (Join-Path 'D:\PersonalAiWorkspace-Fnext1' $selected.suffix) -or $Profile -cnotmatch '^[a-f0-9]{32}$') { throw 'ISOLATED_FIXTURE_REQUIRED' }
        $suffix = $selected.suffix; $port = $selected.port
    } elseif ($Profile) { throw 'EXPLICIT_FIXTURE_REQUIRED' }
    if (!$Profile) { $Profile = '86705e85cb5b4a22869dd34849e0c07b' }
    $root = Join-Path 'D:\PersonalAiWorkspace-Fnext1' $suffix; $data = Join-Path $root 'pgdata'
    $trust = [ordered]@{ Format='FNEXT2_SYNTHETIC_START_V1'; Suffix=$suffix; Root=$root; Data=$data; Port=$port;
        Bin='D:\PostgreSQL\bin'; Profile=$Profile;
        SeparateServicePath='"D:\PostgreSQL\bin\pg_ctl.exe" runservice -N "postgresql-x64-17" -D "D:\PostgreSQL\data" -w';
        UserSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;
        RootIdentity=$pins.Directory($root,$true); DataIdentity=$pins.Directory($data);
        SystemId=''; Databases=@{}; Config=@(); Sources=@() }
    $pins.Tree($data)
    # Review the existing initdb defaults. Reject includes, directory overrides, preloads and recovery hooks.
    $allowed = @('max_connections','shared_buffers','dynamic_shared_memory_type','max_wal_size','min_wal_size',
        'log_file_mode','log_timezone','datestyle','timezone','lc_messages','lc_monetary','lc_numeric','lc_time','default_text_search_config')
    $configText = Read-SyntheticText ($pins.File((Join-Path $data 'postgresql.conf')))
    foreach ($line in ($configText -split '\r?\n')) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^\s*([a-z_]+)\s*=' -or $allowed -cnotcontains $Matches[1]) { throw 'CONFIGURATION_REQUIRES_REVIEW' }
    }
    foreach ($name in @('postgresql.auto.conf','pg_ident.conf')) {
        if ((Read-SyntheticText ($pins.File((Join-Path $data $name)))) -match '(?m)^\s*[^\s#]') { throw 'NONDEFAULT_CONFIGURATION_REQUIRES_REVIEW' }
    }
    foreach ($line in ((Read-SyntheticText ($pins.File((Join-Path $data 'pg_hba.conf')))) -split '\r?\n')) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^\s*(local\s+(all|replication)\s+all\s+scram-sha-256|host\s+(all|replication)\s+all\s+(127\.0\.0\.1/32|::1/128)\s+scram-sha-256)\s*$') { throw 'HBA_REQUIRES_REVIEW' }
    }
    $trust.Config = @(foreach ($name in @('postgresql.conf','postgresql.auto.conf','pg_hba.conf','pg_ident.conf','PG_VERSION')) {
        @{Name=$name; Hash=(Get-SyntheticHash ($pins.File((Join-Path $data $name))))}
    })
    $trust.Sources = @(foreach ($folder in @('bin','lib','share')) {
        Get-ChildItem -LiteralPath (Join-Path 'D:\PostgreSQL' $folder) -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
            @{Path=$_.FullName; Hash=(Get-SyntheticHash ($pins.Source($_.FullName)))}
        }
    })
    $control = Invoke-SyntheticTool 'D:\PostgreSQL\bin\pg_controldata.exe' @('-D',$data)
    if ($control.ExitCode -ne 0 -or $control.HasError -or $control.Text -notmatch '(?m)^Database system identifier:\s+([0-9]+)\s*$') { throw 'CONTROL_REVIEW_FAILED' }
    $trust.SystemId = $Matches[1]
    $fixture = Read-SyntheticSecret $pins (Join-Path $root 'fixture.dpapi')
    if ($fixture.suffix -cne $trust.Suffix -or $fixture.port -ne $trust.Port) { throw 'FIXTURE_REVIEW_FAILED' }
    $actual = Get-SyntheticDatabaseIdentity ([pscustomobject]$trust) $fixture.adminPassword
    foreach ($name in @("fnext1_finance_$suffix","fnext1_workspace_$suffix")) { $trust.Databases[$name] = $actual.databases.$name.oid }
    $trustObject = $trust | ConvertTo-Json -Depth 8 -Compress | ConvertFrom-Json
    Assert-SyntheticDatabaseIdentity $trustObject $actual
    Assert-SyntheticProcess $trustObject @(Get-SyntheticListeners $trust.Port)
    $profileRoot = $pins.Profile($trust.Profile)
    $profileFiles = @(foreach ($relative in @('data/database.dpapi','data/finance.dpapi','data/finance-identity/binding.json')) {
        $path = Join-Path $profileRoot $relative
        @{Path=$path;Hash=(Get-SyntheticHash ($pins.Source($path)))}
    })
    $binding = Read-SyntheticText ($pins.Source((Join-Path $profileRoot 'data/finance-identity/binding.json'))) | ConvertFrom-Json
    $digest = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        foreach ($part in @($trust.SystemId,$trust.Databases["fnext1_finance_$suffix"],"fnext1_finance_$suffix")) {
            $bytes=[Text.Encoding]::UTF8.GetBytes($part); $length=[BitConverter]::GetBytes([int]$bytes.Length)
            if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($length) }
            $digest.AppendData($length); $digest.AppendData($bytes)
        }
        if ($binding.datasetFingerprint -cne [Convert]::ToHexString($digest.GetHashAndReset()).ToLowerInvariant()) { throw 'ORIGINAL_FINANCE_BINDING_DATASET_MISMATCH' }
    } finally { $digest.Dispose() }
    [IO.Directory]::CreateDirectory($destination) | Out-Null
    foreach ($entry in $old.files) {
        if ($entry.path -in @('Open-Synthetic-Finance.cmd','README.txt') -or $entry.path.StartsWith('runtime/')) { continue }
        $output = Join-Path $destination $entry.path
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($output)) | Out-Null
        [IO.File]::Copy((Join-Path $baseline $entry.path),$output,$false)
    }
    [IO.Directory]::CreateDirectory((Join-Path $destination 'runtime')) | Out-Null
    $jarSource = if ($RuntimeJar) { [IO.Path]::GetFullPath($RuntimeJar) } else { Join-Path $baseline 'runtime/personal-ai-workspace.jar' }
    $pins.Source($jarSource) | Out-Null
    [IO.File]::Copy($jarSource,(Join-Path $destination 'runtime/personal-ai-workspace.jar'),$false)
    $manifest = Get-Content -LiteralPath (Join-Path $repo '.verification/fnext2-package-sources.local.json') -Raw | ConvertFrom-Json
    if ((Get-FileHash -LiteralPath (Join-Path $repo '.verification/fnext2-package-sources.local.json')).Hash.ToLowerInvariant() -cne $old.runtimeManifestSha256) { throw 'BASELINE_MANIFEST_MISMATCH' }
    $previousJar = $manifest.RuntimeJar
    $manifest.RuntimeJar = Join-Path $destination 'runtime/personal-ai-workspace.jar'
    ($manifest.Files | Where-Object Path -ceq $previousJar).Path = $manifest.RuntimeJar
    ($manifest.Files | Where-Object Path -ceq $manifest.RuntimeJar).Sha256 = (Get-FileHash -LiteralPath $manifest.RuntimeJar).Hash.ToLowerInvariant()
    $manifestPath = Join-Path $repo ('.verification/' + $DistributionName + '-sources.local.json')
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
    # Re-embed the pinned independent JAR path; publish the current Desktop and bundled frontend.
    & dotnet publish (Join-Path $repo 'desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj') -c Debug -r win-x64 --self-contained false `
        '-p:Mmf3ProfileAcceptanceBuild=true' '-p:Mmf3AcceptanceBuild=false' '-p:FrontendSkipBuild=true' "-p:TrustedDevelopmentManifest=$manifestPath" -o $destination -v minimal
    if ($LASTEXITCODE) { throw 'DESKTOP_PUBLISH_FAILED' }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'synthetic-postgresql.ps1'),(Join-Path $PSScriptRoot 'SyntheticPostgresqlFiles.cs'),(Join-Path $repo 'bootstrap/Shared/WindowsProfileSecurity.cs') -Destination $destination
    $dependencies = @(foreach ($name in @('synthetic-postgresql.ps1','SyntheticPostgresqlFiles.cs','WindowsProfileSecurity.cs','PersonalAiWorkspace.Desktop.exe','PersonalAiWorkspace.Desktop.dll','runtime/personal-ai-workspace.jar')) {
        @{Name=$name;Hash=(Get-FileHash -LiteralPath (Join-Path $destination $name)).Hash.ToLowerInvariant()}
    })
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((@{Trust=$trust; Dependencies=$dependencies; ProfileFiles=$profileFiles} | ConvertTo-Json -Depth 10 -Compress)))
    $launcher = @'
#Requires -Version 7.4
[CmdletBinding()]
param([switch]$CheckOnly)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$stage = 'LAUNCHER_INTEGRITY'
try {
    $settings = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__SETTINGS__')) | ConvertFrom-Json
    foreach ($entry in $settings.Dependencies) {
        $path = Join-Path $PSScriptRoot $entry.Name
        if ((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $entry.Hash) { throw 'PACKAGE_HASH_MISMATCH' }
    }
    Add-Type -CompilerOptions '/nullable:enable' -Path @((Join-Path $PSScriptRoot 'WindowsProfileSecurity.cs'),(Join-Path $PSScriptRoot 'SyntheticPostgresqlFiles.cs'))
    . (Join-Path $PSScriptRoot 'synthetic-postgresql.ps1')
    $stage = 'EXISTING_PROFILE'
    $pins = [SyntheticPostgresqlFiles]::new()
    try {
        $pins.Profile($settings.Trust.Profile) | Out-Null
        foreach ($entry in $settings.ProfileFiles) {
            if ((Get-SyntheticHash ($pins.Source($entry.Path))) -cne $entry.Hash) { throw 'EXISTING_PROFILE_FILE_CHANGED' }
        }
        foreach ($entry in $settings.Dependencies) {
            if ((Get-SyntheticHash ($pins.Source((Join-Path $PSScriptRoot $entry.Name)))) -cne $entry.Hash) { throw 'PACKAGE_HASH_MISMATCH' }
        }
        $stage = 'SYNTHETIC_POSTGRESQL'
        Start-SyntheticPostgresql $settings.Trust -CheckOnly:$CheckOnly
        if (!$CheckOnly) {
            $stage = 'WORKSPACE_EXE'
            Start-Process -FilePath (Join-Path $PSScriptRoot 'PersonalAiWorkspace.Desktop.exe') -ArgumentList '--mmf3-profile-acceptance',$settings.Trust.Profile -WorkingDirectory $PSScriptRoot -WindowStyle Hidden | Out-Null
            Write-Host 'WORKSPACE_LAUNCH_REQUESTED (Native Gate still required)'
        }
    } finally { $pins.Dispose() }
} catch {
    $code = $_.Exception.Message
    if ($code -cnotmatch '^[A-Z][A-Z0-9_]+$') { $code = 'UNCONFIRMED_STATE' }
    Write-Host "STOP stage=$stage code=$code" -ForegroundColor Red
    Write-Host 'No fallback or database/profile repair. Preserve files and review the diagnostic code.'
    exit 10
}
'@
    $launcher.Replace('__SETTINGS__',$encoded) | Set-Content -LiteralPath (Join-Path $destination 'Open-Synthetic-Finance.ps1') -Encoding utf8NoBOM
    $pwsh = (Get-Command pwsh.exe).Source
    @"
@echo off
"$pwsh" -NoLogo -NoProfile -File "%~dp0Open-Synthetic-Finance.ps1"
if errorlevel 1 pause
"@ | Set-Content -LiteralPath (Join-Path $destination 'Open-Synthetic-Finance.cmd') -Encoding ascii
    $readme = @'
Development-only F-next-2 database startup recovery, same Windows user and existing Profile.
Double-click Open-Synthetic-Finance.cmd. Requires the existing PowerShell 7.4+, .NET 10, JDK 21 and WebView2.
The launcher validates the pinned synthetic cluster at 127.0.0.1:55423, starts it only if safely stopped,
then verifies system/database identity with read-only SQL before requesting Workspace launch.
Native Gate remains mandatory. No initdb, database creation, password input/reset, rebinding, or SQLite fallback.
The PostgreSQL cluster stays running after Workspace exits. No services/startup entries are changed.
Do not move this directory or replace pinned binaries/configuration. A STOP requires review, not deleting PID files.
After the next real Windows reboot: double-click the CMD, open Finance, verify Synthetic Alice,
3 accounts and CNY 9007199254740993.30; check existing Memory/Knowledge/Conversation, tray exit and reopen.
Real reboot acceptance is PENDING until the user performs it. This is not a production distribution.
'@
    if ($RuntimeJar) { $readme = $readme.Replace('F-next-2 database startup recovery','F-next-3 read-only transactions and database startup recovery') }
    if ($FixtureReceipt) { $readme += "`nThis package uses the explicitly selected isolated synthetic cluster and Profile; accepted identity files are preserved.`nOpen Finance -> Transactions for the six synthetic records. Account filters use the system ID from Accounts.`n" }
    $budgets = $DistributionName.StartsWith('PersonalAiWorkspace-finance-fnext4-', [StringComparison]::Ordinal)
    $assets = $DistributionName.StartsWith('PersonalAiWorkspace-finance-fnext5-', [StringComparison]::Ordinal)
    $history = $DistributionName.StartsWith('PersonalAiWorkspace-finance-fnext8-', [StringComparison]::Ordinal)
    $portfolio = $history -or $DistributionName.StartsWith('PersonalAiWorkspace-finance-fnext7-', [StringComparison]::Ordinal)
    $investments = $portfolio -or $DistributionName.StartsWith('PersonalAiWorkspace-finance-fnext6-', [StringComparison]::Ordinal)
    if ($investments) {
        $readme = @'
Development-only F-next-6 read-only investment positions, same Windows user and isolated synthetic Profile.
Double-click Open-Synthetic-Finance.cmd. Requires existing PowerShell 7.4+, .NET 10, JDK 21 and WebView2.
The launcher validates the pinned synthetic cluster at 127.0.0.1:55423, starts it only if safely stopped,
then verifies system/database identity with read-only SQL before requesting the packaged Workspace EXE.
Native Gate remains mandatory. No initdb, database creation/reset, password reset, rebinding or SQLite fallback.
Do not move this directory or replace pinned files. A STOP requires review. PostgreSQL remains running after tray exit.
Finance -> Investment Positions: OPEN/CLOSED/ALL, account and Instrument IDs, opaque source cursors, details and refresh.
Accounting cost and cumulative realized P/L retain the source CNY projection; manual and cached references are separate.
SYNTHETIC_FIXED is not a real provider. Unknown amounts remain unknown. No live quote calls, financial writes or portfolio aggregation.
Accounts, Transactions, Assets and the explicit unsupported Budgets view remain available.
Exit from the application tray, then double-click the CMD again. Real Windows reboot acceptance remains PENDING.
This is not a production distribution, Finance Freeze or Production GO.
'@
    }
    if ($portfolio) {
        $readme = $readme.Replace('F-next-6 read-only investment positions', 'F-next-7 read-only portfolio summary and investment positions')
        $readme = $readme.Replace('No live quote calls, financial writes or portfolio aggregation.', 'No live quote calls or financial writes.')
        $readme += "`nFinance -> Portfolio Summary reads ALL owned transaction-driven positions, independent of the position Cursor page. CNY accounting cost and realized P/L are separate from cached CNY market reference values. PARTIAL is only the valued subset; null / UNAVAILABLE is never a fabricated zero. Coverage counts and stale / missing quote / FX warnings retain source semantics.`n"
    }
    if ($history) {
        $readme = $readme.Replace('F-next-7 read-only portfolio summary and investment positions', 'F-next-8 read-only investment history, immutable audit, portfolio and positions')
        $readme += "`nFinance -> Investment History: original opaque Cursor, position/account/instrument/type/correction filters and explicit UTC Instant [from,to). Open a logical transaction to reread detail and immutable audit. REVERSED / REPLACED retain original facts. Grouped reversal and replacement facts belong to the replacement command; posting receipts, final correction receipts and current positions are distinct snapshots. Decimal strings remain exact. No investment write or replay command API.`n"
        Copy-Item -LiteralPath (Join-Path $repo 'finance-domain/investment-source-provenance.json') -Destination $destination
    }
    if ($assets) {
        $readme = $readme.Replace('F-next-3 read-only transactions', 'F-next-5 read-only assets')
        $readme += "`nFinance -> Assets: list, paging, type filter, detail, manual-price valuation and cached source reference valuations. All fixture assets/quotes are synthetic; SYNTHETIC_FIXED is not a real provider. Refresh only rereads stored snapshots, never updates quotes. Unknown amounts are not zero. No cross-currency totals or financial writes.`n"
    }
    if ($budgets) {
        $readme = $readme.Replace('F-next-3 read-only transactions', 'F-next-4 read-only budget availability')
        $readme += "`nFinance -> Budgets reports UNSUPPORTED after server identity verification. The frozen Finance source has no budget model or statistics rules; no budget list, detail, amounts or overspending status is fabricated.`n"
    }
    $readme.Replace('127.0.0.1:55423', ('127.0.0.1:' + $port)) | Set-Content -LiteralPath (Join-Path $destination 'README.txt') -Encoding utf8NoBOM
    [ordered]@{format=$(if ($history) { 'FINANCE_F_NEXT_8_DEVELOPMENT' } elseif ($portfolio) { 'FINANCE_F_NEXT_7_DEVELOPMENT' } elseif ($investments) { 'FINANCE_F_NEXT_6_DEVELOPMENT' } elseif ($assets) { 'FINANCE_F_NEXT_5_DEVELOPMENT' } elseif ($budgets) { 'FINANCE_F_NEXT_4_DEVELOPMENT' } elseif ($RuntimeJar) { 'FINANCE_F_NEXT_3_DEVELOPMENT' } else { 'FINANCE_F_NEXT_2_RECOVERY_DEVELOPMENT' });productionGo=$false;baseline=$old.commit;
        branch=(& git -C $repo branch --show-current).Trim();sourceCommit=(& git -C $repo rev-parse HEAD).Trim();
        sourceDirty=[bool]((& git -C $repo diff --name-only) | Out-String).Trim();
        runtimeManifestSha256=(Get-FileHash $manifestPath).Hash.ToLowerInvariant();
        runtimeManifest=$manifest; realWindowsReboot='PENDING'; profileAndSecretsIncluded=$false;
        files=@(Get-ChildItem -LiteralPath $destination -File -Recurse | Sort-Object FullName | ForEach-Object {
            @{path=$_.FullName.Substring($destination.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
        })} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $destination 'BUILD-INFO.json') -Encoding utf8NoBOM
    Write-Host "BUILT_DEVELOPMENT_ONLY $destination"
} finally { $fixture=$null; $pins.Dispose() }
