using Microsoft.Win32.SafeHandles;
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.WindowsSecurity;

// Development-only per-user storage. Windows/.NET own identity and descriptor decoding.
// ACLs exclude other accounts, not processes running as this Windows user.
internal static class WindowsProfileSecurity
{
    internal const string ApplicationDirectory = "PersonalAIWorkspace.Development";
    internal const string ParentName = "Profiles";
    internal static readonly string[] Directories = ["credentials", "model-state", "data", "webview2-profile",
        "chrome-profile", "browser-test-extension", "evidence", "logs"];

    internal static string Parent()
    {
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        var folder = new Guid("f1b32785-6fba-4fcf-9d55-7b8e7f157091"); // FOLDERID_LocalAppData
        int error = SHGetKnownFolderPath(in folder, 0, user.AccessToken, out var pointer);
        try
        {
            if (error != 0 || pointer == IntPtr.Zero) throw new IOException("Development known folder unavailable.");
            string local = Marshal.PtrToStringUni(pointer) ?? throw new IOException();
            ValidatePath(local);
            string app = Path.Combine(local, ApplicationDirectory);
            // Packaged desktop hosts may virtualize writes even when Known Folder returns a
            // logical user path. Bind to the OS handle's actual directory, never a caller path.
            if (Path.Exists(app))
            {
                using var handle = OpenDirectory(app, allowSystemRedirection: true);
                app = FinalPath(handle);
                ValidatePath(app);
            }
            return Path.Combine(app, ParentName);
        }
        finally { Marshal.FreeCoTaskMem(pointer); }
    }

    internal static string Root(string id)
    {
        if (!Regex.IsMatch(id, @"\A[0-9a-f]{32}\z", RegexOptions.CultureInvariant))
            throw new IOException("Invalid development Profile identity.");
        return Path.Combine(Parent(), id);
    }

    internal static string State(string id, bool create)
    {
        string root = Root(id), parent = Path.GetDirectoryName(root)!, app = Path.GetDirectoryName(parent)!;
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        var sid = user.User ?? throw new IOException();
        // Pin the OS-owned Known Folder chain before creating/validating the private boundary.
        using var ancestors = HoldDirectories(Path.GetDirectoryName(app)!);
        if (create && Path.Exists(root)) throw new IOException("Development Profile already exists.");
        if (create && !Path.Exists(app)) CreateDirectory(app, sid);
        // Re-derive after creation so filesystem virtualization cannot change the bound root.
        root = Root(id); parent = Path.GetDirectoryName(root)!; app = Path.GetDirectoryName(parent)!;
        foreach (string directory in new[] { app, parent })
        {
            if (create && !Path.Exists(directory)) CreateDirectory(directory, sid);
            using var handle = OpenDirectory(directory);
            ValidatePrivate(handle, sid, directory: true);
        }
        if (create) CreateDirectory(root, sid);
        using var profile = HoldDirectories(root);
        foreach (string directory in new[] { app, parent, root })
        {
            using var handle = OpenDirectory(directory);
            ValidatePrivate(handle, sid, directory: true);
        }
        foreach (string name in Directories)
        {
            string directory = Path.Combine(root, name);
            if (create) CreateDirectory(directory, sid);
            using var handle = OpenDirectory(directory);
            ValidatePrivate(handle, sid, directory: true);
        }
        return root;
    }

    internal static bool Trusted(SecurityIdentifier value, SecurityIdentifier user) => value == user
        || value.Value is "S-1-5-18" or "S-1-5-32-544";

    internal static void ValidatePrivate(SafeFileHandle handle, SecurityIdentifier user, bool directory)
    {
        // GetAccessControl reads this handle using the framework's GetSecurityInfo wrapper.
        using var borrowed = new SafeFileHandle(handle.DangerousGetHandle(), ownsHandle: false);
        using var stream = new FileStream(borrowed, FileAccess.Read, 1);
        ValidatePrivate(stream.GetAccessControl(), user, directory);
    }

    internal static void ValidatePrivate(FileSystemSecurity security, SecurityIdentifier user, bool directory = false)
    {
        if (!user.Equals(security.GetOwner(typeof(SecurityIdentifier))) || !security.AreAccessRulesProtected
            || !security.AreAccessRulesCanonical) throw new IOException("Development private ACL rejected.");
        var rules = security.GetAccessRules(true, true, typeof(SecurityIdentifier));
        // Framework rule projection may omit conditional/object ACEs. Unsupported grants fail closed.
        var descriptor = new RawSecurityDescriptor(security.GetSecurityDescriptorBinaryForm(), 0);
        if (descriptor.DiscretionaryAcl is null || descriptor.DiscretionaryAcl.Count != rules.Count)
            throw new IOException("Development private ACL rejected.");
        bool full = false;
        foreach (FileSystemAccessRule rule in rules)
        {
            if (rule.AccessControlType == AccessControlType.Deny) continue;
            var sid = (SecurityIdentifier)rule.IdentityReference;
            // Inherit-only foreign permissions still endanger future SQLite/WebView descendants.
            FileSystemRights harmless = FileSystemRights.ReadAttributes | FileSystemRights.ReadPermissions
                | FileSystemRights.Synchronize | (directory ? FileSystemRights.Traverse : 0);
            if (!Trusted(sid, user) && (rule.FileSystemRights & ~harmless) != 0)
                throw new IOException("Development private ACL rejected.");
            if (sid == user && (rule.PropagationFlags & PropagationFlags.InheritOnly) == 0
                && (rule.FileSystemRights & FileSystemRights.FullControl) == FileSystemRights.FullControl) full = true;
        }
        if (!full) throw new IOException("Development private ACL rejected.");
    }

