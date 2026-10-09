#if MMF3_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
#if MMF3_PROFILE_ACCEPTANCE
using Microsoft.Win32.SafeHandles;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
#endif
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

// Native-only identity. No caller-provided endpoint, target or filesystem location.
internal sealed class Mmf3AcceptanceLaunch
{
#if MMF3_PROFILE_ACCEPTANCE
    internal const string Label = "MMF3 PROFILE TEST / 18765";
    internal const string Argument = "--mmf3-profile-acceptance";
#else
    internal const string Label = "MMF3 TEST / 18765";
    internal const string Argument = "--mmf3-acceptance";
#endif
#if !MMF3_PROFILE_ACCEPTANCE
    private static readonly string[] RequiredDirectories = ["credentials", "model-state", "data",
        "webview2-profile", "chrome-profile", "browser-test-extension", "evidence", "logs"];
#endif
    internal string RunId { get; }
    internal string Root { get; }
    internal string Profile => Path.Combine(Root, "webview2-profile");
    internal string TokenFile => Path.Combine(Root, "credentials", "client-token");
    internal string CredentialTarget => $"PersonalAiWorkspace/Desktop/MMF3/{RunId}/Runtime/127.0.0.1:18765";
    internal string InstanceSuffix => ".MMF3." + RunId;

    private Mmf3AcceptanceLaunch(string runId)
    {
        RunId = runId;
#if MMF3_PROFILE_ACCEPTANCE
        Root = Mmf3ProfileState.Root(runId);
#else
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (!Path.IsPathFullyQualified(local) || local.StartsWith(@"\\", StringComparison.Ordinal))
            throw new IOException("Account-local MMF3 location required.");
        Root = Path.GetFullPath(Path.Combine(local, "PersonalAiWorkspace-Mmf3", runId));
#endif
    }

    internal static Mmf3AcceptanceLaunch Parse(string[] args)
    {
        if (args.Length != 2 || args[0] != Argument || args[1].Length != 32)
            throw new IOException("Explicit MMF3 run identity required.");
        foreach (char c in args[1])
            if (c is not (>= '0' and <= '9') and not (>= 'a' and <= 'f'))
                throw new IOException("Invalid MMF3 run identity.");
        var launch = new Mmf3AcceptanceLaunch(args[1]);
        launch.Validate();
        return launch;
    }

