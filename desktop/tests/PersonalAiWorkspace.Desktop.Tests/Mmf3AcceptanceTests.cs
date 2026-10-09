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
    [Fact]
    public void CompiledEntryAndRunResourcesFailClosedWithoutOpeningFormalState()
    {
        var main = typeof(AssistantApp).Assembly.GetType("PersonalAiWorkspace.Desktop.Program")!
            .GetMethod("Main", BindingFlags.Static | BindingFlags.NonPublic)!;
        // Malformed helper requests return before any clipboard/window/resource access.
        foreach (string mode in new[] { "--clipboard-snapshot", "--clipboard-read", "--clipboard-restore" })
            Assert.Equal(2, main.Invoke(null, [new[] { mode }]));
#if MMF3_ACCEPTANCE && DEBUG
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
