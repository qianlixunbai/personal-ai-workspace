using System.Diagnostics;
using System.IO;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

// Build the existing WPF test project with RuntimeLifecycleProbe=true.
// Run its apphost directly: dotnet-test's injected runtime overrides must not be silently removed.
// The optional gui flow runs the real AssistantApp, bundled WebView and ordinary UI actions.
public static class RuntimeLifecycleProbe
{
#if RUNTIME_LIFECYCLE_PROBE
    [STAThread]
    public static int Main(string[] args)
    {
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        if (args is ["--verify-package-sources"])
        {
            // Packaging contract only: no Profile, ACL, credential, JVM or WPF operation.
            try
            {
                using var sources = TrustedDevelopmentRuntimeSources.Open();
                Assert.Throws<IOException>(() => { using var write = File.OpenWrite(sources.Jar); });
                Console.WriteLine("DEVELOPMENT_PACKAGE_SOURCES_PASS"); return 0;
            }
            catch { Console.WriteLine("DEVELOPMENT_PACKAGE_SOURCES_STOP"); return 10; }
        }
        if (args.Length is < 1 or > 2 || args.Length == 2 && args[1] is not ("close" or "configuration-failure" or "gui")) return 2;
        try
        {
            var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, args[0]]);
            using var single = new SingleInstance(launch.InstanceSuffix);
            Assert.True(single.IsPrimary);
            var app = new AssistantApp(single, acceptance: launch);
            try
            {
                if (args.Length == 2 && args[1] == "gui") VerifyGui(app, launch);
                else VerifyAsync(app, launch, args.Length == 2 ? args[1] : "authentication-failure").GetAwaiter().GetResult();
            }
            finally { app.Cleanup(); }
            Console.WriteLine("REAL_RUNTIME_LIFECYCLE_PROBE_PASS");
            return 0;
        }
        catch { Console.WriteLine("REAL_RUNTIME_PROBE_STOP"); return 10; }
#else
        return 2;
#endif
    }
