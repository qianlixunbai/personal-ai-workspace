#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

// A navigation preference, never an authority: callers must still parse/verify the Profile.
internal static class DevelopmentWorkspaceSelection
{
    internal const string ChooseArgument = "--choose-development-workspace";
    private const string FileName = "last-workspace";

    internal static string? Read()
    {
        string folder = Path.GetDirectoryName(WindowsProfileSecurity.Parent())!;
        if (!Path.Exists(folder)) return null; // First run; only Profile Prepare creates directories.
        using var pins = WindowsProfileSecurity.HoldDirectories(folder);
        ValidateFolder(folder);
        try
        {
            using var input = Open(Path.Combine(folder, FileName));
            byte[] bytes = new byte[33];
            int count = input.ReadAtLeast(bytes, bytes.Length, throwOnEndOfStream: false);
            if (count != 32) throw new IOException("Invalid saved workspace.");
            foreach (byte value in bytes.AsSpan(0, count))
                if (value is not (>= (byte)'0' and <= (byte)'9') and not (>= (byte)'a' and <= (byte)'f'))
                    throw new IOException("Invalid saved workspace.");
            return Encoding.ASCII.GetString(bytes, 0, count);
        }
        catch (FileNotFoundException) { return null; }
    }

    internal static void Save(string id)
    {
        _ = WindowsProfileSecurity.Root(id); // Same ID syntax and OS-owned path resolution.
        string folder = Path.GetDirectoryName(WindowsProfileSecurity.Parent())!;
        using var pins = WindowsProfileSecurity.HoldDirectories(folder);
        ValidateFolder(folder);
        string destination = Path.Combine(folder, FileName);
        try { using var existing = Open(destination); }
        catch (FileNotFoundException) { }
        string temporary = Path.Combine(folder, ".last-workspace-" + Guid.NewGuid().ToString("N"));
        try
        {
            using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None))
            {
                // This non-secret preference inherits the pinned, validated private app directory.
                output.Write(Encoding.ASCII.GetBytes(id));
                output.Flush(flushToDisk: true);
            }
            File.Move(temporary, destination, overwrite: true); // Same-volume atomic publication.
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    private static void ValidateFolder(string folder)
    {
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        WindowsProfileSecurity.ValidatePrivate(new DirectoryInfo(folder).GetAccessControl(), user.User!, directory: true);
    }

    private static FileStream Open(string path)
    {
        if ((File.GetAttributes(path) & (FileAttributes.ReparsePoint | FileAttributes.Directory)) != 0)
            throw new IOException("Invalid saved workspace file.");
        var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        try
        {
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            var security = input.GetAccessControl();
            // Windows rename may reapply parent inheritance. The private parent is held and
            // validated above; validate every effective grant without changing the on-disk ACL.
            security.SetAccessRuleProtection(true, preserveInheritance: true);
            WindowsProfileSecurity.ValidatePrivate(security, user.User!, directory: false);
            return input;
        }
        catch { input.Dispose(); throw; }
    }
}
#endif
