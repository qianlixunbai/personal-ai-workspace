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
#if MMF3_PROFILE_ACCEPTANCE
        Assert.NotNull(assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3ProfileState"));
        Assert.Equal(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            ".personal-ai-workspace-mmf3-tests", id), launch.Root);
        Assert.Equal("MMF3 PROFILE TEST / 18765", Mmf3AcceptanceLaunch.Label);
        Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", id }]));
#else
        Assert.Null(assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3ProfileState"));
        Assert.Equal(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PersonalAiWorkspace-Mmf3", id), launch.Root);
        Assert.Equal("MMF3 TEST / 18765", Mmf3AcceptanceLaunch.Label);
        Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-profile-acceptance", id }]));
#endif
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
        ProfileFixture(main);
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
    private static void ProfileFixture(MethodInfo main)
    {
        string id = Guid.NewGuid().ToString("N");
        string root = Mmf3ProfileState.Root(id);
        var sid = WindowsIdentity.GetCurrent().User!;
        Assert.ThrowsAny<IOException>(() => Mmf3ProfileState.Verify(id));
        Assert.Equal(2, main.Invoke(null, [new[] { Mmf3AcceptanceLaunch.Argument, id }]));
        Assert.False(Directory.Exists(root));
        foreach (string[] args in new[] { Array.Empty<string>(), new[] { Mmf3AcceptanceLaunch.Argument },
            new[] { Mmf3AcceptanceLaunch.Argument, new string('A', 32) },
            new[] { Mmf3AcceptanceLaunch.Argument, "../" + id },
            new[] { "--mmf3-acceptance", id },
            new[] { Mmf3AcceptanceLaunch.Argument, id, "http://127.0.0.1:8765" } })
            Assert.ThrowsAny<IOException>(() => Mmf3AcceptanceLaunch.Parse(args));

        // All creation is descriptor-at-create. Retain this unique empty fixture as review evidence.
        Assert.Equal(root, Mmf3ProfileState.Prepare(id));
        Assert.ThrowsAny<IOException>(() => Mmf3ProfileState.Prepare(id));
        Assert.Equal(root, Mmf3ProfileState.Verify(id));
        foreach (string path in new[] { Path.GetDirectoryName(root)!, root }.Concat(
            new[] { "credentials", "model-state", "data", "webview2-profile", "chrome-profile", "browser-test-extension", "evidence", "logs" }
                .Select(name => Path.Combine(root, name))))
        {
            var actual = new DirectoryInfo(path).GetAccessControl(AccessControlSections.Owner | AccessControlSections.Access);
            Assert.Equal(sid, actual.GetOwner(typeof(SecurityIdentifier)));
            Assert.True(actual.AreAccessRulesProtected);
            var ace = Assert.Single(actual.GetAccessRules(true, true, typeof(SecurityIdentifier)).Cast<FileSystemAccessRule>());
            Assert.Equal(sid, ace.IdentityReference);
            Assert.Equal(FileSystemRights.FullControl, ace.FileSystemRights);
            Assert.Equal(InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, ace.InheritanceFlags);
            Assert.False(ace.IsInherited);
        }
        var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
        // Reject the legacy argument even with an otherwise valid Profile root (Parse only, no GUI).
        Assert.ThrowsAny<IOException>(() => Mmf3AcceptanceLaunch.Parse(["--mmf3-acceptance", id]));
        string[] variables = ["USERPROFILE", "HOME", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA"];
        var previous = variables.ToDictionary(name => name, Environment.GetEnvironmentVariable);
        try
        {
            foreach (string name in variables) Environment.SetEnvironmentVariable(name, "Z:\\untrusted-profile");
            Assert.Equal(root, Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]).Root);
        }
        finally { foreach (string name in variables) Environment.SetEnvironmentVariable(name, previous[name]); }
        Assert.Equal(Path.Combine(root, "webview2-profile"), WorkspaceProfile.Mmf3PrivateFolder(launch));
        Assert.NotEqual(CredentialStore.Target, launch.CredentialTarget);
        var store = new CredentialStore(launch.CredentialTarget); // Never Load/Import/CredRead/CredWrite.
        Assert.Equal(launch.CredentialTarget, typeof(CredentialStore).GetField("target", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(store));
        Assert.Equal(".MMF3." + id, launch.InstanceSuffix);
        using var client = RuntimeClient.CreateMmf3Acceptance(() => throw new InvalidOperationException("No credential reads."));
        Transport(client, 18765);
        Assert.False(new WorkspaceContentPolicy().Development);
        Assert.Equal(launch.TokenFile, launch.ValidateTokenImport(launch.TokenFile));
        foreach (string path in new[] { "credentials/client-token", launch.TokenFile + ":stream",
            Path.Combine(root, "client-token"), Path.Combine(Path.GetDirectoryName(root)!, Guid.NewGuid().ToString("N"), "credentials", "client-token") })
            Assert.Equal(DesktopError.CredentialInvalid, Assert.Throws<DesktopException>(() => launch.ValidateTokenImport(path)).Error);
        Assert.False(File.Exists(launch.TokenFile));

        string data = Path.Combine(root, "data");
        var descriptor = new RawSecurityDescriptor(new DirectoryInfo(data).GetAccessControl(
            AccessControlSections.Owner | AccessControlSections.Access).GetSecurityDescriptorBinaryForm(), 0);
        var policy = typeof(Mmf3ProfileState).GetMethod("ValidateDescriptor", BindingFlags.Static | BindingFlags.NonPublic)!;
        // Owner mismatch: actual descriptor with ONLY its owner field substituted; no foreign-owner OS claim.
        descriptor.Owner = new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null);
        Assert.IsAssignableFrom<IOException>(Assert.Throws<TargetInvocationException>(() => policy.Invoke(null, [descriptor, sid, true])).InnerException);
        var security = new DirectoryInfo(data).GetAccessControl(AccessControlSections.Access);
        security.SetAccessRuleProtection(false, true);
        new DirectoryInfo(data).SetAccessControl(security);
        Assert.ThrowsAny<IOException>(launch.Validate);
        Assert.Equal(2, main.Invoke(null, [new[] { Mmf3AcceptanceLaunch.Argument, id }]));
        Private(data);
        security = new DirectoryInfo(data).GetAccessControl(AccessControlSections.Access);
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null),
            FileSystemRights.DeleteSubdirectoriesAndFiles, AccessControlType.Allow));
        new DirectoryInfo(data).SetAccessControl(security);
        Assert.ThrowsAny<IOException>(launch.Validate);
        // Same real fixture descriptor as an ancestor exercises the distinct replacement-authority predicate.
        descriptor = new RawSecurityDescriptor(new DirectoryInfo(data).GetAccessControl(
            AccessControlSections.Owner | AccessControlSections.Access).GetSecurityDescriptorBinaryForm(), 0);
        Assert.IsAssignableFrom<IOException>(Assert.Throws<TargetInvocationException>(() => policy.Invoke(null, [descriptor, sid, false])).InnerException);
        Private(data);
        Directory.Delete(data);
        Assert.ThrowsAny<IOException>(launch.Validate);
        var start = new ProcessStartInfo("cmd.exe") { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (string arg in new[] { "/c", "mklink", "/J", data, Path.Combine(root, "logs") }) start.ArgumentList.Add(arg);
        using (var junction = Process.Start(start)!) { junction.WaitForExit(); Assert.Equal(0, junction.ExitCode); }
        try { Assert.ThrowsAny<IOException>(launch.Validate); }
        finally { Directory.Delete(data); }
        typeof(Mmf3ProfileState).GetMethod("CreatePrivateDirectory", BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, [data, sid]);
        launch.Validate();
        File.WriteAllText(Path.Combine(root, "evidence", "focused-fixture.json"), System.Text.Json.JsonSerializer.Serialize(new
        { RunId = id, Root = root, CurrentSid = sid.Value, Endpoint = "http://127.0.0.1:18765", launch.CredentialTarget,
            OwnerMismatch = "descriptor predicate only", CredentialManager = "not invoked", GuiRuntimeChrome = "not started" }));
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