#endif
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    private static void VerifyGui(AssistantApp app, Mmf3AcceptanceLaunch launch)
    {
        bool passed = false;
        app.Startup += async (_, _) =>
        {
            try
            {
                var clock = Stopwatch.StartNew();
                while (app.Workspace?.Host.SessionId.Length is not > 0)
                { Assert.True(clock.Elapsed < TimeSpan.FromSeconds(60)); await Task.Delay(100); }
                var core = app.Workspace.Browser.CoreWebView2;
                async Task Wait(string expression)
                {
                    var wait = Stopwatch.StartNew();
                    while (await core.ExecuteScriptAsync(expression) != "true")
                    { Assert.True(wait.Elapsed < TimeSpan.FromSeconds(65)); await Task.Delay(100); }
                }
                await core.ExecuteScriptAsync("location.hash='#/settings'");
                const string button = "[...document.querySelectorAll('button')].find(b=>b.textContent==='重新检查存储'&&!b.disabled)";
                await Wait("Boolean(" + button + ")");
                while (!app.DevelopmentRuntime!.Snapshot().CanQueryStorage)
                { Assert.Equal("STARTING", app.DevelopmentRuntime.Snapshot().Runtime); await Task.Delay(100); }
                await core.ExecuteScriptAsync(button + ".click()");
                const string engine = "[...document.querySelectorAll('dt')].find(x=>x.textContent==='当前持久化引擎')?.nextElementSibling?.textContent";
                await Wait(engine + "==='SQLite'&&document.body.innerText.includes('已响应并通过原生认证')");
                await Wait("[...document.querySelectorAll('dt')].find(x=>x.textContent==='Credential')?.nextElementSibling?.textContent==='Ready'");
                Console.WriteLine("REAL_WEBVIEW2_REACT_NATIVE_BEARER_SQLITE_DISPLAY_PASS");
                using (var image = new FileStream(Path.Combine(launch.Root, "evidence", "runtime-storage-sqlite.png"), FileMode.CreateNew))
                    await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
                await app.DevelopmentRuntime.StopAsync();
                await Wait(engine + "==='等待受控查询'&&document.body.innerText.includes('本次受控 Runtime 已退出')");
                Console.WriteLine("REAL_WEBVIEW2_LIFECYCLE_PUSH_CLEARS_SQLITE_DISPLAY_PASS");
                passed = true;
            }
            catch { Console.WriteLine("WEBVIEW2_GUI_PROBE_STOP"); }
            finally { await app.ExitAsync(); }
        };
        app.Run();
        Assert.True(passed);
    }
    private static async Task VerifyAsync(AssistantApp app, Mmf3AcceptanceLaunch launch, string mode)
    {
        Assert.ThrowsAny<IOException>(() => Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, Guid.NewGuid().ToString("N")]));
        if (mode == "configuration-failure")
        {
            // New isolated negative fixture only. No ACL mutation, valid credentials or database.
            using var invalid = new FileStream(Path.Combine(launch.Root, "data", "database.dpapi"), FileMode.CreateNew, FileAccess.Write, FileShare.Read);
            invalid.WriteByte(0); invalid.Flush(true);
        }
        app.StartDevelopmentRuntime(); app.StartDevelopmentRuntime();
        var actions = (IWorkspaceNativeActions)app;
        StorageStartupStatus? startup = null;
        var clock = Stopwatch.StartNew();
        while (clock.Elapsed < TimeSpan.FromSeconds(75))
        {
            startup = await actions.StorageStartupStatusAsync(CancellationToken.None);
            if (startup.CanQueryStorage || startup.Runtime != "STARTING") break;
            await Task.Delay(150);
        }
        Console.WriteLine("OWNER_FINAL_STATE_" + startup?.Runtime + "_" + startup?.Failure);
        if (app.DevelopmentRuntime!.OwnedProcessId is int created) Console.WriteLine("OWNED_JAVA_PID_" + created);
        if (mode == "configuration-failure")
        {
            Assert.Equal("CONFIGURATION_UNTRUSTED", startup?.Failure);
            await app.DevelopmentRuntime!.StopAsync();
            Console.WriteLine("REAL_JAVA_EARLY_EXIT_FIXED_DIAGNOSTIC_AND_CLEANUP_PASS"); return;
        }
        Assert.NotNull(startup); Assert.True(startup.CanQueryStorage);
        int pid = app.DevelopmentRuntime!.OwnedProcessId!.Value;
        using var owned = Process.GetProcessById(pid); // Observation only; never used to kill by PID.
        app.StartDevelopmentRuntime(); Assert.Equal(pid, app.DevelopmentRuntime.OwnedProcessId);
        var shell = await actions.StatusAsync(CancellationToken.None);
        Assert.Equal(ShellRuntimeState.Available, shell.Runtime); Assert.Equal(ShellCredentialState.Ready, shell.Credential);
        Console.WriteLine("REAL_SPRING_HEALTH_AND_NATIVE_BEARER_PASS");
        var storage = await actions.StorageStatusAsync(CancellationToken.None);
        Assert.Equal("SQLITE", storage.Backend); Assert.False(storage.FinanceEnabled);
        Assert.Equal("NOT_CHECKED", storage.ConfigurationTrust);
        Console.WriteLine("REAL_SQLITE_STORAGE_STATUS_FINANCE_DISABLED_PASS");
        const string document = WorkspaceContentPolicy.ProductionOrigin + "/index.html";
        var sent = new List<string>(); using var bridge = new WorkspaceBridge(new WorkspaceContentPolicy(), actions, sent.Add);
        bridge.BeginDocument(document); bridge.Ready(document); sent.Clear();
        string Request(string method) => JsonSerializer.Serialize(new { version=1,sessionId=bridge.SessionId,requestId=Guid.NewGuid().ToString("D"),method,payload=new{} });
        await bridge.ReceiveAsync(document, document, Request("storage.startupStatus"));
        using (var body = JsonDocument.Parse(Assert.Single(sent))) Assert.True(body.RootElement.GetProperty("result").GetProperty("canQueryStorage").GetBoolean());
        sent.Clear(); await bridge.ReceiveAsync(document, document, Request("storage.status"));
        using (var body = JsonDocument.Parse(Assert.Single(sent))) Assert.Equal("SQLITE", body.RootElement.GetProperty("result").GetProperty("backend").GetString());
        Console.WriteLine("REAL_ASSISTANT_APP_SESSION_BRIDGE_STORAGE_PASS");
        sent.Clear(); string old = Request("storage.status"); bridge.BeginDocument(document); bridge.Ready(document); sent.Clear();
        await bridge.ReceiveAsync(document, document, old); Assert.Empty(sent);
        Console.WriteLine("OLD_BRIDGE_SESSION_REJECTED_PASS");
        if (mode == "close")
        {
            app.Cleanup(); Assert.True(owned.WaitForExit(10000));
            Console.WriteLine("REAL_DESKTOP_CLEANUP_FROM_READY_TERMINATES_OWNED_JAVA_PASS");
            using var cancelled = new CancellationTokenSource(); cancelled.Cancel();
            using var client = RuntimeClient.CreateMmf3Acceptance(() => null);
            using var cancellationOwner = new DevelopmentRuntimeOwner(launch, client);
            cancellationOwner.Start(cancelled.Token); await cancellationOwner.StopAsync();
            Assert.Null(cancellationOwner.OwnedProcessId); Assert.Equal("STARTUP_CANCELLED", cancellationOwner.Snapshot().Failure);
            Console.WriteLine("CANCELLED_START_CREATES_NO_CHILD_PASS"); return;
        }

        // Only the new isolated Profile's synthetic bearer is altered, in place, for a real 401.
        // Restore its original bytes and ACL; never touch production credentials or Credential Manager.
        byte[] token = await File.ReadAllBytesAsync(launch.TokenFile);
        try
        {
            byte[] invalid = (byte[])token.Clone(); invalid[0] = invalid[0] == (byte)'A' ? (byte)'B' : (byte)'A';
            await File.WriteAllBytesAsync(launch.TokenFile, invalid); Array.Clear(invalid);
            var denied = await actions.StorageStartupStatusAsync(CancellationToken.None);
            Assert.False(denied.CanQueryStorage); Assert.Equal("NATIVE_AUTHENTICATION_UNAVAILABLE", denied.Failure);
            Assert.Contains(sent, json => json.Contains("storage.lifecycle", StringComparison.Ordinal) && json.Contains("NATIVE_AUTHENTICATION_UNAVAILABLE", StringComparison.Ordinal));
            await Assert.ThrowsAsync<DesktopException>(() => actions.StorageStatusAsync(CancellationToken.None));
            await app.DevelopmentRuntime.StopAsync();
            Assert.True(owned.WaitForExit(10000));
            Assert.False(app.DevelopmentRuntime.Snapshot().CanQueryStorage);
            Console.WriteLine("REAL_BEARER_FAILURE_BLOCKS_SQL_AND_OWNED_CHILD_EXIT_PASS");
        }
        finally { await File.WriteAllBytesAsync(launch.TokenFile, token); Array.Clear(token); }
    }
#endif
    [Fact]
    public static void StartupDiagnosticsUseOnlyExactBoundedProtocol()
    {
        var diagnostic = new RuntimeStartupDiagnostic();
        diagnostic.Accept("exception contains password and CONNECTION_FAILED");
        diagnostic.Accept("WORKSPACE_STARTUP_V1\tUNKNOWN"); Assert.Null(diagnostic.Failure);
        diagnostic.Accept("WORKSPACE_STARTUP_V1\tCONFIGURATION_UNTRUSTED"); Assert.Equal("CONFIGURATION_UNTRUSTED", diagnostic.Failure);
        diagnostic.Accept("WORKSPACE_STARTUP_V1\tCONNECTION_FAILED"); Assert.Equal("STARTUP_CAUSE_UNAVAILABLE", diagnostic.Failure);
    }
}
