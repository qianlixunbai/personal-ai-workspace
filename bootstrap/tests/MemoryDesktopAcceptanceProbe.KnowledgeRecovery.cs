using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

internal static partial class MemoryDesktopAcceptanceProbe
{
    // Two real JVMs use the same newly restored synthetic Store. This is the existing
    // test HTTP/native adapter, not AssistantApp, Profile activation or user backup UI.
    private static int RunKnowledgeRecovery(string[] args)
    {
        Process? child=null; MainWorkspaceWindow? shell=null;
        var processes=new List<object>();int result=10;
        try
        {
            string java=Path.GetFullPath(args[0]),jna=Path.GetFullPath(args[1]);
            string classpath=File.ReadAllText(args[2]).Trim();output=Path.GetFullPath(args[3]);
            Check(Directory.Exists(output)&&!File.Exists(Path.Combine(output,"owned-profile.json")));
            string id=Guid.NewGuid().ToString("N"),root=Mmf3ProfileState.Root(id);Check(!Path.Exists(root));
            File.WriteAllText(Path.Combine(output,"owned-profile.json"),JsonSerializer.Serialize(new{id,root,synthetic=true}));
            string preference=Path.Combine(Directory.GetParent(Directory.GetParent(root)!.FullName)!.FullName,"last-workspace");
            string? PreferenceHash()=>File.Exists(preference)?Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(preference))):null;
            string? before=PreferenceHash();
            stage="RECOVERY_PROFILE";Mmf3ProfileState.Prepare(id);Mmf3ProfileNative.Provision(id,jna);
            using var pin=Mmf3ProfileState.Pin(id);using var job=RuntimeProcessJob.Create();
            var launch=Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument,id]);
            var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};
            app.Startup+=async (_,_)=>
            {
                using var timeout=new CancellationTokenSource(TimeSpan.FromMinutes(5));var ct=timeout.Token;
                using var watchdog=ct.Register(()=>app.Dispatcher.BeginInvoke(()=>{shell?.DisposeImmediately();app.Shutdown(10);}));
                try
                {
                    foreach(string phase in new[]{"prepare","reopen"})
                    {
                        stage="RECOVERY_JVM_"+phase;
                        var start=new ProcessStartInfo(java){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=root};
                        foreach(string option in new[]{"-Xmx384m","-XX:ActiveProcessorCount=2","-Dworkspace.native.development-profile="+id,
                            "-Dworkspace.native.bootstrap="+Mmf3ProfileNative.Helper,"-Djna.boot.library.path="+Path.Combine(root,"jna-5.17.0-win32-x86-64"),
                            "-Djna.boot.library.name=jnidispatch","-Djna.nosys=true","-Djna.noclasspath=true","-Djna.nounpack=true",
                            "-Djava.io.tmpdir="+Path.Combine(root,"evidence"),"-Djna.tmpdir="+Path.Combine(root,"evidence"),
                            "-Dknowledge.recovery.desktop.fixture="+phase,"-cp",classpath,"MemoryDesktopIntegrationProbe"})start.ArgumentList.Add(option);
                        child=Process.Start(start)??throw new IOException();job.Assign(child);
                        using var stdout=new FileStream(Path.Combine(output,phase+".log"),FileMode.CreateNew);
                        using var stderr=new FileStream(Path.Combine(output,phase+"-errors.log"),FileMode.CreateNew);
                        var log=child.StandardOutput.BaseStream.CopyToAsync(stdout);var errors=child.StandardError.BaseStream.CopyToAsync(stderr);
                        string ready=Path.Combine(root,"evidence","desktop-ready-"+phase+".json");
                        await Wait(()=>{Check(!child.HasExited);return Task.FromResult(File.Exists(ready));},ct,65);
                        using var state=JsonDocument.Parse(File.ReadAllText(ready));
                        int port=state.RootElement.GetProperty("port").GetInt32();Check(port is >1024 and <=65535);
                        string token=state.RootElement.GetProperty("tokenFile").GetString()!;
                        Check(Path.GetFullPath(token)==Path.Combine(root,"evidence","kd","credentials","client-token"));
                        Check(state.RootElement.GetProperty("syntheticRecovery").GetBoolean()&&state.RootElement.GetProperty("phase").GetString()==phase);
                        Check(state.RootElement.GetProperty("pid").GetInt32()==child.Id);
                        using var transport=new RecoveryReadTransport(port);
                        using var client=new RuntimeClient(transport,()=>File.ReadAllText(token).Trim());
                        using var actions=new NativeActions(client);
                        shell=NewWindow(actions,launch);shell.Title="Knowledge restore verification · synthetic / model off";shell.Show();
                        using var expected=JsonDocument.Parse(File.ReadAllText(Path.Combine(root,"evidence","kd","fixture.json")));
                        await VerifyRecoveredKnowledge(shell,client,expected.RootElement,phase,ct);
                        Check(transport.MutationAttempts==0);
                        await shell.ShutdownAsync();Check(shell.Host.CleanupPassed);shell=null;
                        string done=Path.Combine(root,"evidence","desktop-done-"+phase);
                        using(var file=new FileStream(done,FileMode.CreateNew))using(var writer=new StreamWriter(file))writer.Write("PASS");
                        await child.WaitForExitAsync(ct).WaitAsync(TimeSpan.FromSeconds(18),ct);await Task.WhenAll(log,errors);
                        Check(child.ExitCode==0);processes.Add(new{phase,pid=child.Id,startedUtc=child.StartTime.ToUniversalTime(),exited=child.HasExited,exitCode=child.ExitCode,port,mutationAttempts=transport.MutationAttempts,historyGets=transport.HistoryGets});
                        File.WriteAllText(Path.Combine(output,"processes.json"),JsonSerializer.Serialize(processes));
                        child.Dispose();child=null;
                        Check(!System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(p=>p.Port==port));
                    }
                    Check(PreferenceHash()==before);Check(!Directory.EnumerateFileSystemEntries(Path.Combine(root,"data")).Any());
                    File.Copy(Path.Combine(root,"evidence","kd","fixture.json"),Path.Combine(output,"synthetic-expected.json"));
                    File.WriteAllText(Path.Combine(output,"result.json"),JsonSerializer.Serialize(new{passed=true,nativeAdmitted=true,jvmStarts=2,actualAssistantApp=false,
                        actualWebView=true,actualHttp=true,actualSqlite=true,modelStarted=false,profileActivated=false,personalSelectionUnchanged=true,ordinaryProfileDataEmpty=true}));
                    result=0;Pass("ENCRYPTED_KNOWLEDGE_DESKTOP_TWO_JVMS_NO_MODEL_NO_PERSONAL_SELECTION");
                }
                catch(Exception error)
                {
                    File.WriteAllText(Path.Combine(output,"failure.json"),JsonSerializer.Serialize(new{stage,type=error.GetType().Name,desktopError=(error as DesktopException)?.Error.ToString(),
                        frames=new StackTrace(error,true).GetFrames().Select(f=>new{method=f.GetMethod()?.Name,line=f.GetFileLineNumber()})}));
                    if(shell?.Host.IsReady is true){File.WriteAllText(Path.Combine(output,"failure-dom.json"),await Script(shell,"document.body.innerText"));await Capture(shell,"failure.png");}
                    Console.WriteLine("KNOWLEDGE_RECOVERY_DESKTOP_STOP "+stage+" "+error.GetType().Name);
                }
                finally{shell?.DisposeImmediately();app.Shutdown(result);}
            };
            app.Run();return result;
        }
        catch(Exception error){Console.WriteLine("KNOWLEDGE_RECOVERY_DESKTOP_STOP "+stage+" "+error.GetType().Name);return 10;}
        finally
        {
            if(child is not null){if(!child.HasExited){child.Kill(entireProcessTree:true);child.WaitForExit(5000);}File.WriteAllText(Path.Combine(output,"owned-cleanup.json"),JsonSerializer.Serialize(new{pid=child.Id,exited=child.HasExited}));child.Dispose();}
        }
    }
    private static async Task VerifyRecoveredKnowledge(MainWorkspaceWindow shell,RuntimeClient client,JsonElement expected,string phase,CancellationToken ct)
    {
        await Ready(shell,ct);await Script(shell,"location.hash='#/knowledge'");
        await Wait(()=>ScriptIs(shell,"Boolean(document.querySelector('.knowledge-answer'))"),ct);
        Check(!(await client.GetModelStatusAsync(ct)).Ready);
        int submissions=0,historyReads=0;
        shell.Browser.CoreWebView2.WebMessageReceived+=(_,message)=>{
            using var json=JsonDocument.Parse(message.WebMessageAsJson);
            if(!json.RootElement.TryGetProperty("method",out var method))return;
            if(method.GetString()=="knowledge.answerSubmit")submissions++;
            if(method.GetString()=="knowledge.answerHistoryGet")historyReads++;
        };
        var history=await client.ListKnowledgeAnswersAsync(0,ct);Check(history.Total==5);
        var rows=expected.GetProperty("answers").EnumerateArray().ToArray();
        Check(history.Items.Select(r=>r.TaskId).Order().SequenceEqual(rows.Select(r=>r.GetProperty("taskId").GetString()!).Order()));
        await Script(shell,"document.querySelector('.knowledge-answer').closest('details').querySelector('summary').click()");
        await Click(shell,"查看问答历史",ct);await Wait(()=>ScriptIs(shell,"document.querySelectorAll('[aria-label=问答历史] li').length===5"),ct);
        foreach(var row in rows)
        {
            string question=row.GetProperty("question").GetString()!,query=row.GetProperty("query").GetString()!,status=row.GetProperty("status").GetString()!;
            Guid id=Guid.Parse(row.GetProperty("taskId").GetString()!);stage="RECOVERED_HISTORY_"+phase+"_"+status;
            var entry=await client.GetKnowledgeAnswerHistoryAsync(id,ct);Check(entry.Question==question&&entry.Query==query&&entry.Task.Status.ToString()==status);
            Check(history.Items.Single(h=>h.TaskId==id.ToString()).CreatedAt==row.GetProperty("createdAt").GetString());
            await Click(shell,question,ct);
            await Wait(()=>ScriptIs(shell,"document.getElementById('knowledge-answer-question').value==="+Json(question)+"&&document.getElementById('knowledge-answer-query').value==="+Json(query)),ct);
            if(status!="SUCCEEDED")
            {
                string label=status=="FAILED"?"回答失败":status=="CANCELLED"?"回答已取消":"回答超时";
                await Wait(()=>ScriptIs(shell,"document.querySelector('.knowledge-answer [role=status]')?.textContent==="+Json(label)),ct);
                Check(entry.Task.Result is null&&entry.Task.Error is not null);Check(await ScriptIs(shell,"!document.querySelector('.knowledge-answer .knowledge-snippet')"));continue;
            }
            var result=JsonSerializer.Deserialize<KnowledgeAnswerResult>(row.GetProperty("result").GetRawText(),new JsonSerializerOptions{PropertyNameCaseInsensitive=true})!;
            Check(JsonSerializer.Serialize(entry.Task.Result)==JsonSerializer.Serialize(result));
            await Wait(()=>ScriptIs(shell,"document.querySelector('.knowledge-answer .knowledge-snippet')?.textContent==="+Json(result.Answer)),ct);
            var citation=result.Citations.Single();bool available=row.GetProperty("references")[0].GetProperty("availability").GetString()=="AVAILABLE";
            await Click(shell,"打开引用来源",ct);
            if(available)
            {
                stage="RECOVERED_OLD_SOURCE_"+phase;
                string old=expected.GetProperty("oldText").GetString()!,current=expected.GetProperty("currentText").GetString()!;
                Check(citation.SourceRevision=="1"&&(await client.GetKnowledgeAsync(citation.DocumentId,ct)).Document.CurrentReadyRevision=="2");
                Check((await client.PreviewKnowledgeAsync(citation.DocumentId,"1",0,ct)).Text==old);
                Check((await client.PreviewKnowledgeAsync(citation.DocumentId,"2",0,ct)).Text==current);
                string excerpt=old[citation.StartOffset..citation.EndOffset];
                await Wait(()=>ScriptIs(shell,"document.getElementById('knowledge-revision')?.value==='1'&&document.querySelector('[aria-label=纯文本预览] mark')?.textContent==="+Json(excerpt)),ct);
                Check(await ScriptIs(shell,"document.querySelector('[aria-label=纯文本预览]').textContent.includes('当前可用版本为 2')"));
                Check(await ScriptIs(shell,"document.querySelector('[aria-label=纯文本预览] pre').querySelector('script')===null"));
                await Script(shell,"document.querySelector('[aria-label=纯文本预览]').scrollIntoView({block:'center'})");await Capture(shell,phase+"-old-reference.png");
            }
            else
            {
                stage="RECOVERED_MISSING_SOURCE_"+phase;
                await Wait(()=>ScriptIs(shell,"Boolean(document.querySelector('.knowledge-page > p[role=alert]')?.textContent)"),ct);
                try{await client.GetKnowledgeAsync(citation.DocumentId,ct);throw new InvalidOperationException("Missing source unexpectedly available");}
                catch(DesktopException error){Check(error.Error==DesktopError.KnowledgeNotFound);}
                Check(await ScriptIs(shell,"document.querySelector('.knowledge-answer .knowledge-snippet')?.textContent==="+Json(result.Answer)));
                await Script(shell,"document.querySelector('.knowledge-page > p[role=alert]').scrollIntoView({block:'center'})");await Capture(shell,phase+"-missing-reference.png");
            }
        }
        Check(historyReads==5&&submissions==0);Check((await client.ListKnowledgeAsync("ACTIVE",0,ct)).Total==1);
        Check((await client.ListKnowledgeAnswersAsync(0,ct)).Total==5);Check(!(await client.GetModelStatusAsync(ct)).Ready);
        File.WriteAllText(Path.Combine(output,phase+"-ui.json"),JsonSerializer.Serialize(new{phase,historyReads,answerSubmissions=submissions,terminalRecords=5,exactOldRevision=true,missingSourceExplicit=true}));
        Pass("RESTORED_HISTORY_UI_OLD_REFERENCE_AND_MISSING_REFERENCE_"+phase);
    }
    private sealed class RecoveryReadTransport(int port):DelegatingHandler(new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false,UseCookies=false})
    {
        internal int MutationAttempts,HistoryGets;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)
        {
            Check(request.RequestUri!.Scheme=="http"&&request.RequestUri.Host=="127.0.0.1"&&request.RequestUri.Port==8765);
            if(request.Method!=HttpMethod.Get){MutationAttempts++;throw new InvalidOperationException("Read-only restored desktop fixture");}
            if(request.RequestUri.AbsolutePath.StartsWith("/api/v1/knowledge/answer/tasks/",StringComparison.Ordinal))HistoryGets++;
            request.RequestUri=new UriBuilder(request.RequestUri){Port=port}.Uri;return base.SendAsync(request,ct);
        }
    }
}
