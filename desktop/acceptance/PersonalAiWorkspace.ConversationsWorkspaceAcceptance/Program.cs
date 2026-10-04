using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using PersonalAiWorkspace.Desktop.Bridge;
using Forms = System.Windows.Forms;
using Clipboard = System.Windows.Clipboard;
using MessageBox = System.Windows.MessageBox;
using Button = System.Windows.Controls.Button;

namespace PersonalAiWorkspace.ConversationsWorkspaceAcceptance;

internal static class Program
{
    private static AssistantApp app = null!;
    private static MainWorkspaceWindow shell = null!;
    private static RuntimeClient fixture = null!;
    private static JsonElement cases;
    private static string stage = "configuration";
    private static readonly List<string> checks = [];
    private static readonly Dictionary<string, object> metrics = [];
    private static bool realIme;
    private static int exitCode = 1;
    [DllImport("user32.dll")] private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr first, IntPtr second);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr GetKeyboardLayout(uint thread);
    [STAThread] private static int Main()
    {
        cases = JsonDocument.Parse(Environment.GetEnvironmentVariable("M5C_TEST_CASES")!).RootElement.Clone();
        string token = File.ReadAllText(Environment.GetEnvironmentVariable("M5C_TEST_TOKEN_FILE")!).Trim();
        var credentials = new CredentialStore("PersonalAiWorkspace.M5C.Acceptance." + Guid.NewGuid().ToString("N"));
        using var single = new SingleInstance(".M5C.Acceptance." + Guid.NewGuid().ToString("N"));
        credentials.Save(token); fixture = new RuntimeClient(() => token);
        app = new AssistantApp(single, credentials);
        app.Startup += (_, _) => app.Dispatcher.BeginInvoke(new Action(async () =>
        {
            try { await Drive(); exitCode = 0; }
            catch (Exception) { exitCode = 1; }
            finally
            {
                foreach (Window window in app.Windows.Cast<Window>().ToArray())
                    if (window is not AssistantWindow and not MainWorkspaceWindow) window.Close();
                await app.ExitAsync();
            }
        }), DispatcherPriority.ApplicationIdle);
        try { app.Run(); }
        finally { app.Cleanup(); fixture.Dispose(); credentials.Forget(); }
        Console.WriteLine(JsonSerializer.Serialize(new { result = exitCode == 0 ? "PASS" : "FAIL", check = stage,
            releaseWpf = true, realWebView2 = true, bundledReact = true, realRuntime = true, realOllama = true,
            realWindowsPinyin = realIme, testOwnedWinCred = true, checks, metrics }));
        return exitCode;
    }
    private static string Case(string name) => cases.GetProperty(name).GetString()!;
    private static void Require(bool condition, string check)
    { Stage(check); if (!condition) throw new InvalidOperationException(); checks.Add(check); }
    private static void Stage(string check)
    {
        stage = check;
        var progress = Environment.GetEnvironmentVariable("M5C_TEST_PROGRESS");
        if (progress is not null) File.WriteAllText(progress, JsonSerializer.Serialize(new { check, completedChecks = checks.Count }));
    }
    private static Task<string> Js(string script) => shell.Browser.CoreWebView2.ExecuteScriptAsync(script);
    private static async Task Wait(Func<bool> condition, string check, int seconds = 30)
    {
        Stage(check); var deadline = DateTime.UtcNow.AddSeconds(seconds);
        while (!condition()) { if (DateTime.UtcNow > deadline) throw new TimeoutException(); await Task.Delay(75); }
        checks.Add(check);
    }
    private static async Task WaitJs(string script, string check, int seconds = 180)
    {
        Stage(check); var deadline = DateTime.UtcNow.AddSeconds(seconds);
        while (await Js(script) != "true") { if (DateTime.UtcNow > deadline) throw new TimeoutException(); await Task.Delay(100); }
        checks.Add(check);
    }
    private static async Task Ready()
    {
        await Wait(() => shell.Host.SessionId.Length > 0, "bridge-session-ready");
        await WaitJs("document.querySelector('.operation-status')?.textContent === '工作区已连接'", "react-status-ready", 30);
    }
    private static string Visible => "document.querySelector('.page-content > div:not([hidden])')";
    private static async Task Route(string route)
    {
        var watch = Stopwatch.StartNew();
        await Js("document.querySelector('a[href=\"#/" + route + "\"]').click()");
        await WaitJs("document.querySelector('h1')?.textContent.toLowerCase()==='" + route + "'", "route-" + route, 30);
        if (route is "assistant" or "translate")
            await WaitJs("!" + Visible + ".querySelector('textarea').readOnly", route + "-editor-interactive", 30);
        metrics[route + "FirstInteractionMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
    }
    private static async Task Insert(string kind, string text)
    {
        shell.ReturnFocus();
        await Js("(()=>{const t=document.getElementById('" + kind + "-input');t.focus();t.select()})()");
        // Native WebView input insertion exercises the actual controlled production editor.
        // This is ordinary input/paste coverage, never an IME acceptance claim.
        await shell.Browser.CoreWebView2.CallDevToolsProtocolMethodAsync("Input.insertText", JsonSerializer.Serialize(new { text }));
        await WaitJs("document.getElementById('" + kind + "-input').value===" + JsonSerializer.Serialize(text), kind + "-exact-controlled-editor-value", 15);
    }
    private static async Task Select(MemoryItem memory)
    {
        Task<string> opening = Js(Visible + ".querySelector('.memory-choice button').click()");
        await Wait(() => app.Windows.Cast<Window>().OfType<MemorySelectionWindow>().Any(x => x.IsVisible), "production-native-memory-selector");
        var picker = app.Windows.Cast<Window>().OfType<MemorySelectionWindow>().Single(x => x.IsVisible);
        await Wait(() => picker.SearchButton.IsEnabled, "native-selector-initial-load-complete");
        await picker.PreviewAsync(memory); picker.AddPreview(); picker.ConfirmSelection();
        await opening;
        await WaitJs(Visible + ".querySelector('.memory-choice ol')?.textContent.includes(" + JsonSerializer.Serialize(memory.Title) + ")===true", "react-selected-safe-title", 30);
        Require(await Js(Visible + ".querySelector('.memory-choice').textContent.includes('Revision 1')") == "true", "react-selection-revision-display");
    }

    private static Guid conversationId;
    private static async Task Control(string action)
    {
        using var client = new HttpClient(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromMinutes(2) };
        using var response = await client.PostAsync("http://127.0.0.1:11435/__test/" + action, null);
        Require(response.IsSuccessStatusCode, "fixture-control-" + action);
    }
    private static async Task Click(string label)
    {
        await WaitJs("[...document.querySelectorAll('.conversations-layout button')].some(x=>x.textContent===" + JsonSerializer.Serialize(label) + "&&!x.disabled)", "control-ready", 30);
        await Js("(()=>{const b=[...document.querySelectorAll('.conversations-layout button')].find(x=>x.textContent===" + JsonSerializer.Serialize(label) + ");if(!b||b.disabled)throw Error('controlled');b.click()})()");
    }
    private static async Task Idle()
    { await WaitJs("document.getElementById('conversation-input')!==null && !document.getElementById('conversation-input').readOnly && !document.querySelector('.conversation-detail').getAttribute('aria-busy').includes('true')", "conversation-editor-ready", 30); }
    private static async Task New()
    {
        await Click("New Conversation"); await Idle();
        await WaitJs("document.querySelector('.conversation-history').textContent.includes('发送第一条')", "new-empty-durable-history");
    }
    private static async Task Submit(string check, string? contains = null)
    {
        var watch = Stopwatch.StartNew();
        int previous = (await fixture.GetConversationAsync(conversationId, 0, 10, default)).TotalTurns;
        await Click("Send");
        await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('Turn " + (previous + 1) + " · SUCCEEDED')===true", check, 180);
        metrics[check + "ElapsedMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
        if (contains is not null) Require(await Js("document.querySelector('.conversation-turn:last-child pre:last-of-type').textContent.includes(" + JsonSerializer.Serialize(contains) + ")") == "true", check + "-context-marker-correct");
        await Idle();
    }
    private static async Task PendingSend(string key)
    {
        await Insert("conversation", Case(key)); await Click("Send");
        await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('PENDING')===true", "durable-pending-visible");
    }
    private static async Task SelectByTitle(string title)
    {
        await WaitJs("[...document.querySelectorAll('.conversation-select bdi')].some(x=>x.textContent===" + JsonSerializer.Serialize(title) + ")", "durable-list-title-visible", 30);
        var watch = Stopwatch.StartNew();
        await Js("[...document.querySelectorAll('.conversation-select bdi')].find(x=>x.textContent===" + JsonSerializer.Serialize(title) + ").parentElement.click()");
        await WaitJs("document.querySelector('#conversation-detail-title bdi')?.textContent===" + JsonSerializer.Serialize(title), "durable-selection-detail", 30);
        metrics["latestPageReadyMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
    }
    private static async Task BridgeConflict()
    {
        string request = JsonSerializer.Serialize(new { version = 1, sessionId = shell.Host.SessionId, requestId = Guid.NewGuid(), method = "conversations.delete", payload = new { conversationId } });
        await Js("(()=>{window.__deleteConflict=false;const m=" + request + ";const on=e=>{if(e.data.requestId===m.requestId){window.__deleteConflict=e.data.ok===false&&e.data.error.code==='ConversationConflict';chrome.webview.removeEventListener('message',on)}};chrome.webview.addEventListener('message',on);chrome.webview.postMessage(m)})()");
        await WaitJs("window.__deleteConflict===true", "real-bridge-pending-delete-conflict", 15);
    }
    private static async Task Drive()
    {
        app.ShowWorkspace(); shell = app.Workspace!; await Ready();
        var watch = Stopwatch.StartNew(); await Route("conversations");
        await WaitJs("document.querySelectorAll('.conversation-select').length===10", "initial-active-list-page-only", 30);
        metrics["activeListReadyMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
        watch.Restart(); await Click("Next"); await WaitJs("document.querySelector('.conversation-list .pagination span').textContent.startsWith('2 /') && document.querySelector('.conversation-list [role=tabpanel]').getAttribute('aria-busy')==='false'", "list-next-page", 30);
        metrics["listNextPageMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
        await Click("Previous");
        await New(); await Click("Rename");
        await WaitJs("document.getElementById('conversation-title')!==null", "manual-rename-editor", 15);
        await Js("document.getElementById('conversation-title').select()");
        await shell.Browser.CoreWebView2.CallDevToolsProtocolMethodAsync("Input.insertText", JsonSerializer.Serialize(new { text = Case("title") }));
        await Click("Save title");
        await WaitJs("document.querySelector('#conversation-detail-title bdi').textContent===" + JsonSerializer.Serialize(Case("title")), "manual-rename-durable", 30);
        conversationId = (await fixture.ListConversationsAsync(ConversationStatus.ACTIVE, 0, 10, default)).Items.Single(x => x.Title == Case("title")).Id;
        // Turn one establishes the durable fact. The required context assertion is
        // on turn two, whose input does not repeat the code.
        await Insert("conversation", Case("turn1")); await Submit("real-context-turn-one");
        await Insert("conversation", Case("turn2")); await Submit("real-context-turn-two", Case("context"));
        var memory = await fixture.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, Case("memoryTitle"), "The synthetic project marker is " + Case("memory") + "."), default);
        await Select(memory); await Insert("conversation", Case("memoryAsk")); await Submit("real-explicit-memory-turn", Case("memory"));
        Require(await Js("document.querySelector('.conversation-detail .memory-choice').textContent.includes('No Memory')") == "true", "per-turn-memory-consumed");
        await Insert("conversation", Case("nextAsk")); await Submit("next-turn-default-zero-memory");
        await fixture.DeleteMemoryAsync(memory.Id, memory.Revision, default); await Click("Refresh");
        await WaitJs("document.querySelector('.conversation-history').textContent.includes('Historical Memory × 1')", "dangling-historical-memory-still-loads", 30);
        memory = await fixture.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, Case("memoryTitle"), "The synthetic project marker is " + Case("memory") + "."), default);
        await Select(memory); memory = await fixture.UpdateMemoryAsync(memory.Id, new(memory.Revision, memory.Type, memory.Title, memory.Content), default);
        await Insert("conversation", Case("memoryAsk")); await Click("Send");
        await WaitJs("document.querySelector('.conversation-detail .memory-choice').textContent.includes('Needs review')", "real-stale-memory-needs-review", 30);
        await Click("Clear Memory"); await fixture.DeleteMemoryAsync(memory.Id, memory.Revision, default);
        await Ime();
        var afterIme = await fixture.GetConversationAsync(conversationId, 0, 10, default);
        Require(!realIme || afterIme.Turns.Any(x => x.UserMessage.Content == Case("ime")), "real-ime-exact-durable-user-message");
        await Control("hold"); await PendingSend("cancel"); await BridgeConflict();
        await Click("Cancel pending"); await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('CANCELLED')===true", "real-durable-cancel", 30);
        Require(await Js("document.querySelector('.conversation-turn:last-child h4:last-of-type').textContent==='USER'") == "true", "cancel-no-fabricated-assistant"); await Control("release");
        await Control("fail-provider"); await Insert("conversation", Case("failure")); await Click("Send");
        await WaitJs("document.querySelector('.conversation-turn:last-child').textContent.includes('PROVIDER_UNAVAILABLE')", "real-durable-provider-failure", 30); await Control("release");
        await Control("hold"); await PendingSend("reload"); string oldSession = shell.Host.SessionId;
        shell.Browser.CoreWebView2.Reload(); await Wait(() => shell.Host.SessionId.Length > 0 && shell.Host.SessionId != oldSession, "pending-reload-new-session"); await Ready();
        await Route("conversations"); await SelectByTitle(Case("title"));
        await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('PENDING')===true", "reload-durable-pending-rediscovered", 30);
        await Control("release"); await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('SUCCEEDED')===true", "reload-terminal-recovered", 180);
        await Control("hold"); await PendingSend("reopen");
        shell.Close(); await Wait(() => app.Workspace is null, "pending-window-closed"); Require(shell.Host.CleanupPassed, "pending-window-profile-cleanup");
        app.ShowWorkspace(); shell = app.Workspace!; await Ready(); await Route("conversations"); await SelectByTitle(Case("title"));
        await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('PENDING')===true", "reopen-durable-pending-recovered", 30);
        await Control("release"); await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('SUCCEEDED')===true", "reopen-durable-terminal-recovered", 180);
        await Control("hold"); await PendingSend("archivePending");
        await Click("Archive");
        await WaitJs("document.querySelector('.conversation-actions').textContent.includes('ARCHIVED') && document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('PENDING')===true", "archive-pending-retains-durable-execution", 30);
        Require(await Js("document.getElementById('conversation-input').readOnly && document.querySelector('.conversation-send .primary').disabled") == "true", "archive-pending-new-send-blocked");
        await Control("release");
        await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('SUCCEEDED')===true", "archived-pending-observed-to-success", 180);
        await Click("Unarchive"); await Idle();
        await Control("hold"); await PendingSend("restart");
        shell.Close(); await Wait(() => app.Workspace is null, "pre-restart-shell-closed"); await Control("restart");
        app.ShowWorkspace(); shell = app.Workspace!; await Ready(); await Route("conversations"); await SelectByTitle(Case("title"));
        await WaitJs("document.querySelector('.conversation-turn:last-child').textContent.includes('FAILED') && document.querySelector('.conversation-turn:last-child').textContent.includes('EXECUTION_INTERRUPTED')", "runtime-restart-fail-closed-ui-no-replay", 30);
        await Click("Archive"); await WaitJs("document.querySelector('.conversation-actions').textContent.includes('ARCHIVED')", "real-archive-blocks-send", 30);
        Require(await Js("document.getElementById('conversation-input').readOnly && document.querySelector('.conversation-send .primary').disabled") == "true", "archived-editor-send-disabled");
        await Js("document.getElementById('conversation-tab-ACTIVE').click()"); await Js("document.getElementById('conversation-tab-ARCHIVED').click()"); await SelectByTitle(Case("title"));
        await Click("Unarchive"); await Idle(); await Insert("conversation", Case("continued")); await Submit("real-unarchive-continued-turn");
        metrics["dpiScale"] = VisualTreeHelper.GetDpi(shell).DpiScaleX;
        shell.ZoomInButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Require(shell.Browser.ZoomFactor == 1.25 && await Js("document.documentElement.scrollWidth<=innerWidth") == "true", "conversation-125-percent-no-horizontal-overflow"); shell.ZoomResetButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Require(await Js("Object.keys(localStorage).every(x=>x==='workspace.theme') && sessionStorage.length===0 && location.hash==='#/conversations'") == "true", "no-domain-browser-storage-or-url-identities");
        // Fully isolated capacity fixture, created by the Python runner in SQLite.
        await Js("(()=>{window.__largeDetailBytes=0;chrome.webview.addEventListener('message',e=>{if(e.data.result?.totalTurns===1000&&e.data.result?.page===99)window.__largeDetailBytes=new TextEncoder().encode(JSON.stringify(e.data)).length})})()");
        await Control("capacity-first"); await Click("Refresh"); watch.Restart(); await SelectByTitle(Case("capacityTitle"));
        await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('Turn 1000')===true", "1000-turn-latest-page-bounded", 30);
        Require(await Js("document.querySelectorAll('.conversation-turn').length===10 && [...document.querySelectorAll('.conversation-history pre')].every(x=>x.textContent.length===8192)") == "true", "large-legal-runtime-core-projection-typescript-react-exact");
        metrics["largeTenTurnRenderMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
        metrics["largeDetailSerializedBytes"] = JsonSerializer.Deserialize<int>(await Js("window.__largeDetailBytes"));
        watch.Restart(); await Click("Older"); await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('Turn 990')===true", "real-history-older-page", 30);
        metrics["historyOlderPageMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
        watch.Restart(); await Click("Newer"); await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('Turn 1000')===true", "real-history-newer-page", 30);
        metrics["historyNewerPageMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
        await Control("primary-first"); await Click("Refresh"); await SelectByTitle(Case("title"));
        metrics["desktopWorkingSetBytes"] = Process.GetCurrentProcess().WorkingSet64;
        metrics["webViewBrowserWorkingSetBytes"] = Process.GetProcessById((int)shell.Browser.CoreWebView2.BrowserProcessId).WorkingSet64;
        metrics["webViewRuntime"] = shell.Browser.CoreWebView2.Environment.BrowserVersionString;
        // Original source is made unavailable; restore runs against a separate empty Runtime.
        shell.Close(); await Wait(() => app.Workspace is null, "pre-backup-recovery-shell-closed"); await Control("recover");
        app.ShowWorkspace(); shell = app.Workspace!; await Ready(); await Route("conversations"); await SelectByTitle(Case("title"));
        await Insert("conversation", Case("recovered")); await Submit("real-restored-react-context-continue", Case("context"));
        // Native legacy fallback still reads and sends through the same Runtime domain.
        var legacy = new TaskCompletionSource();
        _ = app.Dispatcher.BeginInvoke(new Action(async () => {
            try { await ((IWorkspaceNativeActions)app).OpenAsync(NativeWorkspaceEntry.Conversations, default); legacy.SetResult(); }
            catch (Exception error) { legacy.SetException(error); }
        }));
        await Wait(() => app.Windows.Cast<Window>().OfType<ConversationWindow>().Any(x => x.IsVisible), "legacy-conversation-window-retained");
        var native = app.Windows.Cast<Window>().OfType<ConversationWindow>().Single(x => x.IsVisible);
        await Wait(() => !native.Busy && native.Detail is not null, "legacy-conversation-durable-load"); native.Close(); await legacy.Task;
        await New(); var disposable = (await fixture.ListConversationsAsync(ConversationStatus.ACTIVE, 0, 10, default)).Items.First(x => x.Title == "New conversation");
        await Click("Delete"); await WaitJs("document.querySelector('dialog').open && document.activeElement.textContent==='Cancel'", "delete-default-safe-keyboard-confirmation", 15);
        await Click("永久删除"); await WaitJs("document.getElementById('conversation-input')===null", "delete-no-ghost-selection", 15);
        try { await fixture.GetConversationAsync(disposable.Id, 0, 10, default); throw new InvalidOperationException(); }
        catch (DesktopException error) { Require(error.Error == DesktopError.ConversationNotFound, "real-delete-runtime-not-found"); }
        await New(); await Insert("conversation", Case("crash"));
        try { await shell.Browser.CoreWebView2.CallDevToolsProtocolMethodAsync("Page.crash", "{}").WaitAsync(TimeSpan.FromSeconds(3)); } catch (Exception) { }
        await Wait(() => shell.FallbackTitle.Text == "工作区暂时不可用", "conversation-renderer-crash-fallback");
        shell.Close(); await Wait(() => app.Workspace is null, "crashed-conversation-window-close");
        app.ShowWorkspace(); shell = app.Workspace!; await Ready();
    }
    private static async Task Ime()
    {
        await Insert("conversation", "");
        await Js("(()=>{window.__realIme={starts:0,ends:0,updates:0,composing:false,premature:0,submissions:0,exactBridge:false};const t=document.getElementById('conversation-input');t.addEventListener('compositionstart',()=>{window.__realIme.starts++;window.__realIme.composing=true});t.addEventListener('compositionupdate',()=>window.__realIme.updates++);t.addEventListener('compositionend',()=>{window.__realIme.ends++;window.__realIme.composing=false});const post=window.chrome.webview.postMessage.bind(window.chrome.webview);window.chrome.webview.postMessage=m=>{if(m.method==='conversations.send'){window.__realIme.submissions++;if(window.__realIme.composing)window.__realIme.premature++;window.__realIme.exactBridge=m.payload.message===" + JsonSerializer.Serialize(Case("ime")) + "}post(m)}})()");
        bool manual = Environment.GetEnvironmentVariable("M5C_MANUAL_IME") == "1";
        if (manual)
        {
            MessageBox.Show("请在 Conversation 的真实输入框切换微软拼音，通过键盘拼音输入：\n" + Case("ime") + "\n拼音：ben di gong zuo qu ce shi xing he 731\n选词时按一次 Enter 确认候选，确认没有提交；完成后点击 Send。正文不会进入日志。程序会等待并检查实际 composition 与 exact Runtime 输入。", "M5C Real IME acceptance");
            shell.ReturnFocus(); await Js("document.getElementById('conversation-input').focus()");
            await WaitJs("window.__realIme.starts>0 && window.__realIme.ends>0 && window.__realIme.updates>0 && window.__realIme.premature===0 && window.__realIme.exactBridge", "manual-real-ime-composition-committed-submit", 600);
            await WaitJs("document.querySelector('.conversation-turn:last-child h3')?.textContent.includes('SUCCEEDED')===true", "manual-real-ime-durable-success");
            realIme = true;
        }
        else
        {
            var original = Forms.InputLanguage.CurrentInputLanguage;
            var chinese = Forms.InputLanguage.InstalledInputLanguages.Cast<Forms.InputLanguage>().FirstOrDefault(x => x.Culture.Name == "zh-CN");
            if (chinese is null) { metrics["windowsImeLimitation"] = "No Chinese input language installed"; return; }
            var focus = Native.FocusWindow(new WindowInteropHelper(shell).Handle);
            var originalChildLayout = GetKeyboardLayout(Native.GetWindowThreadProcessId(focus, out _));
            try
            {
                shell.ReturnFocus(); SetForegroundWindow(new WindowInteropHelper(shell).Handle);
                Forms.InputLanguage.CurrentInputLanguage = chinese;
                PostMessage(Native.FocusWindow(new WindowInteropHelper(shell).Handle), 0x0050, IntPtr.Zero, chinese.Handle);
                await Js("document.getElementById('conversation-input').focus()"); await Task.Delay(200);
                if (Native.GetForegroundWindow() != new WindowInteropHelper(shell).Handle)
                { metrics["windowsImeLimitation"] = "Test window was not foreground; no keys sent"; return; }
                await Keys("zhongwen"); Key(0x0D); await Task.Delay(200);
                Require(await Js("window.__realIme.submissions===0") == "true", "native-candidate-enter-does-not-submit");
                metrics["nativeCompositionObserved"] = await Js("window.__realIme.starts>0") == "true";
                // Clear using physical keyboard, then select genuine native candidates.
                KeyDown(0x11); Key(0x41); KeyUp(0x11); Key(0x08); await Task.Delay(150);
                foreach (string pinyin in new[] { "bendi", "gongzuoqu", "ceshi", "xinghe" })
                { await Keys(pinyin); Key(0x20); await Task.Delay(200); }
                await Keys("731"); await Task.Delay(200);
                bool exact = await Js("window.__realIme.starts>0 && window.__realIme.ends>0 && window.__realIme.updates>0 && document.getElementById('conversation-input').value===" + JsonSerializer.Serialize(Case("ime"))) == "true";
                if (exact)
                {
                    await Submit("real-native-pinyin-operation");
                    Require(await Js("window.__realIme.premature===0 && window.__realIme.submissions===1 && window.__realIme.exactBridge") == "true", "real-ime-exact-react-bridge-input");
                    realIme = true;
                }
                else metrics["windowsImeLimitation"] = "Full native candidate selection did not match the exact synthetic phrase; run --manual-ime";
            }
            finally { PostMessage(focus, 0x0050, IntPtr.Zero, originalChildLayout); Forms.InputLanguage.CurrentInputLanguage = original; }
        }
    }
    private static void KeyDown(byte key) => keybd_event(key, 0, 0, UIntPtr.Zero);
    private static void KeyUp(byte key) => keybd_event(key, 0, 2, UIntPtr.Zero);
    private static void Key(byte key) { KeyDown(key); KeyUp(key); }
    private static async Task Keys(string text)
    {
        foreach (char character in text)
        {
            if (Native.GetForegroundWindow() != new WindowInteropHelper(shell).Handle) throw new InvalidOperationException();
            Key((byte)char.ToUpperInvariant(character)); await Task.Delay(75);
        }
    }
}
