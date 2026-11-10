#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace PersonalAiWorkspace.Desktop;

// Native transport for an explicitly chosen SQLite source/target. No UI currently invokes it;
// generic personal maintenance remains BLOCKED. This transport owns its final native confirmation.
// Both children are launched here from pinned product sources; no PID lookup or saved READY record.
internal sealed class MaintenanceRuntimeHandoff : IDisposable
{
    private readonly Mmf3AcceptanceLaunch profile;
    private readonly TrustedDevelopmentRuntimeSources sources;
    private readonly RuntimeProcessJob job;
    private readonly FileStream runtimeLock, modelLock;
    private readonly List<Process> children = new();
    private Process? runtime;
    private string operation = "", challenge = "", configuration = "", binding = "", modelState = "", session = "initial";
    private bool modelTransferred;
    private bool disposed, finished, finishAttempted;
    internal Uri Endpoint { get; private set; } = null!;
    internal string Operation => operation;
    internal string Session => session;
    internal int RuntimePid => runtime?.Id ?? 0;
    internal string ConfigurationVersion => configuration;
    internal long[] BackendPids { get; private set; } = Array.Empty<long>();
    internal bool VerifiedStopped => finished;
    internal string[] RestartBlockers { get; private set; } = ["STOPPED_INSPECTION_REQUIRED"];

    private MaintenanceRuntimeHandoff(Mmf3AcceptanceLaunch profile,TrustedDevelopmentRuntimeSources sources,
            RuntimeProcessJob job,FileStream runtimeLock,FileStream modelLock)
    {this.profile=profile;this.sources=sources;this.job=job;this.runtimeLock=runtimeLock;this.modelLock=modelLock;}

    internal static Task<MaintenanceRuntimeHandoff> StartAsync(Mmf3AcceptanceLaunch profile,
            byte[] configuration,CancellationToken cancellation)=>OpenAsync(profile,configuration,null,cancellation);

    // Restricted maintenance reopen only. No external model authority or ordinary-start bypass.
    internal static Task<MaintenanceRuntimeHandoff> ReopenAsync(Mmf3AcceptanceLaunch profile,
            string operation,CancellationToken cancellation)=>OpenAsync(profile,null,operation,cancellation);

