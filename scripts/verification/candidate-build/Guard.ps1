#Requires -Version 7.4
# SOURCE PREPARATION ONLY until this complete revision and its real input manifest are reviewed.
# Independent build guardian. Does not invoke any former verification/Run.ps1 workflow.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Inputs,[Parameter(Mandatory)][string]$Root)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
if (-not $IsWindows -or [Diagnostics.Process]::GetCurrentProcess().SessionId -ne 1) { throw 'Windows Session 1 required; do not switch identity/session' }
$spec=Read-Inputs $Inputs (Join-Path $PSScriptRoot 'inputs.schema.json')
$repository=[IO.Path]::GetFullPath($spec.repository).TrimEnd('\')
if($repository -cne [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..')).TrimEnd('\')){throw 'Guardian input must name its own repository'}
$Root=[IO.Path]::GetFullPath($Root)
if ([IO.Path]::GetDirectoryName($Root) -cne (Join-Path $repository '.verification') -or
    [IO.Path]::GetFileName($Root) -cnotmatch '^candidate-2ca5041-[0-9]{8}-[a-f0-9]{32}$') { throw 'Fresh repository-local candidate root required' }
Assert-PlainPath $Root
if (Test-Path -LiteralPath $Root) { throw 'Candidate root already exists; never resume or overwrite' }
$baseline=Join-Path $repository 'artifacts/PersonalAiWorkspace-knowledge-conversation-workflows-20261110'
if ([IO.Path]::GetFullPath($spec.baseline.directory) -cne $baseline -or
    [IO.Path]::GetFullPath($spec.baseline.zip) -cne ($baseline+'.zip')) { throw 'Only the reviewed baseline may be used' }
$pwshTree=@($spec.trees | Where-Object id -CEQ 'pwsh')[0]
if ($spec.executables.pwsh -cne 'tools/pwsh/pwsh.exe' -or
    [Diagnostics.Process]::GetCurrentProcess().MainModule.FileName -ine (Resolve-Below $pwshTree.source 'pwsh.exe')) {
    throw 'Guardian must already be running in the reviewed PowerShell host; no relaunch fallback'
}
$mutex=[Threading.Mutex]::new($false,'Local\Dot_KAG_Workspace_HeavyRun')
$acquired=$false; $ownedRoot=$false; $job=$null; $failure=$null; $exitCode=$null; $unchanged=$false; $cleanupConfirmed=$false
$baselineBeforeComplete=$false; $baselinePostflight='NOT_RUN'
try {
    try { $acquired=$mutex.WaitOne([TimeSpan]::FromSeconds(60)) }
    catch [Threading.AbandonedMutexException] { $acquired=$true; throw 'Abandoned heavy lock: previous outcome must be reconciled first' }
    if (-not $acquired) { throw 'Heavy window busy; no build started' }
    # New-Item without Force rejects an existing directory; the exclusive claim below also rejects reuse.
    [void](New-Item -ItemType Directory -Path $Root -ErrorAction Stop)
    $ownedRoot=$true
    $inputHash=Get-Sha $Inputs
    $token=[Guid]::NewGuid().ToString('N')
    Write-NewJson (Resolve-Below $Root 'ownership.json') @{inputSha256=$inputHash;productRevision=$spec.productRevision;buildToolsRevision=$spec.buildToolsRevision;token=$token;systemSandbox=$false}
    [void][IO.Directory]::CreateDirectory((Resolve-Below $Root 'evidence'))
    Copy-PinnedFile $Inputs (Resolve-Below $Root 'control/inputs.json') $inputHash (Get-Item -LiteralPath $Inputs).Length
    $required=@('Guard.ps1','Build.ps1','Common.ps1','BuildSupport.cs','inputs.schema.json','README-candidate.txt')
    if ($spec.controlFiles.Count -ne $required.Count) { throw 'Exact build control inventory required' }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($file in $spec.controlFiles) {
        if ($file.name -cnotin $required -or -not $seen.Add($file.name)) { throw 'Unexpected control input' }
        $relative=if($file.name -ceq 'README-candidate.txt') {'scripts/distribution/README-candidate.txt'} else {'scripts/verification/candidate-build/'+$file.name}
        Copy-PinnedFile (Resolve-Below $repository $relative) (Resolve-Below $Root ('control/'+$file.name)) $file.sha256 $file.bytes
    }
    Copy-InputTree $pwshTree $Root
    if ((Get-Sha ($baseline+'.zip')) -cne $script:BaselineZipHash -or
        (Get-Sha (Join-Path $baseline 'development-bundle.json')) -cne $spec.baseline.manifestSha256) { throw 'Baseline hash mismatch' }
    $before=Get-Inventory $baseline
    Write-NewJson (Resolve-Below $Root 'evidence/baseline-before.json') $before
    $baselineBeforeComplete=$true
    Set-BuildEnvironment $Root
    Write-NewText (Resolve-Below $Root 'control/empty.gitconfig') ''
    Add-Type -Path (Resolve-Below $Root 'control/BuildSupport.cs')
    $job=[WorkspaceCandidateBuild.OwnedBuildJob]::new()
    [Environment]::SetEnvironmentVariable('WORKSPACE_CANDIDATE_JOB_TOKEN',$token,'Process')
    $worker=Resolve-Below $Root 'tools/pwsh/pwsh.exe'
    $arguments=@('-NoLogo','-NoProfile','-NonInteractive','-File',(Resolve-Below $Root 'control/Build.ps1'),'-Root',$Root)
    Write-NewJson (Resolve-Below $Root 'evidence/worker-command.json') @{executable=$worker;arguments=$arguments;deadlineSeconds=660;processMiB=768;jobMiB=2048;systemSandbox=$false}
    $exitCode=$job.RunWorker($worker,[string[]]$arguments,$Root,
        (Resolve-Below $Root 'evidence/worker.stdout'),(Resolve-Below $Root 'evidence/worker.stderr'),
        (Resolve-Below $Root 'evidence/worker-started.json'),660)
    if ($exitCode -ne 0) { throw "Candidate worker failed: $exitCode" }
} catch { $failure=$_.Exception.Message }
finally {
  try {
    if ($job) {
        try { $job.Finish(); $cleanupConfirmed=($job.ActiveProcesses -eq 0 -and $job.UnassignedCleanupConfirmed) }
        catch { $failure=(@($failure,$_.Exception.Message) | Where-Object { $_ }) -join '; ' }
    }
    if ($ownedRoot) {
        # A failed worker may have encountered an access denial. Do not accidentally re-read
        # that target in a blanket finally audit. Preserve UNKNOWN; do not retry the build.
        if(-not $failure -and $exitCode -eq 0 -and $baselineBeforeComplete -and $cleanupConfirmed){
            try {
                $baselinePostflight='STARTED'
                Assert-SameInventory $before (Get-Inventory $baseline)
                if ((Get-Sha ($baseline+'.zip')) -cne $script:BaselineZipHash) { throw 'Baseline ZIP changed' }
                $unchanged=$true;$baselinePostflight='PASS'
            } catch { $failure=$_.Exception.Message;$baselinePostflight='FAILED_NO_RETRY' }
        }else{$baselinePostflight='NOT_RUN_AFTER_FAILURE_OR_UNKNOWN_CLEANUP'}
        $forced=if($job){$job.ForcedCleanup}else{$false}
        if ($forced -and -not $failure) { $failure='Worker left descendants; forced Job cleanup was required' }
        Write-NewJson (Resolve-Below $Root 'evidence/guardian-result.json') ([ordered]@{
            workerExitCode=$exitCode;workerPid=$(if($job){$job.WorkerPid}else{$null});failure=$failure
            baselineUnchanged=$unchanged;jobCleanupConfirmed=$cleanupConfirmed;forcedCleanup=$forced
            baselinePostflight=$baselinePostflight
            unassignedChildCleanupConfirmed=$(if($job){$job.UnassignedCleanupConfirmed}else{$null})
            productionGo=$false;nativeValidation='NOT_RUN';fullDesktopAcceptance=$false;systemSandbox=$false
        })
    }
  } finally {
    try { if ($job) { $job.Dispose() } }
    finally { try { if ($acquired) { $mutex.ReleaseMutex() } } finally { $mutex.Dispose() } }
  }
  if($ownedRoot){Write-NewJson (Resolve-Below $Root 'evidence/guardian-released.json') @{jobHandleClosed=$true;lockReleased=$acquired;workerCleanupConfirmed=$cleanupConfirmed}}
}
if ($failure) { throw $failure }
