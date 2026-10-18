#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaHome)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $repo
$JavaHome = [IO.Path]::GetFullPath($JavaHome).TrimEnd('\')
if (!$IsWindows -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Explicit local JDK 21 required.' }
$release = Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
if ($release -notmatch '(?m)^JAVA_VERSION="21(?:\.|\")') { throw 'JDK 21 required.' }
# Explicit local-source/toolchain trust at build time. No runtime-supplied expected hashes.
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    & (Join-Path $repo 'mvnw.cmd') -B -ntp -pl workspace-application -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Runtime package failed.' }
} finally { $env:JAVA_HOME = $previousJavaHome }
$jar = Join-Path $repo 'target/personal-ai-workspace-0.1.0.jar'
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entry = $zip.GetEntry('META-INF/workspace-runtime.properties')
    if ($null -eq $entry) { $entry = $zip.GetEntry('BOOT-INF/classes/META-INF/workspace-runtime.properties') }
    if ($null -eq $entry -or $null -ne $zip.GetEntry('BOOT-INF/classes/io/github/qianlixunbai/workspace/provider/ollama/V1IsolatedOllamaInstance.class')) { throw 'Default Runtime artifact required.' }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { if ($reader.ReadToEnd() -notmatch '(?m)^kind=default\r?$') { throw 'Default build marker required.' } } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
Push-Location (Join-Path $repo 'desktop/frontend')
try { & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' } } finally { Pop-Location }
$paths = @((Join-Path $JavaHome 'release'), $jar)
foreach ($folder in @('bin', 'lib', 'conf')) {
    $paths += @(Get-ChildItem -LiteralPath (Join-Path $JavaHome $folder) -File -Recurse | Where-Object Name -ne 'src.zip' | Select-Object -ExpandProperty FullName)
}
$files = @(foreach ($path in ($paths | Sort-Object -Unique)) {
    $absolute = [IO.Path]::GetFullPath($path)
    for ($part = $absolute; $null -ne $part; $part = [IO.Path]::GetDirectoryName($part)) {
        if ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked development source rejected.' }
    }
    [ordered]@{ Path=$absolute; Sha256=(Get-FileHash -LiteralPath $absolute).Hash.ToLowerInvariant() }
})
$manifest = Join-Path $repo '.verification/trusted-development-runtime-sources.local.json'
[ordered]@{Format=1; JavaHome=$JavaHome; RuntimeJar=$jar; Files=$files} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifest -Encoding utf8NoBOM
& dotnet build (Join-Path $repo 'desktop/src/PersonalAiWorkspace.Desktop/PersonalAiWorkspace.Desktop.csproj') --no-restore '-p:Mmf3ProfileAcceptanceBuild=true' '-p:Mmf3AcceptanceBuild=false' '-p:FrontendSkipBuild=true' "-p:TrustedDevelopmentManifest=$manifest" -v minimal
if ($LASTEXITCODE -ne 0) { throw 'Profile Desktop build failed.' }
[ordered]@{result='BUILT_DEVELOPMENT_ONLY'; head=(& git rev-parse HEAD);manifestSha256=(Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant();jarSha256=(Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant();sources=$files.Count;productionGo=$false} | ConvertTo-Json
