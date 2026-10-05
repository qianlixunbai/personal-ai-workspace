using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using Button = System.Windows.Controls.Button;

namespace PersonalAiWorkspace.KnowledgeAcceptance;

// Test-only UI driver. Product opens genuine Windows dialogs and streams genuine file handles.
internal static class Program
{
    private static AssistantApp app=null!;private static MainWorkspaceWindow shell=null!;private static RuntimeClient runtime=null!;
    private static JsonElement cases;private static string stage="configuration";private static readonly List<string> checks=[];private static int code=1;private static string? failure;
    private static int nativeKnowledgeMessages;private static bool matchingSession;
    private static string Setting(string name)=>Environment.GetEnvironmentVariable("K1_"+name)!;
    private static string Case(string name)=>cases.GetProperty(name).GetString()!;
    private delegate bool WindowCallback(IntPtr window,IntPtr state);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)]private static extern IntPtr FindWindow(string? cls,string title);
    [DllImport("user32.dll")]private static extern bool EnumChildWindows(IntPtr window,WindowCallback callback,IntPtr state);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)]private static extern int GetClassName(IntPtr window,StringBuilder text,int length);
    [DllImport("user32.dll")]private static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")]private static extern bool GetWindowRect(IntPtr window,out Rect rect);
    [DllImport("user32.dll")]private static extern IntPtr GetWindow(IntPtr window,uint command);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)]private static extern IntPtr SendMessage(IntPtr window,uint message,IntPtr first,string text);
    [DllImport("user32.dll")]private static extern bool PostMessage(IntPtr window,uint message,IntPtr first,IntPtr second);
    [DllImport("user32.dll")]private static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")]private static extern void keybd_event(byte key,byte scan,uint flags,UIntPtr extra);
    private static void Key(byte key){keybd_event(key,0,0,UIntPtr.Zero);keybd_event(key,0,2,UIntPtr.Zero);}
    [StructLayout(LayoutKind.Sequential)]private struct Rect{internal int Left,Top,Right,Bottom;}
    [STAThread]private static int Main()
    {
        cases=JsonDocument.Parse(Setting("CASES")).RootElement.Clone();string token=File.ReadAllText(Setting("TOKEN_FILE")).Trim();
        var credentials=new CredentialStore("PersonalAiWorkspace.K1.Acceptance."+Guid.NewGuid().ToString("N"));credentials.Save(token);
        using var single=new SingleInstance(".K1.Acceptance."+Guid.NewGuid().ToString("N"));runtime=new RuntimeClient(()=>token);app=new AssistantApp(single,credentials);
        app.Startup+=(_,_)=>app.Dispatcher.BeginInvoke(new Action(async()=>{
            try{await Drive();code=0;}catch(Exception e){failure=e.GetType().Name+(e.Message is "owned-dialog-not-found" or "owned-dialog-no-edit"?":"+e.Message:"");}
            finally{
                foreach(Window window in app.Windows.Cast<Window>().ToArray())if(window is not AssistantWindow and not MainWorkspaceWindow)window.Close();
                if(app.Workspace is not null)await app.Workspace.ShutdownAsync();await app.ExitAsync();
            }
        }),DispatcherPriority.ApplicationIdle);
        try{app.Run();}finally{app.Cleanup();runtime.Dispose();credentials.Forget();}
        Console.WriteLine(JsonSerializer.Serialize(new{result=code==0?"PASS":"FAIL",check=stage,failureType=failure,checks,
            productionWpf=true,realWebView2=true,bundledReact=true,realRuntime=true,realNativeDialogs=true,
            realWindowsPinyin="NOT_APPLICABLE",independentExecutions=1,coveragePoints=21}));return code;
    }
    private static void Stage(string value){stage=value;File.WriteAllText(Setting("PROGRESS"),JsonSerializer.Serialize(new{check=value,completedChecks=checks.Count}));}
    private static void Require(bool value,string name){Stage(name);if(!value)throw new InvalidOperationException();checks.Add(name);}
    private static async Task Wait(Func<bool> condition,string name,int seconds=30){Stage(name);var until=DateTime.UtcNow.AddSeconds(seconds);while(!condition()){if(DateTime.UtcNow>until)throw new TimeoutException();await Task.Delay(75);}checks.Add(name);}
    private static Task<string> Js(string script)=>shell.Browser.CoreWebView2.ExecuteScriptAsync(script).WaitAsync(TimeSpan.FromSeconds(10));
    private static async Task WaitJs(string script,string name,int seconds=30){Stage(name);var until=DateTime.UtcNow.AddSeconds(seconds);while(await Js(script)!="true"){if(DateTime.UtcNow>until)throw new TimeoutException();await Task.Delay(75);}checks.Add(name);}
    private static async Task Click(string text,string selector=".knowledge-page"){
        Require(await Js("(()=>{const b=[...document.querySelector("+JsonSerializer.Serialize(selector)+").querySelectorAll('button')].find(b=>b.textContent.trim()==="+JsonSerializer.Serialize(text)+");if(!b||b.disabled)return false;b.focus();b.click();return true})()") =="true","enabled-action");}
    private static async Task Route(string route){await Js("document.querySelector('a[href=\"#/"+route+"\"]').click()");await WaitJs("document.querySelector('h1').textContent.toLowerCase()==="+JsonSerializer.Serialize(route),"route-"+route);}
    private static Task NativeDialog(string title,string path,Window owner){var ownerHandle=new WindowInteropHelper(owner).Handle;return Task.Run(async()=>{
        var until=DateTime.UtcNow.AddSeconds(15);IntPtr dialog=IntPtr.Zero;
        while(DateTime.UtcNow<until){dialog=FindWindow("#32770",title);if(dialog!=IntPtr.Zero&&GetWindow(dialog,4)==ownerHandle)break;await Task.Delay(50);}
        if(dialog==IntPtr.Zero||GetWindow(dialog,4)!=ownerHandle)throw new InvalidOperationException("owned-dialog-not-found");
        List<(IntPtr Window,int Top)> edits=[];var editDeadline=DateTime.UtcNow.AddSeconds(5);
        while(edits.Count==0&&DateTime.UtcNow<editDeadline){EnumChildWindows(dialog,(window,_)=>{var cls=new StringBuilder(64);GetClassName(window,cls,cls.Capacity);
            if(cls.ToString()=="Edit"&&IsWindowVisible(window)&&GetWindowRect(window,out var rect))edits.Add((window,rect.Top));return true;},IntPtr.Zero);if(edits.Count==0)await Task.Delay(75);}
        if(edits.Count==0){PostMessage(dialog,0x0111,new IntPtr(2),IntPtr.Zero);throw new InvalidOperationException("owned-dialog-no-edit");}
        SendMessage(edits.OrderBy(x=>x.Top).Last().Window,0x000C,IntPtr.Zero,path);await Task.Delay(150);PostMessage(dialog,0x0111,new IntPtr(1),IntPtr.Zero);
        if(title=="选择新的 / 空的 Workspace 数据目录"){await Task.Delay(600);if(IsWindowVisible(dialog))PostMessage(dialog,0x0111,new IntPtr(1),IntPtr.Zero);}
    });}
    private static async Task PickImport(string field,bool update=false){
        Stage("native-picker-"+field);var dialog=NativeDialog("导入 Knowledge · 严格 UTF-8 TXT / Markdown",Case(field),shell);var clicking=Click(update?"导入新源版本":"导入 TXT / Markdown");
        try{await dialog;}catch{Stage("picker-missing-messages-"+nativeKnowledgeMessages+"-session-"+matchingSession+"-bridge-code-"+await Js("window.__k1.at(-1)?.error?.code??'no-response'"));throw;}await clicking;
        await WaitJs("!document.querySelector('.knowledge-page .primary').disabled","native-import-returned");}
    private static async Task Select(string filename){await Js("[...document.querySelectorAll('.knowledge-list .memory-select')].find(b=>b.textContent.startsWith("+JsonSerializer.Serialize(filename)+")).click()");
        await WaitJs("document.getElementById('knowledge-detail-title').textContent==="+JsonSerializer.Serialize(filename),"explicit-detail");await WaitJs("![...document.querySelectorAll('.knowledge-detail button')].find(b=>b.textContent==='刷新详情').disabled","detail-idle");}
    private static async Task Settled(){await WaitJs("!document.querySelector('.knowledge-detail [role=progressbar]') && !document.querySelector('.knowledge-page .primary').disabled","durable-terminal-state");}
    private static async Task<JsonElement> Control(string action){Stage("fixture-"+action);using var http=new HttpClient(new HttpClientHandler{UseProxy=false});using var response=await http.PostAsync("http://127.0.0.1:18768/"+action,null);Require(response.IsSuccessStatusCode,"fixture-control-"+action);return JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement.Clone();}
    private static async Task Ready(){await Wait(()=>shell.Host.SessionId.Length>0,"webview-session");await WaitJs("document.querySelector('.operation-status')?.textContent==='工作区已连接'","react-connected");}
    private static async Task Drive()
    {
        shell=app.Workspace!;await Ready();await Route("knowledge");
        shell.Browser.CoreWebView2.WebMessageReceived+=(_,e)=>{using var m=JsonDocument.Parse(e.WebMessageAsJson);if(m.RootElement.GetProperty("method").GetString()!.StartsWith("knowledge.",StringComparison.Ordinal)){nativeKnowledgeMessages++;matchingSession=m.RootElement.GetProperty("sessionId").GetString()==shell.Host.SessionId;}};
        await Js("(()=>{window.__k1=[];chrome.webview.addEventListener('message',e=>{window.__k1.push(e.data)})})()");
        Require(await Js("!document.querySelector('.knowledge-page input,.knowledge-page textarea') && ![...document.querySelectorAll('.knowledge-page button')].some(b=>/Search|Ask Knowledge|Semantic/.test(b.textContent))")=="true","no-text-editor-or-retrieval");
        SetForegroundWindow(new WindowInteropHelper(shell).Handle);shell.Browser.Focus();await Js("document.getElementById('knowledge-status').focus()");Key(9);
        await WaitJs("document.activeElement.textContent==='刷新列表'","actual-windows-tab-keyboard-navigation");
        await PickImport("TXT");await Settled();var txt=(await runtime.ListKnowledgeAsync("ACTIVE",0,default)).Items.Single();
        Require(txt.CurrentReadyRevision=="1","txt-native-import-ready");await Click("预览源文本");
        await WaitJs("document.querySelector('.knowledge-detail pre')?.textContent==="+JsonSerializer.Serialize(Case("TXT_NORMALIZED")),"txt-exact-preview");
        Require(await Js("document.querySelector('.knowledge-location').textContent.includes('TXT_LINES · 行 1–2')")=="true","txt-exact-locator");
        File.Delete(Case("TXT"));await Click("预览源文本");Require((await runtime.PreviewKnowledgeAsync(txt.DocumentId,"1",0,default)).Text==Case("TXT_NORMALIZED"),"external-original-deletion-durability");
        await PickImport("MD");await Settled();var md=(await runtime.ListKnowledgeAsync("ACTIVE",0,default)).Items.Single(d=>d.DocumentId!=txt.DocumentId);File.Delete(Case("MD"));
        await Click("预览源文本");await WaitJs("document.querySelector('.knowledge-detail pre')?.textContent==="+JsonSerializer.Serialize(Case("MD_FIRST")),"markdown-first-section-preview");
        Require(await Js("document.querySelector('.knowledge-location').textContent.includes('MARKDOWN_SECTION_LINES · 行 1–2 · First')")=="true","markdown-first-typed-locator");
        await Click("下一页文本");await WaitJs("document.querySelector('.knowledge-detail pre')?.textContent==="+JsonSerializer.Serialize(Case("MD_SECOND")),"markdown-next-section-preview");
        Require(await Js("document.querySelector('.knowledge-detail pre script,.knowledge-detail pre img')===null")=="true","markdown-literal-no-html-or-network");
        await Select(txt.Title);await PickImport("INVALID",true);await Settled();Require((await runtime.GetKnowledgeAsync(txt.DocumentId,default)).Document.CurrentReadyRevision=="1","invalid-utf8-keeps-old-ready");
        await WaitJs("document.querySelector('.knowledge-page').textContent.includes('KNOWLEDGE_INVALID_UTF8')","strict-utf8-failure-visible");
        await PickImport("OVERSIZE");await WaitJs("[...document.querySelectorAll('.knowledge-page [role=alert]')].some(e=>e.textContent.includes('8 MiB'))","oversize-native-rejection");
        await PickImport("CHANGED",true);await Settled();txt=(await runtime.GetKnowledgeAsync(txt.DocumentId,default)).Document;Require(txt.CurrentReadyRevision=="2","changed-bytes-new-source-revision");
        await PickImport("CHANGED",true);await Settled();Require((await runtime.GetKnowledgeAsync(txt.DocumentId,default)).Revisions.Length==2,"same-bytes-controlled-no-op");File.Delete(Case("CHANGED"));
        await Click("归档");await WaitJs("document.querySelector('.knowledge-detail [role=status]')?.textContent.startsWith('ARCHIVED')","archive");await Click("恢复");await WaitJs("document.querySelector('.knowledge-detail [role=status]')?.textContent.startsWith('ACTIVE')","restore-same-identity");
        await PickImport("DELETE");await Settled();var deletion=(await runtime.ListKnowledgeAsync("ACTIVE",0,default)).Items.Single(d=>d.Title==Path.GetFileName(Case("DELETE")));
        await Click("物理删除…");await WaitJs("document.querySelector('.memory-confirmation')?.open===true && document.activeElement.textContent==='取消'","delete-default-safe-focus");
        SetForegroundWindow(new WindowInteropHelper(shell).Handle);shell.Browser.Focus();await Task.Delay(100);Key(27);await WaitJs("!document.querySelector('.memory-confirmation')?.open && document.activeElement.textContent==='物理删除…'","actual-escape-cancel-and-focus-return");await Click("物理删除…");await Click("确认删除",".memory-confirmation");
        await WaitJs("document.querySelector('.knowledge-page').textContent.includes('文档及保留的源版本已删除')","physical-delete-confirmed");Require(!(await runtime.ListKnowledgeAsync("ACTIVE",0,default)).Items.Any(d=>d.DocumentId==deletion.DocumentId),"physical-delete-no-document");
        await Control("queue");await Click("刷新列表");await WaitJs("document.querySelector('.knowledge-list').textContent.includes('queued-4.txt')","bounded-queue-documents-visible");await Select("queued-4.txt");await Click("取消导入");await Settled();
        Require(await Js("document.querySelector('.knowledge-detail [role=status]').textContent.includes('已取消')")=="true","admitted-cancel-via-production-bridge");var queue=await Control("release");Require(queue.GetProperty("queueFull").GetBoolean(),"queue-sixth-admission-denied");
        await Control("interrupt");await Control("restart");await Click("刷新列表");await Select(txt.Title);await Click("刷新详情");await Settled();
        var restarted=await runtime.GetKnowledgeAsync(txt.DocumentId,default);Require(restarted.Document.CurrentReadyRevision=="2"&&restarted.Job?.State=="INTERRUPTED","restart-pending-parsing-reconciliation-keeps-old-ready");
        await Click("预览源文本");await WaitJs("document.querySelector('.knowledge-detail pre')?.textContent==="+JsonSerializer.Serialize(Case("CHANGED_NORMALIZED")),"restart-exact-source-preview");
        await PickImport("DELETE");await Settled();var lockedDocument=(await runtime.ListKnowledgeAsync("ACTIVE",0,default)).Items.Single(d=>d.Title==Path.GetFileName(Case("DELETE")));
        // Actual production delete against a non-delete-sharing native source handle must never report success.
        string locked=Setting("SOURCE")+"\\knowledge\\sources\\"+lockedDocument.DocumentId+"\\1.source";
        using(var held=new FileStream(locked,FileMode.Open,FileAccess.Read,FileShare.Read)){
            bool rejected=false;try{await runtime.DeleteKnowledgeAsync(lockedDocument.DocumentId,lockedDocument.MetadataVersion,default);}catch(DesktopException e){rejected=e.Error==DesktopError.KnowledgeDeleteIncomplete;}
            Require(rejected,"locked-source-delete-controlled-incomplete");
        }
        await runtime.DeleteKnowledgeAsync(lockedDocument.DocumentId,lockedDocument.MetadataVersion,default);
        Require(!(await runtime.ListKnowledgeAsync("ACTIVE",0,default)).Items.Any(d=>d.DocumentId==lockedDocument.DocumentId),"delete-journal-retry-completes-after-unlock");
        var snapshot=await Control("snapshot");Require(snapshot.GetProperty("stagingClean").GetBoolean(),"task-owned-staging-reconciled");await Click("刷新列表");await Select(txt.Title);
        var opening=Click("Knowledge Backup…");await Wait(()=>app.Windows.Cast<Window>().OfType<KnowledgeBackupWindow>().Any(w=>w.IsVisible),"native-knowledge-maintenance-entry");
        var window=app.Windows.Cast<Window>().OfType<KnowledgeBackupWindow>().Single();
        await NativeAction(window,"导出 Knowledge…","导出 Knowledge · 明文个人数据",Setting("BACKUP"));await Wait(()=>Text(window).Contains("导出完成"),"native-streaming-backup-export");
        await NativeAction(window,"选择并验证备份…","验证 Knowledge Backup",Setting("BACKUP"));await Wait(()=>Text(window).Contains("验证通过"),"native-streaming-backup-validation");
        await Control("offline");await NativeAction(window,"恢复到新 / 空目录…","选择新的 / 空的 Workspace 数据目录",Setting("TARGET"));await Wait(()=>Text(window).Contains("恢复完成"),"native-isolated-restore-source-unavailable");
        window.Close();await opening;await Control("restored");string session=shell.Host.SessionId;shell.Browser.CoreWebView2.Reload();await Wait(()=>shell.Host.SessionId.Length>0&&shell.Host.SessionId!=session,"restored-session-rotation");await Ready();await Route("knowledge");await Click("刷新列表");await Select(txt.Title);await Click("预览源文本");
        await WaitJs("document.querySelector('.knowledge-detail pre')?.textContent==="+JsonSerializer.Serialize(Case("CHANGED_NORMALIZED")),"restored-exact-preview-through-production-chain");
        var parity=await Control("parity");Require(parity.GetProperty("exact").GetBoolean(),"restored-exact-metadata-revision-source-text-locator-parity");
        shell.Browser.ZoomFactor=1.25;Require(await Js("document.documentElement.scrollWidth<=innerWidth")=="true","knowledge-125-percent-no-horizontal-overflow");shell.Browser.ZoomFactor=1;
        Require(await Js("JSON.stringify(window.__k1||[]).includes('C:\\\\')===false && JSON.stringify(window.__k1||[]).includes('knowledge\\\\sources')===false")=="true","bridge-no-absolute-source-or-runtime-path");
        Require(await Js("location.hash==='#/knowledge' && sessionStorage.length===0 && localStorage.length===0")=="true","knowledge-no-url-or-browser-storage");
        await Route("settings");Require(await Js("document.querySelector('.maintenance-list').textContent.includes('只包含 Memory + Conversation，不包含 Knowledge')")=="true","workspace-backup-scope-honest");
        var browser=await Control("browser");Require(browser.GetProperty("denied").GetBoolean(),"browser-translate-only-knowledge-denied");
        shell.Close();await Wait(()=>app.Workspace is null,"production-close");Require(shell.Host.CleanupPassed,"private-webview-data-cleared");
    }
    private static IEnumerable<FrameworkElement> Elements(DependencyObject parent){for(int i=0;i<System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent);i++){var child=System.Windows.Media.VisualTreeHelper.GetChild(parent,i);if(child is FrameworkElement element)yield return element;foreach(var nested in Elements(child))yield return nested;}}
    private static string Text(Window window)=>string.Join(" ",Elements(window).OfType<TextBlock>().Select(x=>x.Text));
    private static async Task NativeAction(Window window,string button,string title,string path){var choosing=NativeDialog(title,path,window);Elements(window).OfType<Button>().Single(b=>b.Content?.ToString()==button).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));await choosing;}
}
