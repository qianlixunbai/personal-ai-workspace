using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using PersonalAiWorkspace.Desktop;
using PersonalAiWorkspace.Desktop.Bridge;
using Forms = System.Windows.Forms;

namespace PersonalAiWorkspace.MainWorkspaceAcceptance;

internal static class Program
{
    private static string stage = "configuration";
    private static readonly List<string> checks = [];
    private static AssistantApp app = null!;
    private static MainWorkspaceWindow shell = null!;
    private static readonly Dictionary<string, object> metrics = [];
    private static int result = 1;
    [DllImport("user32.dll")] private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
    [DllImport("imm32.dll")] private static extern IntPtr ImmGetDefaultIMEWnd(IntPtr window);
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] private static extern IntPtr GetKeyboardLayout(uint thread);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint first, uint second, bool attach);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr window);

    [STAThread] private static int Main()
    {
        var credentials = new CredentialStore("PersonalAiWorkspace.M5A.Acceptance." + Guid.NewGuid().ToString("N"));
        using var single = new SingleInstance(".M5A.Acceptance." + Guid.NewGuid().ToString("N"));
        credentials.Save(File.ReadAllText(Environment.GetEnvironmentVariable("M5A_TEST_TOKEN_FILE")!).Trim());
        app = new AssistantApp(single, credentials);
        app.Startup += (_, _) => app.Dispatcher.BeginInvoke(new Action(async () =>
        {
            try { await Drive(); result = 0; }
            catch (Exception) { result = 1; }
            finally
            {
                foreach (Window window in app.Windows.Cast<Window>().ToArray())
                    if (window is not AssistantWindow and not MainWorkspaceWindow) window.Close();
                await app.ExitAsync();
            }
        }), DispatcherPriority.ApplicationIdle);
        try { app.Run(); }
        finally { app.Cleanup(); credentials.Forget(); }
        Console.WriteLine(JsonSerializer.Serialize(new { result = result == 0 ? "PASS" : "FAIL", check = stage,
            releaseWpf = true, realWebView2 = true, bundledReact = true, realRuntime = true, testOwnedWinCred = true, checks, metrics }));
        return result;
    }
    private static void Require(bool value, string check)
    { stage = check; if (!value) throw new InvalidOperationException(); checks.Add(check); }
    private static async Task Wait(Func<bool> condition, string check, int seconds = 25)
    {
        stage = check; var until = DateTime.UtcNow.AddSeconds(seconds);
        while (!condition()) { if (DateTime.UtcNow > until) throw new TimeoutException(); await Task.Delay(50); }
        checks.Add(check);
    }
    private static Task<string> Js(string script) => shell.Browser.CoreWebView2.ExecuteScriptAsync(script);
    private static async Task WaitJs(string script, string check)
    {
        stage = check; var until = DateTime.UtcNow.AddSeconds(25);
        while (await Js(script) != "true") { if (DateTime.UtcNow > until) throw new TimeoutException(); await Task.Delay(60); }
        checks.Add(check);
    }
    private static async Task Ready()
    {
        await Wait(() => shell.Host.SessionId.Length > 0, "bridge-session-created");
        await WaitJs("document.querySelector('.operation-status')?.textContent === '工作区已连接'", "react-safe-status-ready");
        Require(!shell.FallbackVisible, "native-loading-surface-dismissed");
    }
    private static async Task OpenEntry(string method, Type? expected)
    {
        stage = method;
        Task<string> request = Js("window.chrome.webview.postMessage({version:1,sessionId:" + JsonSerializer.Serialize(shell.Host.SessionId)
            + ",requestId:crypto.randomUUID(),method:" + JsonSerializer.Serialize(method) + ",payload:{}})");
        if (expected is not null)
        {
            await Wait(() => app.Windows.Cast<Window>().Any(w => w.GetType() == expected && w.IsVisible), method + "-real-window");
            var window = app.Windows.Cast<Window>().First(w => w.GetType() == expected && w.IsVisible);
            await Task.Delay(150); window.Close();
        }
        else await Task.Delay(150);
        await request;
        Require(app.MainWindow is AssistantWindow assistant && assistant.IsVisible, method + "-native-surface");
    }
    private static async Task Drive()
    {
        Require(app.TrayVisible && app.TrayMenu?.Items.Count == 4, "one-production-tray-functional");
        var readyTime = Stopwatch.StartNew();
        app.TrayMenu!.Items[0].PerformClick();
        await Wait(() => app.Workspace is not null, "production-tray-main-workspace-entry");
        shell = app.Workspace!; await Ready();
        metrics["shellStatusReadyMs"] = Math.Round(readyTime.Elapsed.TotalMilliseconds, 2);
        var core = shell.Browser.CoreWebView2;
        Require(core.Source.StartsWith("https://workspace.personal-ai.invalid/index.html", StringComparison.Ordinal), "trusted-bundled-origin");
        Require(core.Profile.IsInPrivateModeEnabled && !core.Profile.IsPasswordAutosaveEnabled && !core.Profile.IsGeneralAutofillEnabled, "private-profile-autofill-disabled");
        Require(!core.Settings.AreDevToolsEnabled && !core.Settings.AreHostObjectsAllowed && !core.Settings.AreDefaultContextMenusEnabled, "release-devtools-hostobjects-contextmenu-disabled");
        metrics["webViewRuntime"] = core.Environment.BrowserVersionString;
        metrics["initializationMs"] = Math.Round(shell.Host.InitializationMilliseconds, 2);
        metrics["navigationReadyMs"] = Math.Round(shell.Host.FirstReadyMilliseconds, 2);
        metrics["desktopWorkingSetBytes"] = Process.GetCurrentProcess().WorkingSet64;
        metrics["webViewBrowserWorkingSetBytes"] = Process.GetProcessById((int)core.BrowserProcessId).WorkingSet64;
        var dpi = VisualTreeHelper.GetDpi(shell); metrics["dpiScale"] = dpi.DpiScaleX;
        foreach (string route in new[] { "assistant", "conversations", "memory", "translate", "settings" })
        {
            await Js("document.querySelector('a[href=\"#/" + route + "\"]').click()");
            await WaitJs("document.querySelector('h1')?.textContent.toLowerCase() === '" + route + "'", "sidebar-" + route);
        }
        await OpenEntry("native.openLegacyAssistant", null);
        await OpenEntry("native.openConversations", typeof(ConversationWindow));
        await OpenEntry("native.openMemory", typeof(MemoryWindow));
        await OpenEntry("native.openBrowserPairing", typeof(BrowserPairingWindow));
        await OpenEntry("native.openMemoryBackup", typeof(MemoryBackupWindow));
        await OpenEntry("native.openWorkspaceBackup", typeof(WorkspaceBackupWindow));
        await OpenEntry("native.openCredentialFlow", null);
        Require(((AssistantWindow)app.MainWindow).ImportButton.IsKeyboardFocusWithin, "credential-flow-native-import-focus");
        await HotkeyRegression();
        shell.ReturnFocus(); await Task.Delay(100);
        await WaitJs("document.hasFocus()", "wpf-to-webview-focus-return");
        await Js("document.querySelector('a[href=\"#/memory\"]').focus()");
        keybd_event(0x0D, 0, 0, UIntPtr.Zero); keybd_event(0x0D, 0, 2, UIntPtr.Zero);
        await WaitJs("document.querySelector('h1')?.textContent === 'Memory'", "keyboard-enter-sidebar-navigation");
        keybd_event(0x09, 0, 0, UIntPtr.Zero); keybd_event(0x09, 0, 2, UIntPtr.Zero);
        await Task.Delay(100);
        metrics["focusedElement"] = await Js("document.activeElement.tagName");
        metrics["focusOutline"] = await Js("getComputedStyle(document.activeElement).outlineStyle");
        Require(await Js("getComputedStyle(document.activeElement).outlineStyle !== 'none'") == "true", "visible-keyboard-focus");
        shell.ZoomInButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Require(shell.Browser.ZoomFactor == 1.25 && await Js("document.documentElement.scrollWidth <= innerWidth") == "true", "125-percent-zoom-layout");
        shell.ZoomResetButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        await Js("document.querySelector('.theme-button').click()");
        Require(await Js("Object.keys(localStorage).length === 1 && ['light','dark'].includes(localStorage.getItem('workspace.theme'))") == "true", "storage-theme-only");
        await Js("window.__idb=null; indexedDB.databases().then(x=>window.__idb=x.length); window.__workers=null; navigator.serviceWorker.getRegistrations().then(x=>window.__workers=x.length)");
        await WaitJs("window.__idb === 0 && window.__workers === 0", "no-indexeddb-or-serviceworker");
        await Js("window.__blockedFetch=false; fetch('http://127.0.0.1:8765/actuator/health').catch(()=>window.__blockedFetch=true)");
        await WaitJs("window.__blockedFetch===true", "production-direct-runtime-fetch-blocked");
        await Js("window.__cspFrames=0; document.addEventListener('securitypolicyviolation',e=>{if(e.violatedDirective==='frame-src')window.__cspFrames++}); const f=document.createElement('iframe');f.src='https://external.invalid/';document.body.append(f)");
        await WaitJs("window.__cspFrames > 0", "production-csp-iframe-blocked");
        // Test the second native boundary with CSP temporarily bypassed through a harness-only SDK call.
        await core.CallDevToolsProtocolMethodAsync("Page.enable", "{}");
        await core.CallDevToolsProtocolMethodAsync("Page.setBypassCSP", "{\"enabled\":true}");
        string bypassSession = shell.Host.SessionId;
        core.Reload(); await Wait(() => shell.Host.SessionId.Length > 0 && shell.Host.SessionId != bypassSession, "test-only-csp-bypass-reload"); await Ready();
        int frames = shell.Host.BlockedFrames;
        await Js("const f=document.createElement('iframe');f.src='https://workspace.personal-ai.invalid/index.html';document.body.append(f)");
        await Wait(() => shell.Host.BlockedFrames > frames, "native-frame-navigation-blocked");
        int popups = shell.Host.BlockedPopups;
        await Js("window.open('https://external.invalid/popup')");
        await Wait(() => shell.Host.BlockedPopups > popups, "native-popup-blocked");
        int downloads = shell.Host.BlockedDownloads;
        await Js("const a=document.createElement('a');a.href=URL.createObjectURL(new Blob(['synthetic download']));a.download='synthetic.txt';a.click()");
        await Wait(() => shell.Host.BlockedDownloads > downloads, "native-download-blocked");
        int permissions = shell.Host.BlockedPermissions;
        await Js("window.__permission=null;Notification.requestPermission().then(x=>window.__permission=x)");
        await Wait(() => shell.Host.BlockedPermissions > permissions, "native-permission-request-denied");
        await WaitJs("window.__permission === 'denied'", "permission-denied-in-page");
        await core.CallDevToolsProtocolMethodAsync("Page.setBypassCSP", "{\"enabled\":false}");
        int navigations = shell.Host.BlockedNavigations;
        core.Navigate("https://external.invalid/navigation");
        await Wait(() => shell.Host.BlockedNavigations > navigations, "native-external-navigation-blocked");
        Require(core.Source.StartsWith("https://workspace.personal-ai.invalid/", StringComparison.Ordinal), "blocked-navigation-preserves-document");
        await Js("window.__responses=[];window.chrome.webview.addEventListener('message',e=>window.__responses.push(e.data))");
        foreach (string malformed in new[] {
            "{version:1,sessionId:'fake-session',requestId:crypto.randomUUID(),method:'shell.bootstrap',payload:{}}",
            "{version:1,sessionId:" + JsonSerializer.Serialize(shell.Host.SessionId) + ",requestId:crypto.randomUUID(),method:'native.openWindow',payload:{path:'C:\\private'}}",
            "{version:1,sessionId:" + JsonSerializer.Serialize(shell.Host.SessionId) + ",requestId:crypto.randomUUID(),method:'shell.bootstrap',payload:{bearer:true}}" })
            await Js("window.chrome.webview.postMessage(" + malformed + ")");
        await Task.Delay(200); Require(await Js("window.__responses.length === 0") == "true", "hostile-session-generic-payload-dropped");
        string session = shell.Host.SessionId;
        core.Reload(); await Wait(() => shell.Host.SessionId.Length > 0 && shell.Host.SessionId != session, "reload-rotates-session"); await Ready();

        // Synthetic sensitive text is placed in the renderer only; no domain content belongs in the M5A shell.
        string[] markers = JsonSerializer.Deserialize<string[]>(Environment.GetEnvironmentVariable("M5A_TEST_MARKERS")!)!;
        await Js("const t=document.createElement('textarea');t.id='synthetic-ime';t.value=" + JsonSerializer.Serialize(string.Join(" ", markers) + " 中文输入") + ";t.autocomplete='off';document.body.append(t);t.focus();t.dispatchEvent(new CompositionEvent('compositionstart',{data:'中'}));t.dispatchEvent(new CompositionEvent('compositionend',{data:'中文'}))");
        Require(await Js("document.getElementById('synthetic-ime').value.endsWith('中文输入')") == "true", "synthetic-chinese-composition-event-baseline");
        await NativeIme();
        await Js("document.getElementById('synthetic-ime').remove()");
        await Js("document.querySelector('a[href=\"#/assistant\"]').click()"); await Task.Delay(150);
        await Js("window.scrollTo(0,0)");
        string evidence = Environment.GetEnvironmentVariable("M5A_TEST_EVIDENCE_DIR")!;
        using (var image = File.Create(Path.Combine(evidence, "m5a-shell.png"))) await core.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, image);
        shell.Close(); await Wait(() => app.Workspace is null, "webview-close-disposes-window");
        Require(shell.Host.CleanupPassed, "profile-browsing-data-cleanup");
        app.TrayMenu.Items[0].PerformClick(); shell = app.Workspace!; await Ready();
        Require(shell.Host.SessionId != session, "close-reopen-new-session");
        await StaleResponse();
        await FailureFallback();
        Require(app.TrayVisible, "tray-survives-webview-failure");
        checks.Add("real-windows-shell-acceptance-complete");
    }
    private static async Task HotkeyRegression()
    {
        var input = new TextBox { Text = "Hello, world!", FontSize = 22 };
        var fixture = new Window { Title = "Synthetic selection acceptance", Width = 420, Height = 160, Content = input, Owner = app.MainWindow, Topmost = true };
        fixture.Show(); fixture.Activate(); input.Focus(); input.SelectAll();
        metrics["foregroundWindowPresent"] = Native.GetForegroundWindow() != IntPtr.Zero;
        metrics["selectionFixtureActive"] = fixture.IsActive;
        metrics["nativeAssistantEnabled"] = app.MainWindow.IsEnabled;
        await Wait(() => {
            uint currentThread = GetCurrentThreadId();
            uint foregroundThread = Native.GetWindowThreadProcessId(Native.GetForegroundWindow(), out _);
            bool attached = foregroundThread != 0 && currentThread != foregroundThread && AttachThreadInput(currentThread, foregroundThread, true);
            try { SetForegroundWindow(new WindowInteropHelper(fixture).Handle); fixture.Activate(); input.Focus(); }
            finally { if (attached) AttachThreadInput(currentThread, foregroundThread, false); }
            return Native.GetForegroundWindow() == new WindowInteropHelper(fixture).Handle;
        }, "synthetic-selection-foreground");
        uint clipboardBefore = Native.GetClipboardSequenceNumber();
        foreach (byte key in new byte[] { 0x11, 0x12, 0x10, 0x54 }) keybd_event(key, 0, 0, UIntPtr.Zero);
        foreach (byte key in new byte[] { 0x54, 0x10, 0x12, 0x11 }) keybd_event(key, 0, 2, UIntPtr.Zero);
        var assistant = (AssistantWindow)app.MainWindow;
        await Wait(() => assistant.ResultText.Text.Contains("你好", StringComparison.Ordinal), "real-hotkey-uia-translate-ollama", 140);
        Require(assistant.InputText.Text == "Hello, world!" && !app.Busy, "hotkey-selected-text-only");
        Require(clipboardBefore == Native.GetClipboardSequenceNumber(), "uia-hotkey-leaves-clipboard-unchanged");
        fixture.Close(); assistant.ClearText();
    }
    private static async Task NativeIme()
    {
        var original = Forms.InputLanguage.CurrentInputLanguage;
        var chinese = Forms.InputLanguage.InstalledInputLanguages.Cast<Forms.InputLanguage>().FirstOrDefault(x => x.Culture.Name == "zh-CN");
        metrics["windowsImeVerified"] = false;
        if (chinese is null) { metrics["windowsImeLimitation"] = "Chinese input language not installed"; return; }
        var focus = Native.FocusWindow(new WindowInteropHelper(shell).Handle);
        var thread = Native.GetWindowThreadProcessId(focus, out _);
        var originalChildLayout = GetKeyboardLayout(thread);
        try
        {
            Forms.InputLanguage.CurrentInputLanguage = chinese;
            PostMessage(focus, 0x0050, IntPtr.Zero, chinese.Handle);
            await Task.Delay(200);
            await Js("window.__nativeCompositions=0;const t=document.getElementById('synthetic-ime');t.addEventListener('compositionstart',()=>window.__nativeCompositions++);t.focus();t.setSelectionRange(t.value.length,t.value.length)");
            var ime = ImmGetDefaultIMEWnd(Native.FocusWindow(new WindowInteropHelper(shell).Handle));
            if (ime != IntPtr.Zero)
            {
                SendMessage(ime, 0x0283, new IntPtr(6), new IntPtr(1));
                SendMessage(ime, 0x0283, new IntPtr(2), new IntPtr(1));
            }
            foreach (byte key in new byte[] { 0x5A, 0x48, 0x4F, 0x4E, 0x47, 0x57, 0x45, 0x4E, 0x20 })
            { keybd_event(key, 0, 0, UIntPtr.Zero); keybd_event(key, 0, 2, UIntPtr.Zero); await Task.Delay(60); }
            await Task.Delay(300);
            bool actual = await Js("window.__nativeCompositions>0 && document.getElementById('synthetic-ime').value.endsWith('中文')") == "true";
            metrics["windowsImeVerified"] = actual;
            if (!actual && await Js("window.__nativeCompositions===0") == "true")
            {
                // Pinyin can open in its English mode. Shift switches it without changing another app's layout.
                keybd_event(0x10, 0, 0, UIntPtr.Zero); keybd_event(0x10, 0, 2, UIntPtr.Zero); await Task.Delay(100);
                foreach (byte key in new byte[] { 0x5A, 0x48, 0x4F, 0x4E, 0x47, 0x57, 0x45, 0x4E, 0x20 })
                { keybd_event(key, 0, 0, UIntPtr.Zero); keybd_event(key, 0, 2, UIntPtr.Zero); await Task.Delay(60); }
                await Task.Delay(300);
                actual = await Js("window.__nativeCompositions>0 && document.getElementById('synthetic-ime').value.endsWith('中文')") == "true";
                metrics["windowsImeVerified"] = actual;
            }
            if (actual) checks.Add("real-windows-pinyin-composition-input");
            else metrics["windowsImeLimitation"] = "Native Pinyin composition was not established by this automation";
        }
        finally { PostMessage(focus, 0x0050, IntPtr.Zero, originalChildLayout); Forms.InputLanguage.CurrentInputLanguage = original; }
    }
    private sealed class DelayedActions(IWorkspaceNativeActions actual) : IWorkspaceNativeActions
    {
        internal bool Hold;
        internal TaskCompletionSource<ShellStatus> Delayed = new();
        internal bool Waiting;
        public Task<ShellStatus> StatusAsync(CancellationToken cancellation)
        { if (!Hold) return actual.StatusAsync(cancellation); Hold = false; Waiting = true; return Delayed.Task; }
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken cancellation) => actual.OpenAsync(entry, cancellation);
    }
    private static async Task StaleResponse()
    {
        shell.Close(); await Wait(() => app.Workspace is null, "close-before-stale-test");
        var actions = new DelayedActions(app); shell = new MainWorkspaceWindow(actions); shell.Show(); await Ready();
        actions.Hold = true; string oldId = Guid.NewGuid().ToString("D"); string oldSession = shell.Host.SessionId;
        await Js("window.chrome.webview.postMessage({version:1,sessionId:" + JsonSerializer.Serialize(oldSession) + ",requestId:"
            + JsonSerializer.Serialize(oldId) + ",method:'shell.refreshStatus',payload:{}})");
        await Wait(() => actions.Waiting, "old-document-response-deliberately-held");
        shell.Browser.CoreWebView2.Reload(); await Wait(() => shell.Host.SessionId.Length > 0 && shell.Host.SessionId != oldSession, "held-response-reload"); await Ready();
        await Js("window.__replies=[];window.chrome.webview.addEventListener('message',e=>window.__replies.push(e.data))");
        actions.Delayed.SetResult(await ((IWorkspaceNativeActions)app).StatusAsync(default)); await Task.Delay(150);
        Require(await Js("window.__replies.every(x=>x.requestId!==" + JsonSerializer.Serialize(oldId) + ")") == "true", "real-stale-response-suppressed");
        await shell.ShutdownAsync();
    }
    private static async Task FailureFallback()
    {
        // Explicit test injection uses nonexistent assets. No production failure flag or remote fallback.
        shell = new MainWorkspaceWindow(app, Path.Combine(Path.GetTempPath(), "M5A-Missing-" + Guid.NewGuid().ToString("N")));
        shell.Show(); await Wait(() => shell.FallbackTitle.Text == "工作区暂时不可用", "controlled-initialization-failure-native-fallback");
        shell.NativeAssistantButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Require(app.MainWindow.IsVisible, "fallback-legacy-assistant-action"); await shell.ShutdownAsync();
        shell = new MainWorkspaceWindow(app); shell.Show(); await Ready();
        try { await shell.Browser.CoreWebView2.CallDevToolsProtocolMethodAsync("Page.crash", "{}").WaitAsync(TimeSpan.FromSeconds(3)); } catch (Exception) { }
        await Wait(() => shell.FallbackTitle.Text == "工作区暂时不可用", "renderer-process-failure-native-fallback");
        await shell.ShutdownAsync();
    }
}
