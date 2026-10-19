using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Principal;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class Mmf3AcceptanceTests
{
    // Read-only compiled-artifact check also usable for B1 while its real LocalAppData gate is STOP.
    [Fact]
    public void CompiledVariantIdentityAndCrossModeArgumentsAreIsolated()
    {
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        string profileId = Guid.NewGuid().ToString("N");
        Assert.Equal("开发工作区 · Development-only", Mmf3AcceptanceLaunch.Label);
        Assert.EndsWith(Path.Combine("PersonalAIWorkspace.Development", "Profiles", profileId), Mmf3ProfileState.Root(profileId));
        foreach (string[] arguments in new[] { Array.Empty<string>(), new[] { "--mmf3-acceptance", profileId },
            new[] { Mmf3AcceptanceLaunch.Argument, "invalid" }, new[] { Mmf3AcceptanceLaunch.Argument, profileId, "--root", @"C:\arbitrary" } })
            Assert.ThrowsAny<IOException>(() => Mmf3AcceptanceLaunch.Parse(arguments));
#else
        var assembly = typeof(AssistantApp).Assembly;
        var main = assembly.GetType("PersonalAiWorkspace.Desktop.Program")!
            .GetMethod("Main", BindingFlags.Static | BindingFlags.NonPublic)!;
        string id = Guid.NewGuid().ToString("N");
        foreach (string mode in new[] { "--mmf3-acceptance", "--mmf3-profile-acceptance" })
        {
            Assert.Equal(2, main.Invoke(null, [new[] { mode }]));
            Assert.Equal(2, main.Invoke(null, [new[] { mode, "invalid" }]));
            Assert.Equal(2, main.Invoke(null, [new[] { mode, id, "--root", "C:\\arbitrary" }]));
        }
#if MMF3_ACCEPTANCE && DEBUG
        Assert.NotNull(typeof(RuntimeClient).GetMethod("CreateMmf3Acceptance", BindingFlags.Static | BindingFlags.NonPublic));
        // Constructor derives identity only; Parse/Program reject the absent root without creating it.
        var launch = (Mmf3AcceptanceLaunch)typeof(Mmf3AcceptanceLaunch)
            .GetConstructor(BindingFlags.Instance | BindingFlags.NonPublic, null, [typeof(string)], null)!.Invoke([id]);
        Assert.Null(assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3ProfileState"));
        Assert.Equal(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PersonalAiWorkspace-Mmf3", id), launch.Root);
        Assert.Equal("MMF3 TEST / 18765", Mmf3AcceptanceLaunch.Label);
        Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-profile-acceptance", id }]));
        Assert.Equal($"PersonalAiWorkspace/Desktop/MMF3/{id}/Runtime/127.0.0.1:18765", launch.CredentialTarget);
        Assert.Equal(Path.Combine(launch.Root, "webview2-profile"), launch.Profile);
        Assert.ThrowsAny<IOException>(() => Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]));
        Assert.Equal(2, main.Invoke(null, [Array.Empty<string>()]));
        Assert.Equal(2, main.Invoke(null, [new[] { Mmf3AcceptanceLaunch.Argument, id }]));
        Assert.False(Directory.Exists(launch.Root));
        using var test = RuntimeClient.CreateMmf3Acceptance(() => throw new InvalidOperationException("No credential reads."));
        Transport(test, 18765);
#else
        Assert.Null(assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3AcceptanceLaunch"));
        Assert.Null(assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3ProfileState"));
        Assert.Null(typeof(RuntimeClient).GetMethod("CreateMmf3Acceptance", BindingFlags.Static | BindingFlags.NonPublic));
        foreach (string mode in new[] { "--mmf3-acceptance", "--mmf3-profile-acceptance" })
            Assert.Equal(2, main.Invoke(null, [new[] { mode, id }]));
#endif
#endif
    }

    [Fact]
    public void CompiledEntryAndRunResourcesFailClosedWithoutOpeningFormalState()
    {
        var main = typeof(AssistantApp).Assembly.GetType("PersonalAiWorkspace.Desktop.Program")!
            .GetMethod("Main", BindingFlags.Static | BindingFlags.NonPublic)!;
        // Malformed helper requests return before any clipboard/window/resource access.
        foreach (string mode in new[] { "--clipboard-snapshot", "--clipboard-read", "--clipboard-restore" })
            Assert.Equal(2, main.Invoke(null, [new[] { mode }]));
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        ProfileFixture();
#elif MMF3_ACCEPTANCE && DEBUG
        string id = Guid.NewGuid().ToString("N");
        string parent = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PersonalAiWorkspace-Mmf3");
        string root = Path.GetFullPath(Path.Combine(parent, id));
        // Fixture only: no token, Credential Manager access, WebView environment or app startup.
        Assert.False(Directory.Exists(root));
        foreach (string[] args in new[] { Array.Empty<string>(), new[] { "--mmf3-acceptance" },
            new[] { "--mmf3-acceptance", id.ToUpperInvariant() }, new[] { "--mmf3-acceptance", "../" + id },
            new[] { "--mmf3-acceptance", id, "http://127.0.0.1:8765" }, new[] { "--mmf3-acceptance", id } })
            Assert.Equal(2, main.Invoke(null, [args]));
        Assert.False(Directory.Exists(root));
        Directory.CreateDirectory(root);
        try
        {
            Private(root);
            foreach (string name in new[] { "credentials", "model-state", "data", "webview2-profile", "chrome-profile", "browser-test-extension", "evidence", "logs" })
            { string path = Path.Combine(root, name); Directory.CreateDirectory(path); Private(path); }
            var launch = Mmf3AcceptanceLaunch.Parse(["--mmf3-acceptance", id]);
            Assert.Equal(root, launch.Root);
            Assert.Equal($"PersonalAiWorkspace/Desktop/MMF3/{id}/Runtime/127.0.0.1:18765", launch.CredentialTarget);
            Assert.NotEqual(CredentialStore.Target, launch.CredentialTarget);
            var store = new CredentialStore(launch.CredentialTarget);
            Assert.Equal(launch.CredentialTarget, typeof(CredentialStore).GetField("target", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(store));
            Assert.Equal(Path.Combine(root, "webview2-profile"), WorkspaceProfile.Mmf3PrivateFolder(launch));
            Assert.Empty(Directory.EnumerateFileSystemEntries(launch.Profile));
            Assert.Equal(launch.TokenFile, launch.ValidateTokenImport(Path.Combine(root, "credentials", "..", "credentials", "client-token")));
            foreach (string path in new[] { Path.Combine(parent, "other", "credentials", "client-token"),
                Path.Combine(root, "client-token"), "credentials/client-token", launch.TokenFile + ":stream" })
                Assert.Equal(DesktopError.CredentialInvalid, Assert.Throws<DesktopException>(() => launch.ValidateTokenImport(path)).Error);
            Assert.False(File.Exists(launch.TokenFile));
            using (var single = new SingleInstance(launch.InstanceSuffix))
            {
                Assert.True(single.IsPrimary);
                string name = (string)typeof(SingleInstance).GetField("name", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(single)!;
                Assert.Equal(@"Local\PersonalAiWorkspace.Desktop." + WindowsIdentity.GetCurrent().User!.Value + ".MMF3." + id, name);
                using var activation = EventWaitHandle.OpenExisting(name + ".Open");
            }
            using var normal = new RuntimeClient(() => throw new InvalidOperationException("Must not load credentials."));
            using var test = RuntimeClient.CreateMmf3Acceptance(() => throw new InvalidOperationException("Must not load credentials."));
            Assert.Equal(new Uri("http://127.0.0.1:8765"), RuntimeClient.Endpoint);
            Transport(normal, 8765); Transport(test, 18765);
            Assert.False(new WorkspaceContentPolicy().Development);

            string data = Path.Combine(root, "data");
            Directory.Delete(data);
            Assert.ThrowsAny<IOException>(launch.Validate); // Previously valid descriptor is now stale.
            Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", id }]));
            var start = new ProcessStartInfo("cmd.exe") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (string arg in new[] { "/c", "mklink", "/J", data, Path.Combine(root, "logs") }) start.ArgumentList.Add(arg);
            using (var junction = Process.Start(start)!) { junction.WaitForExit(); Assert.Equal(0, junction.ExitCode); }
            try { Assert.ThrowsAny<IOException>(launch.Validate); Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", id }])); }
            finally { Directory.Delete(data); }
            Directory.CreateDirectory(data); Private(data);
            var security = new DirectoryInfo(data).GetAccessControl();
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), FileSystemRights.ReadData, AccessControlType.Allow));
            new DirectoryInfo(data).SetAccessControl(security);
            Assert.ThrowsAny<IOException>(launch.Validate);
            Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", id }]));
            Private(data);
            security = new DirectoryInfo(data).GetAccessControl(); security.SetAccessRuleProtection(false, true);
            new DirectoryInfo(data).SetAccessControl(security);
            Assert.ThrowsAny<IOException>(launch.Validate);
            Private(data); launch.Validate();
        }
        finally
        {
            // Delete only this newly generated, checked fixture root; never the shared parent.
            Assert.Equal(Path.GetFullPath(Path.Combine(parent, id)), root);
            Assert.StartsWith(Path.GetFullPath(parent) + Path.DirectorySeparatorChar, root, StringComparison.OrdinalIgnoreCase);
            Directory.Delete(root, recursive: true);
        }
#else
        Assert.Null(typeof(AssistantApp).Assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3AcceptanceLaunch"));
        Assert.Null(typeof(RuntimeClient).GetMethod("CreateMmf3Acceptance", BindingFlags.Static | BindingFlags.NonPublic));
        Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", Guid.NewGuid().ToString("N") }]));
#endif
    }

#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    [Fact]
    public void DevelopmentPrivatePolicyAcceptsWindowsAuthoritiesAndRejectsForeignDataAccess()
    {
        var sid = WindowsIdentity.GetCurrent().User!;
        var security = new DirectorySecurity();
        security.SetSecurityDescriptorSddlForm($"O:{sid.Value}D:P(A;OICI;FA;;;{sid.Value})(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)");
        PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.ValidatePrivate(security, sid, directory: true);
        // Normal system/admin ACEs and harmless traversal do not require exact ACE count.
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null),
            FileSystemRights.ReadAttributes | FileSystemRights.Traverse, AccessControlType.Allow));
        PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.ValidatePrivate(security, sid, directory: true);
        foreach (FileSystemRights right in new[] { FileSystemRights.ReadData, FileSystemRights.WriteData,
            FileSystemRights.DeleteSubdirectoriesAndFiles, FileSystemRights.ChangePermissions })
        {
            var unsafeAcl = new DirectorySecurity();
            unsafeAcl.SetSecurityDescriptorBinaryForm(security.GetSecurityDescriptorBinaryForm());
            unsafeAcl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), right, AccessControlType.Allow));
            Assert.Throws<IOException>(() => PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.ValidatePrivate(unsafeAcl, sid, directory: true));
        }
        security.SetOwner(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null));
        Assert.Throws<IOException>(() => PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.ValidatePrivate(security, sid));
    }

    private static void ProfileFixture()
    {
        string[] sdkVariables = ["DOTNET_CLI_TELEMETRY_SESSIONID", "DOTNET_ROOT_X64", "DOTNET_HOST_PATH"];
        var previous = sdkVariables.ToDictionary(name => name, Environment.GetEnvironmentVariable);
        foreach (string name in sdkVariables) Environment.SetEnvironmentVariable(name, null);
        try
        {
            string id = Guid.NewGuid().ToString("N");
            string root = Mmf3ProfileState.Prepare(id);
            Assert.ThrowsAny<IOException>(() => Mmf3ProfileState.Prepare(id));
            Assert.Equal(root, Mmf3ProfileState.Verify(id));
            // Actual production directory-handle mode excludes rename until release.
            using (Mmf3ProfileState.Pin(id))
                Assert.Throws<IOException>(() => Directory.Move(root, root + "-renamed"));
            Mmf3ProfileNative.Provision(id, Environment.GetEnvironmentVariable("MMF3_JNA_ARTIFACT")
                ?? throw new IOException("Explicit reviewed JNA artifact required."));
            var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
            Assert.Equal(root, launch.Root);
            var sid = WindowsIdentity.GetCurrent().User!;
            foreach (string path in new[] { root, Path.Combine(root, "data") })
            {
                var actual = new DirectoryInfo(path).GetAccessControl();
                Assert.Equal(sid, actual.GetOwner(typeof(SecurityIdentifier)));
                Assert.True(actual.AreAccessRulesProtected);
                Assert.Equal(3, actual.GetAccessRules(true, true, typeof(SecurityIdentifier)).Count);
            }
            string[] variables = ["USERPROFILE", "HOME", "LOCALAPPDATA"];
            var old = variables.ToDictionary(name => name, Environment.GetEnvironmentVariable);
            try
            {
                foreach (string name in variables) Environment.SetEnvironmentVariable(name, @"Z:\untrusted");
                Assert.Equal(root, Mmf3ProfileState.Verify(id));
            }
            finally { foreach (string name in variables) Environment.SetEnvironmentVariable(name, old[name]); }
            string data = Path.Combine(root, "data");
            var security = new DirectoryInfo(data).GetAccessControl();
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null),
                FileSystemRights.ReadData, AccessControlType.Allow));
            new DirectoryInfo(data).SetAccessControl(security);
            Assert.ThrowsAny<IOException>(launch.Validate);
            Assert.ThrowsAny<IOException>(() => Mmf3ProfileNative.Verify(launch));
            security.RemoveAccessRuleSpecific(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null),
                FileSystemRights.ReadData, AccessControlType.Allow));
            new DirectoryInfo(data).SetAccessControl(security); // Only this test's fresh synthetic child.
            Directory.Delete(data);
            var start = new ProcessStartInfo("cmd.exe") { UseShellExecute = false, CreateNoWindow = true };
            foreach (string arg in new[] { "/c", "mklink", "/J", data, Path.Combine(root, "logs") }) start.ArgumentList.Add(arg);
            using (var junction = Process.Start(start)!) { junction.WaitForExit(); Assert.Equal(0, junction.ExitCode); }
            Assert.ThrowsAny<IOException>(launch.Validate); // Reparse remains rejected; fixture is retained.
        }
        finally { foreach (string name in sdkVariables) Environment.SetEnvironmentVariable(name, previous[name]); }
    }

#endif

#if MMF3_ACCEPTANCE && DEBUG
    private static void Private(string path)
    {
        var user = WindowsIdentity.GetCurrent().User!;
        var acl = new DirectorySecurity(); acl.SetOwner(user); acl.SetAccessRuleProtection(true, false);
        acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(path).SetAccessControl(acl);
    }
    private static void Transport(RuntimeClient client, int port)
    {
        var http = (HttpClient)typeof(RuntimeClient).GetField("http", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(client)!;
        Assert.Equal(new Uri($"http://127.0.0.1:{port}"), http.BaseAddress);
        var handler = Assert.IsType<HttpClientHandler>(typeof(HttpMessageInvoker).GetField("_handler", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(http));
        Assert.False(handler.UseProxy); Assert.False(handler.AllowAutoRedirect); Assert.False(handler.UseCookies);
    }
#endif
}
