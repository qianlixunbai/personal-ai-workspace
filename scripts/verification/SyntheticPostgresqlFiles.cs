using Microsoft.Win32.SafeHandles;
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using PersonalAiWorkspace.WindowsSecurity;

// Only loaded by the local synthetic launcher/build tooling, never by Desktop/Runtime.
public sealed class SyntheticPostgresqlFiles : IDisposable
{
    private readonly List<IDisposable> pins = new();
    private readonly SecurityIdentifier user = WindowsIdentity.GetCurrent().User!;

    public string Profile(string id) => WindowsProfileSecurity.State(id, create: false);

    public string Directory(string path, bool privateRoot = false)
    {
        pins.Add(WindowsProfileSecurity.HoldDirectories(path));
        using var handle = Open(path, directory: true, immutable: false);
        Private(handle, privateRoot);
        return Identity(handle);
    }

    public FileStream File(string path, bool immutable = true)
    {
        pins.Add(WindowsProfileSecurity.HoldDirectories(Path.GetDirectoryName(path)!));
        var handle = Open(path, directory: false, immutable);
        try
        {
            Private(handle, protectedAcl: false);
            var stream = new FileStream(handle, FileAccess.Read);
            pins.Add(stream);
            return stream;
        }
        catch { handle.Dispose(); throw; }
    }

    public void Tree(string root)
    {
        // Validate every data file, not only the top-level ACL. Hold directories against rename.
        Directory(root);
        foreach (string path in System.IO.Directory.EnumerateFileSystemEntries(root))
        {
            var attributes = System.IO.File.GetAttributes(path);
            if ((attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("LINK_REJECTED");
            if ((attributes & FileAttributes.Directory) != 0) Tree(path);
            else
            {
                using var handle = Open(path, directory: false, immutable: false);
                Private(handle, protectedAcl: false);
            }
        }
    }

    public static string FileIdentity(FileStream file) => Identity(file.SafeFileHandle);

    private void Private(SafeFileHandle handle, bool protectedAcl)
    {
        using var borrowed = new SafeFileHandle(handle.DangerousGetHandle(), ownsHandle: false);
        using var stream = new FileStream(borrowed, FileAccess.Read, 1);
        var acl = stream.GetAccessControl();
        var raw = new RawSecurityDescriptor(acl.GetSecurityDescriptorBinaryForm(), 0);
        var rules = acl.GetAccessRules(true, true, typeof(SecurityIdentifier));
        if (!user.Equals(acl.GetOwner(typeof(SecurityIdentifier))) || !acl.AreAccessRulesCanonical
            || protectedAcl && !acl.AreAccessRulesProtected || raw.DiscretionaryAcl?.Count != 1 || rules.Count != 1)
            throw new IOException("SYNTHETIC_ACL_REJECTED");
        var rule = (FileSystemAccessRule)rules[0]!;
        if (!user.Equals(rule.IdentityReference) || rule.AccessControlType != AccessControlType.Allow
            || rule.FileSystemRights != FileSystemRights.FullControl || (rule.PropagationFlags & PropagationFlags.InheritOnly) != 0)
            throw new IOException("SYNTHETIC_ACL_REJECTED");
    }

    // Binary sources need path/type/link pins; they are reviewed build inputs, not private data.
    public FileStream Source(string path)
    {
        pins.Add(WindowsProfileSecurity.HoldDirectories(Path.GetDirectoryName(path)!));
        var stream = new FileStream(Open(path, directory: false, immutable: true), FileAccess.Read);
        pins.Add(stream);
        return stream;
    }

    private static SafeFileHandle Open(string path, bool directory, bool immutable)
    {
        var handle = CreateFileW(path, 0x80000000, immutable ? 1u : 3u, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero);
        try
        {
            if (handle.IsInvalid || !GetFileInformationByHandle(handle, out var info)
                || (info.Attributes & 0x400) != 0 || ((info.Attributes & 0x10) != 0) != directory
                || !directory && info.Links != 1) throw new IOException("FILE_IDENTITY_REJECTED");
            return handle;
        }
        catch { handle.Dispose(); throw; }
    }

    private static string Identity(SafeFileHandle handle)
    {
        if (!GetFileInformationByHandle(handle, out var info)) throw new IOException("FILE_IDENTITY_REJECTED");
        return $"{info.Volume:X8}:{info.IndexHigh:X8}{info.IndexLow:X8}";
    }

    public void Dispose() { for (int i = pins.Count - 1; i >= 0; i--) pins[i].Dispose(); pins.Clear(); }

    [StructLayout(LayoutKind.Sequential)] private struct Information
    {
        public uint Attributes; public System.Runtime.InteropServices.ComTypes.FILETIME Creation, Access, Write;
        public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", ExactSpelling = true, SetLastError = true)]
    private static extern bool GetFileInformationByHandle(SafeFileHandle handle, out Information information);
}
