#if MMF3_PROFILE_ACCEPTANCE && DEBUG && RUNTIME_LIFECYCLE_PROBE
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using Xunit;
namespace PersonalAiWorkspace.Desktop.Tests;
public static partial class RuntimeLifecycleProbe
{
    // Test-owned shell uses production WebView, bridge and Runtime owner, but never AssistantApp's
    // saved-selection writer, tray or global hotkeys. No fake HTTP persistence or model producer.
    private sealed class MemoryDraftActions : IWorkspaceNativeActions, IDisposable
    {
        internal readonly RuntimeClient Client;
        internal readonly DevelopmentRuntimeOwner Owner;
        public WorkspaceMemory Memory { get; }
        public WorkspaceKnowledge Knowledge { get; }
        internal MemoryDraftActions(Mmf3AcceptanceLaunch launch)
        {
            DevelopmentRuntimeOwner? owner=null;
            Client=RuntimeClient.CreateMmf3Acceptance(()=>owner?.ReadCredential());
            Owner=owner=new(launch,Client); Memory=new(Client); Knowledge=new(Client,null!);
        }
        public Task<ShellStatus> StatusAsync(CancellationToken ct) => Task.FromResult(new ShellStatus(1,"1.0.0.0",Owner.Snapshot().CanQueryStorage?ShellRuntimeState.Available:ShellRuntimeState.Unavailable,Owner.ReadCredential() is null?ShellCredentialState.Missing:ShellCredentialState.Ready,"Available",[]));
        public Task<StorageStatus> StorageStatusAsync(CancellationToken ct)=>Client.GetStorageStatusAsync(ct);
        public Task<StorageStartupStatus> StorageStartupStatusAsync(CancellationToken ct)=>Owner.ReadAsync(ct);
        public event Action<StorageStartupStatus>? StorageStartupChanged { add=>Owner.Changed+=value; remove=>Owner.Changed-=value; }
        public Task OpenAsync(NativeWorkspaceEntry entry,CancellationToken ct)=>throw new InvalidOperationException("No native external actions in owned fixture");
        public void Dispose(){Owner.Dispose();Client.Dispose();}
    }
    private static int RunMemoryDraftHost(string id,string mode)
    {
        if(mode is not ("init" or "startup" or "save" or "reopen"))return 2;
        var launch=Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument,id]);
        using var pin=Mmf3ProfileState.Pin(id);
        string evidence=Path.Combine(launch.Root,"evidence","memory-draft");Directory.CreateDirectory(evidence);
        Console.WriteLine(JsonSerializer.Serialize(new{contract="MEMORY_DRAFT_OWNED_PROFILE_V1",id,mode,root=launch.Root,profile=launch.Profile,selectionWriterUsed=false}));
        string stage="runtime", answer="Synthetic violet review rule: confirm the offline preview before saving.\nThis answer is unverified test content.";
        bool passed=false; int? javaPid=null; var methods=new List<string>();
        var app=new Application {ShutdownMode=ShutdownMode.OnExplicitShutdown};
        using var actions=new MemoryDraftActions(launch);
        using var deadline=new CancellationTokenSource(TimeSpan.FromMinutes(3));var ct=deadline.Token;
        MainWorkspaceWindow? window=null;
        MemoryDraftStartupTrace? startupTrace=null;
        void Save(string suffix,object value)=>File.WriteAllText(Path.Combine(evidence,mode+suffix),JsonSerializer.Serialize(value,new JsonSerializerOptions{WriteIndented=true}));
        async Task Wait(Func<Task<bool>> condition,int seconds=55){var clock=Stopwatch.StartNew();while(!await condition()){Assert.True(clock.Elapsed.TotalSeconds<seconds,stage);await Task.Delay(100,ct);}}
        async Task WebViewReady(){stage="webview";Assert.NotNull(window);await Wait(()=>Task.FromResult(window.Host.IsReady||window.Host.StartupFailureCode is not null));Assert.Null(window.Host.StartupFailureCode);}
        app.Startup+=async(_,_)=>{
            try{
                if(mode!="startup")actions.Owner.Start(ct);
                if(mode!="init"){window=new(actions,confirmation:()=>true,acceptance:launch);startupTrace=new(window);app.MainWindow=window;window.Show();}
                // Isolate controller/message-pump diagnosis from Runtime/model/data preparation.
                if(mode=="startup"){await WebViewReady();passed=true;return;}
                await Wait(()=>Task.FromResult(actions.Owner.Snapshot().CanQueryStorage));javaPid=actions.Owner.OwnedProcessId;
                Assert.Equal(0,(await actions.Client.ListKnowledgeAsync("ACTIVE",0,ct)).Total);
                if(mode=="init") {Assert.Equal(0,(await actions.Client.ListMemoryAsync(new(),ct)).Total);Assert.Equal(0,(await actions.Client.ListKnowledgeAnswersAsync(0,ct)).Total);passed=true;return;}
                var history=Assert.Single((await actions.Client.ListKnowledgeAnswersAsync(0,ct)).Items);
                var original=await actions.Client.GetKnowledgeAnswerHistoryAsync(Guid.Parse(history.TaskId),ct);Assert.Equal(answer,original.Task.Result!.Answer);
                string before=JsonSerializer.Serialize(original);
                await WebViewReady();var core=window!.Browser.CoreWebView2;
                core.WebMessageReceived+=(_,message)=>{using var json=JsonDocument.Parse(message.WebMessageAsJson);if(json.RootElement.TryGetProperty("method",out var method))methods.Add(method.GetString()!);};
                Task<string> Script(string js)=>core.ExecuteScriptAsync(js);
                async Task<bool> Is(string js)=>await Script(js)=="true";
                string J(string v)=>JsonSerializer.Serialize(v);
                async Task Click(string label){string expr="[...document.querySelectorAll('button')].find(b=>b.getClientRects().length&&!b.disabled&&b.textContent==="+J(label)+")";await Wait(()=>Is("Boolean("+expr+")"));await Script(expr+".click()");}
                async Task Input(string name,string value)=>_=await Script("(()=>{const e=document.getElementById("+J(name)+");Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value').set.call(e,"+J(value)+");e.dispatchEvent(new Event('input',{bubbles:true}));})()");
                async Task Snapshot(string name){using var file=File.Create(Path.Combine(evidence,mode+"-"+name+".png"));await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png,file);}
                async Task History(){await Script("location.hash='#/knowledge'");await Wait(()=>Is("Boolean(document.querySelector('.knowledge-answer'))"));await Script("document.querySelector('.knowledge-answer').closest('details').open=true");await Click("查看问答历史");await Click(history.Question);await Wait(()=>Is("document.querySelector('.knowledge-answer .knowledge-snippet')?.textContent==="+J(answer)));}
                if(mode=="save"){
                    stage="historical-draft";Assert.Equal(0,(await actions.Client.ListMemoryAsync(new(),ct)).Total);
                    await History();await Click("整理为记忆草稿");await Wait(()=>Is("document.getElementById('memory-content')?.value==="+J(answer)));
                    Assert.True(await Is("document.body.innerText.includes('助手回复可能不准确')"));Assert.Equal(0,(await actions.Client.ListMemoryAsync(new(),ct)).Total);
                    await Snapshot("draft-unverified");stage="cancel-draft";
                    await Click("新建");await Click("丢弃并继续");await Wait(()=>Is("document.getElementById('memory-content')?.value===''") );
                    Assert.Equal(0,(await actions.Client.ListMemoryAsync(new(),ct)).Total);
                    stage="explicit-save";await History();await Click("整理为记忆草稿");await Wait(()=>Is("document.getElementById('memory-content')?.value==="+J(answer)));
                    await Input("memory-title","Synthetic violet reviewed memory");
                    await Click("保存");
                    // Repeat physical click only while the exact same editor is visible. Busy/clean guards must prevent duplicate writes.
                    await Script("[...document.querySelectorAll('button')].find(b=>b.textContent==='保存')?.click()");
                    await Wait(async()=>(await actions.Client.ListMemoryAsync(new(),ct)).Total==1);
                    await Wait(()=>Is("!document.querySelector('.memory-state')?.textContent.includes('未保存')"));
                }else{
                    stage="independent-reopen";await Script("location.hash='#/memory'");await Click("Synthetic violet reviewed memory");await Wait(()=>Is("document.getElementById('memory-content')?.value==="+J(answer)));
                }
                var item=Assert.Single((await actions.Client.ListMemoryAsync(new(),ct)).Items);var saved=await actions.Client.GetMemoryAsync(item.Id,ct);
                Assert.Equal(answer,saved.Content);Assert.Equal("Synthetic violet reviewed memory",saved.Title);Assert.Equal("MANUAL",saved.Source.ToString());
                Assert.Equal(before,JsonSerializer.Serialize(await actions.Client.GetKnowledgeAnswerHistoryAsync(Guid.Parse(history.TaskId),ct)));
                Assert.DoesNotContain(methods,m=>m is "knowledge.answerSubmit" or "models.mutate" or "memory.candidateSubmit" or "assistant.proposeMemory" or "assistant.submit");
                Assert.Equal(mode=="save"?1:0,methods.Count(m=>m=="memory.create"));
                Assert.True(await Is("document.body.innerText.includes('由你确认保存（MANUAL）')&&document.body.innerText.includes('不代表内容已经核实')"));
                await Snapshot("saved");Save("-record.json",new{memoryId=item.Id,saved.Title,saved.Content,source=saved.Source.ToString(),answerId=history.TaskId,originalAnswerUnchanged=true,assistantProvenancePersisted=false,methods});
                passed=true;
            }catch(Exception e){
                int? memoryCount=null;
                try{memoryCount=(await actions.Client.ListMemoryAsync(new(),ct)).Total;}catch{}
                Save("-failure.json",new{stage,type=e.GetType().Name,message=e.Message,stack=e.StackTrace,runtime=actions.Owner.Snapshot(),memoryCount,methods});
            }
            finally{startupTrace?.Dispose();if(window is not null)await window.ShutdownAsync();await actions.Owner.StopAsync();Save("-result.json",new{passed,stage,appPid=Environment.ProcessId,javaPid,modelCalls=0,fixtureHost=true,selectionWriterUsed=false});app.Shutdown(passed?0:10);}
        };
        app.Run();Console.WriteLine("MEMORY_DRAFT_DESKTOP_"+(passed?"PASS":"STOP")+" "+mode+" "+stage);return passed?0:10;
    }
}
#endif
