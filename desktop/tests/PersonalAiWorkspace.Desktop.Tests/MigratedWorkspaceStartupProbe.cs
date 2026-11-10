#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows.Automation;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private static int RunMigratedWorkspaceStartup(string id,string psql)
    {
        byte[] configuration=Encoding.UTF8.GetBytes(Console.In.ReadToEnd());string stage="PROFILE";
        try
        {
            var profile=Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument,id]);
            using var pins=Mmf3ProfileState.Pin(id);using var single=new SingleInstance(profile.InstanceSuffix);Assert.True(single.IsPrimary);
            using var chosen=JsonDocument.Parse(configuration);var target=chosen.RootElement;
            using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(300));
            string source=Path.Combine(profile.Root,"data","memory.db"),sourceBefore=Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(source)));
            stage="PREPARE_MIGRATED_SOURCE";
            var migrationUi=Task.Run(()=>DriveMigrationDialog(profile,target,psql,true,deadline.Token));
            _=migrationUi.ContinueWith(t=>{if(t.IsFaulted)deadline.Cancel();},TaskScheduler.Default);
            string operation;
            using(var migration=MaintenanceRuntimeHandoff.StartAsync(profile,configuration.ToArray(),deadline.Token).GetAwaiter().GetResult())
            {
                migrationUi.GetAwaiter().GetResult();operation=migration.Operation;
                migration.FinishAsync(deadline.Token).GetAwaiter().GetResult();Assert.True(migration.VerifiedStopped);
            }
            string activation=Path.Combine(profile.Root,"evidence","sqlite-activation",operation);
            string savedConfiguration=Path.Combine(profile.Root,"data","database.dpapi");
            string configurationBefore=Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(savedConfiguration)));
            string[] before=Directory.GetFiles(activation,"*",SearchOption.AllDirectories).Order().ToArray();
            stage="CANCEL_NORMAL_ENTRY";
            string cancelledDigest;
            DevelopmentRuntimeOwner? owner=null;
            using(var client=RuntimeClient.CreateMmf3Acceptance(()=>owner?.ReadCredential()))
            using(owner=new DevelopmentRuntimeOwner(profile,client))
            {
                var ui=Task.Run(()=>DriveResumeDialog(profile,target,false,deadline.Token,owner));
                _=ui.ContinueWith(t=>{if(t.IsFaulted)deadline.Cancel();},TaskScheduler.Default);
                owner.Start(deadline.Token);cancelledDigest=ui.GetAwaiter().GetResult();
                WaitOwner(owner,false,deadline.Token);Assert.Equal("STARTUP_CANCELLED",owner.Snapshot().Failure);
                owner.StopAsync().GetAwaiter().GetResult();Assert.False(owner.LastStopConfirmed);
            }
            Assert.Equal(before,Directory.GetFiles(activation,"*",SearchOption.AllDirectories).Order().ToArray());
            Assert.False(File.Exists(Path.Combine(activation,"next.dpapi")));
            stage="NORMAL_ENTRY_AND_LATER_DATA";
            Guid later;
            string normalDigest;
            using(var client=RuntimeClient.CreateMmf3Acceptance(()=>owner?.ReadCredential()))
            using(owner=new DevelopmentRuntimeOwner(profile,client))
            {
                var ui=Task.Run(()=>DriveResumeDialog(profile,target,true,deadline.Token,owner));
                _=ui.ContinueWith(t=>{if(t.IsFaulted)deadline.Cancel();},TaskScheduler.Default);
                owner.Start(deadline.Token);normalDigest=ui.GetAwaiter().GetResult();Assert.NotEqual(cancelledDigest,normalDigest);
                WaitOwner(owner,true,deadline.Token);
                Assert.Equal("POSTGRESQL",client.GetStorageStatusAsync(deadline.Token).GetAwaiter().GetResult().Backend);
                Assert.Equal(DesktopError.ModelUnavailable,Assert.Throws<DesktopException>(
                    ()=>client.GetModelStatusAsync(deadline.Token).GetAwaiter().GetResult()).Error);
                later=client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE,"Migrated native start","Later data must survive"),deadline.Token).GetAwaiter().GetResult().Id;
                owner.StopAsync().GetAwaiter().GetResult();Assert.True(owner.LastStopConfirmed);
            }
            string first=Path.Combine(activation,"sessions","00000001"),second=Path.Combine(activation,"sessions","00000002");
            Assert.True(File.Exists(Path.Combine(first,"native-exit.dpapi")));
            Assert.False(File.Exists(Path.Combine(first,"next.dpapi")));
            stage="REOPEN_LATER_DATA_AND_INTERRUPT";
            using(var client=RuntimeClient.CreateMmf3Acceptance(()=>owner?.ReadCredential()))
            using(owner=new DevelopmentRuntimeOwner(profile,client))
            {
                var ui=Task.Run(()=>DriveResumeDialog(profile,target,true,deadline.Token,owner));
                _=ui.ContinueWith(t=>{if(t.IsFaulted)deadline.Cancel();},TaskScheduler.Default);
                owner.Start(deadline.Token);Assert.NotEqual(normalDigest,ui.GetAwaiter().GetResult());WaitOwner(owner,true,deadline.Token);
                Assert.Equal("Later data must survive",client.GetMemoryAsync(later,deadline.Token).GetAwaiter().GetResult().Content);
                owner.Dispose();Assert.False(owner.LastStopConfirmed);
            }
            Assert.True(File.Exists(Path.Combine(first,"next.dpapi")));
            Assert.True(File.Exists(Path.Combine(second,"intent.dpapi")));
            Assert.False(File.Exists(Path.Combine(second,"native-exit.dpapi")));
            var interrupted=Directory.GetFiles(activation,"*",SearchOption.AllDirectories).Order().ToArray();
            stage="INTERRUPTED_HISTORY_DENIES_NORMAL_ENTRY";
            using(var client=RuntimeClient.CreateMmf3Acceptance(()=>owner?.ReadCredential()))
            using(owner=new DevelopmentRuntimeOwner(profile,client))
            {
                owner.Start(deadline.Token);WaitOwner(owner,false,deadline.Token);
                Assert.Equal("STARTUP_CAUSE_UNAVAILABLE",owner.Snapshot().Failure);
                owner.StopAsync().GetAwaiter().GetResult();Assert.False(owner.LastStopConfirmed);
            }
            Assert.Equal(interrupted,Directory.GetFiles(activation,"*",SearchOption.AllDirectories).Order().ToArray());
            Assert.False(File.Exists(Path.Combine(second,"next.dpapi")));
            var retained=Sql(psql,target,"SELECT count(*) FROM workspace.memory_items WHERE id='"+later+"'");
            Assert.Equal(0,retained.Code);Assert.Equal("1",retained.Output.Trim());
            Assert.Equal(sourceBefore,Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(source))));
            Assert.Equal(configurationBefore,Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(savedConfiguration))));
            Console.WriteLine(JsonSerializer.Serialize(new{passed=true,profile=id,operation,later,
                cancelledWithoutConsumingStop=true,nativeConfirmationRenewed=true,normalDesktopEntry=true,normalCloseObserved=true,
                modelDisabled=true,laterDataPreserved=true,interruptedRestartBlocked=true,sourceUnchanged=true,configurationUnchanged=true,
                visualAcceptance=false,productionGo=false}));return 0;
        }
        catch(Exception failure){Console.WriteLine("MIGRATED_STARTUP_STOP "+stage+" "+failure.GetType().Name);Console.WriteLine(failure.Message);Console.WriteLine(failure.StackTrace);return 10;}
        finally{CryptographicOperations.ZeroMemory(configuration);}
    }
    private static void WaitOwner(DevelopmentRuntimeOwner owner,bool ready,CancellationToken token)
    {
        var clock=Stopwatch.StartNew();
        while(owner.Snapshot().Runtime=="STARTING") {token.ThrowIfCancellationRequested();Assert.True(clock.Elapsed<TimeSpan.FromSeconds(75));Thread.Sleep(100);}
        Assert.Equal(ready,owner.Snapshot().CanQueryStorage);
    }
    private static string DriveResumeDialog(Mmf3AcceptanceLaunch profile,JsonElement target,bool confirm,CancellationToken token,DevelopmentRuntimeOwner owner)
    {
        var condition=new AndCondition(new PropertyCondition(AutomationElement.ProcessIdProperty,Environment.ProcessId),
            new PropertyCondition(AutomationElement.NameProperty,"Personal AI Workspace · 打开已迁移工作区"));
        AutomationElement? window=null;var clock=Stopwatch.StartNew();
        while(window is null){token.ThrowIfCancellationRequested();Assert.True(clock.Elapsed<TimeSpan.FromSeconds(55));
            if(owner.Snapshot().Runtime!="STARTING")throw new IOException("Resume admission: "+owner.Snapshot().Failure+" "+owner.MigratedStartupFailure);
            window=AutomationElement.RootElement.FindFirst(TreeScope.Children,condition);if(window is null)Thread.Sleep(100);}
        AutomationElement Control(string name)=>window.FindFirst(TreeScope.Descendants,new PropertyCondition(AutomationElement.AutomationIdProperty,name))??throw new IOException();
        var approve=Control("ConfirmResume");Assert.False(approve.Current.IsEnabled);
        var texts=window.FindAll(TreeScope.Descendants,new PropertyCondition(AutomationElement.ControlTypeProperty,ControlType.Text)).Cast<AutomationElement>().Select(e=>e.Current.Name).ToArray();
        string summary=texts.Single(text=>text.Contains("本次确认摘要：",StringComparison.Ordinal));
        Assert.Contains(profile.RunId,summary);Assert.Contains(target.GetProperty("database").GetString()!,summary);
        Assert.Contains("模型执行保持关闭",summary);
        string digest=summary.Split("本次确认摘要：",StringSplitOptions.None)[1];Assert.Matches("^[a-f0-9]{64}$",digest);
        if(confirm){((TogglePattern)Control("AcknowledgeResume").GetCurrentPattern(TogglePattern.Pattern)).Toggle();
            Assert.True(approve.Current.IsEnabled);((InvokePattern)approve.GetCurrentPattern(InvokePattern.Pattern)).Invoke();}
        else ((InvokePattern)Control("CancelResume").GetCurrentPattern(InvokePattern.Pattern)).Invoke();
        return digest;
    }
}
#endif
