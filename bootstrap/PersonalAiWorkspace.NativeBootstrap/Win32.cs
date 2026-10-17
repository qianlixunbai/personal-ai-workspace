using Microsoft.Win32.SafeHandles;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;

[assembly: DefaultDllImportSearchPaths(DllImportSearchPath.System32)]

namespace PersonalAiWorkspace.NativeBootstrap;

internal static class Win32
{
    private const uint ReadControl = 0x20000, ReadAttributes = 0x80, ReadData = 1;
    private const int FullControl = 0x1f01ff;
    // Same privileged ancestor boundary as the preserved ACL candidate; no additional trusted SID.
    private static readonly HashSet<string> Privileged =
    [
        "S-1-5-18", "S-1-5-32-544",
        "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464"
    ];

    internal sealed class User(SafeFileHandle token, SecurityIdentifier sid, bool elevated) : IDisposable
    {
        internal SafeFileHandle Token { get; } = token;
        internal SecurityIdentifier Sid { get; } = sid;
        internal bool Elevated { get; } = elevated;
        public void Dispose() => Token.Dispose();
    }

    internal static User CurrentUser()
    {
        if (!OpenThreadToken(GetCurrentThread(), 8, true, out var token))
        {
            int error = Marshal.GetLastPInvokeError();
            token.Dispose();
            if (error != 1008) throw new Rejected("EFFECTIVE_TOKEN", error);
            Check(OpenProcessToken(GetCurrentProcess(), 8, out token), "PROCESS_TOKEN");
        }
        try
        {
            IntPtr buffer = Marshal.AllocHGlobal(1024);
            try
            {
                Check(GetTokenInformation(token, 1, buffer, 1024, out _), "TOKEN_USER");
                IntPtr pointer = Marshal.ReadIntPtr(buffer);
                if (!IsValidSid(pointer)) throw new Rejected("TOKEN_SID");
                var sid = new SecurityIdentifier(pointer);
                Check(GetTokenInformation(token, 20, buffer, 4, out _), "TOKEN_ELEVATION");
                return new User(token, sid, Marshal.ReadInt32(buffer) != 0);
            }
            finally { Marshal.FreeHGlobal(buffer); }
        }
        catch { token.Dispose(); throw; }
    }

    internal static string Profile(SafeFileHandle token)
    {
        uint capacity = 32768;
        var profile = new StringBuilder((int)capacity);
        Check(GetUserProfileDirectoryW(token, profile, ref capacity), "PROFILE_API");
        return profile.ToString();
    }

