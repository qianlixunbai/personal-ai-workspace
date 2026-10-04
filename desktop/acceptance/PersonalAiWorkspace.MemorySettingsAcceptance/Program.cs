using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using PersonalAiWorkspace.Desktop.Bridge;
using Forms = System.Windows.Forms;

namespace PersonalAiWorkspace.MemorySettingsAcceptance;

internal static class Program
{
    private static AssistantApp app = null!;
    private static MainWorkspaceWindow shell = null!;
    private static RuntimeClient runtime = null!;
    private static CredentialStore credentials = null!;
    private static JsonElement cases;
    private static string stage = "configuration", token = "";
    private static readonly List<string> checks = [];
    private static readonly Dictionary<string, object> metrics = [];
    private static bool realIme;
    private static string? failureType;
    private static int code = 1;
    private static string Case(string name) => cases.GetProperty(name).GetString()!;
    private static string Setting(string name) => Environment.GetEnvironmentVariable("M5D_" + name)!;
    [DllImport("user32.dll")] private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr first, IntPtr second);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr GetKeyboardLayout(uint thread);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr FindWindow(string cls, string title);
    [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr hwnd, uint command);
    [DllImport("user32.dll")] private static extern int GetDlgCtrlID(IntPtr hwnd);
    [STAThread] private static int Main()
    {
        cases = JsonDocument.Parse(Setting("CASES")).RootElement.Clone(); token = File.ReadAllText(Setting("TOKEN_FILE")).Trim();
        credentials = new CredentialStore("PersonalAiWorkspace.M5D.Acceptance." + Guid.NewGuid().ToString("N"));
        using var single = new SingleInstance(".M5D.Acceptance." + Guid.NewGuid().ToString("N"));
        credentials.Save(token); runtime = new RuntimeClient(() => token); app = new AssistantApp(single, credentials);
        app.Startup += (_, _) => app.Dispatcher.BeginInvoke(new Action(async () =>
        {
            try { await Drive(); code = 0; }
            catch (Exception error) { code = 1; failureType = error.GetType().Name; }
            finally
            {
                File.WriteAllText(Setting("PROGRESS"), JsonSerializer.Serialize(new { check = stage, cleanup = true, completedChecks = checks.Count }));
                foreach (Window window in app.Windows.Cast<Window>().ToArray())
                    if (window is not AssistantWindow and not MainWorkspaceWindow) window.Close();
                if (app.Workspace is not null) await app.Workspace.ShutdownAsync();
                await app.ExitAsync();
            }
        }), DispatcherPriority.ApplicationIdle);
        try { app.Run(); }
        finally { app.Cleanup(); runtime.Dispose(); credentials.Forget(); }
        Console.WriteLine(JsonSerializer.Serialize(new { result = code == 0 ? "PASS" : "FAIL", check = stage,
            releaseWpf = true, realWebView2 = true, bundledReact = true, realRuntime = true,
            realWindowsPinyin = realIme, testOwnedCredential = true, failureType, checks, metrics }));
        return code;
    }
    private static void Stage(string name) { stage = name; File.WriteAllText(Setting("PROGRESS"), JsonSerializer.Serialize(new { check = name, completedChecks = checks.Count })); }
    private static void Require(bool value, string name) { Stage(name); if (!value) throw new InvalidOperationException(); checks.Add(name); }
    private static async Task Wait(Func<bool> condition, string name, int seconds = 30)
    { Stage(name); var until = DateTime.UtcNow.AddSeconds(seconds); while (!condition()) { if (DateTime.UtcNow > until) throw new TimeoutException(); await Task.Delay(75); } checks.Add(name); }
    private static Task<string> Js(string value) => shell.Browser.CoreWebView2.ExecuteScriptAsync(value).WaitAsync(TimeSpan.FromSeconds(10));
    private static async Task WaitJs(string value, string name, int seconds = 30)
    { Stage(name); var until = DateTime.UtcNow.AddSeconds(seconds); while (await Js(value) != "true") { if (DateTime.UtcNow > until) throw new TimeoutException(); await Task.Delay(75); } checks.Add(name); }
    private static async Task Ready()
    { await Wait(() => shell.Host.SessionId.Length > 0, "session-ready"); await WaitJs("document.querySelector('.operation-status')?.textContent==='工作区已连接'", "react-connected"); }
    private static async Task Route(string name)
    { var watch = Stopwatch.StartNew(); await Js("document.querySelector('a[href=\"#/" + name + "\"]').click()"); await WaitJs("document.querySelector('h1').textContent.toLowerCase()==='" + name + "'", "route-" + name); metrics.TryAdd(name + "RouteMs", Math.Round(watch.Elapsed.TotalMilliseconds, 2)); }
    private static async Task Click(string text, string selector = ".memory-page")
    { Require(await Js("(()=>{const b=[...document.querySelector(" + JsonSerializer.Serialize(selector) + ").querySelectorAll('button')].find(x=>x.textContent.trim()===" + JsonSerializer.Serialize(text) + ");if(!b||b.disabled)return false;b.click();return true})()") == "true", "enabled-fixture-action"); }
    private static async Task Idle() => await WaitJs("!document.getElementById('memory-title').disabled && document.querySelector('.memory-list ul').getAttribute('aria-busy')==='false'", "memory-idle");
    private static async Task Insert(string id, string text)
    {
        shell.ReturnFocus(); await Js("(()=>{const t=document.getElementById(" + JsonSerializer.Serialize(id) + ");t.focus();t.select()})()");
        await shell.Browser.CoreWebView2.CallDevToolsProtocolMethodAsync("Input.insertText", JsonSerializer.Serialize(new { text }));
        await WaitJs("document.getElementById(" + JsonSerializer.Serialize(id) + ").value===" + JsonSerializer.Serialize(text), "controlled-input-exact");
    }
    private static async Task Filter(string id, string value)
    { await Js("(()=>{const t=document.getElementById('" + id + "');t.value=" + JsonSerializer.Serialize(value) + ";t.dispatchEvent(new Event('change',{bubbles:true}))})()"); await Idle(); }
    private static async Task Select(string title)
    { await Js("[...document.querySelectorAll('.memory-select')].find(x=>x.textContent.startsWith(" + JsonSerializer.Serialize(title) + ")).click()"); await WaitJs("document.getElementById('memory-title').value===" + JsonSerializer.Serialize(title), "explicit-get-editor-ready"); await Idle(); }
    private static async Task Confirm(bool allow)
    { await WaitJs("document.querySelector('.memory-confirmation')?.open===true", "accessible-confirmation"); await Click(allow ? "丢弃并继续" : "取消", ".memory-confirmation"); await Idle(); }
    private static async Task Save()
    { var watch = Stopwatch.StartNew(); await Click("Save"); await WaitJs("document.querySelector('.memory-page [role=status]').textContent==='Memory 已保存。'", "explicit-save-confirmed"); await Idle(); metrics["saveMs"] = Math.Round(watch.Elapsed.TotalMilliseconds, 2); }
    private static Task NativeDecision(bool allow, IntPtr owner) => Task.Run(async () =>
    {
        var until = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < until)
        {
            var dialog = FindWindow("#32770", "Memory");
            if (dialog != IntPtr.Zero && GetWindow(dialog, 4) == owner)
            {
                if (GetDlgCtrlID(Native.FocusWindow(dialog)) != 7) throw new InvalidOperationException();
                PostMessage(dialog, 0x0111, new IntPtr(allow ? 6 : 7), IntPtr.Zero); return;
            }
            await Task.Delay(50);
        }
        throw new TimeoutException();
    });
    private static async Task Control(string action)
    { using var client = new HttpClient(new HttpClientHandler { UseProxy = false }); using var response = await client.PostAsync("http://127.0.0.1:18767/__test/" + action, null); Require(response.IsSuccessStatusCode, "fixture-control-" + action); }
    private static async Task Instrument()
    {
        await Js("(()=>{window.__lists=[];window.__times={};window.__pending={};const post=chrome.webview.postMessage.bind(chrome.webview);chrome.webview.postMessage=m=>{window.__pending[m.requestId]={method:m.method,start:performance.now()};post(m)};chrome.webview.addEventListener('message',e=>{const m=e.data,p=window.__pending[m.requestId];if(!p)return;delete window.__pending[m.requestId];window.__times[p.method]=performance.now()-p.start;if(p.method==='memory.list'&&m.ok)window.__lists=[m.result]})})()");
    }
    private static async Task Timing(string name, string method)
    { metrics[name] = JsonDocument.Parse(await Js("window.__times[" + JsonSerializer.Serialize(method) + "]")).RootElement.Clone(); }
    private static async Task ReloadDocument()
    { string session = shell.Host.SessionId; shell.Browser.CoreWebView2.Reload(); await Wait(() => shell.Host.SessionId.Length > 0 && shell.Host.SessionId != session, "restore-reload-session-rotates"); await Ready(); }
    private static async Task Drive()
    {
        app.ShowWorkspace(); shell = app.Workspace!; await Ready(); await Instrument(); var memoryReady = Stopwatch.StartNew(); await Route("memory"); await Idle(); metrics["memoryRouteReadyMs"] = Math.Round(memoryReady.Elapsed.TotalMilliseconds, 2);
        Require(await Js("document.querySelectorAll('.memory-select').length===20") == "true", "initial-list-bounded-twenty");
        Require(await Js("window.__lists[0].items.every(x=>!Object.hasOwn(x,'content'))") == "true", "real-metadata-only-list");
        await Timing("firstTwentyItemListMs", "memory.list");
        if (Setting("SKIP_IME") == "1")
        { metrics["realPinyinSkipped"] = true; await Click("New"); await Insert("memory-title", Case("imeTitle")); await Insert("memory-content", Case("imeContent")); await Save(); }
        else await Pinyin();
        var imeItem = (await runtime.ListMemoryAsync(new(Case("imeTitle")), default)).Items.Single();
        Require(imeItem.Title == Case("imeTitle") && imeItem.Content == Case("imeContent"), "real-ime-exact-durable-title-content");
        foreach (string value in new[] { Case("imeTitle"), Case("imeContent") })
        {
            await Insert("memory-search", value); await Click("Search"); await Idle();
            Require(await Js("document.querySelectorAll('.memory-select').length===1") == "true", "real-chinese-title-content-search");
        }
        await Insert("memory-search", ""); await Click("Search"); await Idle();
        var editorReady = Stopwatch.StartNew(); await Click("Reload"); await Idle(); Require(await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("imeContent"))) == "true", "real-ime-reload-exact"); metrics["memoryGetEditorReadyMs"] = Math.Round(editorReady.Elapsed.TotalMilliseconds, 2);
        await Timing("memoryGetEditorMs", "memory.get");
        await Insert("memory-title", Case("title")); await Insert("memory-content", Case("content")); await Save();
        var item = await runtime.GetMemoryAsync(imeItem.Id, default); Require(item.Title == Case("title") && item.Content == Case("content"), "react-update-runtime-exact");
        await Insert("memory-content", Case("dirty")); await Click("Archive"); await Idle(); item = await runtime.GetMemoryAsync(item.Id, default);
        Require(item.Status == MemoryStatus.ARCHIVED && item.Content == Case("content"), "archive-does-not-save-draft");
        Require(await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("dirty")) + " && document.querySelector('.memory-state').textContent.includes('未保存')") == "true", "dirty-archive-exact-preserved");
        await Save(); item = await runtime.GetMemoryAsync(item.Id, default); Require(item.Content == Case("dirty") && item.Status == MemoryStatus.ARCHIVED, "save-archived-with-new-revision");
        await Insert("memory-content", Case("restoredDraft")); await Click("Restore"); await Idle(); item = await runtime.GetMemoryAsync(item.Id, default);
        Require(item.Status == MemoryStatus.ACTIVE && item.Content == Case("dirty"), "restore-does-not-save-draft"); await Save();
        await SearchGates(); await Select(Case("title")); await DirtyGates(); await Select(Case("title"));
        await Insert("memory-content", Case("stale")); item = await runtime.GetMemoryAsync(item.Id, default);
        await runtime.UpdateMemoryAsync(item.Id, new(item.Revision, item.Type, item.Title, Case("external")), default);
        var conflict = Stopwatch.StartNew(); await Click("Save"); await WaitJs("document.querySelector('.memory-state').textContent.includes('stale')", "real-revision-conflict-visible"); metrics["revisionConflictMs"] = Math.Round(conflict.Elapsed.TotalMilliseconds, 2);
        Require(await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("stale")) + " && ['Save','Archive','Delete'].every(n=>[...document.querySelectorAll('.memory-editor button')].find(x=>x.textContent===n).disabled)") == "true", "conflict-draft-exact-mutations-blocked");
        await Click("Reload"); await Confirm(false); Require(await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("stale"))) == "true", "conflict-reload-cancel-preserves");
        await Click("Reload"); await Confirm(true); await WaitJs("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("external")), "explicit-reload-latest-truth");
        item = await runtime.GetMemoryAsync(item.Id, default); await Insert("memory-content", Case("missing")); await runtime.DeleteMemoryAsync(item.Id, item.Revision, default);
        await Click("Reload"); await Confirm(true); await WaitJs("document.querySelector('.memory-state').textContent.includes('missing')", "real-concurrent-delete-missing");
        Require(await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("missing"))) == "true", "missing-draft-preserved-no-recreation");
        await Click("New"); await Confirm(true); await Insert("memory-title", Case("title")); await Insert("memory-content", Case("content")); await Save();
        await Pagination(); await Settings(); await Backup();
        await Route("memory"); await Click("New"); await Idle(); await Insert("memory-search", ""); await Filter("memory-status", "ACTIVE"); await Filter("memory-filter-type", "ALL");
        await Control("empty"); await Click("Refresh"); await Idle(); shell.Browser.ZoomFactor = 1.25;
        Require(await Js("document.documentElement.scrollWidth<=innerWidth") == "true", "memory-125-percent-no-overflow");
        metrics["desktopWorkingSetBytes"] = Process.GetCurrentProcess().WorkingSet64;
        metrics["webViewWorkingSetBytes"] = shell.Browser.CoreWebView2.Environment.GetProcessInfos().Sum(x => { try { return Process.GetProcessById(x.ProcessId).WorkingSet64; } catch { return 0L; } });
        await Screenshot("memory-125"); await Js("document.querySelector('.memory-editor').scrollIntoView()"); await Screenshot("memory-editor-125");
        shell.Browser.ZoomFactor = 1; await Js("window.scrollTo(0,0)"); await Screenshot("memory"); await Route("settings"); await Screenshot("settings");
        shell.Close(); await Wait(() => app.Workspace is null, "clean-close"); Require(shell.Host.CleanupPassed, "profile-cleanup-confirmed");
    }
    private static async Task SearchGates()
    {
        await Insert("memory-content", Case("dirty"));
        foreach (var query in new[] { Case("query"), Case("query").ToLowerInvariant(), "%_literal", "OR", "" })
        {
            await Insert("memory-search", query); await Click("Search"); await Idle();
            await Timing("searchResponseMs", "memory.list");
            var expected = await runtime.ListMemoryAsync(new(query), default);
            Require(await Js("document.querySelectorAll('.memory-select').length===" + expected.Items.Count) == "true", "real-literal-search-runtime-parity");
            Require(await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("dirty"))) == "true", "search-preserves-draft");
        }
        await Filter("memory-status", "ARCHIVED"); await Filter("memory-filter-type", "PREFERENCE");
        var archived = await runtime.ListMemoryAsync(new("", MemoryStatus.ARCHIVED, MemoryType.PREFERENCE), default);
        Require(await Js("document.querySelectorAll('.memory-select').length===" + archived.Items.Count) == "true", "status-type-filter-parity");
        await Filter("memory-status", "ACTIVE"); await Filter("memory-filter-type", "ALL");
        await Click("Reload"); await Confirm(true);
    }
    private static async Task DirtyGates()
    {
        await Insert("memory-content", Case("dirty"));
        await Js("[...document.querySelectorAll('.memory-select')].find(x=>x.getAttribute('aria-pressed')==='false').click()"); await Confirm(false);
        await Click("New"); await Confirm(false);
        await Js("document.querySelector('a[href=\"#/settings\"]').click()"); await Confirm(false);
        Require(await Js("location.hash==='#/memory' && document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("dirty"))) == "true", "selection-new-route-cancel-exact");
        await Wait(() => shell.Host.HasDirtyEditor, "typed-native-dirty-signal");
        string session = shell.Host.SessionId; var decision = NativeDecision(false, new WindowInteropHelper(shell).Handle); shell.Browser.CoreWebView2.Reload(); await decision; await Task.Delay(150);
        Require(shell.Host.SessionId == session && await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("dirty"))) == "true", "native-reload-cancel-keeps-document-session-draft");
        decision = NativeDecision(false, new WindowInteropHelper(shell).Handle); shell.Close(); await decision; Require(app.Workspace == shell, "native-close-cancel-keeps-window");
        decision = NativeDecision(true, new WindowInteropHelper(shell).Handle); shell.Browser.CoreWebView2.Reload(); await decision; await Wait(() => shell.Host.SessionId.Length > 0 && shell.Host.SessionId != session, "confirmed-reload-session-rotates"); await Ready(); await Idle(); await Instrument(); await Select(Case("title"));
        await Insert("memory-content", Case("dirty")); await Wait(() => shell.Host.HasDirtyEditor, "close-confirm-dirty-signal");
        decision = NativeDecision(true, new WindowInteropHelper(shell).Handle); shell.Close(); await decision; await Wait(() => app.Workspace is null, "confirmed-native-close");
        app.ShowWorkspace(); shell = app.Workspace!; await Ready(); await Route("memory"); await Idle(); await Instrument();
        Require(await Js("document.getElementById('memory-content').value===''") == "true", "reopen-no-browser-draft-persistence");
    }
    private static async Task Pagination()
    {
        await Insert("memory-search", Case("pageQuery")); await Click("Search"); await Idle(); Require(await Js("document.querySelectorAll('.memory-select').length===20") == "true", "pagination-first-twenty");
        await Click("Next"); await Idle(); Require(await Js("document.querySelectorAll('.memory-select').length===1") == "true", "pagination-last-one");
        await Timing("pageSwitchMs", "memory.list");
        await Click("Previous"); await Idle(); Require(await Js("document.querySelectorAll('.memory-select').length===20") == "true", "pagination-previous-twenty");
        await Click("Next"); await Idle();
        await Js("document.querySelector('.memory-select').click()"); await Idle(); await Click("Delete"); await WaitJs("document.querySelector('.memory-confirmation')?.open===true", "physical-delete-confirmation");
        await Click("取消", ".memory-confirmation"); await Click("Delete"); await Click("永久删除", ".memory-confirmation"); await Idle();
        Require(await Js("document.querySelectorAll('.memory-select').length===20 && document.querySelector('.memory-list .pagination').textContent.includes('Page 1')") == "true", "delete-tail-page-corrects-to-valid-page");
        await Insert("memory-search", ""); await Click("Search"); await Idle(); await Select(Case("title"));
    }
    private static async Task Settings()
    {
        await Route("settings"); Require(await Js("document.querySelector('.status-list').textContent.includes('可连接') && document.querySelector('.status-list').textContent.includes('有效')") == "true", "settings-real-runtime-credential-ready");
        credentials.Forget(); await Click("刷新状态", ".page-content"); await WaitJs("document.querySelector('.status-list').textContent.includes('未导入')", "settings-test-owned-credential-missing"); credentials.Save(token);
        await Control("stop"); await Click("刷新状态", ".page-content"); await WaitJs("document.querySelector('.status-list').textContent.includes('不可连接')", "settings-runtime-unavailable-controlled"); await Control("source");
        await Click("刷新状态", ".page-content"); await WaitJs("document.querySelector('.status-list').textContent.includes('可连接') && document.querySelector('.status-list').textContent.includes('有效')", "settings-refresh-restores-real-status");
        foreach (var (name, type) in new[] { ("Browser Pairing", typeof(BrowserPairingWindow)), ("Memory Backup", typeof(MemoryBackupWindow)), ("Workspace Backup", typeof(WorkspaceBackupWindow)) })
        {
            // Native modal dispatch may precede ExecuteScript completion. Observe and
            // close the real native window before awaiting the script acknowledgement.
            Task<string> opening = Js("[...document.querySelectorAll('.maintenance-row')].find(x=>x.querySelector('h3').textContent===" + JsonSerializer.Serialize(name) + ").querySelector('button').click()");
            await Wait(() => app.Windows.Cast<Window>().Any(x => x.GetType() == type && x.IsVisible), "settings-native-" + name.Replace(' ', '-'));
            app.Windows.Cast<Window>().Single(x => x.GetType() == type && x.IsVisible).Close(); await opening; await WaitJs("document.querySelector('.operation-status').textContent==='原生入口已打开'", "native-entry-returned");
        }
        await Js("[...document.querySelectorAll('.maintenance-row')].find(x=>x.querySelector('h3').textContent==='凭据管理').querySelector('button').click()");
        await Wait(() => app.MainWindow.IsVisible, "native-credential-management-entry"); ((AssistantWindow)app.MainWindow).Close(); shell.ReturnFocus();
        Require(await Js("!document.querySelector('.settings-notices').querySelector('input,select,textarea') && !document.body.textContent.includes(" + JsonSerializer.Serialize(token) + ")") == "true", "settings-no-secret-or-fake-configuration");
    }
    private sealed class MemoryFiles : IMemoryBackupFiles
    {
        private readonly NativeMemoryBackupFiles native = new();
        public MemoryExportDestination? PickExport(Window owner) => new(Setting("MEMORY_FILE"), false);
        public string? PickBackup(Window owner) => Setting("MEMORY_FILE");
        public string? PickTarget(Window owner) => Setting("MEMORY_TARGET");
        public Task<byte[]> ReadAsync(string file, CancellationToken ct) => native.ReadAsync(file, ct);
        public Task WriteAsync(MemoryExportDestination file, ReadOnlyMemory<byte> bytes, CancellationToken ct) => native.WriteAsync(file, bytes, ct);
    }
    private sealed class WorkspaceFiles : IWorkspaceBackupFiles
    {
        private readonly NativeWorkspaceBackupFiles native = new();
        public MemoryExportDestination? PickExport(Window owner) => new(Setting("WORKSPACE_FILE"), false);
        public string? PickBackup(Window owner) => Setting("WORKSPACE_FILE");
        public string? PickTarget(Window owner) => Setting("WORKSPACE_TARGET");
        public Stream OpenRead(string path) => native.OpenRead(path);
        public Task<WorkspaceBackupMetadata> ExportAsync(RuntimeClient client, MemoryExportDestination file, CancellationToken ct) => native.ExportAsync(client, file, ct);
    }
    private static async Task Backup()
    {
        await Route("conversations"); await Js("document.querySelector('.conversation-list button').click()");
        await WaitJs("document.getElementById('conversation-input') && !document.getElementById('conversation-input').readOnly", "real-react-conversation-created");
        await Insert("conversation-input", Case("conversation")); await Js("document.querySelector('.conversation-send .primary').click()");
        await WaitJs("document.querySelector('.conversation-history').textContent.includes('SUCCEEDED')", "terminal-conversation-for-backup", 180);
        var memory = new MemoryBackupWindow(runtime, new MemoryFiles()) { Owner = shell }; memory.Show(); await memory.ExportAsync(); Require(memory.StatusText.Text.StartsWith("Export complete"), "native-memory-backup-export-react-data"); memory.Close();
        var workspace = new WorkspaceBackupWindow(runtime, new WorkspaceFiles()) { Owner = shell }; workspace.Show(); await workspace.ExportAsync(); Require(workspace.StatusText.Text.StartsWith("Export complete"), "native-workspace-export-react-data"); workspace.Close();
        await Control("maintenance");
        memory = new MemoryBackupWindow(runtime, new MemoryFiles()) { Owner = shell }; memory.Show(); await memory.RestoreAsync(); Require(memory.StatusText.Text.StartsWith("Restore complete"), "native-memory-backup-isolated-restore"); memory.Close();
        workspace = new WorkspaceBackupWindow(runtime, new WorkspaceFiles()) { Owner = shell }; workspace.Show(); await workspace.ChooseAsync(); await workspace.RestoreAsync(); Require(workspace.StatusText.Text.StartsWith("Restore complete"), "native-workspace-backup-isolated-restore"); workspace.Close();
        await Control("memory-restored"); await ReloadDocument(); await Route("memory"); await Idle(); await Select(Case("title"));
        Require(await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("content"))) == "true", "react-memory-only-recovery-exact");
        await Control("workspace-restored"); await ReloadDocument(); await Route("memory"); await Idle(); await Select(Case("title"));
        Require(await Js("document.getElementById('memory-content').value===" + JsonSerializer.Serialize(Case("content"))) == "true", "react-workspace-memory-recovery-exact"); await Route("conversations");
        await WaitJs("document.querySelector('.conversation-history').textContent.includes(" + JsonSerializer.Serialize(Case("conversation")) + ")", "react-workspace-conversation-history-recovery");
    }
    private static async Task Pinyin()
    {
        await Click("New");
        Stage("pinyin-native-language-setup");
        var original = Forms.InputLanguage.CurrentInputLanguage;
        var chinese = Forms.InputLanguage.InstalledInputLanguages.Cast<Forms.InputLanguage>().First(x => x.Culture.Name == "zh-CN");
        var focus = Native.FocusWindow(new WindowInteropHelper(shell).Handle); var layout = GetKeyboardLayout(Native.GetWindowThreadProcessId(focus, out _));
        try
        {
            shell.ReturnFocus(); SetForegroundWindow(new WindowInteropHelper(shell).Handle); Forms.InputLanguage.CurrentInputLanguage = chinese;
            PostMessage(focus, 0x0050, IntPtr.Zero, chinese.Handle); await Task.Delay(200);
            foreach (var (id, expected, suffix) in new[] { ("memory-title", Case("imeTitle"), Case("imeTitleSuffix")), ("memory-content", Case("imeContent"), Case("imeContentSuffix")) })
            {
                Stage("pinyin-composition-" + id);
                await Js("(()=>{window.__ime={start:0,update:0,end:0};const t=document.getElementById('" + id + "');t.addEventListener('compositionstart',()=>window.__ime.start++);t.addEventListener('compositionupdate',()=>window.__ime.update++);t.addEventListener('compositionend',()=>window.__ime.end++);t.focus()})()");
                foreach (string value in new[] { "bendi", "gongzuoqu", "ceshi", "xinghe" }) { await Keys(value); Key(0x20); await Task.Delay(200); }
                await Keys(suffix); await Task.Delay(200);
                Require(await Js("window.__ime.start>0 && window.__ime.update>0 && window.__ime.end>0 && document.getElementById('" + id + "').value===" + JsonSerializer.Serialize(expected)) == "true", "real-windows-pinyin-" + id);
            }
            Require((await runtime.ListMemoryAsync(new(Case("imeTitle")), default)).Total == 0, "real-ime-editing-does-not-autosave");
            await Save(); realIme = true;
        }
        finally { PostMessage(focus, 0x0050, IntPtr.Zero, layout); Forms.InputLanguage.CurrentInputLanguage = original; }
    }
    private static void Key(byte value) { keybd_event(value, 0, 0, UIntPtr.Zero); keybd_event(value, 0, 2, UIntPtr.Zero); }
    private static async Task Keys(string text)
    { foreach (char value in text) {
        if (Native.GetForegroundWindow() != new WindowInteropHelper(shell).Handle) {
            Stage("physical-keyboard-workspace-focus-required"); throw new InvalidOperationException();
        }
        Key((byte)char.ToUpperInvariant(value)); await Task.Delay(75);
    } }
    private static async Task Screenshot(string name)
    { using var output = File.Create(Path.Combine(Setting("SCREENSHOTS"), "m5d-" + name + ".png")); await shell.Browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, output); }
}