    // Recheck at resource use as well as admission; never create or repair a directory.
    internal void Validate()
    {
#if MMF3_PROFILE_ACCEPTANCE
        if (!string.Equals(Root, Mmf3ProfileState.Verify(RunId), StringComparison.OrdinalIgnoreCase))
            throw new IOException("MMF3 profile identity changed.");
#else
        for (string? current = Root; current is not null; current = Path.GetDirectoryName(current))
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Linked MMF3 path rejected.");
        ValidatePrivateDirectory(Root);
        foreach (string name in RequiredDirectories) ValidatePrivateDirectory(Path.Combine(Root, name));
#endif
    }

#if !MMF3_PROFILE_ACCEPTANCE
    private static void ValidatePrivateDirectory(string path)
    {
        var attributes = File.GetAttributes(path);
        if ((attributes & FileAttributes.Directory) == 0 || (attributes & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Invalid MMF3 directory.");
        var user = WindowsIdentity.GetCurrent().User!;
        var security = new DirectoryInfo(path).GetAccessControl();
        if (!user.Equals(security.GetOwner(typeof(SecurityIdentifier))) || !security.AreAccessRulesProtected)
            throw new IOException("MMF3 directory owner/ACL mismatch.");
        bool fullControl = false;
        foreach (FileSystemAccessRule rule in security.GetAccessRules(true, true, typeof(SecurityIdentifier)))
        {
            if (rule.AccessControlType != AccessControlType.Allow || !user.Equals(rule.IdentityReference))
                throw new IOException("MMF3 directory is not account private.");
            if ((rule.PropagationFlags & PropagationFlags.InheritOnly) == 0
                && (rule.FileSystemRights & FileSystemRights.FullControl) == FileSystemRights.FullControl) fullControl = true;
        }
        if (!fullControl) throw new IOException("MMF3 directory permission missing.");
    }
#endif

    internal string ValidateTokenImport(string path)
    {
        try
        {
            // Require an absolute native-picker path; normalization cannot grant another source.
            if (!Path.IsPathFullyQualified(path)) throw new IOException();
            string normalized = Path.GetFullPath(path);
            if (!string.Equals(normalized, TokenFile, StringComparison.OrdinalIgnoreCase)) throw new IOException();
            Validate();
            return normalized;
        }
        catch (Exception) { throw new DesktopException(DesktopError.CredentialInvalid); }
    }
}

#if MMF3_PROFILE_ACCEPTANCE
// BEGIN MMF3 PROFILE STATE
// Shared verbatim with the explicit preparation script. No app, token-file or credential access.
// Narrow descriptor/path contract from NativeBootstrap/Win32.cs; no dependency on that candidate.
internal static class Mmf3ProfileState
{
    internal const string ParentName = ".personal-ai-workspace-mmf3-tests";
    private static readonly string[] Directories = { "credentials", "model-state", "data", "webview2-profile",
        "chrome-profile", "browser-test-extension", "evidence", "logs" };

    internal static string Root(string runId)
    {
        ValidateRunId(runId);
        using var identity = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        return Path.Combine(Profile(identity), ParentName, runId);
    }

    internal static string Verify(string runId) => State(runId, false);
    internal static string Prepare(string runId) => State(runId, true);

    private static string State(string runId, bool create)
    {
        ValidateRunId(runId);
        using var identity = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        var sid = identity.User ?? throw new IOException("MMF3 token SID missing.");
        string profile = Profile(identity);
        // Walk root-to-leaf. Metadata handles provide descriptor/identity evidence, NOT a rename pin.
        // Foreign replacement is excluded by the ancestor ACL contract, not by handle sharing.
        var ancestors = new System.Collections.Generic.Stack<string>();
        for (string? part = profile; part is not null; part = Path.GetDirectoryName(part)) ancestors.Push(part);
        foreach (string part in ancestors) ValidateDirectory(part, sid, false);
        // GetUserProfileDirectory uses THIS effective token (the same source as sid).
        // Profile account association is distinct from its filesystem owner; ancestor owners
        // retain the reviewed current/SYSTEM/Administrators/TrustedInstaller boundary.

        // Metadata-only collision check: never enumerate/read/import any legacy run or credential.
        string legacy = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PersonalAiWorkspace-Mmf3", runId);
        if (Exists(legacy)) throw new IOException("MMF3 legacy run identity cannot be reused.");
        string parent = Path.Combine(profile, ParentName), root = Path.Combine(parent, runId);
        if (create && Exists(root)) throw new IOException("MMF3 run already exists; no reuse or repair.");
        if (create && !Exists(parent)) CreatePrivateDirectory(parent, sid);
        ValidateDirectory(parent, sid, true);
        if (create) CreatePrivateDirectory(root, sid);
        ValidateDirectory(root, sid, true);
        foreach (string name in Directories)
        {
            string path = Path.Combine(root, name);
            if (create) CreatePrivateDirectory(path, sid);
            ValidateDirectory(path, sid, true);
        }
        return root;
    }

    private static void ValidateRunId(string runId)
    {
        if (!Regex.IsMatch(runId, @"\A[0-9a-f]{32}\z", RegexOptions.CultureInvariant))
            throw new IOException("Invalid MMF3 profile run identity.");
    }

    private static string Profile(WindowsIdentity identity)
    {
        if (identity.User is null || identity.IsAnonymous) throw new IOException("Invalid MMF3 token identity.");
        uint capacity = 32768;
        var profile = new StringBuilder((int)capacity);
        Check(GetUserProfileDirectoryW(identity.AccessToken, profile, ref capacity), "PROFILE_API");
        string path = profile.ToString();
        // Accept only canonical local DOS paths; never expand environment variables or resolve links.
        if (path.Length > 30000 || !Regex.IsMatch(path, @"\A[A-Za-z]:\\", RegexOptions.CultureInvariant)
            || path.Contains('/') || Path.GetFullPath(path) != path)
            throw new IOException("Invalid MMF3 token profile path.");
        foreach (string part in path[3..].Split('\\'))
            if (part.Length == 0 || part is "." or ".." || part.EndsWith('.') || part.EndsWith(' ')
                || System.Linq.Enumerable.Any(part, c => c < 32 || "<>:\"|?*".Contains(c))
                || Regex.IsMatch(part, @"\A(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(\.|\z)",
                    RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
                throw new IOException("Invalid MMF3 token profile component.");
        return path;
    }

    private static bool Exists(string path)
    {
        if (GetFileAttributesW(@"\\?\" + path) != uint.MaxValue) return true;
        int error = Marshal.GetLastPInvokeError();
        if (error is 2 or 3) return false;
        throw new IOException($"MMF3 path inspection failed ({error}).");
    }

    private static SafeFileHandle Open(string path)
    {
        var handle = CreateFileW(@"\\?\" + path, 0x20080, 3, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero);
        if (handle.IsInvalid)
        {
            int error = Marshal.GetLastPInvokeError(); handle.Dispose();
            throw new IOException($"MMF3 directory open failed ({error}).");
        }
        try
        {
            Check(GetFileInformationByHandleEx(handle, 9, out AttributeTag attributes, 8), "ATTRIBUTES");
            if ((attributes.Attributes & 0x410) != 0x10 || GetFileType(handle) != 1)
                throw new IOException("MMF3 directory/reparse rejected.");
            var final = new StringBuilder(32768);
            uint length = GetFinalPathNameByHandleW(handle, final, (uint)final.Capacity, 0);
            if (length == 0 || length >= final.Capacity
                || !string.Equals(final.ToString(), @"\\?\" + path, StringComparison.OrdinalIgnoreCase))
                throw new IOException("MMF3 path identity rejected.");
            return handle;
        }
        catch { handle.Dispose(); throw; }
    }

    private static RawSecurityDescriptor Descriptor(SafeFileHandle handle)
    {
        uint error = GetSecurityInfo(handle, 1, 5, out _, IntPtr.Zero, out _, IntPtr.Zero, out var descriptor);
        if (error != 0) throw new IOException($"MMF3 descriptor read failed ({error}).");
        try
        {
            if (descriptor == IntPtr.Zero || !IsValidSecurityDescriptor(descriptor))
                throw new IOException("MMF3 invalid descriptor.");
            uint length = GetSecurityDescriptorLength(descriptor);
            if (length < 20 || length > 65536) throw new IOException("MMF3 invalid descriptor size.");
            byte[] bytes = new byte[length]; Marshal.Copy(descriptor, bytes, 0, bytes.Length);
            return new RawSecurityDescriptor(bytes, 0);
        }
        finally { LocalFree(descriptor); }
    }

    private static void ValidateDirectory(string path, SecurityIdentifier sid, bool accountPrivate)
    {
        using var handle = Open(path);
        ValidateDescriptor(Descriptor(handle), sid, accountPrivate);
    }

    private static void ValidateDescriptor(RawSecurityDescriptor sd, SecurityIdentifier sid, bool accountPrivate)
    {
        bool Trusted(SecurityIdentifier? value) => value is not null && (value == sid || value.Value is
            "S-1-5-18" or "S-1-5-32-544" or "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464");
        if (sd.DiscretionaryAcl is null || (sd.ControlFlags & ControlFlags.DiscretionaryAclPresent) == 0)
            throw new IOException("MMF3 DACL missing.");
        if (accountPrivate)
        {
            if (sd.Owner != sid || (sd.ControlFlags & ControlFlags.DiscretionaryAclProtected) == 0)
                throw new IOException("MMF3 private owner/protection mismatch.");
            // Stricter Profile creation contract; legacy B1's gate is left unchanged.
            var acl = sd.DiscretionaryAcl;
            if (acl.Count != 1 || acl[0] is not CommonAce ace || ace.AceType != AceType.AccessAllowed
                || ace.SecurityIdentifier != sid || ace.AccessMask != 0x1f01ff
                || ace.AceFlags != (AceFlags.ObjectInherit | AceFlags.ContainerInherit))
                throw new IOException("MMF3 private ACE mismatch.");
            return;
        }
        if (!Trusted(sd.Owner)) throw new IOException("MMF3 ancestor owner rejected.");
        foreach (GenericAce generic in sd.DiscretionaryAcl)
        {
            if ((generic.AceFlags & AceFlags.InheritOnly) != 0 || generic.AceType == AceType.AccessDenied) continue;
            if (generic is not CommonAce ace || ace.AceType != AceType.AccessAllowed)
                throw new IOException("MMF3 ancestor ACE unsupported.");
            if ((ace.AccessMask & 0x500d0040) != 0 && !Trusted(ace.SecurityIdentifier))
                throw new IOException("MMF3 foreign ancestor replacement authority.");
        }
    }

    private static void CreatePrivateDirectory(string path, SecurityIdentifier sid)
    {
        Check(ConvertStringSecurityDescriptorToSecurityDescriptorW($"O:{sid.Value}D:P(A;OICI;FA;;;{sid.Value})",
            1, out var descriptor, out _), "DESCRIPTOR_CREATE");
        try
        {
            var attributes = new SecurityAttributes { Length = (uint)Marshal.SizeOf<SecurityAttributes>(), Descriptor = descriptor };
            Check(CreateDirectoryW(@"\\?\" + path, ref attributes), "CREATE_DIRECTORY");
        }
        finally { LocalFree(descriptor); }
        ValidateDirectory(path, sid, true);
    }

    private static void Check(bool result, string reason)
    {
        if (!result) throw new IOException($"MMF3 {reason} failed ({Marshal.GetLastPInvokeError()}).");
    }

    [StructLayout(LayoutKind.Sequential)] private struct AttributeTag { public uint Attributes, Tag; }
    [StructLayout(LayoutKind.Sequential)] private struct SecurityAttributes { public uint Length; public IntPtr Descriptor; public int InheritHandle; }
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("userenv.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern bool GetUserProfileDirectoryW(SafeAccessTokenHandle token, StringBuilder path, ref uint size);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr attributes, uint disposition, uint flags, IntPtr template);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern uint GetFileAttributesW(string path);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle, int kind, out AttributeTag info, uint size);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll")] private static extern uint GetFileType(SafeFileHandle handle);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder path, uint size, uint flags);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("advapi32.dll")] private static extern uint GetSecurityInfo(SafeFileHandle handle, int kind, uint information, out IntPtr owner, IntPtr group, out IntPtr dacl, IntPtr sacl, out IntPtr descriptor);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("advapi32.dll")] private static extern bool IsValidSecurityDescriptor(IntPtr descriptor);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("advapi32.dll")] private static extern uint GetSecurityDescriptorLength(IntPtr descriptor);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(string sddl, uint revision, out IntPtr descriptor, out uint size);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern bool CreateDirectoryW(string path, ref SecurityAttributes attributes);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr pointer);
}
// END MMF3 PROFILE STATE
#endif
#endif
