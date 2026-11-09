#if MMF3_PROFILE_ACCEPTANCE && DEBUG && RUNTIME_LIFECYCLE_PROBE
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows.Automation;
using System.Windows.Interop;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    [System.Runtime.InteropServices.DllImport("user32.dll",CharSet=System.Runtime.InteropServices.CharSet.Unicode,EntryPoint="GetWindowTextW")]
    private static extern int WordDialogTitle(IntPtr window,StringBuilder text,int size);
    private static void VerifyWordImport(AssistantApp app,Mmf3AcceptanceLaunch launch,bool reopen,string run,bool compareVersions=false)
    {
        string root=Path.Combine(launch.Root,"evidence","docx-import-"+run),stage="STARTUP",prefix=reopen?"reopen":"first";
        if(!reopen){Assert.False(Directory.Exists(root));Directory.CreateDirectory(root);}else Assert.True(Directory.Exists(root));
        void Save(string name,object value)=>File.WriteAllText(Path.Combine(root,prefix+name),JsonSerializer.Serialize(value,WebKnowledgeJson));
        bool passed=false;Process? java=null;
        app.Startup+=async(_,_)=>{
            using var deadline=new CancellationTokenSource(TimeSpan.FromMinutes(4));var ct=deadline.Token;
            async Task Wait(Func<Task<bool>> ready){var clock=Stopwatch.StartNew();while(!await ready()){Assert.True(clock.Elapsed.TotalSeconds<40,stage);await Task.Delay(100,ct);}}
            try{
                await Wait(()=>Task.FromResult(app.Workspace?.Host.IsReady is true&&app.DevelopmentRuntime!.Snapshot().CanQueryStorage));
                java=Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value);
                Save("-process.json",new{appPid=Environment.ProcessId,javaPid=java.Id,javaStartedUtc=java.StartTime.ToUniversalTime()});
                using var client=RuntimeClient.CreateMmf3Acceptance(app.DevelopmentRuntime.ReadCredential);
                Assert.Equal(3,(await client.ListKnowledgeAnswersAsync(0,ct)).Total);
                var shell=app.Workspace!;var core=shell.Browser.CoreWebView2;var methods=new List<string>();
                core.WebMessageReceived+=(_,message)=>{using var item=JsonDocument.Parse(message.WebMessageAsJson);if(item.RootElement.TryGetProperty("method",out var method))methods.Add(method.GetString()!);};
                Task<string> Script(string js)=>core.ExecuteScriptAsync(js);
                async Task<bool> Is(string js)=>await Script(js)=="true";
                string Json(string value)=>JsonSerializer.Serialize(value);
                async Task Click(string selector,string label){string expression="[...document.querySelectorAll("+Json(selector+" button")+")].find(b=>b.getClientRects().length>0&&!b.disabled&&b.textContent==="+Json(label)+")";await Wait(()=>Is("Boolean("+expression+")"));await Script(expression+".click()");}
                async Task Input(string id,string value)=>_ = await Script("(()=>{const e=document.getElementById("+Json(id)+");Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value').set.call(e,"+Json(value)+");e.dispatchEvent(new Event('input',{bubbles:true}));})()");
                string name="synthetic-word-"+run+".docx",path=Path.Combine(root,name);
                string body=KnowledgeDocxTests.Body+(compareVersions?"<w:p><w:r><w:t>"+string.Concat(Enumerable.Repeat("长资料对比演练😀，",650))+"</w:t></w:r></w:p>":"");
                if(!reopen)File.WriteAllBytes(path,KnowledgeDocxTests.Fixture(body));
                byte[] original=File.ReadAllBytes(path);using var input=new MemoryStream(original,false);
                var expected=KnowledgeDocx.Read(input,name,ct);string hash=Convert.ToHexString(SHA256.HashData(original));
                string updatedPath=Path.Combine(root,"updated-"+name);KnowledgeDocx.Excerpt? updated=null;
                if(compareVersions){
                    if(!reopen)File.WriteAllBytes(updatedPath,KnowledgeDocxTests.Fixture(body.Replace("--inspect=on","--inspect=review",StringComparison.Ordinal)));
                    using var updatedStream=File.OpenRead(updatedPath);updated=KnowledgeDocx.Read(updatedStream,Path.GetFileName(updatedPath),ct);
                }
                await Script("location.hash='#/knowledge'");await Wait(()=>Is("Boolean(document.querySelector('.knowledge-list'))"));
                string id;string[] priorIds;
                if(!reopen){
                    var before=await client.ListKnowledgeAsync("ACTIVE",0,ct);Assert.Equal(3,before.Total);priorIds=before.Items.Select(d=>d.DocumentId).Order().ToArray();
                    async Task DriveDialogs(bool cancel,string? rejectedFile=null,bool update=false){
                        uint thread=GetCurrentThreadId();IntPtr owner=new WindowInteropHelper(shell).Handle;
                        await Task.Run(async()=>{
                            async Task<AutomationElement> FindWindow(string title){
                                var clock=Stopwatch.StartNew();while(true){
                                    IntPtr found=IntPtr.Zero;
                                    EnumThreadWindows(thread,(window,_)=>{
                                        if(GetWindow(window,4)!=owner)return true;
                                        var text=new StringBuilder(256);WordDialogTitle(window,text,text.Capacity);
                                        if(text.ToString().Contains(title,StringComparison.Ordinal)){found=window;return false;}return true;
                                    },IntPtr.Zero);
                                    if(found!=IntPtr.Zero){var element=AutomationElement.FromHandle(found);Assert.Equal(Environment.ProcessId,element.Current.ProcessId);return element;}
                                    Assert.True(clock.Elapsed.TotalSeconds<25,"Owned dialog missing: "+title);await Task.Delay(100,ct);
                                }
                            }
                            Save("-dialog-stage.json",new{stage="FIND_PICKER",cancel});
                            var picker=await FindWindow("导入资料");ValuePattern? filename=null;var clock=Stopwatch.StartNew();
                            while(filename is null){
                                var edits=picker.FindAll(TreeScope.Descendants,new OrCondition(new PropertyCondition(AutomationElement.ControlTypeProperty,ControlType.Edit),new PropertyCondition(AutomationElement.ControlTypeProperty,ControlType.ComboBox))).Cast<AutomationElement>().ToArray();
                                Save("-file-controls.json",edits.Select(e=>new{id=e.Current.AutomationId,type=e.Current.ControlType.ProgrammaticName,filenameLabel=e.Current.Name.StartsWith("文件名",StringComparison.Ordinal)||e.Current.Name.StartsWith("File name",StringComparison.Ordinal),valueSupported=e.TryGetCurrentPattern(ValuePattern.Pattern,out _)}));
                                foreach(var edit in edits){
                                    if(!(edit.Current.Name.StartsWith("文件名",StringComparison.Ordinal)||edit.Current.Name.StartsWith("File name",StringComparison.Ordinal)))continue;
                                    if(edit.TryGetCurrentPattern(ValuePattern.Pattern,out var pattern)){filename=(ValuePattern)pattern;break;}
                                }
                                Assert.True(clock.Elapsed.TotalSeconds<25,"File name edit unavailable");if(filename is null)await Task.Delay(100,ct);
                            }
                            Save("-dialog-stage.json",new{stage="SET_FILENAME",cancel});
                            filename.SetValue(rejectedFile??(update?updatedPath:path));
                            SendMessage((IntPtr)picker.Current.NativeWindowHandle,0x0111,1,0);
                            if(rejectedFile is not null)return;
                            Save("-dialog-stage.json",new{stage="FIND_PREVIEW",cancel});
                            var preview=await FindWindow("确认导入 Word 正文摘录");
                            var text=preview.FindFirst(TreeScope.Descendants,new PropertyCondition(AutomationElement.NameProperty,"Word 正文摘录预览"));Assert.NotNull(text);
                            Assert.Equal(update?updated!.Text:expected.Text,((ValuePattern)text.GetCurrentPattern(ValuePattern.Pattern)).Current.Value.Replace("\r\n","\n",StringComparison.Ordinal));
                            var button=preview.FindFirst(TreeScope.Descendants,new AndCondition(new PropertyCondition(AutomationElement.ControlTypeProperty,ControlType.Button),cancel?new PropertyCondition(AutomationElement.NameProperty,"取消"):new PropertyCondition(AutomationElement.AutomationIdProperty,"ConfirmWordExcerpt")));Assert.NotNull(button);
                            Save("-dialog-stage.json",new{stage="INVOKE_PREVIEW",cancel});
                            ((InvokePattern)button.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
                        },ct);
                    }
                    stage="REJECT_UNSUPPORTED_CONTENT";
                    foreach(var sample in compareVersions?Array.Empty<(string,string)>():new[]{("math",KnowledgeDocxTests.MathBody),("symbol",KnowledgeDocxTests.SymbolBody)}){
                        string rejected=Path.Combine(root,"unsupported-"+sample.Item1+".docx");byte[] bytes=KnowledgeDocxTests.Fixture(sample.Item2);File.WriteAllBytes(rejected,bytes);
                        var selection=DriveDialogs(false,rejected);var click=Click(".knowledge-toolbar","导入文档");await selection;await click;
                        await Wait(()=>Is("document.querySelector('.knowledge-page [role=alert]')?.textContent.includes('公式、字体符号')===true"));
                        Assert.Equal(priorIds,(await client.ListKnowledgeAsync("ACTIVE",0,ct)).Items.Select(d=>d.DocumentId).Order().ToArray());
                        Assert.Equal(bytes,File.ReadAllBytes(rejected));
                    }
                    stage="PREVIEW_CANCEL";var cancel=DriveDialogs(true);var opening=Click(".knowledge-toolbar","导入文档");await cancel;await opening;
                    await Wait(()=>Is("document.querySelector('.knowledge-page').innerText.includes('已取消导入。')"));
                    Assert.Equal(3,(await client.ListKnowledgeAsync("ACTIVE",0,ct)).Total);
                    stage="PREVIEW_CONFIRM";var confirm=DriveDialogs(false);opening=Click(".knowledge-toolbar","导入文档");await confirm;await opening;
                    await Wait(async()=> (await client.ListKnowledgeAsync("ACTIVE",0,ct)).Total==4);
                    id=Assert.Single((await client.ListKnowledgeAsync("ACTIVE",0,ct)).Items,d=>d.Title==expected.Filename).DocumentId;
                    Save("-owned-document.json",new{id,priorIds,hash});
                    if(compareVersions){
                        stage="IMPORT_NEW_VERSION";
                        await Wait(async()=> (await client.GetKnowledgeAsync(id,ct)).Document.ProcessingState=="READY");
                        await Wait(()=>Is("document.getElementById('knowledge-detail-title')?.textContent==="+Json(expected.Filename)));
                        await Click(".knowledge-detail","刷新详情");
                        var update=DriveDialogs(false,update:true);opening=Click(".knowledge-detail","导入新源版本");await update;await opening;
                        await Wait(async()=> (await client.GetKnowledgeAsync(id,ct)).Document.CurrentReadyRevision=="2");
                    }
                }else{
                    using var previous=JsonDocument.Parse(File.ReadAllText(Path.Combine(root,"first-owned-document.json")));
                    id=previous.RootElement.GetProperty("id").GetString()!;priorIds=previous.RootElement.GetProperty("priorIds").EnumerateArray().Select(x=>x.GetString()!).ToArray();
                    Assert.Equal(previous.RootElement.GetProperty("hash").GetString(),hash);Assert.Equal(4,(await client.ListKnowledgeAsync("ACTIVE",0,ct)).Total);
                }
                stage="READY_SEARCH_PREVIEW";
                await Wait(async()=> (await client.GetKnowledgeAsync(id,ct)).Document.ProcessingState=="READY");
                var detail=await client.GetKnowledgeAsync(id,ct);Assert.Equal(expected.Filename,detail.Document.Title);Assert.Equal(compareVersions?2:1,detail.Revisions.Length);Assert.All(detail.Revisions,r=>Assert.Equal("TXT",r.SourceType));
                async Task<string> ReadVersion(string revision){string text="";int offset=0;do{var chunk=await client.PreviewKnowledgeAsync(id,revision,offset,ct);text+=chunk.Text;if(chunk.NextOffset is null)return text;offset=chunk.NextOffset.Value;}while(text.Length<100000);throw new InvalidOperationException("Fixture bound");}
                string sourceText=await ReadVersion("1");Assert.Equal(expected.Text,sourceText);
                if(compareVersions)Assert.Equal(updated!.Text,await ReadVersion("2"));
                await Wait(()=>Is("[...document.querySelectorAll('.knowledge-list .memory-select')].some(b=>b.firstChild.textContent==="+Json(expected.Filename)+")"));
                await Script("[...document.querySelectorAll('.knowledge-list .memory-select')].find(b=>b.firstChild.textContent==="+Json(expected.Filename)+").click()");
                await Wait(()=>Is("document.getElementById('knowledge-detail-title')?.textContent==="+Json(expected.Filename)));
                await Click(".knowledge-detail","刷新详情");await Click(".knowledge-search","限定为当前文档");await Input("knowledge-search-query","NerionDOCX");await Click(".knowledge-search","检索");
                await Wait(()=>Is("document.querySelectorAll('.knowledge-search li').length===1"));await Click(".knowledge-search","打开此位置");
                await Wait(()=>Is("document.querySelector('[aria-label=纯文本预览] mark')?.textContent.includes('NerionDOCX')===true"));
                await Script("document.querySelector('[aria-label=纯文本预览]').scrollIntoView({block:'center'})");
                using(var image=new FileStream(Path.Combine(root,prefix+"-source.png"),FileMode.CreateNew))await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png,image);
                if(compareVersions){
                    stage="COMPARE_VERSIONS";
                    await Script("document.querySelector('.knowledge-comparison').open=true");await Click(".knowledge-comparison","开始对比");
                    await Wait(()=>Is("Boolean(document.querySelector('[aria-label=版本对比结果]'))"));
                    Assert.True(await Is("[...document.querySelectorAll('.knowledge-comparison-change pre')].some(e=>e.textContent.includes('--inspect=on'))&&[...document.querySelectorAll('.knowledge-comparison-change pre')].some(e=>e.textContent.includes('--inspect=review'))"));
                    await Script("document.querySelector('[aria-label=版本对比结果]').scrollIntoView({block:'start'})");
                    using(var image=new FileStream(Path.Combine(root,prefix+"-comparison.png"),FileMode.CreateNew))await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png,image);
                    await Script("[...document.querySelectorAll('.knowledge-comparison-change')].find(e=>e.querySelector('pre').textContent.includes('--inspect=on')).querySelector('button').click()");
                    await Wait(()=>Is("document.querySelector('[aria-label=纯文本预览] .knowledge-location')?.textContent.includes('源版本 1')===true&&document.querySelector('[aria-label=纯文本预览] pre')?.textContent.includes('--inspect=on')===true"));
                    Assert.True(await Is("Boolean(document.querySelector('[aria-label=版本对比结果]'))"));
                    Save("-comparison.json",new{passed=true,reopen,oldRevision="1",newRevision="2",oldTextUnits=sourceText.Length,newTextUnits=updated!.Text.Length,oldTextSha256=Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(sourceText))),newTextSha256=Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(updated.Text))),oldSourceNavigation=true});
                }
                if(reopen){
                    stage="REMOVE_OWNED_FIXTURE";Assert.DoesNotContain(id,priorIds);
                    await Click(".knowledge-detail","物理删除…");await Click("dialog","确认删除");
                    await Wait(async()=> (await client.ListKnowledgeAsync("ACTIVE",0,ct)).Total==3);
                    Assert.Equal(priorIds,(await client.ListKnowledgeAsync("ACTIVE",0,ct)).Items.Select(d=>d.DocumentId).Order().ToArray());
                }
                Assert.Equal(hash,Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path))));
                Assert.Equal(3,(await client.ListKnowledgeAnswersAsync(0,ct)).Total);
                Assert.DoesNotContain(methods,m=>m is "knowledge.answerSubmit" or "models.mutate" or "web.fetchSubmit");
                Save("-result.json",new{passed=true,reopen,id,hash,sourceSha256=Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(sourceText))),unsupportedSamplesRejected=reopen||compareVersions?0:2,cancelVerified=!reopen,confirmationVerified=!reopen,compareVersions,ownFixtureRemoved=reopen,methods,modelCalls=0});
                passed=true;Console.WriteLine("DOCX_DESKTOP_PASS "+prefix);
            }catch(Exception error){Save("-failure.json",new{stage,type=error.GetType().Name,message=error.Message,frames=new StackTrace(error,true).GetFrames().Select(f=>new{method=f.GetMethod()?.Name,line=f.GetFileLineNumber()})});Console.WriteLine("DOCX_DESKTOP_STOP "+stage);}
            finally{try{await app.ExitAsync();if(java is not null)Assert.True(java.WaitForExit(10000));}catch{passed=false;app.Shutdown(10);}finally{java?.Dispose();}}
        };
        app.Run();Assert.True(passed);
    }
}
#endif