    internal static void ValidatePath(string path)
    {
        if (path.Length > 30000 || !Regex.IsMatch(path, @"\A[A-Za-z]:\\", RegexOptions.CultureInvariant)
            || path.Contains('/') || path.Contains('\0') || Path.GetFullPath(path) != path)
            throw new Rejected("PATH");
        foreach (string part in path[3..].Split('\\'))
        {
            if (part.Length == 0 || part is "." or ".." || part.EndsWith('.') || part.EndsWith(' ')
                || part.Any(c => c < 32 || "<>:\"|?*".Contains(c))
                || Regex.IsMatch(part, @"\A(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(\.|\z)",
                    RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
                throw new Rejected("PATH");
        }
    }

    private static string NativePath(string path) => @"\\?\" + path;

    internal static SafeFileHandle Open(string path, bool directory, bool reopenDuringPublish = false)
    {
        // Directories allow read/write sharing but no delete sharing. Files normally deny both writes and deletes.
        var handle = CreateFileW(NativePath(path), ReadControl | ReadAttributes | (directory ? 0 : ReadData),
            reopenDuringPublish ? 7u : directory ? 3u : 1u, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero);
        if (handle.IsInvalid)
        {
            int error = Marshal.GetLastPInvokeError();
            handle.Dispose();
            throw new Rejected(error is 2 or 3 ? "MISSING" : "OPEN", error);
        }
        try { Identity(handle, path, directory); return handle; }
        catch { handle.Dispose(); throw; }
    }

    internal static string Identity(SafeFileHandle handle, string path, bool directory)
    {
        Check(GetFileInformationByHandleEx(handle, 9, out AttributeTag info, 8), "ATTRIBUTES");
        if ((info.Attributes & 0x400) != 0) throw new Rejected("REPARSE");
        if (((info.Attributes & 0x10) != 0) != directory || GetFileType(handle) != 1)
            throw new Rejected("OBJECT_TYPE");
        var name = new StringBuilder(32768);
        uint length = GetFinalPathNameByHandleW(handle, name, (uint)name.Capacity, 0);
        if (length == 0) throw new Rejected("FINAL_PATH", Marshal.GetLastPInvokeError());
        if (length >= name.Capacity || !string.Equals(name.ToString(), NativePath(path), StringComparison.OrdinalIgnoreCase))
            throw new Rejected("PATH_IDENTITY");
        Check(GetFileInformationByHandleEx(handle, 18, out FileId id, 24), "FILE_ID");
        Check(GetFileInformationByHandle(handle, out FileInfo legacy), "FILE_INFO");
        if (!directory && legacy.Links != 1) throw new Rejected("HARDLINK");
        return $"{id.Volume:x16}:{id.Low:x16}{id.High:x16}";
    }

    internal static void CreatePrivateDirectory(string path, SecurityIdentifier sid)
    {
        WithDescriptor(sid, true, attributes =>
        {
            Check(CreateDirectoryW(NativePath(path), ref attributes), "CREATE_DIRECTORY");
            return 0;
        });
    }

    internal static SafeFileHandle CreatePrivateFile(string path, SecurityIdentifier sid)
    {
        return WithDescriptor(sid, false, attributes =>
        {
            // CREATE_NEW + descriptor at creation. DELETE access is for a same-object atomic rename.
            var handle = CreateFilePrivateW(NativePath(path), ReadControl | ReadAttributes | 0x10003,
                1, ref attributes, 1, 0x00200000, IntPtr.Zero);
            if (handle.IsInvalid)
            {
                int error = Marshal.GetLastPInvokeError();
                handle.Dispose();
                throw new Rejected("CREATE_STAGING", error);
            }
            try { Identity(handle, path, false); return handle; }
            catch { handle.Dispose(); throw; }
        });
    }

    private static T WithDescriptor<T>(SecurityIdentifier sid, bool directory, Func<SecurityAttributes, T> create)
    {
        string sddl = $"O:{sid.Value}D:P(A;{(directory ? "OICI" : "")};FA;;;{sid.Value})";
        Check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, out var descriptor, out _), "DESCRIPTOR_CREATE");
        try
        {
            return create(new SecurityAttributes
            {
                Length = (uint)Marshal.SizeOf<SecurityAttributes>(), Descriptor = descriptor, InheritHandle = 0
            });
        }
        finally { Free(descriptor); }
    }

    internal static void Publish(SafeFileHandle stage, string final)
    {
        // x64 FILE_RENAME_INFO offsets: BOOLEAN/union=0, HANDLE=8, DWORD=16, WCHAR[]=20.
        if (IntPtr.Size != 8) throw new Rejected("PLATFORM");
        byte[] name = Encoding.Unicode.GetBytes(NativePath(final));
        byte[] buffer = new byte[20 + name.Length + 2];
        BitConverter.GetBytes((uint)name.Length).CopyTo(buffer, 16);
        name.CopyTo(buffer, 20);
        // Zeroed ReplaceIfExists and RootDirectory; destination never replaces an existing object.
        Check(SetFileInformationByHandle(stage, 3, buffer, (uint)buffer.Length), "PUBLISH");
    }

    private static RawSecurityDescriptor Descriptor(SafeFileHandle handle)
    {
        uint error = GetSecurityInfo(handle, 1, 5, out _, IntPtr.Zero, out _, IntPtr.Zero, out var descriptor);
        if (error != 0) throw new Rejected("SECURITY_READ", (int)error);
        try
        {
            if (descriptor == IntPtr.Zero || !IsValidSecurityDescriptor(descriptor))
                throw new Rejected("DESCRIPTOR_INVALID");
            uint length = GetSecurityDescriptorLength(descriptor);
            if (length < 20 || length > 65536) throw new Rejected("DESCRIPTOR_LENGTH");
            byte[] bytes = new byte[length];
            Marshal.Copy(descriptor, bytes, 0, bytes.Length);
            return new RawSecurityDescriptor(bytes, 0);
        }
        finally { Free(descriptor); }
    }

