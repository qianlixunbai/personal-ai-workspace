param(
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$ArtifactJar
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$helperProject = Join-Path $PSScriptRoot '../PersonalAiWorkspace.NativeBootstrap'
$helper = [IO.Path]::GetFullPath((Join-Path $helperProject 'bin/Release/net10.0-windows/PersonalAiWorkspace.NativeBootstrap.exe'))
$assemblyPath = [IO.Path]::ChangeExtension($helper, '.dll')
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if ([Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'ELEVATED_TEST_REJECTED'
}
$assembly = [Reflection.Assembly]::LoadFrom($assemblyPath)
$win32 = $assembly.GetType('PersonalAiWorkspace.NativeBootstrap.Win32', $true)
$flags = [Reflection.BindingFlags]'Static,NonPublic'
$current = $win32.GetMethod('CurrentUser', $flags).Invoke($null, @())
try {
    $token = $current.GetType().GetProperty('Token', [Reflection.BindingFlags]'Instance,NonPublic').GetValue($current)
    $profile = $win32.GetMethod('Profile', $flags).Invoke($null, @($token))
} finally { $current.Dispose() }
$rows = [Collections.Generic.List[object]]::new()
$fixtures = [Collections.Generic.List[string]]::new()
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class FixtureDacl {
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, ExactSpelling=true)]
    private static extern uint SetNamedSecurityInfoW(string path, int kind, uint information,
        IntPtr owner, IntPtr group, IntPtr dacl, IntPtr sacl);
    public static void Set(string path, byte[] descriptor, bool protect) {
        var pin = GCHandle.Alloc(descriptor, GCHandleType.Pinned);
        try {
            var dacl = IntPtr.Add(pin.AddrOfPinnedObject(), BitConverter.ToInt32(descriptor, 16));
            uint error = SetNamedSecurityInfoW(path, 1, 4u | (protect ? 0x80000000u : 0x20000000u),
                IntPtr.Zero, IntPtr.Zero, dacl, IntPtr.Zero);
            if (error != 0) throw new InvalidOperationException("FIXTURE_DACL_WIN32_" + error);
        } finally { pin.Free(); }
    }
}
'@

function Set-FixtureDacl([string]$Path, $Acl) {
    $fixturePrefix = Join-Path $profile '.personal-ai-workspace-native-bootstrap-fixture-'
    if (-not $Path.StartsWith($fixturePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'MUTATION_OUTSIDE_FIXTURE' }
    [FixtureDacl]::Set($Path, $Acl.GetSecurityDescriptorBinaryForm(), $Acl.AreAccessRulesProtected)
}

function Invoke-Helper([string]$Mode, [string]$Id, [string]$Reason = '') {
    $arguments = @($Mode, $Id)
    if ($Mode -eq 'ProvisionTrustedNative') { $arguments += $ArtifactJar }
    $output = & $helper @arguments
    $exitCode = $LASTEXITCODE
    $result = $output | ConvertFrom-Json
    if ($Reason) {
        if ($exitCode -ne 10 -or $result.result -ne 'STOP' -or $result.reason -ne $Reason) { throw 'UNEXPECTED_STOP_RESULT' }
    } elseif ($exitCode -ne 0 -or $result.result -ne 'PASS' -or $result.elevated) { throw 'UNEXPECTED_PASS_RESULT' }
    return $result
}

function New-Fixture {
    $id = [Guid]::NewGuid().ToString('N')
    $root = Join-Path $profile ('.personal-ai-workspace-native-bootstrap-fixture-' + $id)
    if (Test-Path -LiteralPath $root) { throw 'FIXTURE_ALREADY_EXISTS' }
    $provision = Invoke-Helper 'ProvisionTrustedNative' $id
    $fixtures.Add($id)
    return @{ id=$id; root=$root; native=(Join-Path $root 'jna-5.17.0-win32-x86-64');
        dll=(Join-Path $root 'jna-5.17.0-win32-x86-64/jnidispatch.dll'); provision=$provision }
}

function Descriptor([string]$Path) {
    # Independent Windows descriptor reader, rather than trusting helper JSON or an SDDL it generated.
    $acl = Get-Acl -LiteralPath $Path
    $raw = [Security.AccessControl.RawSecurityDescriptor]::new($acl.GetSecurityDescriptorBinaryForm(), 0)
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    $binary = $acl.GetSecurityDescriptorBinaryForm()
    return @{ protected=$acl.AreAccessRulesProtected; ownerCurrent=($raw.Owner -eq $identity.User);
        descriptorSha256=[Convert]::ToHexStringLower([Security.Cryptography.SHA256]::HashData($binary));
        aceCount=$rules.Count; inheritedCount=@($rules | Where-Object IsInherited).Count;
        foreignAllowCount=@($rules | Where-Object { $_.IdentityReference -ne $identity.User -and $_.AccessControlType -eq 'Allow' }).Count;
        fullControl=($rules.Count -eq 1 -and $rules[0].FileSystemRights -eq 'FullControl');
        reparse=(([IO.File]::GetAttributes($Path) -band [IO.FileAttributes]::ReparsePoint) -ne 0) }
}

function Snapshot($Fixture) {
    $objects = foreach ($path in @($Fixture.root, $Fixture.native, $Fixture.dll)) {
        if (Test-Path -LiteralPath $path) {
            $item = Get-Item -LiteralPath $path -Force
            @{ role=$(if ($path -eq $Fixture.root) {'ROOT'} elseif ($path -eq $Fixture.native) {'NATIVE'} else {'DLL'});
                descriptor=(Descriptor $path); lastWrite=$item.LastWriteTimeUtc.Ticks;
                hash=$(if (-not $item.PSIsContainer) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() } else { '' }) }
        }
    }
    return ConvertTo-Json -InputObject @($objects) -Depth 8 -Compress
}

