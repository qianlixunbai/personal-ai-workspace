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
// generic personal maintenance remains BLOCKED. The caller must own its native choice/confirmation.
// Both children are launched here from pinned product sources; no PID lookup or saved READY record.
internal sealed class MaintenanceRuntimeHandoff : IDisposable
{
    private readonly Mmf3AcceptanceLaunch profile;
    private readonly TrustedDevelopmentRuntimeSources sources;
    private readonly RuntimeProcessJob job;
    private readonly FileStream runtimeLock, modelLock;
    private readonly List<Process> children = new();
    private Process? runtime;
    private string operation = "", challenge = "", configuration = "", binding = "";
    private bool disposed, finished, finishAttempted;
    internal Uri Endpoint { get; private set; } = null!;
    internal string Operation => operation;
    internal int RuntimePid => runtime?.Id ?? 0;
    internal string ConfigurationVersion => configuration;
    internal long[] BackendPids { get; private set; } = Array.Empty<long>();
    internal bool VerifiedStopped => finished;

    private MaintenanceRuntimeHandoff(Mmf3AcceptanceLaunch profile,TrustedDevelopmentRuntimeSources sources,
            RuntimeProcessJob job,FileStream runtimeLock,FileStream modelLock)
    {this.profile=profile;this.sources=sources;this.job=job;this.runtimeLock=runtimeLock;this.modelLock=modelLock;}

    internal static async Task<MaintenanceRuntimeHandoff> StartAsync(Mmf3AcceptanceLaunch profile,
            byte[] configuration,CancellationToken cancellation)
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
            await result.StartCore(configuration,cancellation).ConfigureAwait(false);return result;
        }
        catch
        {
            if(result is not null)result.Dispose();else{modelLock?.Dispose();runtimeLock?.Dispose();job?.Dispose();sources?.Dispose();}
            throw;
        }
        finally{System.Security.Cryptography.CryptographicOperations.ZeroMemory(configuration);}
    }
    private Process Launch(bool owner,bool inspection=false)
    {
        RequireNative();var info=sources.StartInfo(profile,maintenanceOwner:owner,maintenanceRuntime:!owner&&!inspection,maintenanceInspection:inspection);
        var child=Process.Start(info)??throw Stop();children.Add(child);_=child.SafeHandle;job.Assign(child);
        using var parent=Process.GetCurrentProcess();if(child.SessionId!=parent.SessionId)throw Stop();return child;
    }
    private void RequireNative()
    {
        ObjectDisposedException.ThrowIf(disposed,this);profile.Validate();Mmf3ProfileNative.Verify(profile);
        if(!runtimeLock.CanWrite||runtimeLock.Length!=0||modelLock.Length!=0)throw Stop();
        _=PersonalAiWorkspace.WindowsSecurity.SyntheticRecoveryPublication.FileIdentity(runtimeLock.SafeFileHandle,
            Path.Combine(profile.Root,"evidence","runtime-admission.lock"),false);
        _=PersonalAiWorkspace.WindowsSecurity.SyntheticRecoveryPublication.FileIdentity(modelLock.SafeFileHandle,
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
        await Write(source,new{action="PUBLISH",handle=sourceHandle,configuration=Convert.ToBase64String(configurationBytes)},token).ConfigureAwait(false);
        var published=await Read(source,token).ConfigureAwait(false);Stage(published,"PUBLISHED");
        operation=Text(published,"operation");Hex(operation,32);
        if(published.GetProperty("pid").GetInt64()!=source.Id||source.HasExited)throw Stop();

        runtime=Launch(false);hello=await Read(runtime,token).ConfigureAwait(false);Stage(hello,"HELLO");
        if(hello.GetProperty("pid").GetInt64()!=runtime.Id||Text(hello,"profile")!=profile.RunId)throw Stop();
        challenge=Text(hello,"challenge");Hex(challenge,64);
        await Write(source,new{action="HANDOFF",operation,pid=runtime.Id,challenge},token).ConfigureAwait(false);
        var offered=await Read(source,token).ConfigureAwait(false);Stage(offered,"OFFERED");Match(offered);
        RequireNative();if(source.HasExited||runtime.HasExited)throw Stop();
        long runtimeHandle=ProfileRecoveryCoordinator.DuplicateExclusion(runtimeLock,runtime);
        await Write(runtime,new{action="ACCEPT",operation,handle=runtimeHandle},token).ConfigureAwait(false);
        var pinned=await Read(runtime,token).ConfigureAwait(false);Stage(pinned,"PINNED");Match(pinned);
        if(pinned.GetProperty("pid").GetInt64()!=runtime.Id)throw Stop();
        configuration=Text(pinned,"configuration");Hex(configuration,64);binding=pinned.GetProperty("binding").GetRawText();

        // New child has overlapping kernel/source/configuration/target protection BEFORE any
        // source release. Observe this exact source Process exiting; no record can substitute.
        await Write(source,new{action="RELEASE",operation,challenge},token).ConfigureAwait(false);
        var released=await Read(source,token).ConfigureAwait(false);Stage(released,"RELEASED");Match(released);
        await RequireExited(source,token).ConfigureAwait(false);RequireNative();if(runtime.HasExited)throw Stop();
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
        await Write(inspector,new{action="INSPECT_STOPPED",operation,handle},deadline.Token).ConfigureAwait(false);
        var observed=await Read(inspector,deadline.Token).ConfigureAwait(false);Stage(observed,"STOPPED_HISTORY_TARGET_BOUND");Match(observed);
        if(observed.GetProperty("pid").GetInt64()!=inspector.Id||observed.GetProperty("pastRuntimePid").GetInt64()!=runtime.Id
            ||Text(observed,"configuration")!=configuration||observed.GetProperty("binding").GetRawText()!=binding
            ||observed.GetProperty("ordinaryStartupAuthorized").GetBoolean())throw Stop();
        await RequireExited(inspector,deadline.Token).ConfigureAwait(false);finished=true;
    }
    private void RuntimeReply(JsonElement reply,string stage)
    {
        Stage(reply,stage);Match(reply);
        if(runtime is null||reply.GetProperty("pid").GetInt64()!=runtime.Id||Text(reply,"configuration")!=configuration
            ||reply.GetProperty("binding").GetRawText()!=binding)throw Stop();
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
