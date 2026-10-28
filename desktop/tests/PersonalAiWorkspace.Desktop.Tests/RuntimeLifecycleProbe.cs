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
public static partial class RuntimeLifecycleProbe
{
#if RUNTIME_LIFECYCLE_PROBE
    [STAThread]
    public static int Main(string[] args)
    {
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        if (args is ["--x1-selection-fixture"]) return RunX1SelectionFixture();
        if (args is ["--prepare-profile"])
        {
            string preparation = "SOURCES";
            try
            {
                using var sources = TrustedDevelopmentRuntimeSources.Open();
                string id = Guid.NewGuid().ToString("N");
                preparation = "PRIVATE_PROFILE";
                Mmf3ProfileState.Prepare(id);
                preparation = "NATIVE_PROVISION";
                Mmf3ProfileNative.Provision(id, sources.JnaJar);
                Console.WriteLine("SYNTHETIC_PROFILE_CREATED_" + id);
                return 0;
            }
            catch (Exception error) { Console.WriteLine($"SYNTHETIC_PROFILE_STOP stage={preparation} hresult=0x{error.HResult:X8}");
                if (error.Message.StartsWith("Development ", StringComparison.Ordinal)) Console.WriteLine(error.Message);
                return 10; }
        }
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
        bool postgresql = args.Length == 3 && args[1] is "gui-postgresql" or "gui-postgresql-reopen" or "gui-postgresql-storage" or "gui-postgresql-storage-reopen" or "gui-postgresql-conversation" or "gui-postgresql-conversation-reopen" or "gui-postgresql-conversation-recover"
            && System.Text.RegularExpressions.Regex.IsMatch(args[2], "\\A[a-f0-9]{12}\\z");
        if (!postgresql && (args.Length is < 1 or > 2 || args.Length == 2 && args[1] is not ("close" or "restart" or "configuration-failure" or "gui" or "gui-close" or "gui-database" or "gui-environment-failure" or "gui-ready-timeout" or "gui-x1" or "gui-x1-native" or "gui-finance" or "gui-finance-reopen" or "gui-finance-isolation" or "gui-finance-next2" or "gui-finance-next3" or "gui-finance-next4" or "gui-finance-next5" or "gui-finance-next6" or "gui-finance-next6-bob" or "gui-finance-next7" or "gui-finance-next7-bob" or "gui-finance-next8" or "gui-finance-next8-bob" or "gui-finance-next8-ui" or "gui-finance-next9" or "gui-finance-next9-bob" or "gui-finance-next10" or "gui-finance-next10-bob"))) return 2;
        string phase = "PROFILE_PARSE";
        try
        {
            var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, args[0]]);
            phase = "PROFILE_PIN";
            using var paths = Mmf3ProfileState.Pin(launch.RunId);
            using var single = new SingleInstance(launch.InstanceSuffix);
            Assert.True(single.IsPrimary);
            phase = "APP_CREATE";
            var app = new AssistantApp(single, acceptance: launch);
            try
            {
                phase = "GUI_OR_OWNER";
                if (args.Length == 2 && args[1] is "gui-x1" or "gui-x1-native") VerifyX1Gui(app, args[1] == "gui-x1-native");
                else if (args.Length == 2 && args[1] is "gui-finance-next2" or "gui-finance-next4") VerifyFinanceNextGui(app, launch);
                else if (args.Length == 2 && args[1].StartsWith("gui-finance", StringComparison.Ordinal)) VerifyFinanceGui(app, launch, args[1]);
                else if (postgresql && args[1].StartsWith("gui-postgresql-conversation", StringComparison.Ordinal)) VerifyPostgresqlConversationGui(app, launch, args[1].EndsWith("-reopen", StringComparison.Ordinal), args[1].EndsWith("-recover", StringComparison.Ordinal));
                else if (postgresql) VerifyPostgresqlGui(app, launch, args[2], args[1].EndsWith("-reopen", StringComparison.Ordinal), args[1].StartsWith("gui-postgresql-storage", StringComparison.Ordinal));
                else if (args.Length == 2 && args[1] is "gui" or "gui-close" or "gui-database" or "gui-environment-failure" or "gui-ready-timeout") VerifyGui(app, launch, args[1]);
                else VerifyAsync(app, launch, args.Length == 2 ? args[1] : "authentication-failure").GetAwaiter().GetResult();
            }
            finally { app.Cleanup(); }
            Console.WriteLine("REAL_RUNTIME_LIFECYCLE_PROBE_PASS");
            return 0;
        }
        catch (Exception error)
        {
            string code = error.Message switch {
                "Development private ACL rejected." => "PRIVATE_ACL",
                "Development directory identity rejected." => "DIRECTORY_IDENTITY",
                "MMF3 native admission STOP (Development-only)." => "NATIVE_ADMISSION",
                _ => "UNKNOWN" };
            Console.WriteLine($"REAL_RUNTIME_PROBE_STOP stage={phase} code={code} hresult=0x{error.HResult:X8}"); return 10;
        }