function Negative($Fixture, [string]$Name, [string]$Reason) {
    $before = Snapshot $Fixture
    $result = Invoke-Helper 'VerifyTrustedNative' $Fixture.id $Reason
    $after = Snapshot $Fixture
    if ($before -cne $after) { throw 'VERIFY_MUTATED_OBJECT' }
    $rows.Add(@{case=$Name; result='PASS'; actual='STOP'; reason=$result.reason; unchanged=$true;
        descriptors=($after | ConvertFrom-Json)})
}

$positive = New-Fixture
$before = Snapshot $positive
$actual = $before | ConvertFrom-Json
foreach ($item in $actual) {
    $sd = $item.descriptor
    if (-not $sd.protected -or -not $sd.ownerCurrent -or -not $sd.fullControl -or $sd.aceCount -ne 1 -or
        $sd.inheritedCount -ne 0 -or $sd.foreignAllowCount -ne 0 -or $sd.reparse) { throw 'REAL_PRIVATE_DESCRIPTOR_REJECTED' }
}
$verified = Invoke-Helper 'VerifyTrustedNative' $positive.id
if ($verified.fileIdentity -ne $positive.provision.fileIdentity -or (Snapshot $positive) -cne $before) { throw 'VERIFY_IDENTITY_OR_MUTATION' }
$rows.Add(@{case='PROVISION_AND_READONLY_VERIFY';result='PASS';sameFileIdentity=$true;nonElevated=$true;descriptors=$actual})
Invoke-Helper 'ProvisionTrustedNative' $positive.id 'CREATE_DIRECTORY' | Out-Null
if ((Snapshot $positive) -cne $before) { throw 'REPROVISION_MUTATED_OBJECT' }
$rows.Add(@{case='REPROVISION_REFUSED';result='PASS';unchanged=$true})

$missing = New-Fixture
Move-Item -LiteralPath $missing.dll -Destination ($missing.dll + '.retained')
Negative $missing 'MISSING_DLL' 'MISSING'
$tampered = New-Fixture
$bytes = [IO.File]::ReadAllBytes($tampered.dll)
$bytes[128] = $bytes[128] -bxor 1
[IO.File]::WriteAllBytes($tampered.dll, $bytes)
Negative $tampered 'TAMPERED_DLL' 'DLL_HASH'
$unprotected = New-Fixture
$acl = Get-Acl -LiteralPath $unprotected.dll
$acl.SetAccessRuleProtection($false, $true)
Set-FixtureDacl $unprotected.dll $acl
if ((Descriptor $unprotected.dll).protected) { throw 'UNPROTECTED_FIXTURE_FAILED' }
Negative $unprotected 'UNPROTECTED_DACL' 'PROTECTED_DACL'
$foreign = New-Fixture
$acl = Get-Acl -LiteralPath $foreign.dll
$everyone = [Security.Principal.SecurityIdentifier]::new('S-1-1-0')
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($everyone, 'Read', 'Allow'))
Set-FixtureDacl $foreign.dll $acl
if ((Descriptor $foreign.dll).foreignAllowCount -ne 1) { throw 'FOREIGN_FIXTURE_FAILED' }
Negative $foreign 'FOREIGN_ALLOW' 'PRIVATE_ACE'

