using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows.Interop;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using Forms = System.Windows.Forms;

namespace PersonalAiWorkspace.WorkspaceSanity;

// One cross-layer Windows flow; domain/recovery matrices belong to the owning automated suites.
internal static class Program
{
    private static AssistantApp app = null!;
    private static MainWorkspaceWindow shell = null!;
    private static RuntimeClient runtime = null!;
    private static readonly List<string> checks = [];
    private static string stage = "configuration";
    private static string? failure;
    private static int code = 1;
    private static string Setting(string name) => Environment.GetEnvironmentVariable("T0_" + name)
        ?? throw new InvalidOperationException("Missing sanity configuration.");
    private static string Json(string value) => JsonSerializer.Serialize(value);

    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")] private static extern bool IsChild(IntPtr parent, IntPtr child);
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint first, uint second, bool attach);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr window, uint message, IntPtr first, IntPtr second);
    [DllImport("user32.dll")] private static extern IntPtr GetKeyboardLayout(uint thread);
    [DllImport("user32.dll")] private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);

    [STAThread] private static int Main()
    {
        var credentials = new CredentialStore("PersonalAiWorkspace.WorkspaceSanity." + Guid.NewGuid().ToString("N"));
        using var single = new SingleInstance(".WorkspaceSanity." + Guid.NewGuid().ToString("N"));
        try
        {
            string token = File.ReadAllText(Setting("TOKEN_FILE")).Trim();
            credentials.Save(token);
            runtime = new RuntimeClient(() => token);
            app = new AssistantApp(single, credentials);
            app.Startup += (_, _) => app.Dispatcher.BeginInvoke(new Action(async () =>
            {
                try { await Drive().WaitAsync(TimeSpan.FromSeconds(120)); code = 0; }
                catch (Exception e) { failure = e.GetType().Name; }
                finally
                {
                    try { await app.ExitAsync(); }
                    catch (Exception e) { code = 1; failure ??= e.GetType().Name; Stage("desktop-shutdown"); app.Shutdown(1); }
                }
            }), DispatcherPriority.ApplicationIdle);
            app.Run();
            if (code == 0) Require(shell.Host.CleanupPassed && !app.TrayVisible, "clean-desktop-shutdown");
        }
        catch (Exception e) { code = 1; failure ??= e.GetType().Name; }
        finally { app?.Cleanup(); runtime?.Dispose(); credentials.Forget(); }
        Console.WriteLine(JsonSerializer.Serialize(new { result = code == 0 ? "PASS" : "FAIL", check = stage,
            failureType = failure, checks, independentExecutions = 1 }));
        return code;
    }

    private static void Stage(string name)
    {
        stage = name;
        File.WriteAllText(Setting("PROGRESS"), JsonSerializer.Serialize(new { check = name, completedChecks = checks.Count }));
    }
    private static void Require(bool value, string name)
    { Stage(name); if (!value) throw new InvalidOperationException(name); checks.Add(name); }
    private static Task<string> Js(string script) => shell.Browser.CoreWebView2.ExecuteScriptAsync(script).WaitAsync(TimeSpan.FromSeconds(10));
    private static async Task Wait(string name, Func<Task<bool>> ready, int seconds = 30)
    {
        Stage(name);
        var until = DateTime.UtcNow.AddSeconds(seconds);
        while (!await ready()) { if (DateTime.UtcNow >= until) throw new TimeoutException(); await Task.Delay(75); }
        checks.Add(name);
    }
    private static Task WaitJs(string expression, string name, int seconds = 30) => Wait(name, async () => await Js(expression) == "true", seconds);
    private static async Task Ready()
    {
        await Wait("webview-session-ready", () => Task.FromResult(shell.Browser.CoreWebView2 is not null && shell.Host.SessionId.Length > 0));
        await WaitJs("document.querySelector('.operation-status')?.textContent==='工作区已连接'", "react-connected");
    }
    private static async Task Route(string page)
    {
        await Js("document.querySelector('a[href=\"#/" + page + "\"]').click()");
        await WaitJs("document.querySelector('a[href=\"#/" + page + "\"]').getAttribute('aria-current')==='page'", "route-" + page);
    }
    private static Task Input(string id, string value) => Js("(()=>{const t=document.getElementById(" + Json(id) +
        ");Object.getOwnPropertyDescriptor(t.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set.call(t," +
        Json(value) + ");t.dispatchEvent(new Event('input',{bubbles:true}));t.focus()})()");
    private static async Task Click(string selector, string text)
    {
        Require(await Js("(()=>{const b=[...document.querySelector(" + Json(selector) + ").querySelectorAll('button')].find(x=>x.textContent.trim()===" +
            Json(text) + ");if(!b||b.disabled)return false;b.click();return true})()") == "true", "enabled-action");
    }

    private static async Task Drive()
    {
        shell = app.Workspace ?? throw new InvalidOperationException("Workspace did not start.");
        Require(shell.IsVisible, "real-workspace-startup");
        await Ready();
        Require(shell.Browser.CoreWebView2.Profile.IsInPrivateModeEnabled, "production-private-webview");
        await Route("memory");
        await WaitJs("!document.getElementById('memory-title').disabled", "memory-editor-ready");
        await Input("memory-title", Setting("MEMORY_TITLE"));
        await Input("memory-content", Setting("MEMORY_CONTENT"));
        await Click(".memory-editor", "Save");
        await WaitJs("document.querySelector('.memory-state').textContent.includes('Revision 1')", "react-memory-saved");
        var page = await runtime.ListMemoryAsync(new(Setting("MEMORY_TITLE")), default);
        var memory = await runtime.GetMemoryAsync(page.Items.Single().Id, default);
        Require(memory.Content == Setting("MEMORY_CONTENT") && memory.Revision == 1, "runtime-durable-memory");
        await Wait("saved-editor-clean", () => Task.FromResult(!shell.Host.HasDirtyEditor));

        using var source = new MemoryStream(Encoding.UTF8.GetBytes("预算\n" + Setting("SOURCE_TITLE")));
        var job = await runtime.UploadKnowledgeAsync(source, Setting("SOURCE_TITLE"), Guid.NewGuid().ToString("D"), null, null, default);
        await Wait("knowledge-source-ready", async () => (job = await runtime.GetKnowledgeImportAsync(job.RequestId, default)).State == "READY");
        await Wait("lexical-index-ready", async () => (await runtime.KnowledgeSearchStatusAsync(default)).State == "READY");
        await Route("knowledge");
        await WaitJs("!document.getElementById('knowledge-search-query').disabled", "knowledge-editor-ready");
        await Pinyin();
        await Click(".knowledge-search", "打开此位置");
        await WaitJs("document.querySelector('.knowledge-detail pre')?.textContent.includes('预算')===true", "search-result-preview");

        string previous = shell.Host.SessionId;
        shell.Browser.CoreWebView2.Reload();
        await Wait("replacement-session", () => Task.FromResult(shell.Host.SessionId.Length > 0 && shell.Host.SessionId != previous));
        await Ready();
        await WaitJs("document.getElementById('knowledge-search-query')?.value==='' && !document.querySelector('.knowledge-search h3,.knowledge-detail pre')",
            "reload-clears-query-and-loaded-source");
    }

    // Bounded foreground ownership and physical Pinyin approach retained from the approved K2 flow.
    private static async Task<IntPtr> PreparePhysicalInput()
    {
        var hwnd = new WindowInteropHelper(shell).Handle;
        Native.GetWindowThreadProcessId(hwnd, out uint owner);
        Require(IsWindow(hwnd) && owner == (uint)Environment.ProcessId, "physical-input-owned-window");
        shell.ReturnFocus(); SetForegroundWindow(hwnd);
        var until = DateTime.UtcNow.AddSeconds(1);
        while (Native.GetForegroundWindow() != hwnd && DateTime.UtcNow < until) await Task.Delay(50);
        if (Native.GetForegroundWindow() != hwnd)
        {
            uint current = GetCurrentThreadId(), foreground = Native.GetWindowThreadProcessId(Native.GetForegroundWindow(), out _);
            Require(foreground != 0 && foreground != current, "foreground-thread-available");
            bool attached = AttachThreadInput(current, foreground, true), detached = false;
            try { if (attached) { SetForegroundWindow(hwnd); shell.ReturnFocus(); } }
            finally { if (attached) detached = AttachThreadInput(current, foreground, false); }
            Require(attached && detached, "foreground-attachment-cleaned");
            until = DateTime.UtcNow.AddSeconds(2);
            while (Native.GetForegroundWindow() != hwnd && DateTime.UtcNow < until) await Task.Delay(50);
        }
        Require(Native.GetForegroundWindow() == hwnd, "workspace-foreground-established");
        shell.Browser.Focus(); await Js("document.getElementById('knowledge-search-query').focus()");
        await WaitJs("document.hasFocus() && document.activeElement===document.getElementById('knowledge-search-query') && !document.activeElement.disabled",
            "physical-search-target-focused", 3);
        Require(IsChild(hwnd, Native.FocusWindow(hwnd)), "physical-webview-child-focused");
        return hwnd;
    }
    private static void Key(byte key)
    {
        if (Native.GetForegroundWindow() != new WindowInteropHelper(shell).Handle) throw new InvalidOperationException("Lost physical input focus.");
        keybd_event(key, 0, 0, UIntPtr.Zero); keybd_event(key, 0, 2, UIntPtr.Zero);
    }
    private static async Task TypePinyin()
    { foreach (char letter in "yusuan") { Key((byte)char.ToUpperInvariant(letter)); await Task.Delay(90); } }
    private static async Task Pinyin()
    {
        await Js("(()=>{window.__ime={start:0,update:0,end:0,composing:false,submits:0,premature:0};const t=document.getElementById('knowledge-search-query');" +
            "t.addEventListener('compositionstart',()=>{window.__ime.start++;window.__ime.composing=true});t.addEventListener('compositionupdate',()=>window.__ime.update++);" +
            "t.addEventListener('compositionend',()=>{window.__ime.end++;window.__ime.composing=false});const post=chrome.webview.postMessage.bind(chrome.webview);" +
            "chrome.webview.postMessage=m=>{if(m.method==='knowledge.search'){window.__ime.submits++;if(window.__ime.composing)window.__ime.premature++}post(m)}})()");
        var original = Forms.InputLanguage.CurrentInputLanguage;
        var chinese = Forms.InputLanguage.InstalledInputLanguages.Cast<Forms.InputLanguage>().FirstOrDefault(x => x.Culture.Name == "zh-CN");
        Require(chinese is not null, "pinyin-installed");
        var hwnd = await PreparePhysicalInput();
        var focus = Native.FocusWindow(hwnd);
        var layout = GetKeyboardLayout(Native.GetWindowThreadProcessId(focus, out _));
        try
        {
            Forms.InputLanguage.CurrentInputLanguage = chinese!;
            Require(PostMessage(focus, 0x0050, IntPtr.Zero, chinese!.Handle), "pinyin-layout-requested");
            await Task.Delay(300); await PreparePhysicalInput(); await TypePinyin();
            Require(await Js("window.__ime.composing && window.__ime.start>0 && window.__ime.update>0") == "true", "genuine-pinyin-composition");
            Key(0x0D); await WaitJs("!window.__ime.composing", "composition-enter-commits");
            Require(await Js("window.__ime.submits===0 && window.__ime.premature===0") == "true", "composition-enter-no-submit");
            await Input("knowledge-search-query", "");
            await TypePinyin(); Key(0x20); await Task.Delay(300);
            Require(await Js("window.__ime.end>0 && document.getElementById('knowledge-search-query').value==='预算'") == "true", "physical-chinese-query-committed");
            Key(0x0D);
            await WaitJs("document.querySelector('.knowledge-search h4')?.textContent===" + Json(Setting("SOURCE_TITLE")), "physical-enter-lexical-hit");
            Require(await Js("window.__ime.submits===1 && window.__ime.premature===0") == "true", "one-physical-search-no-premature-submit");
        }
        finally { PostMessage(focus, 0x0050, IntPtr.Zero, layout); Forms.InputLanguage.CurrentInputLanguage = original; }
    }
}
