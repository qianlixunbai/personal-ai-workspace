#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.Buffers.Binary;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows.Automation;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private static int RunMaintenanceConfirmationRejections(string id,string psql)
    {
        byte[] configuration=Encoding.UTF8.GetBytes(Console.In.ReadToEnd());string stage="PROFILE";
        try{
            var profile=Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument,id]);
            using var profilePin=Mmf3ProfileState.Pin(id);using var single=new SingleInstance(profile.InstanceSuffix);Assert.True(single.IsPrimary);
            using var chosen=JsonDocument.Parse(configuration);var target=chosen.RootElement;
            string source=Path.Combine(profile.Root,"data","memory.db"),before=Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(source)));
            foreach(bool receiptRace in new[]{false,true}){
                stage=receiptRace?"CONCURRENT_RECEIPT":"WRONG_CONFIRMATION";
                using var sources=TrustedDevelopmentRuntimeSources.Open();using var job=RuntimeProcessJob.Create();
                using var runtimeLock=new FileStream(Path.Combine(profile.Root,"evidence","runtime-admission.lock"),FileMode.Open,FileAccess.ReadWrite,FileShare.Read);
                using var modelLock=new FileStream(Path.Combine(profile.Root,"model-state",".lock"),FileMode.Open,FileAccess.Read,FileShare.Read);
                using var child=Process.Start(sources.StartInfo(profile,maintenanceOwner:true))??throw new IOException();job.Assign(child);
                using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(45));
                try{
                    var hello=ReadReviewFrame(child,deadline.Token);Assert.Equal("OWNER_HELLO",hello.GetProperty("stage").GetString());Assert.Equal(child.Id,hello.GetProperty("pid").GetInt32());
                    WriteReviewFrame(child,new{action="PREPARE",handle=ProfileRecoveryCoordinator.DuplicateExclusion(runtimeLock,child),configuration=Convert.ToBase64String(configuration)},deadline.Token);
                    var prepared=ReadReviewFrame(child,deadline.Token);Assert.Equal("REVIEW_PREPARED",prepared.GetProperty("stage").GetString());
                    string operation=prepared.GetProperty("operation").GetString()!,digest=prepared.GetProperty("digest").GetString()!;
                    if(receiptRace){
                        var inserted=Sql(psql,target,"INSERT INTO workspace.maintenance_import(singleton,operation) VALUES(1,'ffffffffffffffffffffffffffffffff')");
                        Assert.Equal(0,inserted.Code);
                    }else digest=digest[..63]+(digest[63]=='0'?'1':'0');
                    WriteReviewFrame(child,new{action="CONFIRM",operation,digest},deadline.Token);
                    var denied=ReadReviewFrame(child,deadline.Token);Assert.Equal("STOP",denied.GetProperty("stage").GetString());
                    child.StandardInput.Close();Assert.True(child.WaitForExit(10000));Assert.Equal(10,child.ExitCode);Assert.Equal(0u,job.ActiveProcessCount);
                    Assert.Equal(0,child.StandardOutput.BaseStream.ReadByte()+1);Assert.Equal(0,child.StandardError.BaseStream.ReadByte()+1);
                    foreach(string registry in new[]{"sqlite-maintenance","sqlite-configuration","sqlite-activation"})Assert.False(Directory.Exists(Path.Combine(profile.Root,"evidence",registry)));
                    Assert.False(File.Exists(Path.Combine(profile.Root,"data","database.dpapi")));
                    Assert.Equal(before,Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(source))));
                    var counts=Sql(psql,target,"SELECT (SELECT count(*) FROM workspace.memory_items),(SELECT count(*) FROM workspace.maintenance_import)");
                    Assert.Equal(0,counts.Code);Assert.Equal(receiptRace?"0|1":"0|0",counts.Output.Trim());
                }finally{if(!child.HasExited){job.Dispose();child.WaitForExit(10000);}}
            }
            Console.WriteLine(JsonSerializer.Serialize(new{passed=true,profile=id,wrongConfirmationRejected=true,concurrentReceiptRejected=true,
                sourceUnchanged=true,noMigrationJournal=true,noConfiguration=true,noDomainImport=true,productionGo=false}));return 0;
        }catch(Exception failure){Console.WriteLine("MIGRATION_REJECTION_STOP "+stage+" "+failure.GetType().Name);Console.WriteLine(failure.StackTrace);return 10;}
        finally{CryptographicOperations.ZeroMemory(configuration);}
    }
    private static JsonElement ReadReviewFrame(Process child,CancellationToken token)
    {
        byte[] header=new byte[4];child.StandardOutput.BaseStream.ReadExactlyAsync(header,token).AsTask().GetAwaiter().GetResult();
        int size=BinaryPrimitives.ReadInt32BigEndian(header);Assert.InRange(size,1,16384);byte[] bytes=new byte[size];
        child.StandardOutput.BaseStream.ReadExactlyAsync(bytes,token).AsTask().GetAwaiter().GetResult();
        using var document=JsonDocument.Parse(bytes);return document.RootElement.Clone();
    }
    private static void WriteReviewFrame(Process child,object value,CancellationToken token)
    {
        byte[] bytes=JsonSerializer.SerializeToUtf8Bytes(value),header=new byte[4];BinaryPrimitives.WriteInt32BigEndian(header,bytes.Length);
        try{child.StandardInput.BaseStream.WriteAsync(header,token).AsTask().GetAwaiter().GetResult();
            child.StandardInput.BaseStream.WriteAsync(bytes,token).AsTask().GetAwaiter().GetResult();child.StandardInput.BaseStream.FlushAsync(token).GetAwaiter().GetResult();}
        finally{CryptographicOperations.ZeroMemory(bytes);}
    }
    private static int RunMaintenanceConfirmation(string id,string psql)
    {
        string stage="PROFILE";
        byte[] configuration=Encoding.UTF8.GetBytes(Console.In.ReadToEnd());
        try {
            var profile=Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument,id]);
            using var pins=Mmf3ProfileState.Pin(id);
            using var single=new SingleInstance(profile.InstanceSuffix);Assert.True(single.IsPrimary);
            using var chosen=JsonDocument.Parse(configuration);
            var target=chosen.RootElement;
            Assert.False(File.Exists(Path.Combine(profile.Root,"data","database.dpapi")));
            string source=Path.Combine(profile.Root,"data","memory.db");
            string before=Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(source)));
            using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(180));
            stage="CANCEL_REVIEW";
            var cancelledUi=Task.Run(()=>DriveMigrationDialog(profile,target,psql,confirm:false,deadline.Token));
            _=cancelledUi.ContinueWith(t=>{if(t.IsFaulted)deadline.Cancel();},TaskScheduler.Default);
            Assert.ThrowsAny<OperationCanceledException>(()=>MaintenanceRuntimeHandoff.StartAsync(profile,configuration.ToArray(),deadline.Token).GetAwaiter().GetResult());
            string oldDigest=cancelledUi.GetAwaiter().GetResult();
            foreach(string registry in new[]{"sqlite-maintenance","sqlite-configuration","sqlite-activation"})
                Assert.False(Directory.Exists(Path.Combine(profile.Root,"evidence",registry)));
            Assert.Equal(before,Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(source))));
            Assert.Equal("0|0",Sql(psql,target,"SELECT (SELECT count(*) FROM workspace.memory_items),(SELECT count(*) FROM workspace.maintenance_import)").Output.Trim());
            using(var released=new FileStream(source,FileMode.Open,FileAccess.ReadWrite,FileShare.None))Assert.True(released.Length>0);

            stage="CONFIRM_REVIEW";
            var confirmUi=Task.Run(()=>DriveMigrationDialog(profile,target,psql,confirm:true,deadline.Token));
            _=confirmUi.ContinueWith(t=>{if(t.IsFaulted)deadline.Cancel();},TaskScheduler.Default);
            using var handoff=MaintenanceRuntimeHandoff.StartAsync(profile,configuration.ToArray(),deadline.Token).GetAwaiter().GetResult();
            string newDigest=confirmUi.GetAwaiter().GetResult();Assert.NotEqual(oldDigest,newDigest);
            stage="CONFIRMED_RUNTIME";
            using var http=new HttpClient(new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false}){BaseAddress=handoff.Endpoint,Timeout=TimeSpan.FromSeconds(10)};
            http.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",CredentialStore.ReadPrivateFile(profile.ValidateTokenImport(profile.TokenFile),requireProtected:true));
            using var response=http.GetAsync("/api/v1/storage/status").GetAwaiter().GetResult();Assert.Equal(HttpStatusCode.OK,response.StatusCode);
            using(var status=JsonDocument.Parse(response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()))
                Assert.Equal("POSTGRESQL",status.RootElement.GetProperty("backend").GetString());
            handoff.FinishAsync(deadline.Token).GetAwaiter().GetResult();Assert.True(handoff.VerifiedStopped);
            Assert.Equal("1|1",Sql(psql,target,"SELECT (SELECT count(*) FROM workspace.memory_items),(SELECT count(*) FROM workspace.maintenance_import)").Output.Trim());
            Assert.Equal(before,Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(source))));
            Console.WriteLine(JsonSerializer.Serialize(new{passed=true,profile=id,operation=handoff.Operation,
                cancelledWithoutJournal=true,cancelledTargetEmpty=true,sourceWriterExcluded=true,targetWriterExcluded=true,
                nativeCheckboxRequired=true,reviewDigestRenewed=true,confirmedTargetImported=true,verifiedStopped=true,
                visualAcceptance=false,productionGo=false}));return 0;
        }catch(Exception failure){Console.WriteLine("MIGRATION_CONFIRMATION_STOP "+stage+" "+failure.GetType().Name);Console.WriteLine(failure.StackTrace);return 10;}
        finally{CryptographicOperations.ZeroMemory(configuration);}
    }

    private static string DriveMigrationDialog(Mmf3AcceptanceLaunch profile,JsonElement target,string psql,bool confirm,CancellationToken token)
    {
        // Actual native WPF window in this exact fixture Process. UIA semantic actions only;
        // no global clicks, keyboard synthesis, product callback or caller approval boolean.
        var condition=new AndCondition(new PropertyCondition(AutomationElement.ProcessIdProperty,Environment.ProcessId),
            new PropertyCondition(AutomationElement.NameProperty,"Personal AI Workspace · 本次迁移最终确认"));
        AutomationElement? window=null;var clock=Stopwatch.StartNew();
        while(window is null){token.ThrowIfCancellationRequested();Assert.True(clock.Elapsed<TimeSpan.FromSeconds(55));
            window=AutomationElement.RootElement.FindFirst(TreeScope.Children,condition);if(window is null)Thread.Sleep(100);}
        AutomationElement Control(string name)=>window.FindFirst(TreeScope.Descendants,new PropertyCondition(AutomationElement.AutomationIdProperty,name))??throw new IOException();
        var approve=Control("ConfirmMigration");Assert.False(approve.Current.IsEnabled);
        var expand=window.FindFirst(TreeScope.Descendants,new PropertyCondition(AutomationElement.NameProperty,"查看本次来源、目标与数据数量"));
        Assert.NotNull(expand);((ExpandCollapsePattern)expand.GetCurrentPattern(ExpandCollapsePattern.Pattern)).Expand();
        var texts=window.FindAll(TreeScope.Descendants,new PropertyCondition(AutomationElement.ControlTypeProperty,ControlType.Text)).Cast<AutomationElement>().Select(e=>e.Current.Name).ToArray();
        Assert.Contains(texts,text=>text.Contains(profile.RunId)&&text.Contains(target.GetProperty("database").GetString()!));
        string digestLine=texts.Single(text=>text.StartsWith("本次核对摘要：",StringComparison.Ordinal));
        string digest=digestLine.Split('\n')[0]["本次核对摘要：".Length..];Assert.Matches("^[a-f0-9]{64}$",digest);
        foreach(string registry in new[]{"sqlite-maintenance","sqlite-configuration","sqlite-activation"})
            Assert.False(Directory.Exists(Path.Combine(profile.Root,"evidence",registry)));
        Assert.ThrowsAny<IOException>(()=>{using var writer=new FileStream(Path.Combine(profile.Root,"data","memory.db"),FileMode.Open,FileAccess.ReadWrite,FileShare.ReadWrite);});
        var empty=Sql(psql,target,"SELECT (SELECT count(*) FROM workspace.memory_items),(SELECT count(*) FROM workspace.maintenance_import)");
        Assert.Equal(0,empty.Code);Assert.Equal("0|0",empty.Output.Trim());
        var writerBlocked=Sql(psql,target,"BEGIN; SET LOCAL lock_timeout='300ms'; LOCK TABLE workspace.memory_items IN ROW EXCLUSIVE MODE; ROLLBACK;");
        Assert.NotEqual(0,writerBlocked.Code);Assert.Contains("55P03",writerBlocked.Error);
        if(confirm){
            ((TogglePattern)Control("AcknowledgeMigration").GetCurrentPattern(TogglePattern.Pattern)).Toggle();
            Assert.True(approve.Current.IsEnabled);((InvokePattern)approve.GetCurrentPattern(InvokePattern.Pattern)).Invoke();
        }else ((InvokePattern)Control("CancelMigration").GetCurrentPattern(InvokePattern.Pattern)).Invoke();
        return digest;
    }
    private static (int Code,string Output,string Error) Sql(string tool,JsonElement target,string sql)
    {
        var start=new ProcessStartInfo(tool){UseShellExecute=false,CreateNoWindow=true,WindowStyle=ProcessWindowStyle.Hidden,
            RedirectStandardOutput=true,RedirectStandardError=true};
        foreach(string arg in new[]{"-X","-w","-At","-h","127.0.0.1","-p",target.GetProperty("port").GetInt32().ToString(),
            "-U",target.GetProperty("username").GetString()!,"-d",target.GetProperty("database").GetString()!,"-v","ON_ERROR_STOP=1","-v","VERBOSITY=sqlstate","-c",sql})start.ArgumentList.Add(arg);
        start.Environment["PGPASSWORD"]=target.GetProperty("password").GetString();
        using var child=Process.Start(start)??throw new IOException();start.Environment.Remove("PGPASSWORD");
        var output=child.StandardOutput.ReadToEndAsync();var error=child.StandardError.ReadToEndAsync();
        if(!child.WaitForExit(10000)){child.Kill();child.WaitForExit(5000);throw new IOException();}
        return(child.ExitCode,output.GetAwaiter().GetResult(),error.GetAwaiter().GetResult());
    }
}
#endif