    private static async Task<MaintenanceRuntimeHandoff> OpenAsync(Mmf3AcceptanceLaunch profile,
            byte[]? configuration,string? priorOperation,CancellationToken cancellation)
    {
        profile.Validate();Mmf3ProfileNative.Verify(profile);
        TrustedDevelopmentRuntimeSources? sources=null;RuntimeProcessJob? job=null;
        FileStream? runtimeLock=null,modelLock=null;MaintenanceRuntimeHandoff? result=null;
        try
        {
            sources=TrustedDevelopmentRuntimeSources.Open();job=RuntimeProcessJob.Create();
            runtimeLock=new FileStream(Path.Combine(profile.Root,"evidence","runtime-admission.lock"),FileMode.Open,FileAccess.ReadWrite,FileShare.Read);
            modelLock=new FileStream(Path.Combine(profile.Root,"model-state",".lock"),FileMode.Open,FileAccess.Read,FileShare.Read);
            if(runtimeLock.Length!=0||modelLock.Length!=0)throw Stop();
            result=new(profile,sources,job,runtimeLock,modelLock);
            if(priorOperation is null)await result.StartCore(configuration??throw Stop(),cancellation).ConfigureAwait(false);
            else{Hex(priorOperation,32);result.operation=priorOperation;await result.ReopenCore(cancellation).ConfigureAwait(false);}
            return result;
        }
        catch
        {
            if(result is not null)result.Dispose();else{modelLock?.Dispose();runtimeLock?.Dispose();job?.Dispose();sources?.Dispose();}
            throw;
        }
        finally{if(configuration is not null)System.Security.Cryptography.CryptographicOperations.ZeroMemory(configuration);}
    }
    private Process Launch(bool owner,bool inspection=false,bool restart=false)
    {
        RequireNative();var info=sources.StartInfo(profile,maintenanceOwner:owner,maintenanceRuntime:!owner&&!inspection&&!restart,
            maintenanceInspection:inspection,maintenanceRestart:restart);
        var child=Process.Start(info)??throw Stop();children.Add(child);_=child.SafeHandle;job.Assign(child);
        using var parent=Process.GetCurrentProcess();if(child.SessionId!=parent.SessionId)throw Stop();return child;
    }
    private void RequireNative()
    {
        ObjectDisposedException.ThrowIf(disposed,this);profile.Validate();Mmf3ProfileNative.Verify(profile);
        if(!runtimeLock.CanWrite||runtimeLock.Length!=0||!modelTransferred&&modelLock.Length!=0)throw Stop();
        _=PersonalAiWorkspace.WindowsSecurity.SyntheticRecoveryPublication.FileIdentity(runtimeLock.SafeFileHandle,
            Path.Combine(profile.Root,"evidence","runtime-admission.lock"),false);
        if(!modelTransferred)_=PersonalAiWorkspace.WindowsSecurity.SyntheticRecoveryPublication.FileIdentity(modelLock.SafeFileHandle,
            Path.Combine(profile.Root,"model-state",".lock"),false);
    }
    private async Task StartCore(byte[] configurationBytes,CancellationToken cancellation)
    {
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(cancellation);deadline.CancelAfter(TimeSpan.FromSeconds(120));
        var token=deadline.Token;
        var source=Launch(true);
        var hello=await Read(source,token).ConfigureAwait(false);Stage(hello,"OWNER_HELLO");
        if(hello.GetProperty("pid").GetInt64()!=source.Id||Text(hello,"profile")!=profile.RunId)throw Stop();
        long sourceHandle=ProfileRecoveryCoordinator.DuplicateExclusion(runtimeLock,source);
        await Write(source,new{action="PREPARE",handle=sourceHandle,configuration=Convert.ToBase64String(configurationBytes)},token).ConfigureAwait(false);
        var prepared=await Read(source,token).ConfigureAwait(false);Stage(prepared,"REVIEW_PREPARED");
        if(prepared.GetProperty("pid").GetInt64()!=source.Id||source.HasExited)throw Stop();
        var review=MaintenanceMigrationConfirmation.Read(Text(prepared,"review"),Text(prepared,"digest"),profile.RunId,configurationBytes);
        if(Text(prepared,"operation")!=review.Operation)throw Stop();
        bool confirmed=await review.RequestAsync(token).ConfigureAwait(false);
        RequireNative();if(source.HasExited)throw Stop();
        await Write(source,new{action=confirmed?"CONFIRM":"CANCEL",operation=review.Operation,digest=review.Digest},token).ConfigureAwait(false);
        if(!confirmed){
            var cancelled=await Read(source,token).ConfigureAwait(false);Stage(cancelled,"CANCELLED");
            if(Text(cancelled,"operation")!=review.Operation||Text(cancelled,"digest")!=review.Digest)throw Stop();
            await RequireExited(source,token).ConfigureAwait(false);throw new OperationCanceledException("Native migration confirmation cancelled.");
        }
        var published=await Read(source,token).ConfigureAwait(false);Stage(published,"PUBLISHED");
        operation=Text(published,"operation");Hex(operation,32);
        if(operation!=review.Operation||published.GetProperty("pid").GetInt64()!=source.Id||source.HasExited)throw Stop();

        runtime=Launch(false);hello=await Read(runtime,token).ConfigureAwait(false);Stage(hello,"HELLO");
        if(hello.GetProperty("pid").GetInt64()!=runtime.Id||Text(hello,"profile")!=profile.RunId)throw Stop();
        challenge=Text(hello,"challenge");Hex(challenge,64);
        await Write(source,new{action="HANDOFF",operation,pid=runtime.Id,challenge},token).ConfigureAwait(false);
        var offered=await Read(source,token).ConfigureAwait(false);Stage(offered,"OFFERED");Match(offered);
        await TakeOver(source,token).ConfigureAwait(false);
    }
    private async Task ReopenCore(CancellationToken cancellation)
    {
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(cancellation);deadline.CancelAfter(TimeSpan.FromSeconds(120));
        var token=deadline.Token;var source=Launch(false,restart:true);
        var hello=await Read(source,token).ConfigureAwait(false);Stage(hello,"RESTART_HELLO");
        if(hello.GetProperty("pid").GetInt64()!=source.Id||Text(hello,"profile")!=profile.RunId)throw Stop();
        long handle=ProfileRecoveryCoordinator.DuplicateExclusion(runtimeLock,source);
        await Write(source,new{action="PREPARE_RESTART",operation,handle},token).ConfigureAwait(false);
        var prepared=await Read(source,token).ConfigureAwait(false);Stage(prepared,"RESTART_PREPARED");
        session=Text(prepared,"session");
        if(!Regex.IsMatch(session,"\\A[0-9]{8}\\z")||Text(prepared,"operation")!=operation
            ||prepared.GetProperty("pid").GetInt64()!=source.Id||source.HasExited)throw Stop();
        // Durable successor intent already exists, consuming the preceding safe stop before launch.
        runtime=Launch(false);hello=await Read(runtime,token).ConfigureAwait(false);Stage(hello,"HELLO");
        if(hello.GetProperty("pid").GetInt64()!=runtime.Id||Text(hello,"profile")!=profile.RunId)throw Stop();
        challenge=Text(hello,"challenge");Hex(challenge,64);
        await Write(source,new{action="BIND_RESTART",operation,session,pid=runtime.Id,challenge},token).ConfigureAwait(false);
        var offered=await Read(source,token).ConfigureAwait(false);Stage(offered,"OFFERED");Match(offered);
        if(Text(offered,"session")!=session)throw Stop();
        await TakeOver(source,token).ConfigureAwait(false);
    }
    private async Task TakeOver(Process source,CancellationToken token)
    {
        if(runtime is null)throw Stop();
        RequireNative();if(source.HasExited||runtime.HasExited)throw Stop();
        long runtimeHandle=ProfileRecoveryCoordinator.DuplicateExclusion(runtimeLock,runtime);
        await Write(runtime,new{action="ACCEPT",operation,session,handle=runtimeHandle},token).ConfigureAwait(false);
        var pinned=await Read(runtime,token).ConfigureAwait(false);Stage(pinned,"PINNED");Match(pinned);
        if(pinned.GetProperty("pid").GetInt64()!=runtime.Id||Text(pinned,"session")!=session)throw Stop();
        configuration=Text(pinned,"configuration");Hex(configuration,64);binding=pinned.GetProperty("binding").GetRawText();
        modelState=Text(pinned,"modelState");Hex(modelState,64);

        // New child has overlapping kernel/source/configuration/target protection BEFORE any
        // source release. Observe this exact source Process exiting; no record can substitute.
        await Write(source,new{action="RELEASE",operation,challenge},token).ConfigureAwait(false);
        var released=await Read(source,token).ConfigureAwait(false);Stage(released,"RELEASED");Match(released);
        await RequireExited(source,token).ConfigureAwait(false);RequireNative();if(runtime.HasExited)throw Stop();
        // Child still owns the model reader and immutable state pins. The runtime exclusion
        // remains exclusive while the child replaces its reader with the real store writer.
        modelTransferred=true;modelLock.Dispose();
        await Write(runtime,new{action="INITIALIZE",operation,challenge},token).ConfigureAwait(false);
        var verified=await Read(runtime,token).ConfigureAwait(false);RuntimeReply(verified,"VERIFIED");
        BackendPids=verified.GetProperty("backends").EnumerateArray().Select(v=>v.GetInt64()).ToArray();
        if(BackendPids.Length!=3||BackendPids.Any(p=>p<=0)||BackendPids.Distinct().Count()!=3)throw Stop();
        int port=verified.GetProperty("port").GetInt32();if(port<1||port>65535||port==18765)throw Stop();
        Endpoint=new Uri("http://127.0.0.1:"+port);
        RequireNative();if(runtime.HasExited)throw Stop();
        await Write(runtime,new{action="SERVE",operation,challenge},token).ConfigureAwait(false);
        RuntimeReply(await Read(runtime,token).ConfigureAwait(false),"SERVING");
    }
    internal async Task FinishAsync(CancellationToken cancellation)
    {
        RequireNative();if(finishAttempted||finished||runtime is null||runtime.HasExited)throw Stop();finishAttempted=true;
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(cancellation);deadline.CancelAfter(TimeSpan.FromSeconds(60));
        await Write(runtime,new{action="FINISH",operation,challenge},deadline.Token).ConfigureAwait(false);
        RuntimeReply(await Read(runtime,deadline.Token).ConfigureAwait(false),"VERIFIED_STOPPED");
        await RequireExited(runtime,deadline.Token).ConfigureAwait(false);
        var inspector=Launch(false,inspection:true);
        var hello=await Read(inspector,deadline.Token).ConfigureAwait(false);Stage(hello,"INSPECTION_HELLO");
        if(hello.GetProperty("pid").GetInt64()!=inspector.Id||Text(hello,"profile")!=profile.RunId)throw Stop();
        long handle=ProfileRecoveryCoordinator.DuplicateExclusion(runtimeLock,inspector);
        await Write(inspector,new{action="INSPECT_STOPPED",operation,session,handle,runtimePid=runtime.Id,challenge},deadline.Token).ConfigureAwait(false);
        var observed=await Read(inspector,deadline.Token).ConfigureAwait(false);Stage(observed,"STOPPED_HISTORY_TARGET_BOUND");Match(observed);
        if(observed.GetProperty("pid").GetInt64()!=inspector.Id||observed.GetProperty("pastRuntimePid").GetInt64()!=runtime.Id
            ||Text(observed,"configuration")!=configuration||observed.GetProperty("binding").GetRawText()!=binding
            ||Text(observed,"modelState")!=modelState||!observed.GetProperty("sourceAndBackupVerified").GetBoolean()
            ||observed.GetProperty("ordinaryStartupAuthorized").GetBoolean()||Text(observed,"session")!=session
            ||!observed.GetProperty("nativeExitObserved").GetBoolean())throw Stop();
        var blockers=observed.GetProperty("restartBlockers").EnumerateArray().Select(v=>v.GetString()??throw Stop()).ToArray();
        if(!blockers.SequenceEqual(new[]{"ORDINARY_NATIVE_RESTART_REQUIRED","EXTERNAL_MODEL_EXECUTION_UNPROVEN",
                "UNREGISTERED_WRITERS_UNPROVEN","USER_CONFIRMATION_REQUIRED"}))throw Stop();
        await RequireExited(inspector,deadline.Token).ConfigureAwait(false);RestartBlockers=blockers;finished=true;
    }
    private void RuntimeReply(JsonElement reply,string stage)
    {
        Stage(reply,stage);Match(reply);
        if(runtime is null||reply.GetProperty("pid").GetInt64()!=runtime.Id||Text(reply,"configuration")!=configuration
            ||reply.GetProperty("binding").GetRawText()!=binding||Text(reply,"modelState")!=modelState||Text(reply,"session")!=session)throw Stop();
    }
    private void Match(JsonElement value){if(Text(value,"operation")!=operation||Text(value,"challenge")!=challenge)throw Stop();}
    private static string Text(JsonElement value,string name)=>value.GetProperty(name).GetString()??throw Stop();
    private static void Stage(JsonElement value,string expected){
        if(Text(value,"stage")=="STOP")throw new IOException("Maintenance child STOP "+value.GetRawText());
        if(Text(value,"stage")!=expected)throw Stop();
    }
    private static void Hex(string value,int count){if(!Regex.IsMatch(value,"\\A[a-f0-9]{"+count+"}\\z"))throw Stop();}
    private static async Task<JsonElement> Read(Process process,CancellationToken cancellation)
    {
        byte[] header=new byte[4];await process.StandardOutput.BaseStream.ReadExactlyAsync(header,cancellation).ConfigureAwait(false);
        int length=BinaryPrimitives.ReadInt32BigEndian(header);if(length is <1 or >16384)throw Stop();
        byte[] bytes=new byte[length];await process.StandardOutput.BaseStream.ReadExactlyAsync(bytes,cancellation).ConfigureAwait(false);
        using var document=JsonDocument.Parse(bytes);if(document.RootElement.ValueKind!=JsonValueKind.Object)throw Stop();
        if(document.RootElement.EnumerateObject().GroupBy(p=>p.Name,StringComparer.Ordinal).Any(g=>g.Count()!=1))throw Stop();
        return document.RootElement.Clone();
    }
    private static async Task Write(Process process,object value,CancellationToken cancellation)
    {
        byte[] bytes=JsonSerializer.SerializeToUtf8Bytes(value),header=new byte[4];
        try{if(bytes.Length is <1 or >16384)throw Stop();BinaryPrimitives.WriteInt32BigEndian(header,bytes.Length);
            await process.StandardInput.BaseStream.WriteAsync(header,cancellation).ConfigureAwait(false);
            await process.StandardInput.BaseStream.WriteAsync(bytes,cancellation).ConfigureAwait(false);
            await process.StandardInput.BaseStream.FlushAsync(cancellation).ConfigureAwait(false);}
        finally{System.Security.Cryptography.CryptographicOperations.ZeroMemory(bytes);}
    }
    private static async Task RequireExited(Process process,CancellationToken cancellation)
    {
        process.StandardInput.Close();await process.WaitForExitAsync(cancellation).ConfigureAwait(false);
        if(process.ExitCode!=0||await process.StandardOutput.BaseStream.ReadAsync(new byte[1],cancellation).ConfigureAwait(false)!=0
            ||await process.StandardError.BaseStream.ReadAsync(new byte[1],cancellation).ConfigureAwait(false)!=0)throw Stop();
    }
    public void Dispose()
    {
        if(disposed)return;disposed=true;
        // An incomplete caller closes its owned pipe/Job. No completion record or ordinary
        // startup permission is synthesized from this cleanup or from a process exit code.
        foreach(var child in children)try{child.StandardInput.Close();}catch(Exception){}
        job.Dispose();foreach(var child in children){try{if(!child.HasExited)child.WaitForExit(10000);}finally{child.Dispose();}}
        modelLock.Dispose();runtimeLock.Dispose();sources.Dispose();
    }
    private static IOException Stop()=>new("Maintenance Runtime handoff incomplete; preserve operation records");
}
#endif