# Exercise the actual owning predicate on a real controlled unsafe ancestor descriptor.
$unsafe = New-Fixture
$acl = Get-Acl -LiteralPath $unsafe.root
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($everyone, 'DeleteSubdirectoriesAndFiles', 'Allow'))
Set-FixtureDacl $unsafe.root $acl
$beforeUnsafe = Snapshot $unsafe
$open = $win32.GetMethod('Open', $flags)
$ancestorHandle = $open.Invoke($null, @([string]$unsafe.root, $true, $false))
try {
    try { $win32.GetMethod('AncestorAcl', $flags).Invoke($null, @($ancestorHandle, $identity.User)); throw 'ANCESTOR_ACCEPTED' }
    catch {
        $failure = $_.Exception
        while ($failure.InnerException) { $failure = $failure.InnerException }
        if ($failure.Message -ne 'ANCESTOR_REPLACEMENT') { throw 'WRONG_ANCESTOR_REJECTION' }
    }
} finally { $ancestorHandle.Dispose() }
if ((Snapshot $unsafe) -cne $beforeUnsafe) { throw 'ANCESTOR_CHECK_MUTATED_OBJECT' }
$rows.Add(@{case='UNSAFE_ANCESTOR_PREDICATE';result='PASS';actual='ANCESTOR_REPLACEMENT';unchanged=$true;
    descriptor=(Descriptor $unsafe.root);scope='owning predicate with real Windows fixture descriptor'})
Negative $unsafe 'UNSAFE_FIXTURE_ROOT_CLI' 'PRIVATE_ACE'

# A real OS ancestor is already opened/read by normal full-chain verification. No owner mutation/privilege.
# This is predicate coverage, not a fabricated foreign-owned DLL end-to-end fixture.
$volumeRoot = [IO.Path]::GetPathRoot($profile)
$foreignOwner = Descriptor $volumeRoot
if ($foreignOwner.ownerCurrent) { throw 'OWNER_MISMATCH_FIXTURE_UNAVAILABLE' }
$rootHandle = $open.Invoke($null, @($volumeRoot, $true, $false))
try {
    try { $win32.GetMethod('PrivateAcl', $flags).Invoke($null, @($rootHandle, $identity.User, $true)); throw 'OWNER_ACCEPTED' }
    catch {
        $failure = $_.Exception
        while ($failure.InnerException) { $failure = $failure.InnerException }
        if ($failure.Message -ne 'OWNER') { throw 'WRONG_OWNER_REJECTION' }
    }
} finally { $rootHandle.Dispose() }
$rows.Add(@{case='OWNER_MISMATCH_PREDICATE';result='PASS';actual='OWNER';scope='read-only real volume ancestor descriptor; no foreign-owned DLL CLI claim'})

$junction = New-Fixture
Move-Item -LiteralPath $junction.native -Destination ($junction.native + '-retained')
New-Item -ItemType Junction -Path $junction.native -Target ($junction.native + '-retained') | Out-Null
if (-not (Descriptor $junction.native).reparse) { throw 'JUNCTION_FIXTURE_FAILED' }
Negative $junction 'JUNCTION' 'REPARSE'
Invoke-Helper 'VerifyTrustedNative' '..\invalid:fixture' 'FIXTURE_ID' | Out-Null
$rows.Add(@{case='ILLEGAL_PATH_INPUT';result='PASS';actual='FIXTURE_ID'})

# USERPROFILE is deliberately wrong: the token API must continue to select the same valid profile.
$savedProfile = $env:USERPROFILE
try {
    $env:USERPROFILE = 'Z:\untrusted-profile-input'
    Invoke-Helper 'VerifyTrustedNative' $positive.id | Out-Null
} finally { $env:USERPROFILE = $savedProfile }
$rows.Add(@{case='USERPROFILE_OVERRIDE_IGNORED';result='PASS'})

@{result='PASS';flow='one isolated Windows acceptance flow';cases=$rows;fixtureIds=$fixtures;
    nonElevated=$true;foreignOwnedDllCli='NOT EXECUTED; real descriptor owner predicate exercised instead';
    positiveFixtureId=$positive.id;missingFixtureId=$missing.id;invalidFixtureId=$tampered.id} |
    ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'filesystem-results.json') -Encoding utf8
Write-Output 'FOCUSED_WINDOWS_FLOW_PASS'
