using System.Security.AccessControl;
using System.IO;
using System.Security.Principal;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class CredentialTests
{
    [Fact]
    public void WindowsCredentialManagerRoundtripAndForgetUseIsolatedTestTarget()
    {
        var store = new CredentialStore("PersonalAiWorkspace.Tests." + Guid.NewGuid().ToString("N"));
        try
        {
            Assert.Null(store.Load());
            store.Save(new string('t', 43));
            Assert.Equal(new string('t', 43), store.Load());
            store.Forget();
            Assert.Null(store.Load());
            Assert.Equal(DesktopError.CredentialInvalid, Assert.Throws<DesktopException>(() => store.Save("invalid")).Error);
        }
        finally { store.Forget(); }
    }

    [Fact]
    public void ExplicitBootstrapRequiresOwnerOnlyFilePermissionsAndRejectsMalformedFile()
    {
        string folder = Path.Combine(Path.GetTempPath(), "PersonalAiWorkspace.Test." + Guid.NewGuid().ToString("N"));
        string path = Path.Combine(folder, "client-token");
        var store = new CredentialStore("PersonalAiWorkspace.Tests." + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        try
        {
            File.WriteAllText(path, new string('t', 43));
            var acl = new FileSecurity();
            var user = WindowsIdentity.GetCurrent().User!;
            acl.SetOwner(user);
            acl.SetAccessRuleProtection(true, false);
            acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl, AccessControlType.Allow));
            new FileInfo(path).SetAccessControl(acl);
            store.Import(path);
            Assert.Equal(new string('t', 43), store.Load());
            acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), FileSystemRights.ReadData, AccessControlType.Allow));
            new FileInfo(path).SetAccessControl(acl);
            Assert.Equal(DesktopError.CredentialInvalid, Assert.Throws<DesktopException>(() => store.Import(path)).Error);
            acl.RemoveAccessRuleAll(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), FileSystemRights.ReadData, AccessControlType.Allow));
            new FileInfo(path).SetAccessControl(acl);
            File.WriteAllText(path, "malformed");
            Assert.Equal(DesktopError.CredentialInvalid, Assert.Throws<DesktopException>(() => store.Import(path)).Error);
        }
        finally { store.Forget(); Directory.Delete(folder, recursive: true); }
    }
}
