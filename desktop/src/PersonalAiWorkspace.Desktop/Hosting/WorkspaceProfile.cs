using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;

namespace PersonalAiWorkspace.Desktop.Hosting;

internal static class WorkspaceProfile
{
    internal static string CreatePrivateFolder()
    {
        // Fixed account-local location, completely separate from Runtime/auth/backup/project storage.
        string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PersonalAiWorkspace", "MainWorkspaceWebView2");
        Directory.CreateDirectory(root);
        for (string? current = root; current is not null; current = Path.GetDirectoryName(current))
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new IOException("Linked profile path rejected.");
        var user = WindowsIdentity.GetCurrent().User!;
        var security = new DirectorySecurity();
        security.SetOwner(user);
        security.SetAccessRuleProtection(true, false);
        security.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        var directory = new DirectoryInfo(root);
        directory.SetAccessControl(security);
        var actual = directory.GetAccessControl();
        if (!user.Equals(actual.GetOwner(typeof(SecurityIdentifier)))) throw new IOException("Profile owner mismatch.");
        foreach (FileSystemAccessRule rule in actual.GetAccessRules(true, true, typeof(SecurityIdentifier)))
            if (rule.AccessControlType == AccessControlType.Allow && !user.Equals(rule.IdentityReference)) throw new IOException("Profile not account private.");
        return root;
    }
}
