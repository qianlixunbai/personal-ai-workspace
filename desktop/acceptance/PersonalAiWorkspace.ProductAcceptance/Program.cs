using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using PersonalAiWorkspace.Desktop.Bridge;
using Forms = System.Windows.Forms;
using Button = System.Windows.Controls.Button;
using TextBox = System.Windows.Controls.TextBox;

namespace PersonalAiWorkspace.ProductAcceptance;

internal static class Program
{
    private static AssistantApp app = null!;
    private static MainWorkspaceWindow shell = null!;
    private static RuntimeClient runtime = null!;
    private static CredentialStore credentials = null!;
    private static JsonElement cases;
    private static string stage = "configuration";
    private static readonly List<string> checks = [];
    private static readonly Dictionary<string, object> metrics = [];
    private static bool realIme;
    private static string? failureType;
    private static int code = 1;
    private static string Setting(string name) => Environment.GetEnvironmentVariable("M5E_" + name)!;
    private static string Case(string name) => cases.GetProperty(name).GetString()!;
    private static AssistantWindow Quick => app.Windows.Cast<Window>().OfType<AssistantWindow>().Single();
    [DllImport("user32.dll")] private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr first, IntPtr second);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr GetKeyboardLayout(uint thread);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint one, uint two, bool attach);
    private delegate bool EnumWindowCallback(IntPtr hwnd, IntPtr data);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowCallback callback, IntPtr data);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hwnd, StringBuilder text, int length);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr FindWindow(string? cls, string title);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
    [STAThread] private static int Main(string[] args)
    {
        if (args.Length > 0 && args[0] == "--entry")
        {
            try { ActualEntry(); code = 0; } catch (Exception error) { code = 1; failureType = error.GetType().Name; }
            Report(); return code;
        }
        cases = JsonDocument.Parse(Setting("CASES")).RootElement.Clone();
        string token = File.ReadAllText(Setting("TOKEN_FILE")).Trim();
        credentials = new CredentialStore("PersonalAiWorkspace.M5E.Acceptance." + Guid.NewGuid().ToString("N"));
        using var single = new SingleInstance();
        if (!single.IsPrimary) { stage = "preexisting-desktop-stop-without-touching-user"; Report(); return 1; }
        // Intentionally start with a missing test-owned credential. The user's WinCred is untouched.
        runtime = new RuntimeClient(() => token); app = new AssistantApp(single, credentials);
        app.Startup += (_, _) => app.Dispatcher.BeginInvoke(new Action(async () =>
        {
            try { await Drive(); code = 0; } catch (Exception error) { code = 1; failureType = error.GetType().Name; }
            finally
            {
                foreach (Window window in app.Windows.Cast<Window>().ToArray())
                    if (window is not AssistantWindow and not MainWorkspaceWindow) window.Close();
                await app.ExitAsync();
            }
        }), DispatcherPriority.ApplicationIdle);
        try { app.Run(); } finally { app.Cleanup(); runtime.Dispose(); credentials.Forget(); }
        Report(); return code;
    }
    private static void Report() => Console.WriteLine(JsonSerializer.Serialize(new { result = code == 0 ? "PASS" : "FAIL", check = stage, checks, metrics, realWindowsPinyin = realIme, failureType }));
    private static void Stage(string name) { stage = name; File.WriteAllText(Setting("PROGRESS"), JsonSerializer.Serialize(new { check = name, completedChecks = checks.Count })); }
    private static void Require(bool condition, string name) { Stage(name); if (!condition) throw new InvalidOperationException(); checks.Add(name); }
    private static async Task Wait(Func<bool> condition, string name, int seconds = 30)
    { Stage(name); var until = DateTime.UtcNow.AddSeconds(seconds); while (!condition()) { if (DateTime.UtcNow > until) throw new TimeoutException(); await Task.Delay(75); } checks.Add(name); }
    private static Task<string> Js(string script) => shell.Browser.CoreWebView2.ExecuteScriptAsync(script).WaitAsync(TimeSpan.FromSeconds(10));
    private static async Task WaitJs(string script, string name, int seconds = 30)
    { Stage(name); var until = DateTime.UtcNow.AddSeconds(seconds); while (await Js(script) != "true") { if (DateTime.UtcNow > until) throw new TimeoutException(); await Task.Delay(75); } checks.Add(name); }
    private static async Task Ready()
    { await Wait(() => shell.Host.SessionId.Length > 0, "package-session-ready"); await WaitJs("document.querySelector('.operation-status')?.textContent==='工作区已连接'", "package-react-connected"); }
    private static async Task Route(string name)
    { await Js("document.querySelector('a[href=\"#/" + name + "\"]').click()"); await WaitJs("document.querySelector('h1').textContent.toLowerCase()==='" + name + "'", "route-" + name); }
    private static async Task Insert(string id, string text)
    {
        shell.ReturnFocus(); await Js("(()=>{const t=document.getElementById(" + JsonSerializer.Serialize(id) + ");t.focus();t.select()})()");
        await shell.Browser.CoreWebView2.CallDevToolsProtocolMethodAsync("Input.insertText", JsonSerializer.Serialize(new { text }));
        await WaitJs("document.getElementById(" + JsonSerializer.Serialize(id) + ").value===" + JsonSerializer.Serialize(text), "controlled-input-exact");
    }
    private static async Task Click(string text, string selector)
    { Require(await Js("(()=>{const b=[...document.querySelector(" + JsonSerializer.Serialize(selector) + ").querySelectorAll('button')].find(x=>x.textContent.trim()===" + JsonSerializer.Serialize(text) + ");if(!b||b.disabled)return false;b.click();return true})()") == "true", "explicit-enabled-action"); }
    private static async Task Drive()
    {
        shell = app.Workspace ?? throw new InvalidOperationException(); Require(app.MainWindow == shell, "missing-credential-default-main-workspace");
        await Ready(); Require(!Quick.IsVisible, "missing-credential-does-not-show-legacy");
        Require(await Js("document.querySelector('h1').textContent==='Assistant'") == "true", "default-assistant-no-route-storage");
        await Route("settings"); await WaitJs("document.querySelector('.page-content').textContent.includes('Missing')", "settings-real-missing-credential");
        await Js("[...document.querySelectorAll('.maintenance-row')].find(x=>x.querySelector('h3').textContent==='凭据管理').querySelector('button').click()");
        await Wait(() => Quick.IsVisible && Quick.ImportButton.IsKeyboardFocusWithin, "explicit-native-credential-flow");
        await app.ImportCredentialAsync(Setting("TOKEN_FILE")); Quick.Close(); shell.ReturnFocus();
        await WaitJs("document.querySelector('.operation-status').textContent==='原生入口已打开'", "credential-entry-returned");
        await Click("刷新状态", ".page-content"); await WaitJs("document.querySelector('.page-content').textContent.includes('Ready') && document.querySelector('.page-content').textContent.includes('可连接')", "settings-real-authenticated-ready");
        Require(WorkspaceBridge.NativeMethods.Count == 4 && !WorkspaceBridge.NativeMethods.Keys.Any(x => x is "native.openLegacyAssistant" or "native.openConversations" or "native.openMemory"), "retired-js-methods-absent");
        Require(shell.NativeAssistantButton.Visibility == Visibility.Collapsed, "healthy-native-toolbar-retired");
        var core = shell.Browser.CoreWebView2;
        Require(core.Source == "https://workspace.personal-ai.invalid/index.html#/settings" && core.Profile.IsInPrivateModeEnabled && !core.Settings.AreDevToolsEnabled && !core.Settings.AreHostObjectsAllowed, "trusted-production-webview-policy");
        metrics["webViewRuntime"] = core.Environment.BrowserVersionString; metrics["firstReadyMs"] = shell.Host.FirstReadyMilliseconds;
        await Route("assistant"); await Insert("assistant-input", Case("ask")); await SubmitAsk("real-packaged-ollama-assistant");
        await Ime(); Require(realIme, "representative-real-windows-pinyin-pass");
        await Memory(); await Conversation(); await ExplicitMemory(); await Hotkey(); await Maintenance(); await Recovery();
        await Fallback(); checks.Add("compact-packaged-product-flow-complete");
    }
    private static async Task SubmitAsk(string name)
    {
        var watch = Stopwatch.StartNew(); await Js("document.querySelector('.page-content > div:not([hidden]) .operation-actions .primary').click()");
        await WaitJs("document.querySelector('.page-content > div:not([hidden]) .execution-status').textContent.startsWith('Succeeded')", name, 180);
        Require(await Js("document.querySelector('.page-content > div:not([hidden]) .operation-result').textContent.length>0") == "true", name + "-result");
        metrics[name + "Ms"] = watch.ElapsedMilliseconds;
    }
    private static async Task Memory()
    {
        await Route("memory"); await WaitJs("!document.getElementById('memory-title').disabled", "memory-interactive");
        await Click("New", ".memory-page"); await Insert("memory-title", Case("title")); await Insert("memory-content", Case("body"));
        await Click("Save", ".memory-editor"); await WaitJs("document.querySelector('.memory-list').textContent.includes(" + JsonSerializer.Serialize(Case("title")) + ") && !document.getElementById('memory-title').disabled", "memory-created");
        await Insert("memory-content", Case("edited")); await Click("Save", ".memory-editor");
        await WaitJs("!document.getElementById('memory-title').disabled && document.querySelector('.memory-state').textContent.includes('Revision 2') && !document.querySelector('.memory-state').textContent.includes('未保存')", "memory-edited-saved");
        await Insert("memory-search", Case("title")); await Click("Search", ".memory-list");
        await WaitJs("document.querySelectorAll('.memory-select').length===1 && document.querySelector('.memory-list ul').getAttribute('aria-busy')==='false'", "memory-real-search");
        var items = await runtime.ListMemoryAsync(new(Case("title")), default);
        Require(items.Items.Count == 1 && items.Items[0].Content == Case("edited") && items.Items[0].Revision == 2, "memory-runtime-exact-durable-save");
    }
    private static async Task Conversation()
    {
        await Route("conversations"); await WaitJs("!document.querySelector('.conversation-list button').disabled", "conversation-list-interactive");
        await Click("New Conversation", ".conversation-list");
        await WaitJs("document.getElementById('conversation-input') && !document.getElementById('conversation-input').readOnly", "conversation-created");
        await Insert("conversation-input", Case("turn1")); await Click("Send", ".conversation-send");
        await WaitJs("document.querySelector('.conversation-history').textContent.includes('Turn 1 · SUCCEEDED')", "conversation-first-real-ollama", 180);
        await Insert("conversation-input", Case("turn2")); await Click("Send", ".conversation-send");
        await WaitJs("document.querySelector('.conversation-history').textContent.includes('Turn 2 · SUCCEEDED')", "conversation-second-real-ollama", 180);
        var list = await runtime.ListConversationsAsync(ConversationStatus.ACTIVE, 0, 10, default);
        var detail = await runtime.GetConversationAsync(list.Items.Single().Id, 0, 10, default);
        Require(detail.TotalTurns == 2 && detail.Turns[1].AssistantMessage!.Content.Contains(Case("context"), StringComparison.OrdinalIgnoreCase), "two-turn-context-correct");
        var session = shell.Host.SessionId; coreReload(); await Wait(() => shell.Host.SessionId.Length > 0 && shell.Host.SessionId != session, "reload-new-session");
        await Ready(); await WaitJs("document.querySelector('.conversation-history').textContent.includes('Turn 2 · SUCCEEDED')", "reload-durable-conversation");
    }
    private static void coreReload() => shell.Browser.CoreWebView2.Reload();
    private static async Task ExplicitMemory()
    {
        await Route("assistant"); var item = (await runtime.ListMemoryAsync(new(Case("title")), default)).Items.Single();
        Task<string> opening = Js("document.querySelector('.page-content > div:not([hidden]) .memory-choice button').click()");
        await Wait(() => app.Windows.Cast<Window>().OfType<MemorySelectionWindow>().Any(x => x.IsVisible), "privileged-memory-picker");
        var picker = app.Windows.Cast<Window>().OfType<MemorySelectionWindow>().Single();
        await Wait(() => picker.SearchButton.IsEnabled, "picker-loaded"); await picker.PreviewAsync(item); picker.AddPreview(); picker.ConfirmSelection(); await opening;
        await WaitJs("document.querySelector('.page-content > div:not([hidden]) .memory-choice').textContent.includes('1 selected')", "explicit-memory-selected");
        await Insert("assistant-input", Case("memoryAsk")); await SubmitAsk("explicit-memory-real-ollama");
        Require(await Js("document.querySelector('.page-content > div:not([hidden]) .operation-result').textContent.includes(" + JsonSerializer.Serialize(Case("memoryCode")) + ")") == "true", "explicit-memory-context-used");
        await WaitJs("document.querySelector('.page-content > div:not([hidden]) .memory-choice').textContent.includes('No Memory')", "accepted-memory-consumed");
        await Insert("assistant-input", Case("nextAsk")); await SubmitAsk("next-ordinary-operation-no-memory");
        Require(await Js("!document.querySelector('.page-content > div:not([hidden]) .operation-result').textContent.includes(" + JsonSerializer.Serialize(Case("memoryCode")) + ")") == "true", "no-automatic-memory-carry-over");
    }
    private static async Task Hotkey()
    {
        var input = new TextBox { Text = "Hello, world!", FontSize = 22 };
        var fixture = new Window { Title = "Synthetic M5E selection", Width = 420, Height = 160, Content = input, Topmost = true };
        fixture.Show(); fixture.Activate(); input.Focus(); input.SelectAll();
        await Wait(() => {
            uint current = GetCurrentThreadId(), foreground = Native.GetWindowThreadProcessId(Native.GetForegroundWindow(), out _);
            bool attached = foreground != 0 && current != foreground && AttachThreadInput(current, foreground, true);
            try { SetForegroundWindow(new WindowInteropHelper(fixture).Handle); fixture.Activate(); input.Focus(); }
            finally { if (attached) AttachThreadInput(current, foreground, false); }
            return Native.GetForegroundWindow() == new WindowInteropHelper(fixture).Handle;
        }, "selection-fixture-foreground");
        uint clipboardBefore = Native.GetClipboardSequenceNumber();
        foreach (byte key in new byte[] { 0x11, 0x12, 0x10, 0x54 }) KeyDown(key);
        foreach (byte key in new byte[] { 0x54, 0x10, 0x12, 0x11 }) KeyUp(key);
        await Wait(() => Quick.ResultText.Text.Contains("你好", StringComparison.Ordinal) && !app.Busy, "real-packaged-hotkey-uia-translate", 180);
        Require(Quick.InputText.Text == "Hello, world!" && clipboardBefore == Native.GetClipboardSequenceNumber(), "hotkey-selected-text-only-no-clipboard-change");
        fixture.Close(); Quick.Close(); shell.ReturnFocus();
    }
    private static async Task Maintenance()
    {
        await Route("settings");
        foreach (var (name, type) in new[] { ("Browser Pairing", typeof(BrowserPairingWindow)), ("Memory Backup", typeof(MemoryBackupWindow)), ("Workspace Backup", typeof(WorkspaceBackupWindow)) })
        {
            Task<string> opening = Js("[...document.querySelectorAll('.maintenance-row')].find(x=>x.querySelector('h3').textContent===" + JsonSerializer.Serialize(name) + ").querySelector('button').click()");
            await Wait(() => app.Windows.Cast<Window>().Any(x => x.GetType() == type && x.IsVisible), "native-entry-" + name.Replace(' ', '-'));
            app.Windows.Cast<Window>().Single(x => x.GetType() == type && x.IsVisible).Close(); await opening;
            await WaitJs("document.querySelector('.operation-status').textContent==='原生入口已打开'", "native-maintenance-returned");
        }
        Quick.Close(); shell.ReturnFocus();
    }
    private sealed class Files : IWorkspaceBackupFiles
    {
        private readonly NativeWorkspaceBackupFiles native = new();
        public MemoryExportDestination? PickExport(Window owner) => new(Setting("BACKUP_FILE"), false);
        public string? PickBackup(Window owner) => Setting("BACKUP_FILE");
        public string? PickTarget(Window owner) => Setting("RESTORE_TARGET");
        public Stream OpenRead(string path) => native.OpenRead(path);
        public Task<WorkspaceBackupMetadata> ExportAsync(RuntimeClient client, MemoryExportDestination file, CancellationToken ct) => native.ExportAsync(client, file, ct);
    }
    private static async Task Control(string action)
    { using var http = new HttpClient(new HttpClientHandler { UseProxy = false }); using var result = await http.PostAsync(Setting("CONTROL") + "/" + action, new StringContent("{}")); result.EnsureSuccessStatusCode(); }
    private static async Task Recovery()
    {
        var backup = new WorkspaceBackupWindow(runtime, new Files()) { Owner = shell }; backup.Show(); await backup.ExportAsync();
        Require(backup.StatusText.Text.StartsWith("Export complete"), "packaged-native-workspace-export"); backup.Close();
        await Control("maintenance"); backup = new WorkspaceBackupWindow(runtime, new Files()) { Owner = shell }; backup.Show();
        await backup.ChooseAsync(); await backup.RestoreAsync(); Require(backup.StatusText.Text.StartsWith("Restore complete"), "isolated-native-workspace-restore"); backup.Close();
        await Control("restored"); coreReload(); await Ready(); await Route("memory");
        await WaitJs("document.querySelector('.memory-list').textContent.includes(" + JsonSerializer.Serialize(Case("title")) + ")", "restored-memory-visible");
        await Js("document.querySelector('.memory-select').click()");
        await WaitJs("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("edited")), "restored-memory-exact");
        await Route("conversations"); await WaitJs("document.querySelector('.conversation-history').textContent.includes('Turn 2 · SUCCEEDED')", "restored-conversation-visible");
        Require((await runtime.ListMemoryAsync(new(Case("title")), default)).Items.Single().Revision == 2, "restored-exact-revision");
    }
    private static async Task Fallback()
    {
        try { await shell.Browser.CoreWebView2.CallDevToolsProtocolMethodAsync("Page.crash", "{}").WaitAsync(TimeSpan.FromSeconds(3)); } catch { }
        await Wait(() => shell.FallbackVisible, "renderer-crash-native-fallback");
        shell.NativeAssistantButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); Require(Quick.IsVisible, "fallback-direct-wpf-no-webmessage"); Quick.Close();
        await shell.ShutdownAsync(); app.ShowWorkspace(); shell = app.Workspace!; await Ready();
        var invalid = new MainWorkspaceWindow((IWorkspaceNativeActions)app, Path.Combine(Setting("STATE"), "absent-assets")); invalid.Show();
        await Task.Delay(500); Require(invalid.FallbackVisible, "invalid-assets-native-fallback");
        invalid.NativeAssistantButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); Require(Quick.IsVisible, "invalid-assets-quick-assistant"); Quick.Close(); await invalid.ShutdownAsync();
        Require(app.TrayVisible, "tray-survives-renderer-failure");
    }
    private static void ActualEntry()
    {
        int pid = int.Parse(Setting("DESKTOP_PID")); var process = Process.GetProcessById(pid);
        Require(Path.GetFullPath(process.MainModule!.FileName) == Path.GetFullPath(Setting("DESKTOP_EXE")), "actual-shipped-desktop-process");
        var until = DateTime.UtcNow.AddSeconds(30); IntPtr hwnd;
        while ((hwnd = FindWindow(null, "Personal AI Workspace")) == IntPtr.Zero && DateTime.UtcNow < until) Thread.Sleep(100);
        Require(hwnd != IntPtr.Zero, "release-launcher-main-workspace-visible");
        Require(Native.GetWindowThreadProcessId(hwnd, out uint owner) != 0 && owner == pid, "actual-package-window-owner");
        ShowWindow(hwnd, 6);
        using (var second = Process.Start(new ProcessStartInfo(Setting("DESKTOP_EXE")) { WorkingDirectory = Setting("STATE"), UseShellExecute = false, CreateNoWindow = true })!)
        { Require(second.WaitForExit(5000) && second.ExitCode == 0, "actual-second-launch-signals-primary"); }
        until = DateTime.UtcNow.AddSeconds(10); while (IsIconic(hwnd) && DateTime.UtcNow < until) Thread.Sleep(100);
        Require(!IsIconic(hwnd) && Process.GetProcessesByName("PersonalAiWorkspace.Desktop").Length == 1, "exactly-one-desktop-existing-workspace-activated");
        var trayWindows = new List<IntPtr>();
        EnumWindows((handle, _) => { Native.GetWindowThreadProcessId(handle, out uint target); var cls = new StringBuilder(256); GetClassName(handle, cls, 256); if (target == pid && cls.ToString().StartsWith("WindowsForms10.Window")) trayWindows.Add(handle); return true; }, IntPtr.Zero);
        Require(trayWindows.Count > 0, "task-owned-native-tray-window");
        // WinForms NotifyIcon WM_USER+1024 callback, real production DoubleClick handler.
        ShowWindow(hwnd, 6); foreach (var handle in trayWindows) PostMessage(handle, 0x800, new IntPtr(1), new IntPtr(0x203)); Thread.Sleep(700);
        Require(!IsIconic(hwnd), "actual-tray-double-click-main-workspace");
        ((WindowPattern)AutomationElement.FromHandle(hwnd).GetCurrentPattern(WindowPattern.Pattern)).Close();
        until = DateTime.UtcNow.AddSeconds(10); while (FindWindow(null, "Personal AI Workspace") != IntPtr.Zero && DateTime.UtcNow < until) Thread.Sleep(100);
        Require(!process.HasExited && FindWindow(null, "Personal AI Workspace") == IntPtr.Zero, "main-close-keeps-tray-process");
        OpenTrayMenu(trayWindows, pid, "Open Personal AI Workspace");
        until = DateTime.UtcNow.AddSeconds(15); while ((hwnd = FindWindow(null, "Personal AI Workspace")) == IntPtr.Zero && DateTime.UtcNow < until) Thread.Sleep(100);
        Require(hwnd != IntPtr.Zero, "actual-tray-open-main-workspace");
        OpenTrayMenu(trayWindows, pid, "退出"); Require(process.WaitForExit(10000), "explicit-tray-exit-desktop");
    }
    private static void OpenTrayMenu(List<IntPtr> windows, int pid, string name)
    {
        foreach (var handle in windows) PostMessage(handle, 0x800, new IntPtr(1), new IntPtr(0x205));
        var condition = new AndCondition(new PropertyCondition(AutomationElement.ProcessIdProperty, pid), new PropertyCondition(AutomationElement.NameProperty, name));
        AutomationElement? item = null; var until = DateTime.UtcNow.AddSeconds(5);
        while (item is null && DateTime.UtcNow < until) { Thread.Sleep(100); item = AutomationElement.RootElement.FindFirst(TreeScope.Descendants, condition); }
        Require(item is not null, "actual-tray-menu-action-present"); ((InvokePattern)item!.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
    }
    private static void KeyDown(byte key) => keybd_event(key, 0, 0, UIntPtr.Zero);
    private static void KeyUp(byte key) => keybd_event(key, 0, 2, UIntPtr.Zero);
    private static void Key(byte key) { KeyDown(key); KeyUp(key); }
    private static async Task Keys(string text)
    { foreach (char character in text) { if (Native.GetForegroundWindow() != new WindowInteropHelper(shell).Handle) throw new InvalidOperationException(); Key((byte)char.ToUpperInvariant(character)); await Task.Delay(75); } }
    private static async Task Ime()
    {
        await Insert("assistant-input", "");
        await Js("(()=>{window.__ime={starts:0,ends:0,updates:0,composing:false,premature:0,submits:0,exact:false};const t=document.getElementById('assistant-input');t.addEventListener('compositionstart',()=>{window.__ime.starts++;window.__ime.composing=true});t.addEventListener('compositionupdate',()=>window.__ime.updates++);t.addEventListener('compositionend',()=>{window.__ime.ends++;window.__ime.composing=false});const post=window.chrome.webview.postMessage.bind(window.chrome.webview);window.chrome.webview.postMessage=m=>{if(m.method==='assistant.submit'){window.__ime.submits++;if(window.__ime.composing)window.__ime.premature++;window.__ime.exact=m.payload.text===" + JsonSerializer.Serialize(Case("ime")) + "}post(m)}})()");
        var original = Forms.InputLanguage.CurrentInputLanguage;
        var chinese = Forms.InputLanguage.InstalledInputLanguages.Cast<Forms.InputLanguage>().FirstOrDefault(x => x.Culture.Name == "zh-CN");
        Require(chinese is not null, "real-pinyin-installed");
        var focus = Native.FocusWindow(new WindowInteropHelper(shell).Handle); var layout = GetKeyboardLayout(Native.GetWindowThreadProcessId(focus, out _));
        try
        {
            shell.ReturnFocus(); SetForegroundWindow(new WindowInteropHelper(shell).Handle); Forms.InputLanguage.CurrentInputLanguage = chinese!;
            PostMessage(Native.FocusWindow(new WindowInteropHelper(shell).Handle), 0x0050, IntPtr.Zero, chinese!.Handle);
            await Js("document.getElementById('assistant-input').focus()"); await Task.Delay(200);
            await Keys("zhongwen"); Key(0x0D); await Task.Delay(200);
            Require(await Js("window.__ime.submits===0") == "true", "native-candidate-enter-no-submit");
            KeyDown(0x11); Key(0x41); KeyUp(0x11); Key(0x08); await Task.Delay(150);
            foreach (string pinyin in new[] { "bendi", "gongzuoqu", "ceshi", "xinghe" }) { await Keys(pinyin); Key(0x20); await Task.Delay(200); }
            await Keys(Case("imeSuffix")); await Task.Delay(200);
            Require(await Js("window.__ime.starts>0 && window.__ime.ends>0 && window.__ime.updates>0 && document.getElementById('assistant-input').value===" + JsonSerializer.Serialize(Case("ime"))) == "true", "real-native-composition-exact-committed-chinese");
            await SubmitAsk("real-pinyin-submit-ollama");
            Require(await Js("window.__ime.premature===0 && window.__ime.submits===1 && window.__ime.exact") == "true", "real-pinyin-exact-bridge-input"); realIme = true;
        }
        finally { PostMessage(focus, 0x0050, IntPtr.Zero, layout); Forms.InputLanguage.CurrentInputLanguage = original; }
    }
}
