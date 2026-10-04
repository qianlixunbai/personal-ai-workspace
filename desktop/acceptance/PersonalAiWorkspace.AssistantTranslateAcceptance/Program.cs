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

namespace PersonalAiWorkspace.AssistantTranslateAcceptance;

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
        cases = JsonDocument.Parse(Environment.GetEnvironmentVariable("M5B_TEST_CASES")!).RootElement.Clone();
        string token = File.ReadAllText(Environment.GetEnvironmentVariable("M5B_TEST_TOKEN_FILE")!).Trim();
        var credentials = new CredentialStore("PersonalAiWorkspace.M5B.Acceptance." + Guid.NewGuid().ToString("N"));
        using var single = new SingleInstance(".M5B.Acceptance." + Guid.NewGuid().ToString("N"));
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
        var progress = Environment.GetEnvironmentVariable("M5B_TEST_PROGRESS");
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
    private static async Task Submit(string kind, string check, string? contains = null)
    {
        var watch = Stopwatch.StartNew();
        await Js(Visible + ".querySelector('.operation-actions .primary').click()");
        await WaitJs(Visible + ".querySelector('.execution-status').textContent.startsWith('Succeeded')", check, 180);
        metrics[check + "ElapsedMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2);
        Require(await Js(Visible + ".querySelector('.operation-result').textContent.length>0") == "true", check + "-plain-result");
        if (contains is not null) Require(await Js(Visible + ".querySelector('.operation-result').textContent.includes(" + JsonSerializer.Serialize(contains) + ")") == "true", check + "-marker-correct");
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
    private static async Task Drive()
    {
        app.ShowWorkspace(); shell = app.Workspace!; await Ready();
        Require(shell.Browser.CoreWebView2.Profile.IsInPrivateModeEnabled, "production-inprivate-profile");
        await Route("assistant"); await Insert("assistant", Case("ask")); await Submit("assistant", "real-react-ask", Case("result"));
        var previousClipboard = Clipboard.GetDataObject();
        try
        {
            await Js(Visible + ".querySelector('.result-card button').click()");
            await WaitJs(Visible + ".querySelector('.result-card [role=status]').textContent.includes('已复制')", "owned-result-copy", 30);
            Require(Clipboard.ContainsText() && Clipboard.GetText().Contains(Case("result"), StringComparison.Ordinal), "native-clipboard-exact-result");
        }
        finally
        {
            if (Clipboard.ContainsText() && Clipboard.GetText().Contains(Case("result"), StringComparison.Ordinal))
            { if (previousClipboard is null) Clipboard.Clear(); else Clipboard.SetDataObject(previousClipboard, true); }
        }
        await ChangeMode("Summarize"); await Insert("assistant", Case("summary")); await Submit("assistant", "real-react-summarize");
        await ChangeMode("Ask");
        var memory = await fixture.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, Case("title"), "The synthetic project marker is " + Case("memory") + "."), default);
        await Select(memory); await Insert("assistant", Case("memoryAsk")); await Submit("assistant", "real-react-explicit-memory-ask", Case("memory"));
        Require(await Js(Visible + ".querySelector('.memory-choice').textContent.includes('No Memory')") == "true", "selection-cleared-at-admission");
        await Insert("assistant", Case("nextAsk")); await Submit("assistant", "next-ordinary-ask-no-memory");
        await Select(memory);
        memory = await fixture.UpdateMemoryAsync(memory.Id, new(memory.Revision, memory.Type, memory.Title, memory.Content), default);
        await Insert("assistant", Case("memoryAsk")); await Js(Visible + ".querySelector('.operation-actions .primary').click()");
        await WaitJs(Visible + ".querySelector('.memory-choice [role=alert]')?.textContent.includes('Selection stale')===true", "real-stale-memory-selection-rejected", 30);
        Require(await Js(Visible + ".querySelector('.operation-actions .primary').disabled") == "true", "stale-blocks-until-explicit-clear");
        await Js(Visible + ".querySelector('.memory-choice button:nth-of-type(2)').click()");
        await Route("translate"); await Insert("translate", Case("translate")); await Submit("translate", "real-react-single-translate");
        await Insert("translate", Case("cancel")); await Js(Visible + ".querySelector('.operation-actions .primary').click()");
        await WaitJs("!" + Visible + ".querySelector('.operation-actions .secondary').disabled", "cancel-owned-operation-enabled", 30);
        await Js(Visible + ".querySelector('.operation-actions .secondary').click()");
        await WaitJs(Visible + ".querySelector('.execution-status').textContent.startsWith('Cancelled')", "real-react-cancel-terminal", 30);
        Require(await Js(Visible + ".querySelector('.operation-result')===null") == "true", "cancel-has-no-fabricated-answer");
        await Route("assistant"); await Insert("assistant", Case("hostile")); await Submit("assistant", "hostile-input-plain-output");
        Require(await Js(Visible + ".querySelectorAll('script,img').length===0") == "true", "hostile-output-no-html-rendering");
        await Insert("assistant", Case("route")); await Js(Visible + ".querySelector('.operation-actions .primary').click()");
        await Route("memory");
        await Js("document.querySelector('a[href=\"#/assistant\"]').click()");
        await WaitJs(Visible + ".querySelector('.execution-status').textContent.startsWith('Succeeded')", "route-switch-keeps-execution");
        Require(await Js("document.getElementById('assistant-input').value===" + JsonSerializer.Serialize(Case("route"))) == "true", "route-keeps-session-composer");
        await Ime();
        metrics["dpiScale"] = VisualTreeHelper.GetDpi(shell).DpiScaleX;
        shell.ZoomInButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Require(shell.Browser.ZoomFactor == 1.25 && await Js("document.documentElement.scrollWidth <= innerWidth") == "true", "business-editor-125-percent-zoom-layout");
        shell.ZoomResetButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        await Js("document.getElementById('assistant-input').focus()");
        Require(await Js("getComputedStyle(document.getElementById('assistant-input')).outlineStyle!=='none'") == "true", "production-editor-visible-focus");
        await NativeRegression();
        var core = shell.Browser.CoreWebView2;
        string session = shell.Host.SessionId; core.Reload();
        await Wait(() => shell.Host.SessionId.Length > 0 && shell.Host.SessionId != session, "reload-new-session"); await Ready();
        Require(await Js("document.getElementById('assistant-input').value==='' && document.querySelector('.operation-result')===null") == "true", "reload-no-personal-content-or-replay");
        Require(await Js("Object.keys(localStorage).every(x=>x==='workspace.theme') && sessionStorage.length===0") == "true", "storage-no-domain-data");
        metrics["desktopWorkingSetBytes"] = Process.GetCurrentProcess().WorkingSet64;
        metrics["webViewBrowserWorkingSetBytes"] = Process.GetProcessById((int)core.BrowserProcessId).WorkingSet64;
        metrics["webViewRuntime"] = core.Environment.BrowserVersionString;
        shell.Close(); await Wait(() => app.Workspace is null, "business-window-close"); Require(shell.Host.CleanupPassed, "business-profile-cleanup");
        app.ShowWorkspace(); shell = app.Workspace!; await Ready();
        Require(await Js("document.getElementById('assistant-input').value==='' && document.querySelector('.operation-result')===null") == "true", "reopen-no-replay-or-personal-content");
        await Insert("assistant", Case("crash"));
        try { await shell.Browser.CoreWebView2.CallDevToolsProtocolMethodAsync("Page.crash", "{}").WaitAsync(TimeSpan.FromSeconds(3)); } catch (Exception) { }
        await Wait(() => shell.FallbackTitle.Text == "工作区暂时不可用", "business-renderer-crash-native-fallback");
        shell.Close(); await Wait(() => app.Workspace is null, "crashed-business-window-close");
        app.ShowWorkspace(); shell = app.Workspace!; await Ready();
        Require(await Js("document.getElementById('assistant-input').value===''") == "true", "post-crash-reopen-no-personal-content");
        await fixture.DeleteMemoryAsync(memory.Id, memory.Revision, default);
        metrics["windowsImeVerified"] = realIme;
    }
    private static async Task ChangeMode(string mode)
    {
        await Js("(()=>{const s=document.getElementById('assistant-mode');s.value=" + JsonSerializer.Serialize(mode) + ";s.dispatchEvent(new Event('change',{bubbles:true}))})()");
        await WaitJs(Visible + ".querySelector('.operation-actions .primary').textContent.includes('" + mode + "')", "mode-" + mode, 30);
    }
    private static async Task NativeRegression()
    {
        await ((IWorkspaceNativeActions)app).OpenAsync(NativeWorkspaceEntry.LegacyAssistant, default);
        var native = (AssistantWindow)app.MainWindow;
        foreach (var operation in new[] { (Index: 1, Input: Case("summary"), Name: "native-summarize"), (Index: 2, Input: Case("nextAsk"), Name: "native-stateless-ask") })
        {
            native.ActionSelector.SelectedIndex = operation.Index; native.InputText.Text = operation.Input;
            native.TranslateButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            await Wait(() => !app.Busy && native.ResultText.Text.Length > 0, operation.Name + "-real-wpf-ollama", 180);
        }
        native.Close(); shell.ReturnFocus();
    }
    private static async Task Ime()
    {
        await Insert("assistant", "");
        await Js("(()=>{window.__realIme={starts:0,ends:0,updates:0,composing:false,premature:0,submissions:0,exactBridge:false};const t=document.getElementById('assistant-input');t.addEventListener('compositionstart',()=>{window.__realIme.starts++;window.__realIme.composing=true});t.addEventListener('compositionupdate',()=>window.__realIme.updates++);t.addEventListener('compositionend',()=>{window.__realIme.ends++;window.__realIme.composing=false});const post=window.chrome.webview.postMessage.bind(window.chrome.webview);window.chrome.webview.postMessage=m=>{if(m.method==='assistant.submit'){window.__realIme.submissions++;if(window.__realIme.composing)window.__realIme.premature++;window.__realIme.exactBridge=m.payload.text===" + JsonSerializer.Serialize(Case("ime")) + "}post(m)}})()");
        bool manual = Environment.GetEnvironmentVariable("M5B_MANUAL_IME") == "1";
        if (manual)
        {
            MessageBox.Show("请在 Assistant 的真实输入框切换微软拼音，通过键盘拼音输入：\n" + Case("ime") + "\n拼音：ben di gong zuo qu ce shi xing he 731\n选词时按一次 Enter 确认候选，确认没有提交；完成后点击 Submit Ask。正文不会进入日志。程序会等待并检查实际 composition 与 exact Runtime 输入。", "M5B Real IME acceptance");
            shell.ReturnFocus(); await Js("document.getElementById('assistant-input').focus()");
            await WaitJs("window.__realIme.starts>0 && window.__realIme.ends>0 && window.__realIme.updates>0 && window.__realIme.premature===0 && window.__realIme.exactBridge && document.getElementById('assistant-input').value===" + JsonSerializer.Serialize(Case("ime"))
                + " && " + Visible + ".querySelector('.execution-status').textContent.startsWith('Succeeded')", "manual-real-ime-composition-committed-submit", 600);
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
                await Js("document.getElementById('assistant-input').focus()"); await Task.Delay(200);
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
                bool exact = await Js("window.__realIme.starts>0 && window.__realIme.ends>0 && window.__realIme.updates>0 && document.getElementById('assistant-input').value===" + JsonSerializer.Serialize(Case("ime"))) == "true";
                if (exact)
                {
                    await Submit("assistant", "real-native-pinyin-operation");
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
