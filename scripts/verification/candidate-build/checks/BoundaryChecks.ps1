#Requires -Version 7.4
# Pure follow-up to two review changes. No child process, baseline, Git or product I/O.
param([Parameter(Mandatory)][string]$Receipt)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../Common.ps1')
$checks=[Collections.Generic.List[string]]::new()
foreach($path in @('sub/PersonalAiWorkspace.Desktop.dll','sub/PERSONALAIWORKSPACE.CORE.DLL','personalaIworkspace.desktop.dll')){
    $failed=$false;try{Assert-PublishRule $path 'input-file'}catch{$failed=$true}
    if(-not $failed){throw 'Product alias accepted'};$checks.Add($path)
}
Assert-PublishRule 'PersonalAiWorkspace.Desktop.dll' 'generated-product';$checks.Add('canonical-generated')
# Fragments exercise the pure comparator only, never Read-Inputs or a build entry.
$file=@{path='synthetic.bin';sha256=('0'*64);bytes=5}
$fragment=@{trees=@(@{id='java';files=@($file)})}
$manifestFragment=@{files=@(@{path='runtime/java/synthetic.bin';sha256=('0'*64);bytes=5})}
Assert-BaselineJavaTree $fragment $manifestFragment;$checks.Add('java-ZIP-relation')
$file.sha256=('1'*64)
$failed=$false;try{Assert-BaselineJavaTree $fragment $manifestFragment}catch{$failed=$true}
if(-not $failed){throw 'Unbound Java fragment accepted'};$checks.Add('java-mismatch-rejected-before-copy')
Write-NewJson $Receipt @{passed=$true;checks=$checks.ToArray();childProcesses=$false;realBuild=$false;productExecution=$false}
Write-Output ('BOUNDARY PASS: '+$checks.Count+' pure checks')