    internal static void PrivateAcl(SafeFileHandle handle, SecurityIdentifier sid, bool directory)
    {
        var sd = Descriptor(handle);
        if (sd.Owner != sid) throw new Rejected("OWNER");
        const ControlFlags required = ControlFlags.DiscretionaryAclPresent | ControlFlags.DiscretionaryAclProtected;
        if ((sd.ControlFlags & required) != required || sd.DiscretionaryAcl is null)
            throw new Rejected("PROTECTED_DACL");
        var acl = sd.DiscretionaryAcl;
        if (acl.Count != 1 || acl[0] is not CommonAce ace || ace.AceType != AceType.AccessAllowed
            || ace.SecurityIdentifier != sid || ace.AccessMask != FullControl
            || ace.AceFlags != (directory ? AceFlags.ObjectInherit | AceFlags.ContainerInherit : AceFlags.None))
            throw new Rejected("PRIVATE_ACE");
    }

    internal static void AncestorAcl(SafeFileHandle handle, SecurityIdentifier sid)
    {
        var sd = Descriptor(handle);
        bool Trusted(SecurityIdentifier? value) => value is not null && (value == sid || Privileged.Contains(value.Value));
        if (!Trusted(sd.Owner) || sd.DiscretionaryAcl is null
            || (sd.ControlFlags & ControlFlags.DiscretionaryAclPresent) == 0)
            throw new Rejected("ANCESTOR_OWNER_DACL");
        foreach (GenericAce generic in sd.DiscretionaryAcl)
        {
            if ((generic.AceFlags & AceFlags.InheritOnly) != 0 || generic.AceType == AceType.AccessDenied) continue;
            if (generic is not CommonAce ace || ace.AceType != AceType.AccessAllowed)
                throw new Rejected("ANCESTOR_ACE_UNSUPPORTED");
            // GENERIC_ALL/WRITE, DELETE, WRITE_DAC/OWNER and FILE_DELETE_CHILD allow replacement/authority.
            // Deny ACEs are not used to excuse a dangerous Allow. No new trusted SID is inferred.
            if ((ace.AccessMask & 0x500d0040) != 0 && !Trusted(ace.SecurityIdentifier))
                throw new Rejected("ANCESTOR_REPLACEMENT");
        }
    }

    private static void Check(bool result, string reason)
    {
        if (!result) throw new Rejected(reason, Marshal.GetLastPInvokeError());
    }

    private static void Free(IntPtr value)
    {
        if (value != IntPtr.Zero && LocalFree(value) != IntPtr.Zero)
            throw new Rejected("LOCAL_FREE", Marshal.GetLastPInvokeError());
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes { public uint Length; public IntPtr Descriptor; public int InheritHandle; }
    [StructLayout(LayoutKind.Sequential)]
    private struct AttributeTag { public uint Attributes, Tag; }
    [StructLayout(LayoutKind.Sequential)]
    private struct FileId { public ulong Volume, Low, High; }
    [StructLayout(LayoutKind.Sequential)]
    private struct FileInfo
    {
        public uint Attributes, CreationLow, CreationHigh, AccessLow, AccessHigh, WriteLow, WriteHigh;
        public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }

    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentThread();
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenThreadToken(IntPtr thread, uint access, bool openAsSelf, out SafeFileHandle token);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr process, uint access, out SafeFileHandle token);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool GetTokenInformation(SafeFileHandle token, int kind, IntPtr buffer, uint size, out uint used);
    [DllImport("advapi32.dll")] private static extern bool IsValidSid(IntPtr sid);
    [DllImport("userenv.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern bool GetUserProfileDirectoryW(SafeFileHandle token, StringBuilder path, ref uint size);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr attributes, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", EntryPoint = "CreateFileW", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern SafeFileHandle CreateFilePrivateW(string path, uint access, uint share, ref SecurityAttributes attributes, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern bool CreateDirectoryW(string path, ref SecurityAttributes attributes);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle, int kind, out AttributeTag info, uint size);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle, int kind, out FileId info, uint size);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetFileInformationByHandle(SafeFileHandle handle, out FileInfo info);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint GetFileType(SafeFileHandle handle);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder path, uint size, uint flags);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetFileInformationByHandle(SafeFileHandle handle, int kind, byte[] info, uint size);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)] private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(string sddl, uint revision, out IntPtr descriptor, out uint size);
    [DllImport("advapi32.dll")] private static extern uint GetSecurityInfo(SafeFileHandle handle, int kind, uint information, out IntPtr owner, IntPtr group, out IntPtr dacl, IntPtr sacl, out IntPtr descriptor);
    [DllImport("advapi32.dll")] private static extern uint GetSecurityDescriptorLength(IntPtr descriptor);
    [DllImport("advapi32.dll")] private static extern bool IsValidSecurityDescriptor(IntPtr descriptor);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr LocalFree(IntPtr value);
}
