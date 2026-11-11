# Definitions only. No build, process launch or product loading on import.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ProductRevision = '2ca50415cc6752288110fb31aa0abff6af7d4ed9'
$script:BaselineRevision = 'b4ae52e1fe537085427693b891a038969a3d8643'
$script:BaselineZipHash = '428d89e397dd5e156e6edfc9519a4e28c7b698bf8ce61c35b06e1b1684223c56'
$script:TreeDestinations = @{
    pwsh='tools/pwsh'; git='tools/git'; java='tools/java'; node='tools/node'; dotnet='tools/dotnet'
    frontend='work/source/desktop/frontend/node_modules'; nuget='inputs/nuget-feed'
    locks='inputs/locks'; contracts='inputs/contracts'
}
$script:BootstrapPins = @{
    '.exe'='d1166043c27929957e2c8597c6fd6bbe8a369ad1cfa45791d7dd665506983166'
    '.dll'='9dafed0b1379e9f013d6eb83c9ac5518e611c0e15faa3557991a0c4341029698'
    '.deps.json'='27af3a8da9f3b6155d86236c59339053f077e3ad33724e8f5e40281fb0375885'
    '.runtimeconfig.json'='3e965cd7cfd553c2ff4e842d8d8019aaae2da0a21d92ff85a87573dd8d1b95c6'
}
function Assert-RelativePath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path.Contains('\') -or $Path.StartsWith('/') -or
        $Path -match '[\x00-\x1f<>:"|?*]' -or $Path.Split('/') -contains '' -or
        $Path.Split('/') -contains '..' -or $Path.Split('/') -contains '.') { throw "Invalid relative path: $Path" }
    foreach ($part in $Path.Split('/')) {
        if ($part -match '[. ]$|^(?i:con|prn|aux|nul|conin\$|conout\$|clock\$|com[1-9¹²³]|lpt[1-9¹²³])(?:\.|$)') { throw 'Windows path alias rejected' }
    }
}
function Resolve-Below([string]$Root, [string]$Relative) {
    Assert-RelativePath $Relative
    $base = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    $path = [IO.Path]::GetFullPath((Join-Path $Root $Relative))
    if (-not $path.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) { throw 'Path escaped root' }
    return $path
}
function Assert-PlainPath([string]$Path) {
    for ($part=[IO.Path]::GetFullPath($Path); $part; $part=[IO.Path]::GetDirectoryName($part)) {
        if ((Test-Path -LiteralPath $part) -and ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Reparse point rejected: $part"
        }
    }
}
function Get-Sha([string]$Path) { Assert-PlainPath $Path; return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-BytesSha([byte[]]$Bytes) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant() }
function Write-NewBytes([string]$Path, [byte[]]$Bytes) {
    Assert-PlainPath $Path
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    try { $stream.Write($Bytes); $stream.Flush($true) } finally { $stream.Dispose() }
}
function Write-NewText([string]$Path,[string]$Text) { Write-NewBytes $Path ([Text.UTF8Encoding]::new($false).GetBytes($Text)) }
function Write-NewJson([string]$Path,$Value) { Write-NewText $Path ((ConvertTo-Json -InputObject $Value -Depth 40) + "`n") }
function ConvertFrom-StrictJson([string]$Json) {
    if ($Json.Length -gt 32MB) { throw 'Oversized input JSON' }
    $document=[Text.Json.JsonDocument]::Parse($json)
    function Assert-UniqueJson($Element) {
        if ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
            $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            foreach ($property in $Element.EnumerateObject()) {
                if (-not $names.Add($property.Name)) { throw 'Duplicate JSON property' }
                Assert-UniqueJson $property.Value
            }
        } elseif ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
            foreach ($item in $Element.EnumerateArray()) { Assert-UniqueJson $item }
        }
    }
    try { Assert-UniqueJson $document.RootElement } finally { $document.Dispose() }
    return ConvertFrom-Json -InputObject $json -AsHashtable -Depth 60
}
function Read-StrictJson([string]$Path) {
    Assert-PlainPath $Path
    if ((Get-Item -LiteralPath $Path).Length -gt 32MB) { throw 'Oversized input JSON' }
    return ConvertFrom-StrictJson ([IO.File]::ReadAllText($Path,[Text.UTF8Encoding]::new($false,$true)))
}
function Read-Inputs([string]$Path,[string]$Schema) {
    $inputSpec=Read-StrictJson $Path
    if (-not (Test-Json -Json ([IO.File]::ReadAllText($Path)) -SchemaFile $Schema -ErrorAction Stop)) { throw 'Input schema rejected' }
    if ($inputSpec.productRevision -cne $script:ProductRevision -or $inputSpec.baseline.commit -cne $script:BaselineRevision -or
        $inputSpec.baseline.zipSha256 -cne $script:BaselineZipHash) { throw 'Unreviewed product/baseline revision' }
    $ids=@($inputSpec.trees.id | Sort-Object -Unique)
    if ($ids.Count -ne 9 -or $inputSpec.trees.Count -ne 9) { throw 'Exactly nine fixed input trees required' }
    foreach ($tree in $inputSpec.trees) {
        if (-not $script:TreeDestinations.ContainsKey($tree.id) -or $tree.source -match '(?i)[\\/](?:\.verification|personal-finance-os|kag)(?:[\\/]|$)|PersonalAIWorkspace\.Development') {
            throw 'Unapproved input tree source'
        }
        if (-not [IO.Path]::IsPathFullyQualified($tree.source)) { throw 'Absolute input tree required' }
    }
    return $inputSpec
}
function Copy-PinnedFile([string]$From,[string]$To,[string]$Hash,[long]$Length) {
    Assert-PlainPath $From; Assert-PlainPath $To
    if ((Get-Item -LiteralPath $From).Length -ne $Length -or (Get-Sha $From) -cne $Hash) { throw "Input changed: $From" }
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($To))
    [IO.File]::Copy($From,$To,$false)
    if ((Get-Sha $To) -cne $Hash -or (Get-Sha $From) -cne $Hash) { throw 'Input changed during copy' }
}
function Copy-InputTree($Tree,[string]$Root) {
    $destination=Resolve-Below $Root $script:TreeDestinations[$Tree.id]
    if (Test-Path -LiteralPath $destination) { throw 'Input destination already exists' }
    [void][IO.Directory]::CreateDirectory($destination)
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($file in $Tree.files) {
        if (-not $names.Add($file.path)) { throw 'Duplicate input filename' }
        Copy-PinnedFile (Resolve-Below $Tree.source $file.path) (Resolve-Below $destination $file.path) $file.sha256 $file.bytes
    }
}
function Set-BuildEnvironment([string]$Root) {
    # A process-local environment, NOT a filesystem/network sandbox or Windows identity change.
    $windows=[Environment]::GetEnvironmentVariable('SystemRoot','Process')
    if (-not [IO.Path]::IsPathFullyQualified($windows)) { throw 'SystemRoot unavailable' }
    foreach ($key in @([Environment]::GetEnvironmentVariables('Process').Keys)) {
        if ($key -cne 'SystemRoot' -and $key -cne 'WINDIR') { [Environment]::SetEnvironmentVariable($key,$null,'Process') }
    }
    $settings=@{
        SystemRoot=$windows; WINDIR=$windows; COMSPEC=(Join-Path $windows 'System32/cmd.exe')
        PATH=((@('tools/node','tools/dotnet','tools/pwsh','tools/git/cmd') | ForEach-Object { Resolve-Below $Root $_ }) + (Join-Path $windows 'System32') -join ';')
        TEMP='tmp/general'; TMP='tmp/general'; USERPROFILE='cache/tool-user'; APPDATA='cache/roaming'; LOCALAPPDATA='cache/local'
        DOTNET_CLI_HOME='cache/dotnet'; DOTNET_ROOT='tools/dotnet'; DOTNET_ROOT_X64='tools/dotnet'
        NUGET_PACKAGES='cache/nuget/packages'; NUGET_HTTP_CACHE_PATH='cache/nuget/http'; NUGET_PLUGINS_CACHE_PATH='cache/nuget/plugins'; NUGET_SCRATCH='tmp/nuget'
        MSBuildUserExtensionsPath='cache/msbuild-user'; PSModulePath='tools/pwsh/Modules'
        JAVA_HOME='tools/java'; GIT_CONFIG_GLOBAL='control/empty.gitconfig'; GIT_CONFIG_SYSTEM='control/empty.gitconfig'
        DOTNET_CLI_TELEMETRY_OPTOUT='1'; POWERSHELL_TELEMETRY_OPTOUT='1'; DOTNET_MULTILEVEL_LOOKUP='0'
        DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE='true'; DOTNET_CLI_DO_NOT_USE_MSBUILD_SERVER='1'
        MSBUILDDISABLENODEREUSE='1'; DOTNET_PROCESSOR_COUNT='2'; DOTNET_GCHeapHardLimit='0x30000000'
        NODE_OPTIONS='--max-old-space-size=384'; GIT_CONFIG_NOSYSTEM='1'; GIT_OPTIONAL_LOCKS='0'; GIT_NO_REPLACE_OBJECTS='1'
        GIT_NO_LAZY_FETCH='1'; GIT_ATTR_NOSYSTEM='1'; GIT_TERMINAL_PROMPT='0'
        NUGET_CERT_REVOCATION_MODE='offline'
    }
    foreach ($key in @('TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','DOTNET_CLI_HOME','DOTNET_ROOT','DOTNET_ROOT_X64',
        'NUGET_PACKAGES','NUGET_HTTP_CACHE_PATH','NUGET_PLUGINS_CACHE_PATH','NUGET_SCRATCH','MSBuildUserExtensionsPath','PSModulePath','JAVA_HOME','GIT_CONFIG_GLOBAL','GIT_CONFIG_SYSTEM')) {
        $settings[$key]=Resolve-Below $Root $settings[$key]
    }
    foreach ($key in @('TEMP','USERPROFILE','APPDATA','LOCALAPPDATA','DOTNET_CLI_HOME','NUGET_PACKAGES','NUGET_HTTP_CACHE_PATH','NUGET_PLUGINS_CACHE_PATH','NUGET_SCRATCH','MSBuildUserExtensionsPath')) {
        [void][IO.Directory]::CreateDirectory($settings[$key])
    }
    foreach ($pair in $settings.GetEnumerator()) { [Environment]::SetEnvironmentVariable($pair.Key,$pair.Value,'Process') }
}
function Get-Inventory([string]$Directory) {
    Assert-PlainPath $Directory
    $result=[Collections.Generic.List[object]]::new()
    # Only newly-owned trees. NEVER call on a baseline or on .verification itself.
    $pending=[Collections.Generic.Stack[string]]::new(); $pending.Push($Directory)
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked inventory object' }
            if ($item.PSIsContainer) { $pending.Push($item.FullName) } else {
                $relative=[IO.Path]::GetRelativePath($Directory,$item.FullName).Replace('\','/')
                Assert-RelativePath $relative
                $result.Add([ordered]@{path=$relative;sha256=(Get-Sha $item.FullName);bytes=$item.Length})
            }
        }
    }
    return @($result | Sort-Object path -CaseSensitive)
}
function Assert-SameInventory($Expected,$Actual) {
    $wanted=@{}; foreach ($file in $Expected) { if($wanted.ContainsKey($file.path)){throw 'Duplicate inventory path'}; $wanted[$file.path]=$file }
    if ($wanted.Count -ne @($Actual).Count) { throw 'Inventory count changed' }
    foreach ($file in $Actual) {
        if (-not $wanted.ContainsKey($file.path) -or $wanted[$file.path].sha256 -cne $file.sha256 -or $wanted[$file.path].bytes -ne $file.bytes) { throw "Inventory changed: $($file.path)" }
    }
}
function New-AllowedTree($Files) {
    $filesByName=@{}; $directories=@{''=$true}
    foreach($file in $Files){
        Assert-RelativePath $file.path
        if($filesByName.ContainsKey($file.path) -or $directories.ContainsKey($file.path)){throw 'Ambiguous allowed file'}
        if($file.sha256 -cnotmatch '^[a-f0-9]{64}$' -or $file.bytes -isnot [ValueType] -or $file.bytes -lt 0){throw 'Invalid allowed file identity'}
        $filesByName[$file.path]=$file
        $parts=$file.path.Split('/')
        for($i=1;$i -lt $parts.Length;$i++){
            $parent=$parts[0..($i-1)] -join '/'
            if($filesByName.ContainsKey($parent)){throw 'File/directory collision'}
            $directories[$parent]=$true
        }
    }
    return @{files=$filesByName;directories=$directories}
}
function Assert-AllowedInventory([string]$Directory,$Expected) {
    # Enumerate names only at allowed nodes. Unknown files are never opened/hashed;
    # unknown directories are never descended, even when a failure occurs.
    $allowed=New-AllowedTree $Expected; Assert-PlainPath $Directory
    $pending=[Collections.Generic.Stack[string]]::new();$pending.Push('')
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    while($pending.Count){
        $relative=$pending.Pop();$current=if($relative){Resolve-Below $Directory $relative}else{$Directory}
        $children=@(Get-ChildItem -LiteralPath $current -Force)
        # Validate the entire sibling set before reading any sibling file.
        foreach($item in $children){
            $name=if($relative){$relative+'/'+$item.Name}else{$item.Name}
            if($item.PSIsContainer){if(-not $allowed.directories.ContainsKey($name)){throw 'Unknown directory; not descended'}}
            elseif(-not $allowed.files.ContainsKey($name)){throw 'Unknown file; not hashed'}
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked allowed-tree object'}
        }
        foreach($item in $children){
            $name=if($relative){$relative+'/'+$item.Name}else{$item.Name}
            if($item.PSIsContainer){$pending.Push($name);continue}
            $file=$allowed.files[$name]
            if(-not $seen.Add($name) -or $name -cne $file.path -or $item.Length -ne $file.bytes -or (Get-Sha $item.FullName) -cne $file.sha256){throw 'Allowed file changed'}
        }
    }
    if($seen.Count -ne $allowed.files.Count){throw 'Allowed inventory incomplete'}
}
function Read-PinnedArchiveManifest([string]$ZipPath,[string]$ZipHash,[string]$ManifestHash) {
    # This generic byte checker is also used with synthetic ZIPs. Production callers
    # MUST supply the fixed BaselineZipHash, never only an independent manifest hash.
    if((Get-Sha $ZipPath) -cne $ZipHash){throw 'Pinned archive hash differs'}
    $zip=[IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entries=@{};$directories=[Collections.Generic.List[string]]::new()
        foreach($entry in $zip.Entries){
            $name=$entry.FullName.TrimEnd('/');Assert-RelativePath $name
            if($entries.ContainsKey($name)){throw 'Ambiguous pinned archive entry'}
            $entries[$name]=$entry
            if($entry.FullName.EndsWith('/')){$directories.Add($name)}
        }
        if(-not $entries.ContainsKey('development-bundle.json') -or $entries['development-bundle.json'].FullName -cne 'development-bundle.json'){throw 'Missing canonical archive manifest'}
        $manifestBytes=Read-ZipEntryBytes $entries['development-bundle.json']
        if((Get-BytesSha $manifestBytes) -cne $ManifestHash){throw 'Manifest not bound to pinned ZIP'}
        $manifest=ConvertFrom-StrictJson ([Text.UTF8Encoding]::new($false,$true).GetString($manifestBytes))
        $expected=@($manifest.files)+@(@{path='development-bundle.json';sha256=$ManifestHash;bytes=$manifestBytes.Length})
        $allowed=New-AllowedTree $expected
        foreach($name in $directories){if(-not $allowed.directories.ContainsKey($name)){throw 'Unlisted archive directory'}}
        if($entries.Count -ne $allowed.files.Count+$directories.Count){throw 'Unlisted archive content'}
        foreach($file in $expected){
            if(-not $entries.ContainsKey($file.path)){throw 'Missing archive payload'}
            $entry=$entries[$file.path]
            if($entry.FullName -cne $file.path -or $entry.Length -ne $file.bytes -or (Get-ZipEntrySha $entry) -cne $file.sha256){throw 'Manifest/payload ZIP mismatch'}
        }
        return @{manifest=$manifest;inventory=$expected}
    }finally{$zip.Dispose()}
}
function Assert-BaselineJavaTree($Spec,$Manifest) {
    $javaTree=@($Spec.trees | Where-Object id -CEQ 'java')[0]
    $baselineJava=@($Manifest.files | Where-Object {$_.path.StartsWith('runtime/java/',[StringComparison]::Ordinal)})
    if($javaTree.files.Count -ne $baselineJava.Count){throw 'Java toolchain must be the complete pinned baseline tree'}
    foreach($file in $javaTree.files){
        $matches=@($baselineJava | Where-Object path -CEQ ('runtime/java/'+$file.path))
        if($matches.Count -ne 1 -or $matches[0].sha256 -cne $file.sha256 -or $matches[0].bytes -ne $file.bytes){throw 'Java toolchain differs from pinned ZIP'}
    }
}
function Assert-PublishRule([string]$Path,[string]$Kind) {
    Assert-RelativePath $Path
    $products=@('PersonalAiWorkspace.Desktop.exe','PersonalAiWorkspace.Desktop.dll','PersonalAiWorkspace.Desktop.deps.json',
        'PersonalAiWorkspace.Desktop.runtimeconfig.json','PersonalAiWorkspace.Core.dll','PersonalAiWorkspace.Core.deps.json')
    if($Path.StartsWith('MainWorkspace/',[StringComparison]::OrdinalIgnoreCase) -or
        $Path -imatch '(^|/)(data|credentials|profile|model-state|webview2-profile|evidence|\.git|\.verification|node_modules)(/|$)|\.(pdb|log|trx|db|sqlite|lnk|cmd|bat|ps1|msi)$'){throw 'Disallowed publish payload'}
    $leaf=$Path.Split('/')[-1]
    $canonical=@($products | Where-Object {$_ -ieq $leaf})
    if($canonical.Count){if($Path -cne $canonical[0] -or $Kind -cne 'generated-product'){throw 'Canonical product name and fresh generation required'}}
    elseif($Kind -ceq 'generated-product'){throw 'Unknown generated product'}
}
function Assert-PrivatePathValues([string]$Root,[string]$Value) {
    # Values come from parsed JSON keys/strings or XML attributes/text, not raw JSON
    # escapes. Semicolon lists are checked one member at a time, including later items.
    $base=[IO.Path]::GetFullPath($Root).TrimEnd('\','/')
    foreach($member in $Value.Split(';')){
        $part=$member.Trim().Trim('"',"'")
        if(-not $part){continue}
        foreach($match in [regex]::Matches($part,'(?i)(?:(?<![A-Za-z0-9])[a-z]:[\\/]|(?<![:A-Za-z0-9])[\\/]{2})[^"<>\r\n]*')){
            $path=$match.Value.Trim().Trim('"',"'").Replace('/','\')
            # UNC/device roots are outside a local R. No resolution/open of the target.
            if($path.StartsWith('\\')){throw 'External UNC/device path'}
            $absolute=[IO.Path]::GetFullPath($path).TrimEnd('\')
            if(-not $absolute.Equals($base,[StringComparison]::OrdinalIgnoreCase) -and
                -not $absolute.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'External absolute path'}
        }
        if($part -match '^[\\/](?![\\/])|^[A-Za-z]:(?![\\/])|(?i)^file:'){throw 'Ambiguous rooted path/URI'}
    }
}
function Assert-StructuredPaths([string]$Root,[string]$Path) {
    if([IO.Path]::GetExtension($Path) -ieq '.json'){
        $value=Read-StrictJson $Path
        function Visit-PathValue($Value){
            if($Value -is [Collections.IDictionary]){foreach($key in $Value.Keys){Assert-PrivatePathValues $Root ([string]$key);Visit-PathValue $Value[$key]}}
            elseif($Value -is [string]){Assert-PrivatePathValues $Root $Value}
            elseif($Value -is [Collections.IEnumerable]){foreach($item in $Value){Visit-PathValue $item}}
        }
        Visit-PathValue $value
    }else{
        $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
        $reader=[Xml.XmlReader]::Create($Path,$settings)
        try{while($reader.Read()){
            if($reader.HasAttributes){while($reader.MoveToNextAttribute()){Assert-PrivatePathValues $Root $reader.Value};[void]$reader.MoveToElement()}
            if($reader.NodeType -in @([Xml.XmlNodeType]::Text,[Xml.XmlNodeType]::CDATA)){Assert-PrivatePathValues $Root $reader.Value}
        }}finally{$reader.Dispose()}
    }
}
function Read-ZipEntryBytes($Entry) {
    if ($Entry.Length -gt 256MB) { throw 'Oversized archive entry' }
    $source=$Entry.Open(); $memory=[IO.MemoryStream]::new()
    try { $source.CopyTo($memory); return ,$memory.ToArray() } finally { $source.Dispose(); $memory.Dispose() }
}
function Get-ZipEntrySha($Entry) {
    $stream=$Entry.Open()
    try{return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}
}
function New-PrivateGitReader([string]$Repository,[string]$Root) {
    # Object-only copy: no original config, config.worktree, includes, refs, index,
    # attributes or hooks are ever opened by Git. Reject reference files without following.
    $original=Join-Path $Repository '.git';Assert-PlainPath $original
    if(-not (Get-Item -LiteralPath $original).PSIsContainer){throw 'Linked/worktree Git directory unsupported'}
    foreach($name in @('commondir','shallow')){if(Test-Path -LiteralPath (Join-Path $original $name)){throw 'Indirect/partial object store unsupported'}}
    $objects=Join-Path $original 'objects';Assert-PlainPath $objects
    $reader=Resolve-Below $Root 'work/object-reader.git'
    if(Test-Path -LiteralPath $reader){throw 'Object reader must be new'}
    [void][IO.Directory]::CreateDirectory((Join-Path $reader 'objects'))
    [void][IO.Directory]::CreateDirectory((Join-Path $reader 'refs'))
    Write-NewText (Join-Path $reader 'HEAD') "ref: refs/heads/unused`n"
    Write-NewText (Join-Path $reader 'config') "[core]`n repositoryformatversion = 0`n bare = true`n fsmonitor = false`n[gc]`n auto = 0`n"
    $copied=[Collections.Generic.List[object]]::new()
    foreach($folder in Get-ChildItem -LiteralPath $objects -Force){
        if(-not $folder.PSIsContainer -or $folder.Name -cnotmatch '^(?:[a-f0-9]{2}|pack|info)$' -or
            ($folder.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Unsupported Git object directory; not descended'}
        $children=@(Get-ChildItem -LiteralPath $folder.FullName -Force)
        # info/alternates, http-alternates, commit-graphs, and unknown entries are not followed.
        if($folder.Name -ceq 'info' -and $children.Count){throw 'Object info references require separate review; no target followed'}
        foreach($file in $children){
            $valid=if($folder.Name -ceq 'pack'){$file.Name -cmatch '^pack-[a-f0-9]{40}\.(pack|idx|rev|bitmap)$'}else{$file.Name -cmatch '^[a-f0-9]{38}$'}
            if(-not $valid -or $file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Unsupported Git object; not read'}
        }
        foreach($file in $children){
            $path='objects/'+$folder.Name+'/'+$file.Name;$hash=Get-Sha $file.FullName
            Copy-PinnedFile $file.FullName (Resolve-Below $reader $path) $hash $file.Length
            $copied.Add(@{path=$path;sha256=$hash;bytes=$file.Length})
        }
    }
    Write-NewJson (Resolve-Below $Root 'evidence/git-object-inputs.json') $copied.ToArray()
    return $reader
}
function Invoke-BuildTool([string]$Role,[string[]]$Arguments,[string]$Cwd,[int[]]$Accept=@(0),[string]$StdinFile='') {
    if (-not $script:Spec.executables.ContainsKey($Role)) { throw 'Unknown executable role' }
    $relative=$script:Spec.executables[$Role]; $exe=Resolve-Below $script:Root $relative
    if (-not $script:InputHashes.ContainsKey($relative) -or (Get-Sha $exe) -cne $script:InputHashes[$relative]) { throw 'Unpinned tool executable' }
    $script:Step++
    $prefix=Resolve-Below $script:Root ('evidence/step-{0:D3}' -f $script:Step)
    Write-NewJson ($prefix+'-command.json') ([ordered]@{role=$Role;executable=$relative;sha256=$script:InputHashes[$relative];arguments=$Arguments;cwd=$Cwd;stdinFile=$StdinFile})
    $code=[WorkspaceCandidateBuild.ArtifactReader]::RunTool($exe,$Arguments,$Cwd,($prefix+'.stdout'),($prefix+'.stderr'),$prefix,300,$StdinFile)
    Write-NewJson ($prefix+'-exit.json') @{exitCode=$code}
    if ($code -notin $Accept) { throw "Build tool failed: $Role, exit $code, step $script:Step" }
    return [pscustomobject]@{Code=$code;Stdout=($prefix+'.stdout');Stderr=($prefix+'.stderr')}
}
function Add-ZipFile($Archive,[string]$Name,[string]$File) {
    Assert-RelativePath $Name
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive,$File,$Name,[IO.Compression.CompressionLevel]::NoCompression)
}
function Assert-ZipMatches([string]$File,$Expected) {
    $wanted=@{};foreach($item in $Expected){if($wanted.ContainsKey($item.path)){throw 'Duplicate expected ZIP entry'};$wanted[$item.path]=$item}
    $zip=[IO.Compression.ZipFile]::OpenRead($File);$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    try {
        foreach($entry in $zip.Entries){
            Assert-RelativePath $entry.FullName
            if(-not $seen.Add($entry.FullName) -or -not $wanted.ContainsKey($entry.FullName)){throw 'Unexpected ZIP entry'}
            if($entry.Length -ne $wanted[$entry.FullName].bytes -or (Get-ZipEntrySha $entry) -cne $wanted[$entry.FullName].sha256){throw 'ZIP bytes differ'}
            if($entry.FullName.StartsWith('BOOT-INF/lib/') -and $entry.CompressedLength -ne $entry.Length){throw 'Nested Boot library must be stored'}
        }
        if($seen.Count -ne $wanted.Count){throw 'ZIP inventory incomplete'}
    }finally{$zip.Dispose()}
}