#else
        return 2;
#endif
    }
#endif
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    private static void VerifyGui(AssistantApp app, Mmf3AcceptanceLaunch launch, string mode)
    {
        bool passed = false;
        Process? owned = null;
        app.Startup += async (_, _) =>
        {
            try
            {
                var clock = Stopwatch.StartNew();
                while (app.Workspace?.Host.IsReady is not true && app.Workspace?.Host.StartupFailureCode is null)
                { Assert.True(clock.Elapsed < TimeSpan.FromSeconds(40)); await Task.Delay(100); }
                if (app.DevelopmentRuntime!.OwnedProcessId is int pid) owned = Process.GetProcessById(pid);
                if (mode is "gui-environment-failure" or "gui-ready-timeout")
                {
                    string expectedCode = mode == "gui-environment-failure" ? "INITIALIZATION_FAILED" : "STARTUP_TIMEOUT";
                    string expectedStage = mode == "gui-ready-timeout" ? "NAVIGATION_COMPLETED" : "PROFILE_VALIDATED";
                    Assert.Equal(expectedCode, app.Workspace!.Host.StartupFailureCode);
                    Assert.Equal(expectedStage, app.Workspace.Host.StartupStage);
                    Assert.False(app.Workspace.Host.IsReady);
                    Assert.True(app.Workspace.FallbackVisible);
                    Assert.Contains(expectedCode + " / " + expectedStage, app.Workspace.FallbackMessage.Text);
                    Console.WriteLine("REAL_STARTUP_FAILURE_EXPLICIT_NATIVE_FALLBACK_PASS");
                    passed = true; return;
                }
                Assert.True(app.Workspace!.Host.IsReady);
                Assert.False(app.Workspace.FallbackVisible);
                var core = app.Workspace.Browser.CoreWebView2;
                Assert.Equal(WorkspaceContentPolicy.ProductionOrigin + "/index.html", core.Source);
                Assert.Equal("true", await core.ExecuteScriptAsync("document.querySelector('#root').childElementCount>0&&document.styleSheets.length>0&&!document.body.innerText.includes('工作区页面暂时无法显示')"));
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
                if (mode == "gui-database")
                {
                    await core.ExecuteScriptAsync("[...document.querySelectorAll('button')].find(b=>b.textContent==='打开原生数据库设置'&&!b.disabled).click()");
                    DatabaseSettingsWindow? dialog = null;
                    var nativeWait = Stopwatch.StartNew();
                    while (dialog is null)
                    {
                        Assert.True(nativeWait.Elapsed < TimeSpan.FromSeconds(10));
                        dialog = app.Windows.OfType<DatabaseSettingsWindow>().SingleOrDefault();
                        await Task.Delay(100);
                    }
                    var panel = Assert.IsType<System.Windows.Controls.StackPanel>(dialog.Content);
                    var password = Assert.Single(panel.Children.OfType<System.Windows.Controls.PasswordBox>());
                    Assert.Empty(password.Password);
                    while (!panel.Children.OfType<System.Windows.Controls.TextBlock>().Any(t => t.Text.Contains("尚未保存数据库配置；当前默认 SQLite。", StringComparison.Ordinal)))
                    { Assert.True(nativeWait.Elapsed < TimeSpan.FromSeconds(50)); await Task.Delay(100); }
                    dialog.Close();
                    await Wait(engine + "==='SQLite'");
                    Console.WriteLine("REAL_REACT_NATIVE_DATABASE_WINDOW_PASSWORDBOX_TRUSTED_STATE_AND_RETURN_PASS");
                }
                using (var image = new FileStream(Path.Combine(launch.Root, "evidence", $"runtime-storage-sqlite-{DateTime.UtcNow:yyyyMMddHHmmssfff}.png"), FileMode.CreateNew))
                    await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
                Assert.True(app.DevelopmentRuntime.Snapshot().CanQueryStorage);
                Console.WriteLine("REAL_GUI_READY_BEFORE_NORMAL_EXIT");
                if (mode == "gui")
                {
                    await app.DevelopmentRuntime.StopAsync();
                    await Wait(engine + "==='等待受控查询'&&document.body.innerText.includes('本次受控 Runtime 已退出')");
                    Console.WriteLine("REAL_WEBVIEW2_LIFECYCLE_PUSH_CLEARS_SQLITE_DISPLAY_PASS");
                }
                passed = true;
            }
            catch { Console.WriteLine("WEBVIEW2_GUI_PROBE_STOP"); }
            finally
            {
                try
                {
                    await app.ExitAsync();
                    if (owned is not null)
                    {
                        Assert.True(owned.WaitForExit(10000));
                        Console.WriteLine("REAL_GUI_NORMAL_EXIT_OWNED_JAVA_CLEANUP_PASS");
                    }
                }
                catch { passed = false; Console.WriteLine("REAL_GUI_NORMAL_EXIT_STOP"); app.Shutdown(10); }
                finally { owned?.Dispose(); }
            }
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
        if (mode == "restart")
        {
            string oldToken = CredentialStore.ReadPrivateFile(launch.TokenFile, requireProtected: true);
            using var staleClient = RuntimeClient.CreateMmf3Acceptance(() => oldToken);
            await staleClient.CheckCredentialAsync(CancellationToken.None);
            await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
            Assert.True(owned.WaitForExit(10000));
            clock.Restart();
            while (!app.DevelopmentRuntime.Snapshot().CanQueryStorage)
            {
                Assert.True(clock.Elapsed < TimeSpan.FromSeconds(75));
                Assert.Equal("STARTING", app.DevelopmentRuntime.Snapshot().Runtime);
                await Task.Delay(150);
            }
            Assert.NotEqual(pid, app.DevelopmentRuntime.OwnedProcessId);
            var rejected = await Assert.ThrowsAsync<DesktopException>(() => staleClient.CheckCredentialAsync(CancellationToken.None));
            Assert.Equal(DesktopError.Unauthorized, rejected.Error);
            Assert.Equal("SQLITE", (await actions.StorageStatusAsync(CancellationToken.None)).Backend);
            Assert.Contains(sent, json => json.Contains("storage.lifecycle", StringComparison.Ordinal) && json.Contains("STARTING", StringComparison.Ordinal));
            using var restarted = Process.GetProcessById(app.DevelopmentRuntime.OwnedProcessId!.Value);
            await app.DevelopmentRuntime.StopAsync();
            Assert.True(restarted.WaitForExit(10000));
            Console.WriteLine("REAL_RESTART_OLD_JAVA_EXIT_OLD_BEARER_DENIED_NEW_SESSION_SQLITE_AND_CLEANUP_PASS");
            return;
        }
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
