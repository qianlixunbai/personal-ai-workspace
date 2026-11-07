using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Web.WebView2.Core;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using PersonalAiWorkspace.Desktop.Bridge;

// Explicit manual fixture, run from the ordinary desktop host. Real bundled React,
// WebView2, native bridge, RuntimeClient, HTTP and SQLite; synthetic provider only.
// No production source, identity check, default endpoint or user configuration changes.
internal static class MemoryDesktopAcceptanceProbe
{
    private const string Source = "For project Amber, I prefer short bullet points.";
    private static string stage = "INPUT";
    private static string output = "";
    private static void Check(bool value) { if (!value) throw new InvalidOperationException(stage); }
    private static string Json(string value) => JsonSerializer.Serialize(value);

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length != 4) return 2;
        Process? child = null;
        try
        {
            string java = Path.GetFullPath(args[0]), jna = Path.GetFullPath(args[1]);
            string classpath = File.ReadAllText(args[2]).Trim(); output = Path.GetFullPath(args[3]);
            Check(Directory.Exists(output) && !File.Exists(Path.Combine(output, "owned-profile.json")));
            string id = Guid.NewGuid().ToString("N"), root = Mmf3ProfileState.Root(id);
            Check(!Path.Exists(root));
            File.WriteAllText(Path.Combine(output, "owned-profile.json"), JsonSerializer.Serialize(new { id, root, synthetic = true }));
            stage = "PROFILE";
            Mmf3ProfileState.Prepare(id); Mmf3ProfileNative.Provision(id, jna);
            using var pin = Mmf3ProfileState.Pin(id);
            var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
            var start = new ProcessStartInfo(java) { UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = root };
            foreach (string option in new[] { "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Dworkspace.native.development-profile=" + id,
                "-Dworkspace.native.bootstrap=" + Mmf3ProfileNative.Helper,
                "-Djna.boot.library.path=" + Path.Combine(root, "jna-5.17.0-win32-x86-64"), "-Djna.boot.library.name=jnidispatch",
                "-Djna.nosys=true", "-Djna.noclasspath=true", "-Djna.nounpack=true",
                "-Djava.io.tmpdir=" + Path.Combine(root, "evidence"), "-Djna.tmpdir=" + Path.Combine(root, "evidence"),
                "-Dmemory.desktop.fixture=true", "-cp", classpath, "MemoryDesktopIntegrationProbe" }) start.ArgumentList.Add(option);
            using var job = RuntimeProcessJob.Create();
            child = Process.Start(start) ?? throw new IOException(); job.Assign(child);
            File.WriteAllText(Path.Combine(output, "owned-child.json"), JsonSerializer.Serialize(new { pid = child.Id, startedUtc = child.StartTime.ToUniversalTime(), java, synthetic = true }));
            using var stdout = File.Create(Path.Combine(output, "integration.log"));
            using var stderr = File.Create(Path.Combine(output, "integration-errors.log"));
            var log = child.StandardOutput.BaseStream.CopyToAsync(stdout);
            var errors = child.StandardError.BaseStream.CopyToAsync(stderr);
            stage = "HTTP_FIXTURE";
            string ready = Path.Combine(root, "evidence", "desktop-ready.json");
            var clock = Stopwatch.StartNew();
            while (!File.Exists(ready)) { Check(!child.HasExited && clock.Elapsed < TimeSpan.FromSeconds(55)); Thread.Sleep(100); }
            using var fixture = JsonDocument.Parse(File.ReadAllText(ready));
            int port = fixture.RootElement.GetProperty("port").GetInt32();
            string tokenFile = Path.GetFullPath(fixture.RootElement.GetProperty("tokenFile").GetString()!);
            Check(fixture.RootElement.GetProperty("syntheticProvider").GetBoolean() && port is > 1024 and <= 65535);
            Check(tokenFile.StartsWith(Path.Combine(root, "evidence", "api-memory-"), StringComparison.OrdinalIgnoreCase)
                && tokenFile.EndsWith(Path.Combine("credentials", "client-token"), StringComparison.Ordinal));
            using var client = new RuntimeClient(new FixtureTransport(port), () => File.ReadAllText(tokenFile).Trim());
            var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
            using var actions = new NativeActions(client);
            MainWorkspaceWindow? shell = null;
            int result = 10;
            app.Startup += async (_, _) =>
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(190));
                using var watchdog = timeout.Token.Register(() => app.Dispatcher.BeginInvoke(() => { shell?.DisposeImmediately(); app.Shutdown(10); }));
                try
                {
                    stage = "SEED";
                    Check((await client.ListMemoryAsync(new(), timeout.Token)).Total == 0);
                    var seed = await client.CreateMemoryAsync(new(MemoryType.PREFERENCE, "Original Amber preference", Source), timeout.Token);
                    shell = NewWindow(actions, launch); shell.Show();
                    await VerifyWorkflow(shell, actions, client, seed, timeout.Token);
                    stage = "REOPEN";
                    await shell.ShutdownAsync(); Check(shell.Host.CleanupPassed);
                    shell = NewWindow(actions, launch); shell.Show(); await Ready(shell, timeout.Token);
                    await Script(shell, "location.hash='#/memory'");
                    await Wait(() => ScriptIs(shell, "document.querySelectorAll('.memory-select').length===1"), timeout.Token);
                    await Script(shell, "document.querySelector('.memory-select').click()");
                    await Wait(() => ScriptIs(shell, "document.querySelector('#memory-content')?.value==='Updated Amber preference after selection.'"), timeout.Token);
                    Check((await client.ListMemoryAsync(new(), timeout.Token)).Items.Single().Revision == 3);
                    Pass("REOPEN_REAL_WEBVIEW_PERSISTED_REVISION");
                    stage = "ARCHIVE_RESTORE_DELETE";
                    await Click(shell, "归档", timeout.Token);
                    await Wait(async () => (await client.GetMemoryAsync(seed.Id, timeout.Token)).Status == MemoryStatus.ARCHIVED, timeout.Token);
                    Check((await client.RecallMemoryAsync("Amber", null, timeout.Token)).Total == 0);
                    await Click(shell, "恢复", timeout.Token);
                    await Wait(async () => (await client.GetMemoryAsync(seed.Id, timeout.Token)).Status == MemoryStatus.ACTIVE, timeout.Token);
                    Check((await client.RecallMemoryAsync("Amber", null, timeout.Token)).Total == 1);
                    await Click(shell, "删除", timeout.Token); await Click(shell, "永久删除", timeout.Token);
                    await Wait(async () => (await client.ListMemoryAsync(new(), timeout.Token)).Total == 0, timeout.Token);
                    Check((await client.RecallMemoryAsync("Amber", null, timeout.Token)).Total == 0);
                    Pass("UI_ARCHIVE_RESTORE_DELETE_REAL_RECALL");
                    await Capture(shell, "final-memory.png");
                    await shell.ShutdownAsync(); Check(shell.Host.CleanupPassed);
                    result = 0;
                }
                catch (Exception error)
                {
                    Console.WriteLine("DESKTOP_STOP " + stage + " " + error.GetType().Name);
                    File.WriteAllText(Path.Combine(output, "failure.json"), JsonSerializer.Serialize(new { stage, type = error.GetType().Name,
                        frames = new StackTrace(error, true).GetFrames().Select(f => new { method = f.GetMethod()?.Name, line = f.GetFileLineNumber() }).ToArray() }));
                    if (shell?.Host.IsReady == true)
                    {
                        File.WriteAllText(Path.Combine(output, "synthetic-failure-dom.json"), await Script(shell, "document.body.innerText"));
                        await Capture(shell, "failure.png");
                    }
                }
                finally
                {
                    shell?.DisposeImmediately();
                    using var done = new FileStream(Path.Combine(root, "evidence", "desktop-done"), FileMode.CreateNew);
                    using var writer = new StreamWriter(done); writer.Write(result == 0 ? "PASS" : "FAIL");
                    app.Shutdown(result);
                }
            };
            app.Run();
            stage = "OWNED_CLEANUP";
            Check(child.WaitForExit(12000)); Task.WhenAll(log, errors).GetAwaiter().GetResult();
            Check(result == 0 && child.ExitCode == 0);
            string trace = Path.Combine(root, "evidence", "desktop-provider-requests.json");
            File.Copy(trace, Path.Combine(output, "synthetic-provider-requests.json"));
            File.WriteAllText(Path.Combine(output, "result.json"), JsonSerializer.Serialize(new {
                passed = true, syntheticProvider = true, realModel = false, nativeAdmitted = true, javaExited = child.HasExited,
                childPid = child.Id, hotkeysInstalled = false, existingServicesTouched = false }));
            Pass("MEMORY_DESKTOP_ACCEPTANCE"); return 0;
        }
        catch (Exception error) { Console.WriteLine("DESKTOP_STOP " + stage + " " + error.GetType().Name); return 10; }
        finally
        {
            if (child is not null)
            {
                if (!child.HasExited) { child.Kill(entireProcessTree: true); child.WaitForExit(5000); }
                File.WriteAllText(Path.Combine(output, "owned-cleanup.json"), JsonSerializer.Serialize(new { pid = child.Id, exited = child.HasExited }));
                child.Dispose();
            }
        }
    }

    private static MainWorkspaceWindow NewWindow(NativeActions actions, Mmf3AcceptanceLaunch launch)
    {
        var shell = new MainWorkspaceWindow(actions, acceptance: launch) { Title = "Memory acceptance · synthetic data / provider",
            ShowActivated = false, Width = 1280, Height = 860 };
        actions.Window = shell; return shell;
    }
    private static async Task VerifyWorkflow(MainWorkspaceWindow shell, NativeActions actions, RuntimeClient client, MemoryItem seed, CancellationToken ct)
    {
        await Ready(shell, ct);
        stage = "SOURCE_CONVERSATION";
        await Script(shell, "location.hash='#/conversations'"); await Click(shell, "新建对话", ct);
        await Input(shell, "conversation-input", Source, ct); await Click(shell, "发送", ct);
        await Wait(() => ScriptIs(shell, "document.body.innerText.includes('第 1 轮 · 已完成')"), ct);
        var conversation = (await client.ListConversationsAsync(ConversationStatus.ACTIVE, 0, 10, ct)).Items.Single();
        await Click(shell, "保存为记忆草稿", ct);
        stage = "CANDIDATE_DUPLICATE_REVIEW";
        await Click(shell, "生成记忆候选", ct);
        await Wait(() => ScriptIs(shell, "document.querySelector('#candidate-content')?.value===" + Json(Source)), ct);
        Check((await client.ListMemoryAsync(new(), ct)).Total == 1);
        await Click(shell, "检查已有记忆", ct);
        await Wait(() => ScriptIs(shell, "document.body.innerText.includes('已找到正文完全相同的记忆')"), ct);
        Check(await ScriptIs(shell, Button("采用候选到编辑器", false) + ".disabled"));
        await Script(shell, "document.querySelector('.memory-candidate input[type=checkbox]').click()");
        await Click(shell, "采用候选到编辑器", ct);
        Check((await client.GetMemoryAsync(seed.Id, ct)).Revision == 1);
        await Click(shell, "用此草稿修订已有记忆", ct);
        await Script(shell, "document.querySelector('.memory-select').click()");
        await Click(shell, "采用修订到编辑器", ct);
        Check((await client.GetMemoryAsync(seed.Id, ct)).Revision == 1);
        await Click(shell, "保存", ct);
        await Wait(async () => (await client.GetMemoryAsync(seed.Id, ct)).Revision == 2, ct);
        Check((await client.ListMemoryAsync(new(), ct)).Items.Single().Id == seed.Id);
        Pass("CANDIDATE_DUPLICATE_EXPLICIT_REVIEW_SAME_ID_REVISION_SAVE");
        await Capture(shell, "reviewed-memory.png");
        stage = "NATIVE_RECALL_PICKER";
        await Script(shell, "location.hash='#/conversations'");
        // A real modal WPF picker can defer the WebView script completion until it
        // closes. Drive its own controls before awaiting that completion.
        await Wait(() => ScriptIs(shell, "Boolean(" + Button("选择记忆…") + ")"), ct);
        var openPicker = Script(shell, Button("选择记忆…") + ".click()");
        stage = "PICKER_OPEN";
        await Wait(() => Task.FromResult(actions.Picker is not null), ct);
        var picker = actions.Picker!;
        stage = "PICKER_READY";
        await Wait(() => Task.FromResult(picker.RecallButton.IsEnabled && picker.MemoryList.Items.Count > 0), ct);
        stage = "PICKER_QUERY";
        picker.SearchBox.Text = "Amber short";
        picker.RecallButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
        await Wait(() => Task.FromResult(picker.RecallButton.IsEnabled && picker.PageText.Text.StartsWith("相关 ")), ct);
        Check(picker.MemoryList.Items.Count == 1 && !picker.UseButton.IsEnabled && picker.SelectedList.Items.Count == 0);
        stage = "PICKER_PREVIEW";
        picker.MemoryList.SelectedIndex = 0;
        await Wait(() => Task.FromResult(picker.AddButton.IsEnabled), ct);
        Check(picker.PreviewContent.Text == Source);
        stage = "PICKER_CONFIRM";
        picker.AddButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
        Check(picker.SelectedList.Items.Count == 1 && picker.UseButton.IsEnabled);
        picker.UseButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
        await openPicker.WaitAsync(ct);
        stage = "PICKER_BRIDGE_SELECTION";
        await Wait(() => ScriptIs(shell, "document.body.innerText.includes('本次使用 1 条')"), ct);
        await Script(shell, "document.querySelector('.conversation-memory-retention input').click()");
        for (int turn = 2; turn <= 3; turn++)
        {
            await Input(shell, "conversation-input", "Synthetic retained turn " + turn, ct); await Click(shell, "发送", ct);
            await Wait(() => ScriptIs(shell, "document.body.innerText.includes('第 " + turn + " 轮 · 已完成')"), ct);
            var latest = (await client.GetConversationAsync(conversation.Id, 0, 10, ct)).Turns.Single(t => t.Sequence == turn);
            Check(latest.Memories!.Single() == new ConversationSelection(seed.Id, 2, 0));
            Check(await ScriptIs(shell, "document.body.innerText.includes('连续使用 1 条')"));
        }
        Pass("REAL_WPF_RECALL_PREVIEW_EXPLICIT_ADD_TWO_RETAINED_TURNS");
        stage = "STALE_AND_CLEAR";
        await client.UpdateMemoryAsync(seed.Id, new(2, MemoryType.PREFERENCE, "Amber updated", "Updated Amber preference after selection."), ct);
        await Input(shell, "conversation-input", "Synthetic stale attempt", ct); await Click(shell, "发送", ct);
        await Wait(() => ScriptIs(shell, "document.body.innerText.includes('记忆已变更，请重新选择或清除后再发送。')"), ct);
        Check((await client.GetConversationAsync(conversation.Id, 0, 10, ct)).TotalTurns == 3);
        await Click(shell, "清除记忆", ct);
        await Input(shell, "conversation-input", "Synthetic cleared turn", ct); await Click(shell, "发送", ct);
        await Wait(() => ScriptIs(shell, "document.body.innerText.includes('第 4 轮 · 已完成')"), ct);
        var detail = await client.GetConversationAsync(conversation.Id, 0, 10, ct);
        Check(detail.Turns.Single(t => t.Sequence == 4).Memories!.Count == 0);
        Check(detail.Turns.Single(t => t.Sequence == 2).Memories!.Single().Revision == 2);
        Pass("STALE_REJECTED_WITHOUT_TURN_CLEAR_STOPS_FUTURE_REFERENCES");
        await Capture(shell, "conversation-history.png");
    }
    private static async Task Ready(MainWorkspaceWindow shell, CancellationToken ct)
    { stage = "WEBVIEW_READY"; await Wait(() => Task.FromResult(shell.Host.IsReady || shell.Host.StartupFailureCode is not null), ct); Check(shell.Host.IsReady); }
    private static async Task Wait(Func<Task<bool>> condition, CancellationToken ct)
    { var clock = Stopwatch.StartNew(); while (!await condition()) { Check(clock.Elapsed < TimeSpan.FromSeconds(25)); await Task.Delay(100, ct); } }
    private static Task<string> Script(MainWorkspaceWindow shell, string code) => shell.Browser.CoreWebView2.ExecuteScriptAsync(code);
    private static async Task<bool> ScriptIs(MainWorkspaceWindow shell, string code) => await Script(shell, code) == "true";
    private static string Button(string label, bool enabled = true) => "[...document.querySelectorAll('button')].find(b=>b.getClientRects().length>0&&b.textContent===" + Json(label) + (enabled ? "&&!b.disabled" : "") + ")";
    private static async Task Click(MainWorkspaceWindow shell, string label, CancellationToken ct)
    { await Wait(() => ScriptIs(shell, "Boolean(" + Button(label) + ")"), ct); await Script(shell, Button(label) + ".click()"); }
    private static async Task Input(MainWorkspaceWindow shell, string id, string value, CancellationToken ct)
    {
        await Wait(() => ScriptIs(shell, "Boolean(document.getElementById(" + Json(id) + ")?.getClientRects().length)"), ct);
        await Script(shell, "(()=>{const e=document.getElementById(" + Json(id) + ");Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value').set.call(e," + Json(value) + ");e.dispatchEvent(new Event('input',{bubbles:true}));})()");
    }
    private static async Task Capture(MainWorkspaceWindow shell, string name)
    { using var file = File.Create(Path.Combine(output, name)); await shell.Browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, file); }
    private static void Pass(string name) => Console.WriteLine("PASS " + name);

    private sealed class FixtureTransport(int port) : DelegatingHandler(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false, UseCookies = false })
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Check(request.RequestUri!.Scheme == "http" && request.RequestUri.Host == "127.0.0.1" && request.RequestUri.Port == 8765);
            request.RequestUri = new UriBuilder(request.RequestUri) { Port = port }.Uri;
            return base.SendAsync(request, ct);
        }
    }
    private sealed class NativeActions : IWorkspaceNativeActions, IDisposable
    {
        private readonly RuntimeClient client;
        internal MainWorkspaceWindow? Window;
        internal MemorySelectionWindow? Picker;
        public WorkspaceOperations Operations { get; }
        public WorkspaceConversations Conversations { get; }
        public WorkspaceMemory Memory { get; }
        internal NativeActions(RuntimeClient client)
        {
            this.client = client; Operations = new(client, Select, _ => throw new InvalidOperationException("No clipboard in fixture"));
            Conversations = new(client, Select); Memory = new(client);
        }
        private Task<IReadOnlyList<MemorySelection>?> Select(CancellationToken ct)
        {
            ct.ThrowIfCancellationRequested(); Check(Picker is null);
            var picker = new MemorySelectionWindow(client) { Owner = Window, ShowActivated = false }; Picker = picker;
            Console.WriteLine("PICKER_ENTER");
            using var cancel = ct.Register(() => picker.Dispatcher.BeginInvoke(() => { if (!picker.IsClosed) picker.Close(); }));
            try { picker.ShowDialog(); ct.ThrowIfCancellationRequested(); return Task.FromResult(picker.Selection); }
            finally { Console.WriteLine("PICKER_EXIT selection=" + (picker.Selection?.Count ?? -1)); Picker = null; }
        }
        public async Task<ShellStatus> StatusAsync(CancellationToken ct)
        { await client.CheckHealthAsync(ct); await client.CheckCredentialAsync(ct); return new(1, "1.0.0.0", ShellRuntimeState.Available, ShellCredentialState.Ready, "Available", []); }
        public Task<StorageStatus> StorageStatusAsync(CancellationToken ct) => client.GetStorageStatusAsync(ct);
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken ct) => throw new InvalidOperationException("Unrelated native action unavailable in fixture");
        public void Dispose() { Picker?.Close(); Operations.Dispose(); }
    }
}
