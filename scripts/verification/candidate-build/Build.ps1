#Requires -Version 7.4
# Worker only. Review this revision and a complete real input manifest before using Guard.ps1.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Root)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Common.ps1')
$script:Root=[IO.Path]::GetFullPath($Root)
if ($PSScriptRoot -cne (Resolve-Below $Root 'control')) { throw 'Worker must run from the fresh private control directory' }
$ownership=Read-StrictJson (Resolve-Below $Root 'ownership.json')
if ([Environment]::GetEnvironmentVariable('WORKSPACE_CANDIDATE_JOB_TOKEN') -cne $ownership.token) { throw 'Use the independent guardian, never invoke worker directly' }
Write-NewText (Resolve-Below $Root 'evidence/worker-claim') $ownership.token
$script:Spec=Read-Inputs (Resolve-Below $Root 'control/inputs.json') (Resolve-Below $Root 'control/inputs.schema.json')
if ((Get-Sha (Resolve-Below $Root 'control/inputs.json')) -cne $ownership.inputSha256) { throw 'Input manifest changed after guardian admission' }
$script:InputHashes=@{}; $script:Step=0
foreach ($tree in $script:Spec.trees) {
    foreach ($file in $tree.files) {
        $relative=$script:TreeDestinations[$tree.id]+'/'+$file.path
        Assert-RelativePath $relative
        if ($script:InputHashes.ContainsKey($relative)) { throw 'Input collision' }
        $script:InputHashes[$relative]=$file.sha256
    }
    if ($tree.id -cnotin @('pwsh','frontend')) { Copy-InputTree $tree $Root }
}
Add-Type -Path (Resolve-Below $Root 'control/BuildSupport.cs')
$source=Resolve-Below $Root 'work/source'
$repository=[IO.Path]::GetFullPath($script:Spec.repository)
$baseline=[IO.Path]::GetFullPath($script:Spec.baseline.directory)
$generated=[Collections.Generic.List[object]]::new()
function Record-Generated([string]$Path,[string]$Role,$Origin) {
    $relative=[IO.Path]::GetRelativePath($Root,$Path).Replace('\','/')
    $generated.Add([ordered]@{path=$relative;role=$Role;sha256=(Get-Sha $Path);bytes=(Get-Item -LiteralPath $Path).Length;origin=$Origin})
}
function Assert-SourceStable {
    foreach ($file in $script:SourceFiles) {
        if ((Get-Sha (Resolve-Below $source $file.path)) -cne $file.sha256) { throw "Snapshot source changed: $($file.path)" }
    }
    foreach ($relative in $script:InputHashes.Keys) {
        if ((Get-Sha (Resolve-Below $Root $relative)) -cne $script:InputHashes[$relative]) { throw "Frozen input changed: $relative" }
    }
}
function Export-SourceSnapshot {
    $head=Invoke-BuildTool git @('-C',$repository,'rev-parse','HEAD') $Root
    if ([IO.File]::ReadAllText($head.Stdout).Trim() -cne $script:Spec.buildToolsRevision) { throw 'Build tooling revision must be current reviewed HEAD' }
    [void](Invoke-BuildTool git @('-C',$repository,'diff','--quiet','HEAD','--','scripts/verification/candidate-build','scripts/distribution/README-candidate.txt') $Root)
    foreach ($file in $script:Spec.controlFiles) {
        $relative=if($file.name -ceq 'README-candidate.txt'){'scripts/distribution/README-candidate.txt'}else{'scripts/verification/candidate-build/'+$file.name}
        $blob=Invoke-BuildTool git @('-C',$repository,'show',($script:Spec.buildToolsRevision+':'+$relative)) $Root
        if ((Get-Sha $blob.Stdout) -cne (Get-Sha (Resolve-Below $Root ('control/'+$file.name)))) { throw 'Control bytes differ from build-tools commit' }
    }
    # The reused internal module/dependency graph must be unchanged, including its resources.
    [void](Invoke-BuildTool git @('-C',$repository,'diff','--quiet',$script:BaselineRevision,$script:ProductRevision,'--',
        'finance-domain','pom.xml','workspace-application/pom.xml','desktop/frontend/package.json','desktop/frontend/package-lock.json',
        'bootstrap/Shared','bootstrap/PersonalAiWorkspace.NativeBootstrap','desktop/Directory.Build.props',
        'desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj','desktop/src/PersonalAiWorkspace.Core/PersonalAiWorkspace.Core.csproj') $Root)
    $treeResult=Invoke-BuildTool git @('-C',$repository,'ls-tree','-r','-z','--full-tree',$script:ProductRevision) $Root
    $entries=@{}
    foreach ($record in ([IO.File]::ReadAllText($treeResult.Stdout,[Text.UTF8Encoding]::new($false,$true))).Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)) {
        if ($record -cnotmatch '^(100644|100755) blob ([a-f0-9]{40})\t(.+)$') { throw 'Unsupported Git tree mode/path' }
        $path=$Matches[3]; Assert-RelativePath $path
        if ($entries.ContainsKey($path)) { throw 'Case-colliding snapshot paths' }; $entries[$path]=$Matches[2]
    }
    $archivePath=Resolve-Below $Root 'tmp/source.zip'
    [void](Invoke-BuildTool git @('-C',$repository,'archive','--format=zip',('--output='+$archivePath),$script:ProductRevision) $Root)
    if(Test-Path -LiteralPath $source){throw 'Snapshot must be fresh'}
    [void][IO.Directory]::CreateDirectory($source)
    $script:SourceFiles=[Collections.Generic.List[object]]::new()
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $zip=[IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $path=$entry.FullName; Assert-RelativePath $path
            if (-not $seen.Add($path) -or -not $entries.ContainsKey($path)) { throw 'Unexpected/duplicate exported source' }
            $bytes=Read-ZipEntryBytes $entry
            if ([WorkspaceCandidateBuild.ArtifactReader]::GitBlobId($bytes) -cne $entries[$path]) { throw 'Git export attribute/filter changed source bytes' }
            Write-NewBytes (Resolve-Below $source $path) $bytes
            $script:SourceFiles.Add([ordered]@{path=$path;sha256=(Get-BytesSha $bytes);bytes=$bytes.Length;gitBlob=$entries[$path]})
        }
    } finally { $zip.Dispose() }
    if ($seen.Count -ne $entries.Count) { throw 'Incomplete source snapshot' }
    Write-NewJson (Resolve-Below $Root 'evidence/source-inputs.json') @($script:SourceFiles | Sort-Object path -CaseSensitive)
    if ((Get-Sha (Resolve-Below $source 'src/main/java/io/github/qianlixunbai/workspace/model/ModelStateStore.java')) -cne
        '2e2a097f34a5aae5381dbA2d125b6f64cda48bc9747bd2a3c5106453ae6b28ae'.ToLowerInvariant()) { throw 'Frozen safety candidate differs' }
}
function Prepare-BaselineInputs {
    $manifestPath=Join-Path $baseline 'development-bundle.json'
    if ((Get-Sha $manifestPath) -cne $script:Spec.baseline.manifestSha256) { throw 'Baseline manifest changed' }
    $manifest=Read-StrictJson $manifestPath
    if ($manifest.commit -cne $script:BaselineRevision -or $manifest.productionGo -ne $false -or $manifest.trust -cne 'DEVELOPMENT_ONLY') { throw 'Wrong baseline identity' }
    if(-not $manifest.ContainsKey('sourceDirty') -or $manifest.sourceDirty -isnot [bool]){throw 'Baseline source status must be stated, never inferred clean'}
    $script:BaselineProvenance=[ordered]@{manifestCommit=$manifest.commit;manifestSourceDirty=$manifest.sourceDirty;
        zipSha256=$script:BaselineZipHash;manifestSha256=$script:Spec.baseline.manifestSha256;
        reusedClassSourceAttestation='NOT_ESTABLISHED';financeResources='checked against fixed snapshot';
        meaning='Pinned historical bytes; unchanged Git module does not prove the historical compiled class source'}
    Write-NewJson (Resolve-Below $Root 'evidence/baseline-provenance.json') $script:BaselineProvenance
    $javaTree=@($script:Spec.trees | Where-Object id -CEQ 'java')[0]
    $baselineJava=@($manifest.files | Where-Object {$_.path.StartsWith('runtime/java/')})
    if($javaTree.files.Count -ne $baselineJava.Count){throw 'Java toolchain must be the complete pinned baseline tree'}
    foreach($file in $javaTree.files){
        $matches=@($baselineJava | Where-Object path -CEQ ('runtime/java/'+$file.path))
        if($matches.Count -ne 1 -or $matches[0].sha256 -cne $file.sha256 -or $matches[0].bytes -ne $file.bytes){throw 'Java toolchain differs from reviewed baseline'}
    }
    $paths=@('runtime/personal-ai-workspace.jar','runtime/jna-5.17.0.jar')+
        @($script:BootstrapPins.Keys | ForEach-Object {'NativeBootstrap/PersonalAiWorkspace.NativeBootstrap'+$_})
    foreach ($path in $paths) {
        $files=@($manifest.files | Where-Object path -CEQ $path)
        if ($files.Count -ne 1) { throw 'Baseline input missing or duplicated' }; $file=$files[0]
        $relative='inputs/baseline/'+$path
        Copy-PinnedFile (Resolve-Below $baseline $path) (Resolve-Below $Root $relative) $file.sha256 $file.bytes
        $script:InputHashes[$relative]=$file.sha256
    }
    foreach ($suffix in $script:BootstrapPins.Keys) {
        $relative='inputs/baseline/NativeBootstrap/PersonalAiWorkspace.NativeBootstrap'+$suffix
        if ((Get-Sha (Resolve-Below $Root $relative)) -cne $script:BootstrapPins[$suffix]) { throw 'Fixed NativeBootstrap pin differs' }
        # Existing project Content paths are retained, but point inside the snapshot.
        $target=Resolve-Below $source ('bootstrap/PersonalAiWorkspace.NativeBootstrap/bin/Release/net10.0-windows/PersonalAiWorkspace.NativeBootstrap'+$suffix)
        Copy-PinnedFile (Resolve-Below $Root $relative) $target $script:BootstrapPins[$suffix] (Get-Item -LiteralPath (Resolve-Below $Root $relative)).Length
    }
}
function Assert-FinanceResources([byte[]]$Bytes) {
    $memory=[IO.MemoryStream]::new($Bytes,$false)
    $archive=[IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read)
    try {
        $resources=@($script:SourceFiles | Where-Object path -CLike 'finance-domain/src/main/resources/*')
        $expected=@{}; foreach($file in $resources){$expected[$file.path.Substring('finance-domain/src/main/resources/'.Length)]=$file}
        $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach($entry in $archive.Entries){
            if($entry.FullName.EndsWith('/')){continue}; Assert-RelativePath $entry.FullName
            if(-not $seen.Add($entry.FullName)){throw 'Duplicate finance module entry'}
            if($entry.FullName.EndsWith('.class') -or $entry.FullName -cmatch '^META-INF/(MANIFEST\.MF|maven/io\.github\.qianlixunbai/finance-domain/pom\.(xml|properties))$'){continue}
            if(-not $expected.ContainsKey($entry.FullName) -or (Get-BytesSha (Read-ZipEntryBytes $entry)) -cne $expected[$entry.FullName].sha256){throw 'Reused finance resource differs from fixed source'}
        }
        foreach($name in $expected.Keys){if(-not $seen.Contains($name)){throw 'Finance resource missing'}}
    } finally {$archive.Dispose();$memory.Dispose()}
}
function Prepare-JarReuse {
    $wanted=@{}; foreach($entry in $script:Spec.jarEntries){Assert-RelativePath $entry.path;if($wanted.ContainsKey($entry.path)){throw 'Duplicate JAR reuse path'};$wanted[$entry.path]=$entry}
    $script:JarReuse=[Collections.Generic.List[object]]::new()
    $zip=[IO.Compression.ZipFile]::OpenRead((Resolve-Below $Root 'inputs/baseline/runtime/personal-ai-workspace.jar'))
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $reuseSeen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    try {
        foreach($entry in $zip.Entries){
            if($entry.FullName.EndsWith('/')){continue};$name=$entry.FullName;Assert-RelativePath $name
            if(-not $seen.Add($name)){throw 'Duplicate baseline JAR entry'}
            if($name.StartsWith('BOOT-INF/classes/') -or $name -cin @('META-INF/MANIFEST.MF','META-INF/workspace-runtime.properties','BOOT-INF/classpath.idx','BOOT-INF/layers.idx',
                'META-INF/maven/io.github.qianlixunbai/workspace-application/pom.xml','META-INF/maven/io.github.qianlixunbai/workspace-application/pom.properties')){continue}
            if(-not $wanted.ContainsKey($name)){throw "Unclassified old JAR entry: $name"}
            $rule=$wanted[$name];$bytes=Read-ZipEntryBytes $entry
            if($entry.Length -ne $rule.bytes -or (Get-BytesSha $bytes) -cne $rule.sha256){throw 'JAR reuse input hash mismatch'}
            switch -CaseSensitive ($rule.role) {
                'dependency' { if($name -cnotmatch '^BOOT-INF/lib/[A-Za-z0-9_.+-]+\.jar$' -or $name -clike '*finance-domain*'){throw 'Invalid third-party dependency role'} }
                'finance-module' {if($name -cne 'BOOT-INF/lib/finance-domain-0.1.0.jar'){throw 'Unexpected internal module'};Assert-FinanceResources $bytes}
                'boot-loader' {if($name -cnotmatch '^org/springframework/boot/loader/.+\.class$'){throw 'Unexpected framework class'}}
                'boot-service' {if($name -cne 'META-INF/services/java.nio.file.spi.FileSystemProvider' -or [Text.Encoding]::UTF8.GetString($bytes) -cne "org.springframework.boot.loader.nio.file.NestedFileSystemProvider`n"){throw 'Unknown Boot service'}}
                'empty-legacy-marker' {
                    # Explicit byte-preserving compatibility decision, NOT signature evidence.
                    # No implicit allowance: the reviewed input must name the path, hash and reason.
                    if($name -cne 'META-INF/BOOT.SF' -or $bytes.Length -ne 0){throw 'Unknown signature/legacy metadata'}
                }
                default {throw 'Unknown reuse role'}
            }
            $file=Resolve-Below $Root ('inputs/jar-entries/'+$name)
            Write-NewBytes $file $bytes;$script:InputHashes['inputs/jar-entries/'+$name]=$rule.sha256
            [void]$reuseSeen.Add($name);$script:JarReuse.Add($rule)
            if($name.StartsWith('BOOT-INF/lib/')) {
                $libraryRelative='work/java-libraries/'+$name.Substring('BOOT-INF/lib/'.Length)
                Write-NewBytes (Resolve-Below $Root $libraryRelative) $bytes;$script:InputHashes[$libraryRelative]=$rule.sha256
            }
        }
        $index=$zip.GetEntry('BOOT-INF/classpath.idx')
        if(-not $index){throw 'Reviewed classpath order unavailable'}
        $oldOrder=@(([Text.Encoding]::UTF8.GetString((Read-ZipEntryBytes $index))).Split("`n",[StringSplitOptions]::RemoveEmptyEntries) | ForEach-Object {
            if($_ -cnotmatch '^- "(BOOT-INF/lib/[A-Za-z0-9_.+-]+\.jar)"\r?$'){throw 'Unknown classpath index syntax'};$Matches[1]
        })
        $newOrder=@($script:JarReuse | Where-Object { $_.role -cin @('dependency','finance-module') } | ForEach-Object path)
        if(($oldOrder -join "`n") -cne ($newOrder -join "`n")){throw 'Reviewed dependency order differs from baseline'}
    } finally {$zip.Dispose()}
    if($reuseSeen.Count -ne $wanted.Count -or @($script:JarReuse | Where-Object role -CEQ 'finance-module').Count -ne 1){throw 'Incomplete/mismatched explicit JAR reuse inventory'}
    Write-NewJson (Resolve-Below $Root 'evidence/jar-reuse.json') $script:JarReuse.ToArray()
}
function Build-RuntimeJar {
    $parent=Resolve-Below $Root 'inputs/contracts/spring-boot-starter-parent-4.1.1.pom'
    if((Get-Sha $parent) -cne '04cc8a51d17b04d518c232ab5423fb3b9ca6c9eb95d2562800267a6bf991f953'){throw 'Resource filtering parent contract changed'}
    [xml]$parentXml=[IO.File]::ReadAllText($parent)
    $ns=[Xml.XmlNamespaceManager]::new($parentXml.NameTable);$ns.AddNamespace('m','http://maven.apache.org/POM/4.0.0')
    if($parentXml.SelectSingleNode('/m:project/m:properties/m:resource.delimiter',$ns).InnerText -cne '@' -or
        $parentXml.SelectSingleNode('//m:plugin[m:artifactId="maven-resources-plugin"]/m:configuration/m:useDefaultDelimiters',$ns).InnerText -cne 'false'){throw 'Unsupported resource filtering rules'}
    $assembly=Resolve-Below $Root 'work/runtime-assembly';[void][IO.Directory]::CreateDirectory($assembly)
    $classes=Resolve-Below $Root 'work/java-classes';[void][IO.Directory]::CreateDirectory($classes)
    $javaSources=@($script:SourceFiles | Where-Object { $_.path.StartsWith('src/main/java/') -and $_.path.EndsWith('.java') } | Sort-Object path -CaseSensitive)
    $argsFile=Resolve-Below $Root 'work/java-sources.txt'
    Write-NewText $argsFile (($javaSources | ForEach-Object {'"'+$_.path+'"'}) -join "`n")
    [void](Invoke-BuildTool javac @('-J-Xmx384m','-J-XX:ActiveProcessorCount=2',('-J-Djava.io.tmpdir='+(Resolve-Below $Root 'tmp/general')),
        '--release','21','-parameters','-encoding','UTF-8','-proc:none','-implicit:none','-sourcepath','',
        '-cp',((Resolve-Below $Root 'work/java-libraries')+'\*'),'-d',$classes,('@'+$argsFile)) $source)
    $classFiles=@(Get-Inventory $classes)
    if(-not $classFiles.Count -or @($classFiles | Where-Object { -not $_.path.EndsWith('.class') }).Count){throw 'Unexpected compiler output'}
    foreach($file in $classFiles){
        $path=Resolve-Below $assembly ('BOOT-INF/classes/'+$file.path)
        Copy-PinnedFile (Resolve-Below $classes $file.path) $path $file.sha256 $file.bytes
        Record-Generated $path 'compiled-workspace-class' @{productRevision=$script:ProductRevision;compiler=$script:Spec.executables.javac;annotationProcessing='disabled'}
    }
    foreach($file in @($script:SourceFiles | Where-Object { $_.path.StartsWith('src/main/resources/') })){
        $name=$file.path.Substring('src/main/resources/'.Length)
        if($name.EndsWith('.class') -or $name.EndsWith('.jar')){throw 'Unexpected binary product resource'}
        $bytes=[IO.File]::ReadAllBytes((Resolve-Below $source $file.path))
        if($name -cmatch '(^|/)application[^/]*\.(yml|yaml|properties)$' -and [Text.UTF8Encoding]::new($false,$true).GetString($bytes) -match '@[^@]+@'){
            throw 'Build-time @...@ resource substitution is not implemented; review new placeholders first'
        }
        # With no @ delimiters, the reviewed parent filter is an identity transform.
        # In particular ${user.home} stays a runtime expression, never a build-user path.
        $path=Resolve-Below $assembly ('BOOT-INF/classes/'+$name);Write-NewBytes $path $bytes
        Record-Generated $path 'current-product-resource' @{productRevision=$script:ProductRevision;source=$file.path;sha256=$file.sha256;filter='@ only; no substitutions in fixed snapshot'}
    }
    foreach($entry in $script:JarReuse){
        $path=Resolve-Below $assembly $entry.path
        Copy-PinnedFile (Resolve-Below $Root ('inputs/jar-entries/'+$entry.path)) $path $entry.sha256 $entry.bytes
        Record-Generated $path ('reused-'+$entry.role) @{baselineCommit=$script:BaselineRevision;entry=$entry.path;sha256=$entry.sha256;reason=$entry.reason}
    }
    $sourceList=@($script:SourceFiles | Sort-Object path -CaseSensitive | ForEach-Object {[ordered]@{Path=$_.path;Sha256=$_.sha256}})
    $sourceBytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $sourceList -Depth 4 -Compress)+"`n")
    Write-NewBytes (Resolve-Below $Root 'evidence/runtime-source-manifest.json') $sourceBytes
    $identity=@('format=WORKSPACE_RUNTIME_V1','kind=default',('sourceSha='+(Get-BytesSha $sourceBytes)))
    $owners=@('provider/ollama/OllamaProvider','provider/ollama/OllamaVisionEnvironment','security/WindowsNativeGate')
    $keys=@('providerSha','environmentSha','gateSha')
    for($i=0;$i -lt 3;$i++){$identity+=$keys[$i]+'='+(Get-Sha (Resolve-Below $classes ('io/github/qianlixunbai/workspace/'+$owners[$i]+'.class')))}
    # Default identity has exactly six keys. The isolated-development ten-key contract
    # does not apply: only fixed src/main/java is compiled, with no development classes.
    if($identity.Count -ne 6 -or @($classFiles | Where-Object {$_.path -match 'V1Isolated|v1.?isolated'}).Count){throw 'Default Runtime identity/class contract differs'}
    Write-NewText (Resolve-Below $assembly 'META-INF/workspace-runtime.properties') (($identity -join "`n")+"`n")
    $attributes=@('Manifest-Version: 1.0','Created-By: Workspace Candidate Builder','Java-Version: 21','Build-Jdk-Spec: 21',
        'Implementation-Title: PersonalAiWorkspace','Implementation-Version: 0.1.0','Main-Class: org.springframework.boot.loader.launch.JarLauncher',
        'Start-Class: io.github.qianlixunbai.workspace.PersonalAiWorkspaceApplication','Spring-Boot-Version: 4.1.1',
        'Spring-Boot-Classes: BOOT-INF/classes/','Spring-Boot-Lib: BOOT-INF/lib/','Spring-Boot-Classpath-Index: BOOT-INF/classpath.idx','Spring-Boot-Layers-Index: BOOT-INF/layers.idx')
    $manifestLines=foreach($line in $attributes){while($line.Length -gt 70){$line.Substring(0,70);$line=' '+$line.Substring(70)};$line}
    Write-NewText (Resolve-Below $assembly 'META-INF/MANIFEST.MF') (($manifestLines -join "`r`n")+"`r`n`r`n")
    $libraries=@($script:JarReuse | Where-Object {$_.role -cin @('dependency','finance-module')} | ForEach-Object path)
    Write-NewText (Resolve-Below $assembly 'BOOT-INF/classpath.idx') (($libraries | ForEach-Object {'- "'+$_+'"'}) -join "`n")
    $layers=@('- "dependencies":')+@($libraries | Where-Object {$_ -cne 'BOOT-INF/lib/finance-domain-0.1.0.jar'} | ForEach-Object {'  - "'+$_+'"'})+
        @('- "spring-boot-loader":','  - "org/"','- "snapshot-dependencies":','- "application":','  - "BOOT-INF/classes/"','  - "BOOT-INF/classpath.idx"','  - "BOOT-INF/layers.idx"','  - "BOOT-INF/lib/finance-domain-0.1.0.jar"','  - "META-INF/"')
    Write-NewText (Resolve-Below $assembly 'BOOT-INF/layers.idx') (($layers -join "`n")+"`n")
    $pom=Resolve-Below $source 'workspace-application/pom.xml'
    Write-NewBytes (Resolve-Below $assembly 'META-INF/maven/io.github.qianlixunbai/workspace-application/pom.xml') ([IO.File]::ReadAllBytes($pom))
    Write-NewText (Resolve-Below $assembly 'META-INF/maven/io.github.qianlixunbai/workspace-application/pom.properties') "artifactId=workspace-application`ngroupId=io.github.qianlixunbai`nversion=0.1.0`n"
    $script:RuntimeJar=Resolve-Below $Root 'work/personal-ai-workspace.jar'
    $zip=[IO.Compression.ZipFile]::Open($script:RuntimeJar,[IO.Compression.ZipArchiveMode]::Create)
    try {foreach($file in Get-Inventory $assembly){Add-ZipFile $zip $file.path (Resolve-Below $assembly $file.path)}}finally{$zip.Dispose()}
    # Reopen every entry; compare exactly with the empty assembly tree, including resources.
    Assert-ZipMatches $script:RuntimeJar (Get-Inventory $assembly)
    Record-Generated $script:RuntimeJar 'assembled-runtime' @{productRevision=$script:ProductRevision;internalFinance='explicit unchanged module reuse';nativeValidation='NOT_RUN'}
}
function Prepare-RuntimePayload {
    $script:RuntimeInputs=@{};$pins=[Collections.Generic.List[object]]::new()
    foreach($file in $script:Spec.runtimeFiles){
        Assert-RelativePath $file.path;Assert-RelativePath $file.source
        $expectedSource=if($file.path.StartsWith('runtime/java/')){'tools/java/'+$file.path.Substring('runtime/java/'.Length)}elseif($file.path -ceq 'runtime/jna-5.17.0.jar'){'inputs/baseline/runtime/jna-5.17.0.jar'}else{throw 'Unexpected runtime payload role'}
        if($file.source -cne $expectedSource -or $script:RuntimeInputs.ContainsKey($file.path) -or
            -not $script:InputHashes.ContainsKey($file.source) -or $script:InputHashes[$file.source] -cne $file.sha256){throw 'Runtime input is not pinned'}
        $script:RuntimeInputs[$file.path]=Resolve-Below $Root $file.source
        $pins.Add([ordered]@{Path=$file.path;Sha256=$file.sha256})
    }
    foreach($required in @('runtime/java/bin/java.exe','runtime/java/bin/server/jvm.dll','runtime/java/lib/modules','runtime/java/release','runtime/java/LICENSE','runtime/jna-5.17.0.jar')){
        if(-not $script:RuntimeInputs.ContainsKey($required)){throw 'Required runtime payload missing'}
    }
    if(@($script:RuntimeInputs.Keys | Where-Object {$_.StartsWith('runtime/java/')}).Count -ne @($script:InputHashes.Keys | Where-Object {$_.StartsWith('tools/java/')}).Count){throw 'Runtime Java payload must include its complete reviewed tree'}
    $script:RuntimeInputs['runtime/personal-ai-workspace.jar']=$script:RuntimeJar
    $pins.Add([ordered]@{Path='runtime/personal-ai-workspace.jar';Sha256=(Get-Sha $script:RuntimeJar)})
    $script:RuntimePins=@($pins | Sort-Object Path -CaseSensitive)
    $script:EmbeddedManifest=Resolve-Below $Root 'work/trusted-runtime-sources.json'
    Write-NewJson $script:EmbeddedManifest ([ordered]@{Format=3;JavaHome='runtime/java';RuntimeJar='runtime/personal-ai-workspace.jar';JnaJar='runtime/jna-5.17.0.jar';Files=$script:RuntimePins})
}
function Build-Frontend {
    Copy-InputTree (@($script:Spec.trees | Where-Object id -CEQ 'frontend')[0]) $Root
    $frontend=Resolve-Below $source 'desktop/frontend'
    if(Test-Path -LiteralPath (Join-Path $frontend 'dist')){throw 'Frontend output must be absent'}
    [void](Invoke-BuildTool node @((Join-Path $frontend 'node_modules/typescript/bin/tsc'),'--noEmit') $frontend)
    [void](Invoke-BuildTool node @((Join-Path $frontend 'node_modules/vite/bin/vite.js'),'build') $frontend)
    [void](Invoke-BuildTool node @((Join-Path $frontend 'scripts/assets.mjs')) $frontend)
    $script:FrontendFiles=Get-Inventory (Join-Path $frontend 'dist')
    $assets=Read-StrictJson (Join-Path $frontend 'dist/workspace-assets.json')
    if($assets.formatVersion -ne 1 -or $assets.bridgeVersion -ne 1 -or $assets.files.Count -ne ($script:FrontendFiles.Count-1)){throw 'Frontend resource manifest differs'}
    foreach($file in $script:FrontendFiles){
        if($file.path -ceq 'workspace-assets.json'){continue}
        if($file.path -cne 'index.html' -and $file.path -cnotmatch '^assets/[A-Za-z0-9_.-]+\.(js|css|svg|png|woff2)$'){throw 'Unexpected frontend asset role'}
        if(-not $assets.files.ContainsKey($file.path) -or $assets.files[$file.path] -cne $file.sha256){throw 'Frontend asset hash mismatch'}
    }
}
function Assert-NuGetInputs {
    $wanted=@{};$archives=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($package in $script:Spec.nugetPackages){
        Assert-RelativePath $package.archive
        if(-not $package.archive.EndsWith('.nupkg') -or -not $archives.Add($package.archive)){throw 'NuGet input must be a unique package archive'}
        $key=$package.id+'/'+$package.version
        if($wanted.ContainsKey($key)){throw 'Duplicate NuGet dependency'};$wanted[$key]=$package
        $relative='inputs/nuget-feed/'+$package.archive
        if(-not $script:InputHashes.ContainsKey($relative)){throw 'NuGet archive is not a frozen input'}
        $stream=[IO.File]::OpenRead((Resolve-Below $Root $relative))
        try{$hash=[Convert]::ToBase64String([Security.Cryptography.SHA512]::HashData($stream))}finally{$stream.Dispose()}
        if($hash -cne $package.sha512){throw 'NuGet archive content hash differs'}
        $archive=[IO.Compression.ZipFile]::OpenRead((Resolve-Below $Root $relative))
        try{
            $nuspec=@($archive.Entries | Where-Object {$_.FullName -cmatch '^[^/]+\.nuspec$'})
            if($nuspec.Count -ne 1 -or $nuspec[0].Length -gt 1MB){throw 'NuGet identity metadata unavailable'}
            $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
            $stream=$nuspec[0].Open();$reader=[Xml.XmlReader]::Create($stream,$settings);$document=[Xml.XmlDocument]::new();$document.XmlResolver=$null
            try{$document.Load($reader)}finally{$reader.Dispose();$stream.Dispose()}
            $id=$document.SelectSingleNode('/*[local-name()="package"]/*[local-name()="metadata"]/*[local-name()="id"]')
            $version=$document.SelectSingleNode('/*[local-name()="package"]/*[local-name()="metadata"]/*[local-name()="version"]')
            if(-not $id -or -not $version -or $id.InnerText -ine $package.id -or $version.InnerText -cne $package.version){throw 'NuGet filename/declared version is not its actual identity'}
        }finally{$archive.Dispose()}
    }
    $feedFiles=@($script:Spec.trees | Where-Object id -CEQ 'nuget')[0].files
    if($feedFiles.Count -ne $archives.Count){throw 'Private feed must contain exactly the reviewed packages'}
    foreach($file in $feedFiles){if(-not $archives.Contains($file.path)){throw 'Unclassified private feed input'}}
    $script:NuGetExpected=$wanted
}
function Assert-RestoredDependencies {
    $wanted=$script:NuGetExpected
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($project in @('PersonalAiWorkspace.Desktop','PersonalAiWorkspace.Core')){
        $assetPath=Resolve-Below $source ('desktop/src/'+$project+'/obj/project.assets.json')
        $assets=Read-StrictJson $assetPath
        if($assets.packageFolders.Count -ne 1 -or [IO.Path]::GetFullPath(@($assets.packageFolders.Keys)[0]).TrimEnd('\') -cne (Resolve-Below $Root 'cache/nuget/packages')){throw 'Restore escaped private NuGet cache'}
        foreach($item in $assets.libraries.GetEnumerator()){
            if($item.Value.type -ceq 'project'){continue}
            if($item.Value.type -cne 'package' -or -not $wanted.ContainsKey($item.Key) -or $item.Value.sha512 -cne $wanted[$item.Key].sha512){throw 'Resolved dependency graph differs from reviewed graph'}
            [void]$seen.Add($item.Key)
        }
        # Generated assets/props/targets may only use private absolute paths, not the old cache.
        foreach($file in Get-ChildItem -LiteralPath ([IO.Path]::GetDirectoryName($assetPath)) -File){
            if($file.Extension -notin @('.json','.props','.targets')){continue}
            $text=[IO.File]::ReadAllText($file.FullName).Replace('\\','\')
            foreach($match in [regex]::Matches($text,'[A-Za-z]:\\[^"<>\r\n]+')){
                $absolute=$match.Value.Replace('/','\').TrimEnd('\')
                if(-not $absolute.StartsWith($Root+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Generated restore file contains an external absolute path'}
            }
        }
    }
    # Runtime/framework download dependencies can also be present in the lock/feed; extras are
    # retained in the reviewed input inventory, not silently inferred from a machine cache.
    Write-NewJson (Resolve-Below $Root 'evidence/restored-packages.json') @($seen | Sort-Object)
}
function Build-Desktop {
    $sdkTree=@($script:Spec.trees | Where-Object id -CEQ 'dotnet')[0]
    $versions=@($sdkTree.files.path | Where-Object {$_ -cmatch '^sdk/[^/]+/'} | ForEach-Object {$_.Split('/')[1]} | Sort-Object -Unique)
    if($versions.Count -ne 1 -or $versions[0] -cne $script:Spec.dotnetSdkVersion){throw 'Exactly one reviewed .NET SDK required'}
    [Environment]::SetEnvironmentVariable('MSBuildSDKsPath',(Resolve-Below $Root ('tools/dotnet/sdk/'+$versions[0]+'/Sdks')),'Process')
    foreach($name in @('Desktop','Core')){
        $lockPath=Resolve-Below $Root ('inputs/locks/'+$name+'.packages.lock.json')
        if(-not $script:InputHashes.ContainsKey('inputs/locks/'+$name+'.packages.lock.json')){throw 'Real reviewed NuGet lock file required'}
        Write-NewBytes (Resolve-Below $source ('desktop/src/PersonalAiWorkspace.'+$name+'/packages.lock.json')) ([IO.File]::ReadAllBytes($lockPath))
    }
    $nugetConfig=Resolve-Below $Root 'control/NuGet.Config'
    $feed=[Security.SecurityElement]::Escape((Resolve-Below $Root 'inputs/nuget-feed'))
    Write-NewText $nugetConfig ('<configuration><packageSources><clear/><add key="private" value="'+$feed+'"/></packageSources><auditSources><clear/></auditSources><packageSourceMapping><clear/></packageSourceMapping></configuration>')
    $emptyTargets=Resolve-Below $Root 'control/empty.targets';Write-NewText $emptyTargets '<Project />'
    $props=@('-p:Mmf3ProfileAcceptanceBuild=true','-p:Mmf3AcceptanceBuild=false','-p:MainWorkspaceDev=false','-p:FrontendSkipBuild=true',
        '-p:RuntimeFrameworkVersion=10.0.14','-p:SelfContained=true','-p:UseSharedCompilation=false','-p:MSBuildEnableWorkloadResolver=false',
        ('-p:DirectoryBuildPropsPath='+(Resolve-Below $source 'desktop/Directory.Build.props')),('-p:DirectoryBuildTargetsPath='+$emptyTargets),
        ('-p:CustomBeforeMicrosoftCommonTargets='+$emptyTargets),('-p:CustomAfterMicrosoftCommonTargets='+$emptyTargets),'-m:1','-nodeReuse:false')
    $project=Resolve-Below $source 'desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj'
    # Local archive extraction into R only. No remote audit/download/installation fallback.
    Assert-NuGetInputs
    [void](Invoke-BuildTool dotnet (@('restore',$project,'-r','win-x64','--locked-mode','--configfile',$nugetConfig,'-p:NuGetAudit=false','-p:Configuration=Debug')+$props) $source)
    Assert-RestoredDependencies
    $script:Publish=Resolve-Below $Root 'work/desktop-publish'
    if(Test-Path -LiteralPath $script:Publish){throw 'Publish must start empty'}
    [void](Invoke-BuildTool dotnet (@('publish',$project,'--no-restore','-c','Debug','-r','win-x64','--self-contained','true',
        ('-p:TrustedDevelopmentManifest='+$script:EmbeddedManifest),'-p:PublishSingleFile=false','-p:PublishTrimmed=false','-p:DebugType=None','-p:DebugSymbols=false',
        '-o',$script:Publish,'-v','minimal')+$props) $source)
}
function Get-OriginHash($Origin) {
    Assert-RelativePath $Origin.source
    $path=Resolve-Below $Root $Origin.source
    if($script:InputHashes.ContainsKey($Origin.source)){$expected=$script:InputHashes[$Origin.source]}
    elseif($Origin.source.StartsWith('work/source/')){
        $relative=$Origin.source.Substring('work/source/'.Length)
        $matches=@($script:SourceFiles | Where-Object path -CEQ $relative)
        if($matches.Count -ne 1){throw 'Origin is not a frozen source file'};$expected=$matches[0].sha256
    }else{throw 'Origin is not a reviewed input'}
    if((Get-Sha $path) -cne $expected){throw 'Origin input changed'}
    if($Origin.kind -ceq 'input-file'){
        if($Origin.sha256 -cne $expected){throw 'Origin file hash differs'};return $expected
    }
    if(-not $Origin.source.StartsWith('inputs/nuget-feed/') -or -not $Origin.source.EndsWith('.nupkg')){throw 'Only pinned NuGet archives may supply package entries'}
    if(@($script:Spec.nugetPackages | Where-Object {('inputs/nuget-feed/'+$_.archive) -ceq $Origin.source}).Count -ne 1){throw 'Package origin has no reviewed package identity'}
    Assert-RelativePath $Origin.entry
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try{
        $matches=@($zip.Entries | Where-Object FullName -IEQ $Origin.entry)
        if($matches.Count -ne 1 -or $matches[0].FullName -cne $Origin.entry){throw 'Ambiguous package entry'}
        $hash=Get-BytesSha (Read-ZipEntryBytes $matches[0])
        if($hash -cne $Origin.sha256){throw 'Package entry hash differs'};return $hash
    }finally{$zip.Dispose()}
}
function Assemble-Candidate {
    $expected=@{}
    $products=@('PersonalAiWorkspace.Desktop.exe','PersonalAiWorkspace.Desktop.dll','PersonalAiWorkspace.Desktop.deps.json',
        'PersonalAiWorkspace.Desktop.runtimeconfig.json','PersonalAiWorkspace.Core.dll','PersonalAiWorkspace.Core.deps.json')
    foreach($file in $script:Spec.publishFiles){
        Assert-RelativePath $file.path
        if($expected.ContainsKey($file.path) -or $file.path.StartsWith('MainWorkspace/')){throw 'Duplicate or improperly classified publish path'}
        if($file.path -cmatch '(^|/)(data|credentials|model-state|webview2-profile|evidence|\.git|\.verification|node_modules)(/|$)|\.(pdb|log|trx|db|sqlite|lnk|cmd|bat|ps1|msi)$'){throw 'Disallowed publish payload'}
        if(($file.path -cin $products) -ne ($file.origin.kind -ceq 'generated-product')){throw 'Product DLL/EXE must come from this fresh build'}
        $hash=if($file.origin.kind -ceq 'generated-product'){$null}else{Get-OriginHash $file.origin}
        $expected[$file.path]=@{hash=$hash;origin=$file.origin}
    }
    foreach($file in $script:FrontendFiles){$expected['MainWorkspace/'+$file.path]=@{hash=$file.sha256;origin=@{kind='generated-frontend';productRevision=$script:ProductRevision;source='desktop/frontend/dist/'+$file.path}}}
    foreach($required in @('PersonalAiWorkspace.Desktop.exe','PersonalAiWorkspace.Desktop.dll','PersonalAiWorkspace.Core.dll','PersonalAiWorkspace.Desktop.deps.json','PersonalAiWorkspace.Desktop.runtimeconfig.json')){
        if(-not $expected.ContainsKey($required)){throw 'Required current product payload missing'}
    }
    foreach($suffix in $script:BootstrapPins.Keys){
        $path='NativeBootstrap/PersonalAiWorkspace.NativeBootstrap'+$suffix
        if(-not $expected.ContainsKey($path) -or $expected[$path].hash -cne $script:BootstrapPins[$suffix]){throw 'Publish whitelist changed a fixed native pin'}
    }
    $actual=Get-Inventory $script:Publish
    if($actual.Count -ne $expected.Count){throw 'Fresh publish output differs from reviewed whitelist; do not copy old files to fill gaps'}
    $payload=Resolve-Below $Root 'work/payload'
    if(Test-Path -LiteralPath $payload){throw 'Payload must start empty'};[void][IO.Directory]::CreateDirectory($payload)
    foreach($file in $actual){
        if(-not $expected.ContainsKey($file.path) -or ($expected[$file.path].hash -and $file.sha256 -cne $expected[$file.path].hash)){throw "Unexpected publish file or bytes: $($file.path)"}
        $target=Resolve-Below $payload $file.path
        Copy-PinnedFile (Resolve-Below $script:Publish $file.path) $target $file.sha256 $file.bytes
        Record-Generated $target 'candidate-publish-payload' $expected[$file.path].origin
    }
    $config=Read-StrictJson (Resolve-Below $payload 'PersonalAiWorkspace.Desktop.runtimeconfig.json')
    if($config.runtimeOptions.ContainsKey('frameworks') -or -not $config.runtimeOptions.ContainsKey('includedFrameworks')){throw 'Self-contained Desktop required'}
    foreach($name in @('Microsoft.NETCore.App','Microsoft.WindowsDesktop.App')){
        $framework=@($config.runtimeOptions.includedFrameworks | Where-Object name -CEQ $name)
        if($framework.Count -ne 1 -or $framework[0].version -cne '10.0.14'){throw 'Reviewed runtime pack version differs'}
    }
    foreach($path in $script:RuntimeInputs.Keys){
        $from=$script:RuntimeInputs[$path];$target=Resolve-Below $payload $path
        Copy-PinnedFile $from $target (Get-Sha $from) (Get-Item -LiteralPath $from).Length
        Record-Generated $target 'candidate-runtime-payload' @{source=[IO.Path]::GetRelativePath($Root,$from).Replace('\','/');generated=($path -ceq 'runtime/personal-ai-workspace.jar')}
    }
    $embeddedText=[WorkspaceCandidateBuild.ArtifactReader]::EmbeddedRuntimeManifest((Resolve-Below $payload 'PersonalAiWorkspace.Desktop.dll'))
    Write-NewText (Resolve-Below $Root 'evidence/embedded-runtime-manifest.json') $embeddedText
    $embedded=Read-StrictJson (Resolve-Below $Root 'evidence/embedded-runtime-manifest.json')
    if($embedded.Format -ne 3 -or $embedded.JavaHome -cne 'runtime/java' -or $embedded.RuntimeJar -cne 'runtime/personal-ai-workspace.jar' -or
        $embedded.JnaJar -cne 'runtime/jna-5.17.0.jar' -or $embedded.Files.Count -ne $script:RuntimePins.Count){throw 'Embedded manifest contract differs'}
    $pinNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($pin in $embedded.Files){
        if(-not $pinNames.Add($pin.Path) -or -not $script:RuntimeInputs.ContainsKey($pin.Path) -or
            (Get-Sha (Resolve-Below $payload $pin.Path)) -cne $pin.Sha256){throw 'Embedded Runtime pin differs'}
    }
    $readme=[IO.File]::ReadAllText((Resolve-Below $Root 'control/README-candidate.txt'))
    Write-NewText (Resolve-Below $payload 'README-CANDIDATE.txt') $readme
    $identity=[ordered]@{format='WORKSPACE_CANDIDATE_V1';artifactRole='CANDIDATE';usage='NOT_FOR_USE';productionGo=$false;nativeValidation='NOT_RUN';fullDesktopAcceptance=$false;
        systemSandbox=$false;installer=$false;updatesEnabled=$false;productRevision=$script:Spec.productRevision;buildToolsRevision=$script:Spec.buildToolsRevision;
        inputsSha256=$ownership.inputSha256;baselineReuse=$script:BaselineProvenance;
        profileAcceptanceBuild=$true;legacyAcceptanceBuild=$false;configuration='Debug';files=(Get-Inventory $payload)}
    Write-NewJson (Resolve-Below $payload 'candidate-manifest.json') $identity
    $envelope=Resolve-Below $Root 'work/review-envelope';[void][IO.Directory]::CreateDirectory($envelope)
    $innerName='payload-CANDIDATE-NOT_FOR_USE.zip';$inner=Resolve-Below $envelope $innerName
    [IO.Compression.ZipFile]::CreateFromDirectory($payload,$inner,[IO.Compression.CompressionLevel]::Fastest,$false)
    Assert-ZipMatches $inner (Get-Inventory $payload)
    Write-NewText (Resolve-Below $envelope 'README-CANDIDATE.txt') $readme
    $identity.files=Get-Inventory $envelope
    $identity['payloadSha256']=Get-Sha $inner
    Write-NewJson (Resolve-Below $envelope 'candidate-manifest.json') $identity
    [void][IO.Directory]::CreateDirectory((Resolve-Below $Root 'review'))
    $script:ReviewArchive=Resolve-Below $Root 'review/PersonalAiWorkspace-CANDIDATE-NOT_FOR_USE-2ca5041.review.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($envelope,$script:ReviewArchive,[IO.Compression.CompressionLevel]::Fastest,$false)
    Assert-ZipMatches $script:ReviewArchive (Get-Inventory $envelope)
}

$failure=$null
try {
    Export-SourceSnapshot
    Prepare-BaselineInputs
    Prepare-JarReuse
    Build-RuntimeJar
    Prepare-RuntimePayload
    Build-Frontend
    Build-Desktop
    Assert-SourceStable
    Assemble-Candidate
    Assert-SourceStable
} catch { $failure=$_.Exception.Message }
finally {
    Write-NewJson (Resolve-Below $Root 'evidence/outputs-and-origins.json') $generated.ToArray()
    Write-NewJson (Resolve-Below $Root 'evidence/worker-result.json') ([ordered]@{
        passed=($null -eq $failure);failure=$failure;productRevision=$script:Spec.productRevision;buildToolsRevision=$script:Spec.buildToolsRevision
        productionGo=$false;nativeValidation='NOT_RUN';fullDesktopAcceptance=$false;systemSandbox=$false
        reviewArchive=$(if($null -eq $failure){[IO.Path]::GetRelativePath($Root,$script:ReviewArchive)}else{$null})
    })
}
if($failure){throw $failure}
