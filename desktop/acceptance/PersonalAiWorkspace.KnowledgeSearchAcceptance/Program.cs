using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using Forms = System.Windows.Forms;

namespace PersonalAiWorkspace.KnowledgeSearchAcceptance;

// One production WPF/WebView2/React/Runtime flow. Evidence contains labels only.
internal static class Program
{
    private static AssistantApp app=null!;private static MainWorkspaceWindow shell=null!;private static RuntimeClient runtime=null!;
    private static JsonElement cases;private static string stage="configuration";private static readonly List<string> checks=[];
    private static int code=1;private static string? failure,failureMessage;private static string[]? failureStack;private static bool realIme;
    private static bool foregroundFallbackUsed,foregroundAttachSucceeded,foregroundDetachSucceeded;
    private static string Setting(string name)=>Environment.GetEnvironmentVariable("K2_"+name)!;
    private static string Case(string name)=>cases.GetProperty(name).GetString()!;
    [DllImport("user32.dll")]private static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")]private static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")]private static extern bool IsChild(IntPtr parent,IntPtr child);
    [DllImport("user32.dll")]private static extern bool AttachThreadInput(uint first,uint second,bool attach);
    [DllImport("kernel32.dll")]private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")]private static extern bool PostMessage(IntPtr window,uint message,IntPtr first,IntPtr second);
    [DllImport("user32.dll")]private static extern IntPtr GetKeyboardLayout(uint thread);
    [DllImport("user32.dll")]private static extern void keybd_event(byte key,byte scan,uint flags,UIntPtr extra);
    private static void Key(byte key){Require(Native.GetForegroundWindow()==new WindowInteropHelper(shell).Handle,"physical-keyboard-workspace-focus");keybd_event(key,0,0,UIntPtr.Zero);keybd_event(key,0,2,UIntPtr.Zero);}
    [STAThread]private static int Main()
    {
        cases=JsonDocument.Parse(Setting("CASES")).RootElement.Clone();string token=File.ReadAllText(Setting("TOKEN_FILE")).Trim();
        var credentials=new CredentialStore("PersonalAiWorkspace.K2.Acceptance."+Guid.NewGuid().ToString("N"));credentials.Save(token);
        using var single=new SingleInstance(".K2.Acceptance."+Guid.NewGuid().ToString("N"));runtime=new RuntimeClient(()=>token);app=new AssistantApp(single,credentials);
        app.Startup+=(_,_)=>app.Dispatcher.BeginInvoke(new Action(async()=>{
            try{await Drive();code=0;}catch(Exception e){failure=e.GetType().Name;
                failureMessage=e is InvalidOperationException&&e.Message==stage?stage:null;
                failureStack=new System.Diagnostics.StackTrace(e,true).GetFrames()
                    .Where(f=>f.GetMethod()?.DeclaringType?.Namespace==typeof(Program).Namespace)
                    .Select(f=>f.GetMethod()!.DeclaringType!.Name+"."+f.GetMethod()!.Name+":"+f.GetFileLineNumber()).ToArray();}
            finally{if(app.Workspace is not null)await app.Workspace.ShutdownAsync();await app.ExitAsync();}
        }),DispatcherPriority.ApplicationIdle);
        try{app.Run();}finally{app.Cleanup();runtime.Dispose();credentials.Forget();}
        Console.WriteLine(JsonSerializer.Serialize(new{result=code==0?"PASS":"FAIL",check=stage,failureType=failure,failureMessage,failureStack,checks,
            productionWpf=true,realWebView2=true,bundledReact=true,realRuntime=true,realWindowsPinyin=realIme,
            foregroundFallbackUsed,foregroundAttachSucceeded,foregroundDetachSucceeded,independentExecutions=1}));return code;
    }
    private static void Stage(string value){stage=value;File.WriteAllText(Setting("PROGRESS"),JsonSerializer.Serialize(new{check=value,completedChecks=checks.Count}));}
    private static void Require(bool value,string name){Stage(name);if(!value)throw new InvalidOperationException(name);checks.Add(name);}
    private static Task<string> Js(string script)=>shell.Browser.CoreWebView2.ExecuteScriptAsync(script).WaitAsync(TimeSpan.FromSeconds(10));
    private static async Task WaitJs(string script,string name,int seconds=30){Stage(name);var until=DateTime.UtcNow.AddSeconds(seconds);while(await Js(script)!="true"){if(DateTime.UtcNow>until)throw new TimeoutException();await Task.Delay(75);}checks.Add(name);}
    private static async Task<bool> AsyncBoolean(string expression)
    {
        try{
            await Js("(()=>{window.__k2Async={done:false};(async()=>{try{const value=await ("+expression+");window.__k2Async={done:true,ok:typeof value==='boolean',value}}catch{window.__k2Async={done:true,ok:false}}})();})()");
            await WaitJs("window.__k2Async.done===true","async-result-completed",10);
            Require(await Js("window.__k2Async.ok===true") =="true","async-result-is-completed-boolean");
            return await Js("window.__k2Async.value===true") =="true";
        }finally{await Js("delete window.__k2Async");}
    }
    private static async Task Ready(){var end=DateTime.UtcNow.AddSeconds(30);while(shell.Browser.CoreWebView2 is null||shell.Host.SessionId.Length==0){if(DateTime.UtcNow>end)throw new TimeoutException();await Task.Delay(75);}await WaitJs("document.querySelector('.operation-status')?.textContent==='工作区已连接'","react-connected");await Js("document.querySelector('a[href=\"#/knowledge\"]').click()");await WaitJs("document.getElementById('knowledge-search-query')!==null","production-keyword-input");}
    private static async Task Capture(){await Js("(()=>{window.__k2=[];chrome.webview.addEventListener('message',e=>{window.__k2.push(e.data);window.__k2=window.__k2.slice(-64)})})()");}
    private static async Task Click(string text){Require(await Js("(()=>{const b=[...document.querySelector('.knowledge-page').querySelectorAll('button')].find(b=>b.textContent.trim()==="+JsonSerializer.Serialize(text)+");if(!b||b.disabled)return false;b.focus();b.click();return true})()") =="true","enabled-action");}
    private static async Task IndexReady(){Stage("index-ready");var end=DateTime.UtcNow.AddSeconds(20);while((await runtime.KnowledgeSearchStatusAsync(default)).State!="READY"){if(DateTime.UtcNow>end)throw new TimeoutException();await Task.Delay(100);}checks.Add("index-ready");}
    private static async Task Input(string query){await WaitJs("!document.getElementById('knowledge-search-query').disabled","search-input-idle");
        await Js("(()=>{const t=document.getElementById('knowledge-search-query');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(t,"+JsonSerializer.Serialize(query)+");t.dispatchEvent(new Event('input',{bubbles:true}));t.focus()})()");}
    private static async Task Search(string query){await IndexReady();await Input(query);await Click("检索");await WaitJs("document.querySelector('.knowledge-search h3')!==null","explicit-search-result");}
    private static async Task<JsonElement> Hits(){string text=await Js("JSON.stringify(window.__k2.filter(r=>Array.isArray(r.result?.hits)).at(-1).result.hits)");return JsonDocument.Parse(JsonSerializer.Deserialize<string>(text)!).RootElement.Clone();}
    private static async Task<JsonElement> Control(string action){Stage("fixture-"+action);using var http=new HttpClient(new HttpClientHandler{UseProxy=false});using var response=await http.PostAsync("http://127.0.0.1:18768/"+action,null);Require(response.IsSuccessStatusCode,"fixture-control-"+action);return JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement.Clone();}
    private static async Task Reload(){shell.Browser.CoreWebView2.Reload();await Task.Delay(600);await Ready();await Capture();await IndexReady();}
    private static async Task Refresh(){await Click("刷新列表");await WaitJs("!document.getElementById('knowledge-search-query').disabled","refreshed-keyword-input");}
    private static async Task Drive()
    {
        shell=app.Workspace!;await Ready();await Capture();
        Require(await Js("(async()=>true)()") =="{}","async-smoke-legacy-promise-object");
        Require(await AsyncBoolean("(async()=>{await Promise.resolve();return true})()"),"async-smoke-completed-boolean");
        await IndexReady();await Search("budget");var first=await Hits();
        Require(first.GetArrayLength()==3&&first[0].GetProperty("title").GetString()==Case("TITLE"),"bm25-title-heading-body-ranking");
        Require(first[1].GetProperty("title").GetString()==Case("HEADING"),"markdown-heading-above-body");
        await Click("打开此位置");await WaitJs("document.querySelector('.knowledge-detail pre')!==null","result-opens-exact-preview");
        Require(await Js("document.querySelector('.knowledge-location').textContent.includes('文本偏移 '+"+first[0].GetProperty("startOffset").GetInt32()+")") =="true","exact-source-preview-offset");
        var once=await runtime.SearchKnowledgeAsync("budget",10,default);var twice=await runtime.SearchKnowledgeAsync("budget",10,default);
        Require(JsonSerializer.Serialize(once)==JsonSerializer.Serialize(twice),"deterministic-logical-order");
        await Search(Case("QUERY"));Require((await Hits()).GetArrayLength()==3,"fresh-latin-query-canary");
        Require(await Js("!location.href.includes("+JsonSerializer.Serialize(Case("QUERY"))+")&&!JSON.stringify({...localStorage,...sessionStorage}).includes("+JsonSerializer.Serialize(Case("QUERY"))+")") =="true","query-absent-url-storage");
        // Empty database/cache namespaces positively prove no query canary was persisted there.
        Require(await AsyncBoolean("(async()=> (await indexedDB.databases()).length===0&&(await caches.keys()).length===0)()"),"no-indexeddb-or-cache-query-storage");
        Require(await Js("window.__k2.filter(r=>Array.isArray(r.result?.hits)).every(r=>!/(sourceDigest|representationDigest|corpusFingerprint|tokens|bm25|rowid|absolutePath)/.test(JSON.stringify(r)))") =="true","bridge-no-digest-index-metadata");
        Require(await Js("document.querySelector('.knowledge-search script,.knowledge-search img,.knowledge-search iframe')===null") =="true","snippet-literal-no-html-execution");
        shell.Browser.ZoomFactor=1.25;Require(await Js("document.documentElement.scrollWidth<=document.documentElement.clientWidth") =="true","search-125-percent-no-horizontal-overflow");shell.Browser.ZoomFactor=1;
        await Input("");await Pinyin();Require(realIme,"real-windows-pinyin-search-pass");
        var doc=(await runtime.ListKnowledgeAsync("ACTIVE",0,default)).Items.Single(d=>d.Title==Case("TITLE"));
        doc=await runtime.KnowledgeLifecycleAsync(doc.DocumentId,doc.MetadataVersion,"archive",default);await IndexReady();await Refresh();await Search("budget");Require((await Hits()).GetArrayLength()==2,"archive-absent-active-search");
        doc=await runtime.KnowledgeLifecycleAsync(doc.DocumentId,doc.MetadataVersion,"restore",default);await IndexReady();await Refresh();await Search("budget");Require((await Hits()).GetArrayLength()==3,"restore-reappears-search");
        await Update(doc,"replacementonly 预算 "+Case("QUERY"));await IndexReady();await Refresh();await Search("originalonly");Require((await Hits()).GetArrayLength()==0,"new-ready-replaces-old-corpus");
        await Search("replacementonly");Require((await Hits())[0].GetProperty("sourceRevision").GetString()=="2","current-ready-exact-revision");
        doc=(await runtime.GetKnowledgeAsync(doc.DocumentId,default)).Document;using(var bad=new MemoryStream(new byte[]{0xc3,0x28})){
            var job=await runtime.UploadKnowledgeAsync(bad,"invalid.txt",Guid.NewGuid().ToString("D"),doc.DocumentId,doc.MetadataVersion,default);await Job(job.RequestId,"FAILED");}
        await Search("replacementonly");Require((await Hits())[0].GetProperty("sourceRevision").GetString()=="2","failed-revision-keeps-current-searchable");
        await Click("重建关键词索引");await IndexReady();await Search("replacementonly");Require((await Hits()).GetArrayLength()==1,"explicit-derived-rebuild");
        foreach(string action in new[]{"missing","corrupt"}){await Control(action);await Reload();await Search("replacementonly");Require((await Hits()).GetArrayLength()==1,"index-"+action+"-recovery");}
        Require((await Control("restore")).GetProperty("exact").GetBoolean(),"backup-truth-source-and-search-parity");await Reload();await Search("replacementonly");Require((await Hits())[0].GetProperty("sourceRevision").GetString()=="2","restored-runtime-rebuild-parity");
        Require((await Control("browser")).GetProperty("denied").GetBoolean(),"browser-search-status-rebuild-denied");
        await Js("document.querySelector('a[href=\"#/assistant\"]').click()");await Js("document.querySelector('a[href=\"#/knowledge\"]').click()");
        Require(await Js("document.getElementById('knowledge-search-query').value===''") =="true","query-cleared-on-route-leave");
    }
    private static async Task Job(string id,string expected){var end=DateTime.UtcNow.AddSeconds(10);KnowledgeJob job;
        do{job=await runtime.GetKnowledgeImportAsync(id,default);if(job.State is not ("PENDING" or "PARSING"))break;await Task.Delay(100);}while(DateTime.UtcNow<end);
        Require(job.State==expected,"source-update-terminal-"+expected.ToLowerInvariant());}
    private static async Task Update(KnowledgeDocument doc,string text){using var bytes=new MemoryStream(Encoding.UTF8.GetBytes(text));var job=await runtime.UploadKnowledgeAsync(bytes,doc.Title,Guid.NewGuid().ToString("D"),doc.DocumentId,doc.MetadataVersion,default);await Job(job.RequestId,"READY");}
    private static async Task<IntPtr> PreparePhysicalInput()
    {
        shell.Dispatcher.VerifyAccess();var hwnd=new WindowInteropHelper(shell).Handle;
        Native.GetWindowThreadProcessId(hwnd,out uint owner);
        Require(IsWindow(hwnd)&&owner==(uint)Environment.ProcessId,"physical-input-owned-live-window");
        if(!shell.IsVisible)shell.Show();if(shell.WindowState==WindowState.Minimized)shell.WindowState=WindowState.Normal;
        shell.ReturnFocus();SetForegroundWindow(hwnd);
        var until=DateTime.UtcNow.AddSeconds(1);
        while(Native.GetForegroundWindow()!=hwnd&&DateTime.UtcNow<until)await Task.Delay(50);
        if(Native.GetForegroundWindow()!=hwnd){
            // Last-resort acceptance path already used by Product/MainWorkspace acceptance.
            uint current=GetCurrentThreadId(),foreground=Native.GetWindowThreadProcessId(Native.GetForegroundWindow(),out _);
            foregroundFallbackUsed=true;
            Require(foreground!=0&&foreground!=current,"physical-input-foreground-thread-available");
            bool attached=AttachThreadInput(current,foreground,true);foregroundAttachSucceeded=attached;
            try{if(attached){SetForegroundWindow(hwnd);shell.ReturnFocus();}}
            finally{if(attached)foregroundDetachSucceeded=AttachThreadInput(current,foreground,false);}
            Require(attached&&foregroundDetachSucceeded,"physical-input-foreground-attachment-cleaned");
            until=DateTime.UtcNow.AddSeconds(2);
            while(Native.GetForegroundWindow()!=hwnd&&DateTime.UtcNow<until)await Task.Delay(50);
        }
        Require(IsWindow(hwnd)&&Native.GetForegroundWindow()==hwnd,"physical-input-workspace-foreground-established");
        shell.Browser.Focus();await Js("document.getElementById('knowledge-search-query').focus()");
        await WaitJs("document.hasFocus()&&document.activeElement===document.getElementById('knowledge-search-query')&&!document.activeElement.disabled","physical-input-search-target-focused",3);
        var focus=Native.FocusWindow(hwnd);
        Require(Native.GetForegroundWindow()==hwnd&&focus!=IntPtr.Zero&&IsChild(hwnd,focus),"physical-input-webview-child-focused");
        return hwnd;
    }
    private static async Task Pinyin()
    {
        Stage("native-pinyin");await Js("(()=>{window.__ime={start:0,end:0,update:0,composing:false,premature:0,submits:0};const t=document.getElementById('knowledge-search-query');t.addEventListener('compositionstart',()=>{window.__ime.start++;window.__ime.composing=true});t.addEventListener('compositionupdate',()=>window.__ime.update++);t.addEventListener('compositionend',()=>{window.__ime.end++;window.__ime.composing=false});const post=chrome.webview.postMessage.bind(chrome.webview);chrome.webview.postMessage=m=>{if(m.method==='knowledge.search'){window.__ime.submits++;if(window.__ime.composing)window.__ime.premature++}post(m)}})()");
        var original=Forms.InputLanguage.CurrentInputLanguage;var chinese=Forms.InputLanguage.InstalledInputLanguages.Cast<Forms.InputLanguage>().FirstOrDefault(x=>x.Culture.Name=="zh-CN");Require(chinese is not null,"pinyin-installed");
        var hwnd=await PreparePhysicalInput();var focus=Native.FocusWindow(hwnd);var layout=GetKeyboardLayout(Native.GetWindowThreadProcessId(focus,out _));
        try{
            Forms.InputLanguage.CurrentInputLanguage=chinese!;PostMessage(focus,0x0050,IntPtr.Zero,chinese!.Handle);await Task.Delay(300);await PreparePhysicalInput();
            foreach(char letter in "yusuan"){Key((byte)char.ToUpperInvariant(letter));await Task.Delay(90);}
            Require(await Js("window.__ime.composing&&window.__ime.start>0&&window.__ime.update>0") =="true","genuine-pinyin-before-composition-enter");
            Key(0x0D);await WaitJs("!window.__ime.composing","composition-enter-commits");
            Require(await Js("window.__ime.submits===0&&window.__ime.premature===0") =="true","composition-enter-does-not-submit");
            await Input("");
            foreach(char letter in "yusuan"){Key((byte)char.ToUpperInvariant(letter));await Task.Delay(90);}Key(0x20);await Task.Delay(300);
            Require(await Js("window.__ime.start>0&&window.__ime.update>0&&window.__ime.end>0&&window.__ime.submits===0&&document.getElementById('knowledge-search-query').value==='预算'") =="true","genuine-pinyin-composition-committed-query");
            Key(0x0D);await WaitJs("document.querySelector('.knowledge-search h3')!==null","physical-enter-search-submit");
            Require(await Js("window.__ime.submits===1&&window.__ime.premature===0") =="true","pinyin-no-premature-query");Require((await Hits()).GetArrayLength()==3,"chinese-lexical-results");realIme=true;
            Require(await Js("!location.href.includes('预算')&&!location.href.includes(encodeURIComponent('预算'))&&!JSON.stringify({...localStorage,...sessionStorage}).includes('预算')") =="true","pinyin-query-absent-url-storage");
            Require(await AsyncBoolean("(async()=> (await indexedDB.databases()).length===0&&(await caches.keys()).length===0)()"),"pinyin-query-absent-indexeddb-cache");
        }finally{PostMessage(focus,0x0050,IntPtr.Zero,layout);Forms.InputLanguage.CurrentInputLanguage=original;}
    }
}
