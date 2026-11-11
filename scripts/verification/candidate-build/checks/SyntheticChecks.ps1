#Requires -Version 7.4
# Independent synthetic check. Does NOT invoke Guard.ps1/Build.ps1 or Read-Inputs.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Root)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../Common.ps1')
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$Root=[IO.Path]::GetFullPath($Root)
if(-not $IsWindows -or [Diagnostics.Process]::GetCurrentProcess().SessionId -ne 1){throw 'Windows Session 1 required'}
if([IO.Path]::GetDirectoryName($Root) -cne (Join-Path $repository '.verification') -or
    [IO.Path]::GetFileName($Root) -cnotmatch '^candidate-tools-synthetic-[a-f0-9]{32}$'){throw 'New dedicated synthetic root required'}
Assert-PlainPath $Root
if(Test-Path -LiteralPath $Root){throw 'Do not resume or rerun an existing check'}
$latch=Join-Path $repository '.verification/candidate-tools-synthetic-active.json'
$mutex=[Threading.Mutex]::new($false,'Local\Dot_KAG_Workspace_HeavyRun')
$acquired=$false;$owned=$false;$latchCreated=$false;$jobChecksSafe=$true;$released=$false;$failure=$null
$checks=[Collections.Generic.List[string]]::new();$jobs=[Collections.Generic.List[object]]::new()
function Expect-Rejection([string]$Name,[scriptblock]$Action){
    $rejected=$false;try{& $Action}catch{$rejected=$true}
    if(-not $rejected){throw "Negative case accepted: $Name"};$checks.Add($Name)
}
try {
    try{$acquired=$mutex.WaitOne([TimeSpan]::FromSeconds(5))}catch [Threading.AbandonedMutexException]{
        $acquired=$true
        if(-not (Test-Path -LiteralPath $latch)){Write-NewJson $latch @{state='ABANDONED_LOCK_UNRECONCILED';root=$Root}}
        throw 'Abandoned lock; no synthetic workload started'
    }
    if(-not $acquired){throw 'Heavy window busy; no check started'}
    foreach($marker in @($latch,(Join-Path $repository '.verification/candidate-build-active.json'))){
        Assert-PlainPath $marker;if(Test-Path -LiteralPath $marker){throw 'Unresolved marker; stop without replay'}
    }
    Write-NewJson $latch @{state='ACTIVE_OR_UNRECONCILED';root=$Root;guardianPid=$PID};$latchCreated=$true
    [void](New-Item -ItemType Directory -Path $Root);$owned=$true
    Write-NewJson (Resolve-Below $Root 'scope.json') @{kind='SYNTHETIC_ONLY';productExecution=$false;realBuild=$false;baselineAccess=$false;systemSandbox=$false}
    # Compile only the new support/harness; no product assembly or fixture is loaded.
    Add-Type -Path @((Join-Path $PSScriptRoot '../BuildSupport.cs'),(Join-Path $PSScriptRoot 'SyntheticFixture.cs'))
    foreach($name in @('COM¹','COM².txt','COM³','LPT¹','LPT².ext','LPT³','CONIN$','CONOUT$.txt','CLOCK$','NUL','a/../b','a\b','a.','a:stream')){
        Expect-Rejection ('path:'+ $name) {Assert-RelativePath $name}
    }
    Assert-RelativePath 'a/COM10.txt';$checks.Add('ordinary-relative-path')
    foreach($name in @('personalaIworkspace.desktop.dll','PERSONALAIWORKSPACE.CORE.DLL','DATA/value.txt','Credentials/value','mainworkspace/index.html')){
        Expect-Rejection ('publish:'+ $name) {Assert-PublishRule $name 'input-file'}
    }
    Expect-Rejection 'lowercase-generated-product' {Assert-PublishRule 'personalaIworkspace.desktop.dll' 'generated-product'}
    Assert-PublishRule 'PersonalAiWorkspace.Desktop.dll' 'generated-product';$checks.Add('canonical-generated-product')
    foreach($value in @('C:/outside/file','C:\outside\file','\\server\share\file','//server/share/file',($Root+'\ok;C:/outside/two'),($Root+'\ok;\\server\share\two'),($Root+'\..\outside'),'C:relative','/rooted')){
        Expect-Rejection 'external-path-value' {Assert-PrivatePathValues $Root $value}
    }
    Assert-PrivatePathValues $Root ($Root+'\one;'+$Root.Replace('\','/')+'/two')
    Assert-PrivatePathValues $Root 'http://schemas.microsoft.com/developer/msbuild/2003'
    $checks.Add('private-path-list-and-XML-namespace')
    Expect-Rejection 'duplicate-JSON-key' {ConvertFrom-StrictJson '{"a":1,"A":2}'}
    Expect-Rejection 'nested-duplicate-JSON-key' {ConvertFrom-StrictJson '{"a":[{"x":1,"x":2}]}'}
    $schema=Read-StrictJson (Join-Path $PSScriptRoot '../inputs.schema.json')
    # Validate only the origin sub-schema; no fake real build inputs are produced.
    $originSchema=ConvertTo-Json -Depth 30 @{ '$schema'=$schema.'$schema';'$ref'='#/$defs/origin';'$defs'=$schema.'$defs' }
    if(-not (Test-Json -Json '{"kind":"generated-product"}' -Schema $originSchema)){throw 'Origin schema positive failed'}
    Expect-Rejection 'origin-schema-extra-property' {Test-Json -Json '{"kind":"generated-product","source":"old.dll"}' -Schema $originSchema -ErrorAction Stop}
    $checks.Add('origin-schema-positive')
    $badJson=Resolve-Below $Root 'external.json';Write-NewJson $badJson @{folders=($Root+'\ok;C:/outside/later')}
    Expect-Rejection 'parsed-JSON-path-list' {Assert-StructuredPaths $Root $badJson}
    $badXml=Resolve-Below $Root 'external.targets'
    Write-NewText $badXml ('<Project xmlns="http://schemas.microsoft.com/developer/msbuild/2003"><Item Include="'+[Security.SecurityElement]::Escape($Root+'\ok;\\server\share\later')+'"/></Project>')
    Expect-Rejection 'parsed-XML-path-list' {Assert-StructuredPaths $Root $badXml}
    $goodXml=Resolve-Below $Root 'private.targets';Write-NewText $goodXml ('<Project xmlns="http://schemas.microsoft.com/developer/msbuild/2003"><Path>'+[Security.SecurityElement]::Escape($Root+'\one;'+$Root+'\two')+'</Path></Project>')
    Assert-StructuredPaths $Root $goodXml;$checks.Add('parsed-XML-private-paths')

    $synthetic=Resolve-Below $Root 'archive-source';[void][IO.Directory]::CreateDirectory($synthetic)
    Write-NewText (Resolve-Below $synthetic 'allowed/file.txt') 'SYNTHETIC DATA'
    $inventory=Get-Inventory $synthetic
    Write-NewJson (Resolve-Below $synthetic 'development-bundle.json') @{files=$inventory}
    $zipPath=Resolve-Below $Root 'synthetic.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($synthetic,$zipPath)
    $manifestHash=Get-Sha (Resolve-Below $synthetic 'development-bundle.json')
    $trusted=Read-PinnedArchiveManifest $zipPath (Get-Sha $zipPath) $manifestHash
    Assert-AllowedInventory $synthetic $trusted.inventory
    Assert-ZipMatches $zipPath $trusted.inventory;$checks.Add('archive-manifest-binding')
    Expect-Rejection 'unbound-manifest' {Read-PinnedArchiveManifest $zipPath (Get-Sha $zipPath) ('0'*64)}
    Expect-Rejection 'unbound-archive' {Read-PinnedArchiveManifest $zipPath ('0'*64) $manifestHash}
    Expect-Rejection 'case-colliding-inventory' {New-AllowedTree @(@{path='a';sha256=('0'*64);bytes=1},@{path='A';sha256=('0'*64);bytes=1})}
    $hashCalls=[Collections.Generic.List[string]]::new();$listCalls=[Collections.Generic.List[string]]::new()
    $originalSha=${function:Get-Sha}
    function Get-Sha([string]$Path){$hashCalls.Add($Path);& $originalSha $Path}
    function Get-ChildItem([string]$LiteralPath,[switch]$Force){$listCalls.Add($LiteralPath);Microsoft.PowerShell.Management\Get-ChildItem -LiteralPath $LiteralPath -Force:$Force}
    $unknownFile=Resolve-Below $synthetic 'unknown-file';Write-NewText $unknownFile 'SYNTHETIC UNKNOWN'
    Expect-Rejection 'unknown-file-no-hash' {Assert-AllowedInventory $synthetic $trusted.inventory}
    if($hashCalls.Contains($unknownFile)){throw 'Unknown file was hashed'}
    Remove-Item -LiteralPath $unknownFile
    $unknownDir=Resolve-Below $synthetic 'unknown-directory';Write-NewText (Resolve-Below $unknownDir 'nested.txt') 'SYNTHETIC NESTED'
    Expect-Rejection 'unknown-directory-no-descent' {Assert-AllowedInventory $synthetic $trusted.inventory}
    if($listCalls.Contains($unknownDir)){throw 'Unknown directory was descended'}
    Write-NewJson (Resolve-Below $Root 'inventory-read-trace.json') @{hashPaths=$hashCalls.ToArray();listedPaths=$listCalls.ToArray();unknownFile=$unknownFile;unknownDirectory=$unknownDir}
    Set-Item -LiteralPath Function:Get-Sha -Value $originalSha
    Remove-Item -LiteralPath Function:Get-ChildItem
    $duplicateZip=Resolve-Below $Root 'duplicate.zip';$zip=[IO.Compression.ZipFile]::Open($duplicateZip,[IO.Compression.ZipArchiveMode]::Create)
    try{[void]$zip.CreateEntry('same');[void]$zip.CreateEntry('SAME')}finally{$zip.Dispose()}
    Expect-Rejection 'duplicate-ZIP-entry' {Assert-ZipMatches $duplicateZip @(@{path='same';bytes=0;sha256=(Get-BytesSha ([byte[]]::new(0)))})}
    foreach($mode in @('normal','missing','external','duplicate','offset','negative','huge','truncated','utf8','directory')){
        $file=Resolve-Below $Root ($mode+'.pe-fixture');[WorkspaceCandidateChecks.SyntheticFixture]::WritePe($file,$mode)
        if($mode -ceq 'normal'){
            if([WorkspaceCandidateBuild.ArtifactReader]::EmbeddedRuntimeManifest($file) -cne '{"synthetic":true}'){throw 'Synthetic PE value differs'}
            $checks.Add('PE-normal')
        }else{Expect-Rejection ('PE-'+$mode) {[WorkspaceCandidateBuild.ArtifactReader]::EmbeddedRuntimeManifest($file)}}
    }
    foreach($mode in @('Normal','Timeout','Descendant','assignment')){
        $jobChecksSafe=$false
        $result=[WorkspaceCandidateChecks.SyntheticFixture]::CheckJob([Diagnostics.Process]::GetCurrentProcess().MainModule.FileName,(Join-Path $PSScriptRoot 'Child.ps1'),$Root,$mode)
        $jobs.Add($result);Write-NewJson (Resolve-Below $Root ($mode+'-job-result.json')) $result
        $jobChecksSafe=$result.cleanupConfirmed -and $result.jobCloseConfirmed
        if(-not $jobChecksSafe -or -not $result.passed){throw 'Independent Job case failed; no automatic retry'}
    }
}catch{$failure=$_.Exception.Message}
finally {
    try{
        if($owned){Write-NewJson (Resolve-Below $Root 'result.json') @{passed=($null -eq $failure);failure=$failure;checks=$checks.ToArray();jobs=$jobs.ToArray();jobCleanupConfirmed=$jobChecksSafe;productExecution=$false;realBuild=$false;systemSandbox=$false}}
        if($latchCreated -and $jobChecksSafe){Remove-Item -LiteralPath $latch -ErrorAction Stop}
    }finally{try{if($acquired){$mutex.ReleaseMutex();$released=$true}}finally{$mutex.Dispose()}}
    if($owned){Write-NewJson (Resolve-Below $Root 'released.json') @{lockReleased=$released;jobCleanupConfirmed=$jobChecksSafe;reentryBlocked=(-not $jobChecksSafe)}}
}
if($failure){throw $failure}
Write-Output ('SYNTHETIC PASS: '+$checks.Count+' functional checks; '+$jobs.Count+' Job scenarios. '+$Root)
