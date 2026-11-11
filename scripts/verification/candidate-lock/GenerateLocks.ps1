#Requires -Version 7.4
# SOURCE REVIEW ONLY. One future offline restore; no Guard/Build/product entry points.
# Invoke only in the reviewed PowerShell host after reviewing this exact file and inputs.
[CmdletBinding()]
param([switch]$Worker)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repository='D:\IDEA\Daima\personal-ai-workspace'
$contractHash='6b5c069a8f5fe6256f842e2bf65f1bb5dee303a1f487c4fc370dec5de0759827'
$commonHash='ad86946901ac238ca8f63b66d712f3ce5bb60603d206a14169eb9df4d03a4e8f'
$common=if($Worker){Join-Path $PSScriptRoot 'Common.ps1'}else{Join-Path $repository 'scripts/verification/candidate-build/Common.ps1'}
# Check ancestors before importing code. Later path operations reuse the pinned Common.
foreach($p in @($PSCommandPath,$common,(Join-Path $PSScriptRoot 'lock-inputs.json'))){
    for($part=[IO.Path]::GetFullPath($p);$part;$part=[IO.Path]::GetDirectoryName($part)){
        if((Test-Path -LiteralPath $part) -and ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Linked control path'}
    }
}
if((Get-FileHash -LiteralPath $common -Algorithm SHA256).Hash.ToLowerInvariant() -cne $commonHash){throw 'Common source changed'}
. $common
$contractPath=Join-Path $PSScriptRoot 'lock-inputs.json'
if((Get-Sha $contractPath) -cne $contractHash){throw 'Lock input contract changed; review a new revision'}
$contract=Read-StrictJson $contractPath
$script:Root=$contract.root
if($contract.repository -cne $repository -or $contract.productRevision -cne $script:ProductRevision -or
    $contract.format -cne 'WORKSPACE_LOCK_ONLY_INPUTS_V1' -or $contract.sdk -cne '10.0.401'){throw 'Unexpected fixed contract'}
Assert-PlainPath $Root
if(-not $IsWindows -or [Diagnostics.Process]::GetCurrentProcess().SessionId -ne 1){throw 'Windows Session 1 required; no session/identity fallback'}
$source=Resolve-Below $Root 'source'
$feed=Resolve-Below $Root 'inputs/nuget-feed'
$script:Spec=@{executables=@{git='tools/git/cmd/git.exe';dotnet='tools/dotnet/dotnet.exe'}}
$script:InputHashes=@{}; $script:Step=0

function Set-LockEnvironment {
    Set-BuildEnvironment $Root
    foreach($key in @('JAVA_HOME','NODE_OPTIONS')){[Environment]::SetEnvironmentVariable($key,$null,'Process')}
    $values=@{
        PATH=((@('tools/dotnet','tools/pwsh','tools/git/cmd') | ForEach-Object {Resolve-Below $Root $_}) + (Join-Path $env:SystemRoot 'System32') -join ';')
        MSBuildSDKsPath=(Resolve-Below $Root 'tools/dotnet/sdk/10.0.401/Sdks')
        DOTNET_SKIP_FIRST_TIME_EXPERIENCE='1';DOTNET_ADD_GLOBAL_TOOLS_TO_PATH='0';MSBuildEnableWorkloadResolver='false'
        DOTNET_GENERATE_ASPNET_CERTIFICATE='false'
    }
    foreach($entry in $values.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
}

function Export-LockSource {
    $reader=New-PrivateGitReader $repository $Root
    $gitArgs=@('--no-replace-objects',('--git-dir='+$reader),'-c','core.fsmonitor=false','-c','core.attributesFile=',
        '-c','diff.external=','-c','core.hooksPath=','-c','gc.auto=0')
    $tree=Invoke-BuildTool git ($gitArgs+@('ls-tree','-r','-z','--full-tree',$script:ProductRevision)) $Root
    $entries=@{}
    foreach($record in ([IO.File]::ReadAllText($tree.Stdout,[Text.UTF8Encoding]::new($false,$true))).Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)){
        if($record -cnotmatch '^(100644|100755) blob ([a-f0-9]{40})\t(.+)$'){throw 'Unsupported Git object mode/path'}
        $path=$Matches[3];Assert-RelativePath $path
        if($entries.ContainsKey($path) -or $path -match '(^|/)(obj|bin|node_modules|dist)/|(^|/)packages\.lock\.json$'){throw 'Duplicate or generated source input'}
        $entries[$path]=$Matches[2]
    }
    $paths=@($entries.Keys | Sort-Object -CaseSensitive)
    $request=Resolve-Below $Root 'tmp/blob-requests.txt'
    Write-NewText $request ((($paths | ForEach-Object {$entries[$_]}) -join "`n")+"`n")
    $blobs=Invoke-BuildTool git ($gitArgs+@('cat-file','--batch')) $Root @(0) $request
    if(Test-Path -LiteralPath $source){throw 'Source must be new'}
    $files=[Collections.Generic.List[object]]::new();$stream=[IO.File]::OpenRead($blobs.Stdout)
    try{foreach($path in $paths){
        $header=[Text.StringBuilder]::new()
        while(($next=$stream.ReadByte()) -ne 10){if($next -lt 0 -or $header.Length -gt 100){throw 'Invalid blob header'};[void]$header.Append([char]$next)}
        if($header.ToString() -cnotmatch '^([a-f0-9]{40}) blob ([0-9]+)$' -or $Matches[1] -cne $entries[$path]){throw 'Unexpected blob'}
        $length=[long]$Matches[2];if($length -gt 256MB){throw 'Oversized blob'}
        $bytes=[byte[]]::new([int]$length);$stream.ReadExactly($bytes,0,$bytes.Length)
        if($stream.ReadByte() -ne 10 -or [WorkspaceCandidateBuild.ArtifactReader]::GitBlobId($bytes) -cne $entries[$path]){throw 'Blob identity differs'}
        Write-NewBytes (Resolve-Below $source $path) $bytes
        $files.Add(@{path=$path;gitBlob=$entries[$path];bytes=$length;sha256=(Get-BytesSha $bytes)})
    };if($stream.ReadByte() -ne -1){throw 'Trailing blob data'}}finally{$stream.Dispose()}
    Write-NewJson (Resolve-Below $Root 'evidence/source-inputs.json') $files.ToArray()
}

function Invoke-LockRestore {
    # New nearest global.json, without altering the frozen root global.json.
    Write-NewText (Resolve-Below $source 'desktop/global.json') '{"sdk":{"version":"10.0.401","rollForward":"disable","allowPrerelease":false}}'
    $config=Resolve-Below $Root 'control/NuGet.Config';$escaped=[Security.SecurityElement]::Escape($feed)
    Write-NewText $config ('<configuration><packageSources><clear/><add key="private" value="'+$escaped+'"/></packageSources><auditSources><clear/></auditSources><packageSourceMapping><clear/></packageSourceMapping><fallbackPackageFolders><clear/></fallbackPackageFolders></configuration>')
    $empty=Resolve-Below $Root 'control/empty.targets';Write-NewText $empty '<Project />'
    $props=@('-p:Mmf3ProfileAcceptanceBuild=true','-p:Mmf3AcceptanceBuild=false','-p:MainWorkspaceDev=false','-p:FrontendSkipBuild=true',
        '-p:RuntimeFrameworkVersion=10.0.14','-p:SelfContained=true','-p:UseSharedCompilation=false','-p:MSBuildEnableWorkloadResolver=false',
        ('-p:DirectoryBuildPropsPath='+(Resolve-Below $source 'desktop/Directory.Build.props')),('-p:DirectoryBuildTargetsPath='+$empty),
        ('-p:CustomBeforeMicrosoftCommonTargets='+$empty),('-p:CustomAfterMicrosoftCommonTargets='+$empty),'-m:1','-nodeReuse:false',
        ('-p:RestoreSources='+$feed),'-p:RestoreAdditionalProjectSources=','-p:RestoreFallbackFolders=','-p:RestoreAdditionalProjectFallbackFolders=',
        '-p:DisableImplicitNuGetFallbackFolder=true','-p:DisableImplicitLibraryPacksFolder=true','-p:RestoreIgnoreFailedSources=false',
        '-p:NuGetInteractive=false','-p:ImportDirectoryPackagesProps=false',('-p:DirectoryPackagesPropsPath='+$empty))
    $packageCache=Resolve-Below $Root 'cache/nuget/packages'
    if(@(Get-ChildItem -LiteralPath $packageCache -Force).Count){throw 'Package cache must start empty'}
    $project=Resolve-Below $source 'desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj'
    Write-NewJson (Resolve-Below $Root 'evidence/restore-control-files.json') @(
        foreach($p in @($config,$empty,(Resolve-Below $source 'desktop/global.json'))){@{path=$p;sha256=(Get-Sha $p);bytes=(Get-Item -LiteralPath $p).Length}})
    # Exactly one restore. No locked-mode, retry, missing-package fallback or second entry.
    [void](Invoke-BuildTool dotnet (@('restore',$project,'-r','win-x64','--use-lock-file','--disable-parallel','--no-http-cache',
        '--configfile',$config,'-p:NuGetAudit=false','-p:Configuration=Debug','--verbosity','diagnostic')+$props) (Resolve-Below $source 'desktop'))
    Write-NewJson (Resolve-Below $Root 'evidence/worker-complete.json') @{restoreExitCode=0;restoreCalls=1;locksApproved=$false}
}

function Confirm-LockOutputs {
    # Called only after worker exit and Job/handle cleanup confirmation. Never in failure recovery.
    $wanted=@{};foreach($p in $contract.packages){$wanted[$p.id+'/'+$p.version]=$p}
    $cache=Resolve-Below $Root 'cache/nuget/packages';$actual=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($id in Get-ChildItem -LiteralPath $cache -Force){
        Assert-PlainPath $id.FullName
        if(-not $id.PSIsContainer -or $id.Name -cnotin @($contract.packages | ForEach-Object {$_.id.ToLowerInvariant()})){throw 'Unexpected cache package; not descended'}
        foreach($version in Get-ChildItem -LiteralPath $id.FullName -Force){
            Assert-PlainPath $version.FullName;$key=$id.Name+'/'+$version.Name
            if(-not $version.PSIsContainer -or -not $wanted.ContainsKey($key) -or -not $actual.Add($key)){throw 'Unexpected cache version; not descended'}
        }
    }
    if($actual.Count -ne 8){throw 'Fresh cache does not contain exactly eight reviewed packages'}
    $hashes=@(foreach($p in $contract.packages){
        $dir=Resolve-Below $cache ($p.id.ToLowerInvariant()+'/'+$p.version)
        $metadataPath=Join-Path $dir '.nupkg.metadata';$metadata=Read-StrictJson $metadataPath
        if($metadata.version -notin @(1,2) -or $metadata.contentHash -cne $p.contentHash -or
            [IO.Path]::GetFullPath($metadata.source).TrimEnd('\') -cne $feed){throw 'Fresh package metadata differs or uses an extra source'}
        if((Get-Sha (Join-Path $dir $p.archive)) -cne $p.sha256){throw 'Fresh archive differs'}
        @{id=$p.id;version=$p.version;archiveSha512=$p.archiveSha512;contentHash=$metadata.contentHash;metadataSha256=(Get-Sha $metadataPath)}
    })
    $locks=[Collections.Generic.List[object]]::new()
    foreach($name in @('Desktop','Core')){
        $dir=Resolve-Below $source ('desktop/src/PersonalAiWorkspace.'+$name)
        $lockPath=Join-Path $dir 'packages.lock.json';$lock=Read-StrictJson $lockPath
        $lockedPackages=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $direct=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase);$projectSeen=$false
        if($lock.version -notin @(1,2) -or $lock.dependencies -isnot [Collections.IDictionary] -or $lock.dependencies.Count -eq 0){throw 'Missing native lock graph'}
        foreach($tfm in $lock.dependencies.Keys){
            if($tfm -cnotmatch '^net10\.0-windows7\.0(?:/win-x64)?$'){throw 'Unexpected lock framework/RID'}
            foreach($dep in $lock.dependencies[$tfm].GetEnumerator()){
                if($dep.Value.type -ceq 'Project'){
                    if($name -cne 'Desktop' -or $dep.Key -ine 'PersonalAiWorkspace.Core'){throw 'Unexpected project in lock'}
                    $projectSeen=$true
                    continue
                }
                $key=$dep.Key+'/'+$dep.Value.resolved
                if($dep.Value.type -cnotin @('Direct','Transitive','CentralTransitive') -or -not $wanted.ContainsKey($key) -or $dep.Value.contentHash -cne $wanted[$key].contentHash){throw 'Unexpected locked dependency/content hash'}
                [void]$lockedPackages.Add($key)
                if($dep.Value.type -ceq 'Direct'){[void]$direct.Add($dep.Key)}
            }
        }
        $expectedPackages=@(if($name -ceq 'Desktop'){'DocumentFormat.OpenXml/3.5.1';'DocumentFormat.OpenXml.Framework/3.5.1';'Microsoft.Web.WebView2/1.0.4258.31';'PdfPig/0.1.16'})
        $expectedDirect=@(if($name -ceq 'Desktop'){'DocumentFormat.OpenXml';'Microsoft.Web.WebView2';'PdfPig'})
        if(-not $lockedPackages.SetEquals([string[]]$expectedPackages) -or -not $direct.SetEquals([string[]]$expectedDirect) -or $projectSeen -ne ($name -ceq 'Desktop')){throw 'Incomplete lock dependency graph'}
        $obj=Join-Path $dir 'obj';$assets=Read-StrictJson (Join-Path $obj 'project.assets.json')
        if($assets.packageFolders.Count -ne 1 -or [IO.Path]::GetFullPath(@($assets.packageFolders.Keys)[0]).TrimEnd('\') -cne $cache){throw 'Non-private asset cache'}
        if($assets.project.restore.sources.Count -ne 1 -or @($assets.project.restore.sources.Keys)[0] -cne $feed){throw 'Extra restore source'}
        $assetPackages=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        if(-not $assets.targets.Contains('net10.0-windows7.0/win-x64')){throw 'Requested asset RID missing'}
        foreach($tfm in $assets.targets.Keys){if($tfm -cnotmatch '^net10\.0-windows7\.0(?:/win-x64)?$'){throw 'Unexpected asset framework/RID'}}
        foreach($lib in $assets.libraries.GetEnumerator()){
            if($lib.Value.type -ceq 'project'){if($name -cne 'Desktop' -or $lib.Key -ine 'PersonalAiWorkspace.Core/1.0.0'){throw 'Unexpected asset project'};continue}
            if($lib.Value.type -cne 'package' -or -not $wanted.ContainsKey($lib.Key) -or $lib.Value.sha512 -cne $wanted[$lib.Key].contentHash){throw 'Unexpected asset dependency'}
            [void]$assetPackages.Add($lib.Key)
        }
        if(-not $assetPackages.SetEquals($lockedPackages)){throw 'Assets and lock package sets differ'}
        if(Test-Path -LiteralPath (Join-Path $dir 'bin')){throw 'Unexpected build output directory'}
        foreach($file in Get-Inventory $obj){
            if([IO.Path]::GetExtension($file.path) -in @('.dll','.exe','.pdb')){throw 'Unexpected compiled restore output'}
            if([IO.Path]::GetExtension($file.path) -in @('.json','.props','.targets')){Assert-StructuredPaths $Root (Resolve-Below $obj $file.path)}
        }
        Assert-StructuredPaths $Root $lockPath
        $locks.Add(@{name=$name;path=$lockPath;sha256=(Get-Sha $lockPath);bytes=(Get-Item -LiteralPath $lockPath).Length;graph=$lock})
    }
    # Check original Git bytes individually; additions (locks/obj/global.json) are not frozen source.
    foreach($file in Read-StrictJson (Resolve-Below $Root 'evidence/source-inputs.json')){
        $p=Resolve-Below $source $file.path
        if((Get-Item -LiteralPath $p).Length -ne $file.bytes -or (Get-Sha $p) -cne $file.sha256){throw 'Frozen source changed'}
    }
    Write-NewJson (Resolve-Below $Root 'evidence/nuget-hashes.json') $hashes
    Write-NewJson (Resolve-Below $Root 'evidence/output-inventory.json') @(
        foreach($rel in @('source/desktop/src/PersonalAiWorkspace.Desktop/obj','source/desktop/src/PersonalAiWorkspace.Core/obj','cache/nuget')){
            @{root=$rel;files=@(Get-Inventory (Resolve-Below $Root $rel))}})
    foreach($lock in $locks){Copy-PinnedFile $lock.path (Resolve-Below $Root ('accepted-locks/'+$lock.name+'.packages.lock.json')) $lock.sha256 $lock.bytes}
    Write-NewJson (Resolve-Below $Root 'evidence/lock-review.json') @{status='GENERATED_PENDING_INDEPENDENT_REVIEW';locks=$locks.ToArray();approvedForCandidate=$false}
}

if($Worker){
    if($PSCommandPath -cne (Resolve-Below $Root 'control/GenerateLocks.ps1')){throw 'Worker must use the private script'}
    $ownership=Read-StrictJson (Resolve-Below $Root 'ownership.json')
    if(-not $env:WORKSPACE_CANDIDATE_JOB_TOKEN -or $ownership.token -cne $env:WORKSPACE_CANDIDATE_JOB_TOKEN -or
        $ownership.runnerSha256 -cne (Get-Sha $PSCommandPath) -or $ownership.inputSha256 -cne $contractHash){throw 'Worker ownership mismatch'}
    Set-LockEnvironment
    $support=Resolve-Below $Root 'control/BuildSupport.cs'
    if((Get-Sha $support) -cne $contract.support.sha256){throw 'Support source changed'}
    Add-Type -Path $support
    foreach($treeRef in $contract.trees){
        $treePath=Resolve-Below $Root ('control/'+$treeRef.id+'-tree.json')
        if((Get-Sha $treePath) -cne $treeRef.inventory.sha256){throw 'Tool inventory changed'}
        $tree=Read-StrictJson $treePath
        foreach($file in $tree.files){$script:InputHashes['tools/'+$tree.id+'/'+$file.path]=$file.sha256}
    }
    Export-LockSource
    Invoke-LockRestore
    exit 0
}

if($PSCommandPath -cne (Join-Path $repository 'scripts/verification/candidate-lock/GenerateLocks.ps1')){throw 'Use the reviewed repository entry'}
if(Test-Path -LiteralPath $Root){throw 'Fixed run root exists; never resume, overwrite or retry'}
$pwsh=$contract.trees[0]
if([Diagnostics.Process]::GetCurrentProcess().MainModule.FileName -ine (Resolve-Below $pwsh.source 'pwsh.exe')){throw 'Must already be in the reviewed host; no relaunch fallback'}
$mutex=[Threading.Mutex]::new($false,'Local\Dot_KAG_Workspace_HeavyRun')
# Share the existing latch, so Guard and synthetic checks also see this runner's unresolved work.
$latch=Join-Path $repository '.verification/candidate-build-active.json'
$acquired=$false;$owned=$false;$latched=$false;$abandoned=$false;$attempted=$false;$job=$null
$failure=$null;$exitCode=$null;$clean=$false;$released=$false;$accepted=$false;$blocked=$true
try{
    try{$acquired=$mutex.WaitOne([TimeSpan]::FromSeconds(60))}catch [Threading.AbandonedMutexException]{
        $acquired=$true;$abandoned=$true
        if(-not(Test-Path -LiteralPath $latch)){Write-NewJson $latch @{state='ABANDONED_LOCK_UNRECONCILED';root=$Root};$latched=$true}
        throw 'Abandoned shared lock; reconcile prior outcome first'
    }
    if(-not $acquired){throw 'Shared heavy window busy; no process started'}
    foreach($p in @($latch,(Join-Path $repository '.verification/candidate-tools-synthetic-active.json'))){Assert-PlainPath $p;if(Test-Path -LiteralPath $p){throw 'Unresolved prior marker; no process started'}}
    Write-NewJson $latch @{state='LOCK_INPUTS_ACTIVE_OR_UNRECONCILED';root=$Root;guardianPid=$PID;session=1};$latched=$true
    [void](New-Item -ItemType Directory -Path $Root -ErrorAction Stop);$owned=$true
    $token=[guid]::NewGuid().ToString('N');$runnerHash=Get-Sha $PSCommandPath
    Write-NewJson (Resolve-Below $Root 'ownership.json') @{token=$token;runnerSha256=$runnerHash;inputSha256=$contractHash;productRevision=$script:ProductRevision;systemSandbox=$false}
    Copy-PinnedFile $PSCommandPath (Resolve-Below $Root 'control/GenerateLocks.ps1') $runnerHash (Get-Item -LiteralPath $PSCommandPath).Length
    Copy-PinnedFile $contractPath (Resolve-Below $Root 'control/lock-inputs.json') $contractHash (Get-Item -LiteralPath $contractPath).Length
    Copy-PinnedFile $common (Resolve-Below $Root 'control/Common.ps1') $commonHash $contract.common.bytes
    Copy-PinnedFile (Join-Path $repository 'scripts/verification/candidate-build/BuildSupport.cs') (Resolve-Below $Root 'control/BuildSupport.cs') $contract.support.sha256 $contract.support.bytes
    foreach($treeRef in $contract.trees){
        $dst=Resolve-Below $Root ('control/'+$treeRef.id+'-tree.json')
        Copy-PinnedFile (Resolve-Below $repository $treeRef.inventory.path) $dst $treeRef.inventory.sha256 $treeRef.inventory.bytes
        $tree=Read-StrictJson $dst
        if($tree.id -cne $treeRef.id -or $tree.source -cne $treeRef.source -or $tree.files.Count -ne $treeRef.count){throw 'Unexpected tool tree'}
        Copy-InputTree $tree $Root
    }
    foreach($p in $contract.packages){
        $dst=Resolve-Below $feed $p.archive;Copy-PinnedFile $p.source $dst $p.sha256 $p.bytes
        $stream=[IO.File]::OpenRead($dst)
        try{if([Convert]::ToBase64String([Security.Cryptography.SHA512]::HashData($stream)) -cne $p.archiveSha512){throw 'Archive SHA512 differs'}}finally{$stream.Dispose()}
    }
    Write-NewJson (Resolve-Below $Root 'evidence/input-copy-complete.json') @{contractSha256=$contractHash;treeCounts=@($contract.trees | ForEach-Object {$_.count});archives=8}
    Set-LockEnvironment
    Write-NewText (Resolve-Below $Root 'control/empty.gitconfig') ''
    Add-Type -Path (Resolve-Below $Root 'control/BuildSupport.cs')
    $attempted=$true;$job=[WorkspaceCandidateBuild.OwnedBuildJob]::new()
    [Environment]::SetEnvironmentVariable('WORKSPACE_CANDIDATE_JOB_TOKEN',$token,'Process')
    $exe=Resolve-Below $Root 'tools/pwsh/pwsh.exe';$workerArguments=@('-NoLogo','-NoProfile','-NonInteractive','-File',(Resolve-Below $Root 'control/GenerateLocks.ps1'),'-Worker')
    Write-NewJson (Resolve-Below $Root 'evidence/worker-command.json') @{executable=$exe;arguments=$workerArguments;deadlineSeconds=660;maxProcesses=12;processMiB=768;jobMiB=2048;systemSandbox=$false}
    $exitCode=$job.RunWorker($exe,[string[]]$workerArguments,$Root,(Resolve-Below $Root 'evidence/worker.stdout'),(Resolve-Below $Root 'evidence/worker.stderr'),(Resolve-Below $Root 'evidence/worker-started.json'),660)
    if($exitCode -ne 0){throw "Lock worker failed: $exitCode"}
}catch{$failure=$_.Exception.Message}
finally{
    try{
        if($job){
            try{$job.Finish();$clean=($job.ActiveProcesses -eq 0 -and $job.UnassignedCleanupConfirmed -and $job.AuxiliaryHandlesClosed)}catch{$failure=(@($failure,$_.Exception.Message) | Where-Object {$_}) -join '; '}
            try{$job.Dispose()}catch{$failure=(@($failure,$_.Exception.Message) | Where-Object {$_}) -join '; '}
            $clean=$clean -and $job.JobCloseConfirmed
            if($job.ForcedCleanup -and -not $failure){$failure='Worker left descendants; forced cleanup required'}
        }elseif(-not $attempted -and -not $abandoned){$clean=$true}
        if($owned){
            if(-not $failure -and $exitCode -eq 0 -and $clean){
                try{Confirm-LockOutputs;$accepted=$true}catch{$failure=$_.Exception.Message}
            }
            Write-NewJson (Resolve-Below $Root 'evidence/guardian-result.json') @{
                failure=$failure;workerExitCode=$exitCode;jobCleanupConfirmed=$clean;locksCopiedForReview=$accepted
                forcedCleanup=$(if($job){$job.ForcedCleanup}else{$false});jobCloseConfirmed=$(if($job){$job.JobCloseConfirmed}else{$null})
                workerResumed=$(if($job){$job.WorkerResumed}else{$false});assignmentConfirmed=$(if($job){$job.AssignmentConfirmed}else{$false})
                unassignedCleanupConfirmed=$(if($job){$job.UnassignedCleanupConfirmed}else{$null});auxiliaryHandlesClosed=$(if($job){$job.AuxiliaryHandlesClosed}else{$null})
                productionGo=$false;nativeValidation='NOT_RUN';approvedForCandidate=$false;systemSandbox=$false
            }
        }
        if($latched -and $clean -and -not $abandoned){Remove-Item -LiteralPath $latch -ErrorAction Stop;$blocked=$false}
    }finally{try{if($acquired){$mutex.ReleaseMutex();$released=$true}}finally{$mutex.Dispose()}}
    if($owned){Write-NewJson (Resolve-Below $Root 'evidence/guardian-released.json') @{lockReleased=$released;jobCleanupConfirmed=$clean;reentryBlocked=$blocked}}
}
if($failure){throw $failure}
