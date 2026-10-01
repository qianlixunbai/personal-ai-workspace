using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed class CredentialStore(string? testTarget = null)
{
    internal const string Target = "PersonalAiWorkspace/Desktop/Runtime/127.0.0.1:8765";
    private readonly string target = testTarget ?? Target;
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct Credential
    {
        internal uint Flags, Type;
        internal string? TargetName, Comment;
        internal long LastWritten;
        internal uint BlobSize;
        internal IntPtr Blob;
        internal uint Persist, AttributeCount;
        internal IntPtr Attributes;
        internal string? Alias, UserName;
    }
    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);
    [DllImport("advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredWrite(ref Credential credential, uint flags);
    [DllImport("advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredDelete(string target, uint type, uint flags);
    [DllImport("advapi32.dll")] private static extern void CredFree(IntPtr buffer);

    internal string? Load()
    {
        if (!CredRead(target, 1, 0, out IntPtr pointer))
        {
            if (Marshal.GetLastWin32Error() == 1168) return null;
            throw new DesktopException(DesktopError.CredentialStorage);
        }
        try
        {
            var credential = Marshal.PtrToStructure<Credential>(pointer);
            if (credential.BlobSize != 43 || credential.Blob == IntPtr.Zero) throw new DesktopException(DesktopError.CredentialInvalid);
            byte[] bytes = new byte[43];
            Marshal.Copy(credential.Blob, bytes, 0, bytes.Length);
            var token = Encoding.ASCII.GetString(bytes);
            if (!CredentialFormat.Valid(token)) throw new DesktopException(DesktopError.CredentialInvalid);
            return token;
        }
        finally { CredFree(pointer); }
    }

    internal void Import(string path)
    {
        // Explicit file-picker action only. No ambient search, HTTP pairing endpoint or token arguments.
        try
        {
            path = Path.GetFullPath(path);
            if (path.StartsWith(@"\\", StringComparison.Ordinal) || !File.Exists(path)) throw new DesktopException(DesktopError.CredentialInvalid);
            if (new DriveInfo(Path.GetPathRoot(path)!).DriveType is DriveType.Network or DriveType.Unknown or DriveType.NoRootDirectory)
                throw new DesktopException(DesktopError.CredentialInvalid);
            for (string? current = path; current is not null; current = Path.GetDirectoryName(current))
                if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new DesktopException(DesktopError.CredentialInvalid);
            // Validate the opened file handle and deny concurrent write/delete during import.
            using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            var user = WindowsIdentity.GetCurrent().User!;
            var security = input.GetAccessControl();
            if (!user.Equals(security.GetOwner(typeof(SecurityIdentifier)))) throw new DesktopException(DesktopError.CredentialInvalid);
            foreach (FileSystemAccessRule rule in security.GetAccessRules(true, true, typeof(SecurityIdentifier)))
                if (rule.AccessControlType == AccessControlType.Allow && !user.Equals(rule.IdentityReference)
                    && (rule.FileSystemRights & (FileSystemRights.ReadData | FileSystemRights.WriteData | FileSystemRights.ChangePermissions | FileSystemRights.TakeOwnership)) != 0)
                    throw new DesktopException(DesktopError.CredentialInvalid);
            if (input.Length > 128) throw new DesktopException(DesktopError.CredentialInvalid);
            // Read bounded bytes; no file path/token gets logged on any error path.
            byte[] bytes = new byte[129];
            int count = input.ReadAtLeast(bytes, bytes.Length, throwOnEndOfStream: false);
            if (count > 128) throw new DesktopException(DesktopError.CredentialInvalid);
            var token = Encoding.ASCII.GetString(bytes, 0, count).Trim();
            if (!CredentialFormat.Valid(token)) throw new DesktopException(DesktopError.CredentialInvalid);
            Save(token);
        }
        catch (DesktopException) { throw; }
        catch (Exception) { throw new DesktopException(DesktopError.CredentialInvalid); }
    }

    internal void Save(string token)
    {
        if (!CredentialFormat.Valid(token)) throw new DesktopException(DesktopError.CredentialInvalid);
        byte[] bytes = Encoding.ASCII.GetBytes(token);
        IntPtr blob = Marshal.AllocHGlobal(bytes.Length);
        try
        {
            Marshal.Copy(bytes, 0, blob, bytes.Length);
            var credential = new Credential
            {
                Type = 1, TargetName = target, BlobSize = (uint)bytes.Length, Blob = blob,
                Persist = 2, UserName = "Personal AI Workspace local client"
            };
            if (!CredWrite(ref credential, 0)) throw new DesktopException(DesktopError.CredentialStorage);
        }
        finally
        {
            for (int index = 0; index < bytes.Length; index++) Marshal.WriteByte(blob, index, 0);
            System.Security.Cryptography.CryptographicOperations.ZeroMemory(bytes);
            Marshal.FreeHGlobal(blob);
        }
    }
    internal void Forget()
    {
        if (!CredDelete(target, 1, 0) && Marshal.GetLastWin32Error() != 1168)
            throw new DesktopException(DesktopError.CredentialStorage);
    }
}
