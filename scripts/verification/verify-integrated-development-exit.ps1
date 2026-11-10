param(
 [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{32}$')][string]$ProfileId,
 [Parameter(Mandatory)][ValidatePattern('^integrated-exit-observation-[a-z0-9-]+$')][string]$EvidenceName
)
# A focused final-r2 lifecycle observation. Existing local fixture/toolchain evidence is required.
# The named evidence directory must contain unchanged Seed.ps1 / SeedGuard.java fixtures from
# .verification/integrated-web-runtime-20261110; these only prepare synthetic metadata.
# The ordinary product entry is unchanged. Invoke its owned tray Exit once after React is ready.
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.Encoding]::UTF8
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $repo
$evidence=Join-Path $repo ('.verification/'+$EvidenceName)
[void][IO.Directory]::CreateDirectory($evidence)
$Phase='startup'
$bundle=Join-Path $repo 'artifacts/PersonalAiWorkspace-integrated-development-20261110-r2'

$attempt=Join-Path $evidence ($Phase+'-attempt.json')
if(Test-Path -LiteralPath $attempt){throw 'Phase already attempted; inspect existing evidence.'}
if([Diagnostics.Process]::GetCurrentProcess().SessionId -ne 1){throw 'Wrong session.'}
if(([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Administrator not supported.'}
foreach($name in @('WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS','WEBVIEW2_USER_DATA_FOLDER','WEBVIEW2_BROWSER_EXECUTABLE_FOLDER','WEBVIEW2_PIPE_FOR_SCRIPT_DEBUGGER','WEBVIEW2_WAIT_FOR_SCRIPT_DEBUGGER')){if([Environment]::GetEnvironmentVariable($name)){throw 'Unexpected browser override.'}}
# No port query or process inventory. The product retains its own unmodified admission.
$mutex=[Threading.Mutex]::new($false,'Local\Dot_KAG_Workspace_HeavyRun')
$held=$false;$job=$null;$child=$null;$stdout=$null;$stderr=$null;$copyOut=$null;$copyErr=$null
$exited=$false;$forced=$false;$failure=$null;$before=$null;$active=$null;$members=@();$proof=$null;$drained=$false
try {
 try {$held=$mutex.WaitOne(1000)}catch [Threading.AbandonedMutexException]{$held=$true;throw 'Abandoned lock; inspect before launch.'}
 if(!$held){Write-Output 'HEAVY_LOCK_BUSY';exit 20}
 $overall=[Diagnostics.Stopwatch]::StartNew()
 . (Join-Path $repo 'scripts/distribution/development-bundle.ps1')
 # Prior final bundle integrity accepted; no repeated full audit.
 if((Get-FileHash -LiteralPath (Join-Path $bundle 'PersonalAiWorkspace.Desktop.dll')).Hash -cne '07FDDD16EE97F6F1C1E03104BF5B5EC8746384FCC0F40EDFDC8D5E56C3235AFF'){throw 'Delivered Desktop changed.'}
 [ordered]@{phase=$Phase;profileId=$profileId;guardianPid=$PID;utc=[DateTime]::UtcNow;productCommit='75b9614d012c4cd6ee3b597da7f4074cdf59240b';portSystemQuery='NOT_ATTEMPTED';modelsRequested=0}|ConvertTo-Json|Set-Content -LiteralPath $attempt -Encoding utf8
 $jobSource=[IO.File]::ReadAllText((Join-Path $repo 'desktop/src/PersonalAiWorkspace.Desktop/RuntimeProcessJob.cs')).Replace('limits.Basic.LimitFlags = 0x2000;', 'limits.Basic.LimitFlags = 0x2300; limits.ProcessMemoryLimit = new UIntPtr(768u * 1024 * 1024); limits.JobMemoryLimit = new UIntPtr(2048u * 1024 * 1024);')
 Add-Type -CompilerOptions '/nullable:enable' -TypeDefinition ($jobSource+@'
public static class FullExeOwnedJob {
 public static System.IDisposable Create() => RuntimeProcessJob.Create();
 public static void Assign(System.IDisposable job,System.Diagnostics.Process child) => ((RuntimeProcessJob)job).Assign(child);
 public static uint Count(System.IDisposable job) => ((RuntimeProcessJob)job).ActiveProcessCount;
 [System.Runtime.InteropServices.DllImport("kernel32.dll",SetLastError=true)]static extern bool QueryInformationJobObject(RuntimeProcessJob job,int kind,System.IntPtr data,uint length,System.IntPtr returned);
 public static long[] Members(System.IDisposable owner) {
  var p=System.Runtime.InteropServices.Marshal.AllocHGlobal(4096);
  try {if(!QueryInformationJobObject((RuntimeProcessJob)owner,3,p,4096,System.IntPtr.Zero))throw new System.ComponentModel.Win32Exception();
   int n=System.Runtime.InteropServices.Marshal.ReadInt32(p,4);if(n<0||n>128)throw new System.IO.IOException("Job membership bound");
   var ids=new long[n];for(int i=0;i<n;i++)ids[i]=System.Runtime.InteropServices.Marshal.ReadIntPtr(p,8+i*System.IntPtr.Size).ToInt64();return ids;
  }finally{System.Runtime.InteropServices.Marshal.FreeHGlobal(p);}
 }
}
'@)
 $job=[PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Create()
 $prepare=Join-Path $evidence 'prepare'
 if($Phase -eq 'startup'){
  if(Test-Path -LiteralPath $prepare){throw 'Preparation already attempted.'}
  [IO.Directory]::CreateDirectory($prepare)|Out-Null
  $prepTool=Join-Path $repo '.verification/memory-native-persistence-20261110/bundle-under-test/PersonalAiWorkspace.Desktop.Tests.dll'
  if((Get-FileHash -LiteralPath $prepTool).Hash -cne '62BB9FB11C2CD86C0048DD412FE383813128535E2384CB8935259E5F8F942450'){throw 'Preparation tool changed.'}
  $prepStart=[Diagnostics.ProcessStartInfo]::new((Get-Command dotnet).Source)
  $prepStart.UseShellExecute=$false;$prepStart.CreateNoWindow=$true;$prepStart.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden;$prepStart.WorkingDirectory=$repo
  $prepStart.RedirectStandardOutput=$true;$prepStart.RedirectStandardError=$true
  foreach($arg in @('exec',$prepTool,'--native-memory-prepare',$prepare,$profileId)){$prepStart.ArgumentList.Add($arg)}
  $child=[Diagnostics.Process]::Start($prepStart);[PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Assign($job,$child)
  $out=$child.StandardOutput.ReadToEndAsync();$err=$child.StandardError.ReadToEndAsync()
  [ordered]@{pid=$child.Id;startedUtc=$child.StartTime.ToUniversalTime();profileId=$profileId}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'prepare-started.json') -Encoding utf8
  if(!$child.WaitForExit(30000)){throw 'Preparation deadline; do not replay.'}
  if(!$out.Wait(5000)-or !$err.Wait(5000)){throw 'Preparation output deadline.'}
  [IO.File]::WriteAllText((Join-Path $evidence 'prepare-stdout.log'),$out.Result);[IO.File]::WriteAllText((Join-Path $evidence 'prepare-stderr.log'),$err.Result)
  [ordered]@{pid=$child.Id;exitCode=$child.ExitCode;completed=$true}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'prepare-exit.json') -Encoding utf8
  if($child.ExitCode -ne 0){throw 'Preparation failed; do not replay.'}
  $child.Dispose();$child=$null
 }
 $proof=Get-Content -LiteralPath (Join-Path $prepare 'owned-profile.json') -Raw|ConvertFrom-Json
 if(!$proof.synthetic-or !$proof.newlyPrepared-or $proof.id -cne $profileId){throw 'Synthetic Profile binding mismatch.'}
 # OpenOnce bypasses preference read/write; do not inspect the real default record.
 function PreferenceHash { return 'NOT_READ_OPEN_ONCE' }
 $before=PreferenceHash
 & (Join-Path $evidence 'Seed.ps1') -Proof $proof -Job $job
 $seed=Get-Content -LiteralPath (Join-Path $evidence 'seed-result.json') -Raw|ConvertFrom-Json
 if($overall.ElapsedMilliseconds -ge 420000){throw 'Overall deadline before product launch.'}
 $start=[Diagnostics.ProcessStartInfo]::new((Join-Path $bundle 'PersonalAiWorkspace.Desktop.exe'))
 $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden;$start.WorkingDirectory=$bundle
 $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
 $start.ArgumentList.Add('--open-development-workspace-once');$start.ArgumentList.Add($profileId)
 $child=[Diagnostics.Process]::Start($start);[PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Assign($job,$child)
 $stdout=[IO.File]::Create((Join-Path $evidence ($Phase+'-stdout.log')));$stderr=[IO.File]::Create((Join-Path $evidence ($Phase+'-stderr.log')))
 $copyOut=$child.StandardOutput.BaseStream.CopyToAsync($stdout);$copyErr=$child.StandardError.BaseStream.CopyToAsync($stderr)
 $receipt=Join-Path $evidence ($Phase+'-exe.json')
 $state=[ordered]@{phase=$Phase;pid=$child.Id;startedUtc=$child.StartTime.ToUniversalTime().ToString('o');executable=$start.FileName;profileId=$profileId;profileRoot=$proof.root;arguments=@($start.ArgumentList);guardianPid=$PID;preferenceBefore=$before;productCommit='75b9614d012c4cd6ee3b597da7f4074cdf59240b';completed=$false}
 $state|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $receipt -Encoding utf8
 Write-Output "EXE_PHASE_STARTED phase=$Phase pid=$($child.Id)"
 $watch=$overall;$lastRecord=-5
 while(!$child.HasExited){
  $remainingMs=420000-$overall.ElapsedMilliseconds
  if($remainingMs -le 0){throw 'Owned EXE deadline; no replay.'}
  if($child.WaitForExit([int][Math]::Min(500,$remainingMs))){break}
  if($watch.Elapsed.TotalSeconds-$lastRecord -ge 5){$lastRecord=$watch.Elapsed.TotalSeconds;[ordered]@{elapsedSeconds=[math]::Round($lastRecord,1);utc=[DateTime]::UtcNow;jobMemberPids=@([PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Members($job));activeCount=[PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Count($job)}|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $evidence ($Phase+'-job-members.jsonl')) -Encoding utf8}
  if($watch.Elapsed.TotalSeconds -gt 420){throw 'Owned EXE deadline; no replay.'}
 }
 $exited=$true;$state.completed=$true;$state.exitCode=$child.ExitCode;$state.preferenceAfter=PreferenceHash
 $state|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $receipt -Encoding utf8
 if($state.preferenceBefore -cne $state.preferenceAfter){throw 'Default preference changed.'}
 if($child.ExitCode -ne 0){throw 'Product exited nonzero.'}
 Write-Output "EXE_PHASE_EXIT phase=$Phase exit=$($child.ExitCode)"
 # Controller disposal and desktop exit do not prove its process group has finished.
 # Observe ONLY this retained Job, before closing it. The 15 seconds is part of, not
 # added to, the original 420-second budget; KILL_ON_JOB_CLOSE and memory caps remain.
 $desktopExitObservedUtc=[DateTime]::UtcNow
 $budgetMs=[Math]::Max(0,[Math]::Min(15000,420000-$overall.ElapsedMilliseconds))
 $drain=[Diagnostics.Stopwatch]::StartNew();$samples=[Collections.Generic.List[object]]::new()
 do {
  $count=[PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Count($job)
  $ownedMembers=@([PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Members($job))
  $sample=[ordered]@{elapsedMs=$drain.ElapsedMilliseconds;activeCount=$count;members=$ownedMembers;utc=[DateTime]::UtcNow}
  $samples.Add($sample)
  $sample|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $evidence 'post-desktop-exit-samples.jsonl') -Encoding utf8
  if($count -eq 0 -and $ownedMembers.Count -eq 0){$drained=$true;break}
  $left=[Math]::Min($budgetMs-$drain.ElapsedMilliseconds,420000-$overall.ElapsedMilliseconds)
  if($left -le 0){break}
  [Threading.Thread]::Sleep([int][Math]::Min(100,$left))
 }while($drain.ElapsedMilliseconds -lt $budgetMs -and $overall.ElapsedMilliseconds -lt 420000)
 [ordered]@{desktopExitCode=$child.ExitCode;desktopExitObservedUtc=$desktopExitObservedUtc;budgetMs=$budgetMs;totalBudgetMs=420000;observedThroughMs=$drain.ElapsedMilliseconds;overallElapsedMs=$overall.ElapsedMilliseconds;zeroObserved=$drained;samples=$samples}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $evidence 'post-desktop-exit-drain.json') -Encoding utf8
 if(!$drained){throw 'Owned Job did not drain within observation and original overall deadline; preserve evidence.'}
 $guardAfter=(Get-FileHash -LiteralPath (Join-Path $proof.root 'model-state/execution-guard.json')).Hash
 if($guardAfter -cne $seed.guardSha256){throw 'Synthetic guard changed; preserve evidence.'}
 [ordered]@{passed=$true;guardSha256=$guardAfter;guardPreserved=$true;modelActionsTriggered=0;remoteExecutionEnded='NOT_PROVEN';productionGo=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence 'guard-after-exit.json') -Encoding utf8
 Write-Output "OWNED_JOB_DRAINED observedMs=$($drain.ElapsedMilliseconds)"
}catch{$failure=$_.Exception.Message;throw}
finally{
 try{
  if($job){$active=[PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Count($job);$members=@([PersonalAiWorkspace.Desktop.FullExeOwnedJob]::Members($job))}
  if($child -and !$child.HasExited){$forced=$true}
 }finally{
  if($job){$job.Dispose()}
  if($child -and !$child.HasExited){[void]$child.WaitForExit(10000)}
  if($copyOut){[void]$copyOut.Wait(5000);[void]$copyErr.Wait(5000);$stdout.Dispose();$stderr.Dispose()}
  try {
   if($proof -and $proof.synthetic -and $proof.newlyPrepared -and $proof.id -ceq $profileId){
    # Only the token in this run's newly prepared synthetic Profile; retain all guard/data evidence.
    $token=Join-Path $proof.root 'credentials/client-token'
    if(Test-Path -LiteralPath $token){Remove-Item -LiteralPath $token}
   }
  } catch { if(!$failure){$failure='Synthetic token cleanup failed.'} }
  finally {
   if($held){$mutex.ReleaseMutex()};$mutex.Dispose()
  }
  [ordered]@{phase=$Phase;guardianPid=$PID;desktopExited=if($child){$child.HasExited}else{$null};desktopExitObservedBeforeGuardianCleanup=$exited;desktopForced=$forced;guardianClosedActiveJob=($null -ne $active -and $active -gt 0);jobDrainedBeforeCleanup=$drained;activeJobProcessesBeforeCleanup=$active;jobMembersBeforeCleanup=$members;jobReleased=($null -ne $job);lockReleased=$held;temporaryTokenRemoved=if($proof){!(Test-Path -LiteralPath (Join-Path $proof.root 'credentials/client-token'))}else{$null};port18765='NOT_QUERIED';failure=$failure;utc=[DateTime]::UtcNow}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $evidence ($Phase+'-cleanup.json')) -Encoding utf8
 }
}