    internal static void CreateDirectory(string path, SecurityIdentifier user)
    {
        var security = new DirectorySecurity();
        security.SetOwner(user);
        security.SetAccessRuleProtection(true, false);
        foreach (var sid in new[] { user, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null) })
            security.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        // DirectoryInfo.Create(security) accepts existing paths. Keep the product's CREATE_NEW rule.
        byte[] descriptor = security.GetSecurityDescriptorBinaryForm();
        var pin = GCHandle.Alloc(descriptor, GCHandleType.Pinned);
        try
        {
            var attributes = new SecurityAttributes { Length = (uint)Marshal.SizeOf<SecurityAttributes>(), Descriptor = pin.AddrOfPinnedObject() };
            if (!CreateDirectoryW(@"\\?\" + path, ref attributes)) throw new IOException("Development directory creation rejected.");
        }
        finally { pin.Free(); }
    }

    internal static DirectoryPins HoldDirectories(string leaf)
    {
        var result = new DirectoryPins();
        try
        {
            var paths = new Stack<string>();
            for (string? path = leaf; path is not null; path = Path.GetDirectoryName(path)) paths.Push(path);
            foreach (string path in paths) result.Handles.Add(OpenDirectory(path));
            return result;
        }
        catch { result.Dispose(); throw; }
    }

    internal sealed class DirectoryPins : IDisposable
    {
        internal readonly List<SafeFileHandle> Handles = new();
        public void Dispose() { for (int i = Handles.Count - 1; i >= 0; i--) Handles[i].Dispose(); }
    }

    private static string FinalPath(SafeFileHandle handle)
    {
        var final = new StringBuilder(32768);
        uint length = GetFinalPathNameByHandleW(handle, final, (uint)final.Capacity, 0);
        if (length == 0 || length >= final.Capacity || !final.ToString().StartsWith(@"\\?\", StringComparison.Ordinal))
            throw new IOException("Development directory identity rejected.");
        return final.ToString()[4..];
    }

    private static SafeFileHandle OpenDirectory(string path, bool allowSystemRedirection = false)
    {
        // LIST_DIRECTORY and no SHARE_DELETE block ancestor rename, including foreign DELETE_CHILD.
        // Keep these handles for the entire Desktop/Runtime lifetime, not just metadata inspection.
        var handle = CreateFileW(@"\\?\" + path, 0x20081, 3, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero);
        try
        {
            if (handle.IsInvalid || !GetFileInformationByHandleEx(handle, 9, out AttributeTag tag, 8)
                || (tag.Attributes & 0x410) != 0x10) throw new IOException("Development directory identity rejected.");
            string final = FinalPath(handle);
            if (!allowSystemRedirection && !string.Equals(final, path, StringComparison.OrdinalIgnoreCase))
                throw new IOException("Development directory identity rejected.");
            return handle;
        }
        catch { handle.Dispose(); throw; }
    }

    private static void ValidatePath(string path)
    {
        if (path.Length > 30000 || !Regex.IsMatch(path, @"\A[A-Za-z]:\\") || path.Contains('/')
            || Path.GetFullPath(path) != path) throw new IOException("Development local path required.");
        foreach (string part in path[3..].Split('\\'))
            if (part.Length == 0 || part is "." or ".." || part.EndsWith('.') || part.EndsWith(' ')
                || part.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) throw new IOException("Development local path required.");
    }

    [StructLayout(LayoutKind.Sequential)] private struct AttributeTag { public uint Attributes, Tag; }
    [StructLayout(LayoutKind.Sequential)] private struct SecurityAttributes { public uint Length; public IntPtr Descriptor; public int InheritHandle; }
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("shell32.dll", ExactSpelling = true)] private static extern int SHGetKnownFolderPath(in Guid folder, uint flags, SafeAccessTokenHandle token, out IntPtr path);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr attributes, uint disposition, uint flags, IntPtr template);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern bool CreateDirectoryW(string path, ref SecurityAttributes attributes);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle, int kind, out AttributeTag info, uint size);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder path, uint size, uint flags);
}
