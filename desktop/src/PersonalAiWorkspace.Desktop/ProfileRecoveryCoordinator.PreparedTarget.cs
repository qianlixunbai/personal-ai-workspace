#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    private readonly Dictionary<string,Process> syntheticConsumers=new(StringComparer.Ordinal);
    internal sealed record SyntheticTargetPlan(int Version, string Kind, string State, string OperationId,
        string TargetProfileId, string BackupId, string SourceRegistrationSha256, string PayloadSha256,
        string ManifestSha256, string DatabaseName, string OwnerRole, string AppRole, string OwnershipReceipt);
    internal sealed record PreparedTargetIdentity(int Version, string Kind, string OperationId, string TargetProfileId,
        string PlanSha256, string RootIdentity, string DataIdentity);

    // Called only by the isolated native fixture after its own child's authenticated-source
    // response. There is no UI/CLI/product mutation entry: full personal stop proof is absent.
    // Synthetic sources remain in their own namespace and can never reserve a product plan.
    private SyntheticTargetPlan PrepareSyntheticTarget(string syntheticApplication, string backupId,Process consumer)
    {
        ObjectDisposedException.ThrowIf(disposed,this);
        if(authority is null || consumer.HasExited || !Path.GetFileName(syntheticApplication).StartsWith("paw-source-producer-",StringComparison.Ordinal))throw Stop();
        authority.Validate(); Mmf3ProfileNative.Verify(authority); RequireHex(backupId,32);
        string sourceDirectory=Path.Combine(syntheticApplication,"Recovery","synthetic-sources",backupId);
        foreach(string path in new[]{syntheticApplication,Path.Combine(syntheticApplication,"Recovery"),Path.GetDirectoryName(sourceDirectory)!,sourceDirectory})
            HoldPrivateDirectory(path);
        if(!Directory.EnumerateFileSystemEntries(sourceDirectory).Select(Path.GetFileName).SequenceEqual(new[]{"source.dpapi"}))throw Stop();
        using var sourceFile=OpenRecord(Path.Combine(sourceDirectory,"source.dpapi"));
        var registered=ReadRecord<BoundSyntheticSource>(sourceFile,SyntheticSourceEntropy(backupId));
        ValidateSourceFields(registered.Source,backupId);
        if(registered.Version!=2 || registered.Source.Kind!="SYNTHETIC_WORKSPACE_BACKUP_RESULT"
            || registered.Source.State!="SYNTHETIC_BACKUP_RECORDED" || registered.SourceApplicationRoleOid==0)throw Stop();
        RequireHex(registered.SourceConfigurationIdentity,48);RequireHex(registered.SourceConfigurationSha256,64);
        sourceFile.Position=0;string sourceHash=Convert.ToHexStringLower(SHA256.HashData(sourceFile));
        string operation=Guid.NewGuid().ToString("N"),target=Guid.NewGuid().ToString("N"),suffix=operation[..12];
        var plan=new SyntheticTargetPlan(1,"SYNTHETIC_RECOVERY_TARGET","PREPARING",operation,target,backupId,sourceHash,
            registered.Source.PayloadSha256,registered.Source.ManifestSha256,"personal_ai_workspace_b3_"+suffix,
            "workspace_b3_owner_"+suffix,"workspace_b3_app_"+suffix,"workspace-b3:"+Guid.NewGuid().ToString("D"));
        string recovery=Path.Combine(applicationRoot,"Recovery"),targets=Path.Combine(recovery,"synthetic-targets");
        EnsurePrivateDirectory(recovery);EnsurePrivateDirectory(Path.Combine(recovery,"targets"));EnsurePrivateDirectory(targets);
        string entry=Path.Combine(targets,target),root=WindowsProfileSecurity.Root(target);
        if(Exists(root) || Exists(entry) || target==authority.RunId || target==registered.Source.SourceProfileId)throw Stop();
        var single=new SingleInstance(".MMF3."+target);
        if(!single.IsPrimary){single.Dispose();throw Stop();}held.Add(single);
        using var user=WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        WindowsProfileSecurity.CreateDirectory(entry,user.User!);HoldPrivateDirectory(entry);
        PublishRecord(entry,"plan",SyntheticTargetEntropy(target,"plan"),plan); // Before any Profile/SQL CREATE.
        if(ReadRecord<SyntheticTargetPlan>(Path.Combine(entry,"plan.dpapi"),SyntheticTargetEntropy(target,"plan"))!=plan)throw Stop();
        if(WindowsProfileSecurity.State(target,true)!=root)throw Stop();
        string rootIdentity=HoldSourceDirectoryIdentity(root),dataIdentity=HoldSourceDirectoryIdentity(Path.Combine(root,"data"));
        foreach(string name in WindowsProfileSecurity.Directories)
            if(Directory.EnumerateFileSystemEntries(Path.Combine(root,name)).Any())throw Stop();
        using var planFile=OpenRecord(Path.Combine(entry,"plan.dpapi"));
        var prepared=new PreparedTargetIdentity(1,"SYNTHETIC_PREPARED_TARGET",operation,target,
            Convert.ToHexStringLower(SHA256.HashData(planFile)),rootIdentity,dataIdentity);
        PublishRecord(entry,"prepared",SyntheticTargetEntropy(target,"prepared"),prepared);
        if(ReadRecord<PreparedTargetIdentity>(Path.Combine(entry,"prepared.dpapi"),SyntheticTargetEntropy(target,"prepared"))!=prepared)throw Stop();
        syntheticConsumers.Add(target,consumer);
        return plan; // No JNA provision, credentials, database, READY or selection mutation.
    }

    private sealed record SyntheticReady(int Version,string Kind,string State,string OperationId,string TargetProfileId,
        string OfflineSha256,string PlanSha256,string PreparedSha256,string AttemptSha256);
    private void RequireSyntheticConsumerExited(string target)
    {
        ObjectDisposedException.ThrowIf(disposed,this);
        if(authority is null||!syntheticConsumers.TryGetValue(target,out var child)||!child.HasExited||child.ExitCode!=0)throw Stop();
        authority.Validate();Mmf3ProfileNative.Verify(authority);
        // This fresh fixture authority is not a personal stopped-writer certificate.
        if(Directory.EnumerateFileSystemEntries(Path.Combine(authority.Root,"model-state")).Select(Path.GetFileName).Any(n=>n!=".lock"))throw Stop();
    }
    private void PublishSyntheticReady(string target)
    {
        RequireSyntheticConsumerExited(target);
        using(var evidence=SyntheticRecoveryPublication.Inspect(target))
        {
            if(evidence.Stage!="OFFLINE_VERIFIED"||Directory.Exists(Path.Combine(evidence.Root,"jna-5.17.0-win32-x86-64")))throw Stop();
            var ready=new SyntheticReady(1,"SYNTHETIC_RECOVERY_READY","READY",SyntheticRecoveryPublication.Text(evidence.Offline,"OperationId"),target,
                evidence.Hashes["offline"],evidence.Hashes["plan"],evidence.Hashes["prepared"],evidence.Hashes["attempt"]);
            PublishRecord(evidence.Entry,"ready",SyntheticTargetEntropy(target,"ready"),ready);
        }
        using var confirmed=SyntheticRecoveryPublication.Inspect(target);if(confirmed.Stage!="READY")throw Stop();
    }
    private sealed record SyntheticAuthorization(int Version,string Kind,string State,string OperationId,string TargetProfileId,
        string ReadySha256,string RequestId,string Mode,int OwnerPid,long OwnerStartUtcTicks,int OwnerSessionId,string AuthorityProfileId);
    private sealed record SyntheticLaunch(int Version,string Kind,string State,string OperationId,string TargetProfileId,
        string AuthorizationSha256,int RuntimePid,long RuntimeStartUtcTicks,int RuntimeSessionId,string JarSha256);
    private sealed record SyntheticActive(int Version,string Kind,string State,string OperationId,string TargetProfileId,
        string LaunchSha256,string NativeIdentity,string StorageContract);

    // Explicit isolated fixture call only. No personal/UI entry or selection write. The
    // original consumer, both maintenance handles and coordinator must still be owned here.
    private SyntheticActivation AuthorizeSyntheticActivation(string target,string requestId,FileStream runtimeLock,FileStream modelLock)
    {
        RequireHex(requestId,32);RequireSyntheticConsumerExited(target);
        var activation=new SyntheticActivation(this,target,runtimeLock,modelLock);
        activation.RequireMaintenance();
        if(IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(p=>p.Port==18765))throw Stop();
        using var evidence=SyntheticRecoveryPublication.Inspect(target);
        if(evidence.Stage!="READY"||Exists(Path.Combine(evidence.Root,"jna-5.17.0-win32-x86-64")))throw Stop();
        using var owner=Process.GetCurrentProcess();
        var auth=new SyntheticAuthorization(1,"SYNTHETIC_ACTIVATION_AUTHORIZATION","ACTIVATION_AUTHORIZED",
            SyntheticRecoveryPublication.Text(evidence.Offline,"OperationId"),target,evidence.Hashes["ready"],requestId,
            "EXPLICIT_ISOLATED_SYNTHETIC",owner.Id,owner.StartTime.ToUniversalTime().Ticks,owner.SessionId,authority!.RunId);
        PublishRecord(evidence.Entry,"authorization",SyntheticTargetEntropy(target,"authorization"),auth);
        activation.Validate();return activation;
    }

    internal sealed class SyntheticActivation : IDisposable
    {
        private readonly ProfileRecoveryCoordinator coordinator;
        private readonly FileStream authorityRuntime,authorityModel;
        private readonly object sync=new();
        private Process? child;
        private bool disposed,provisionAttempted,launchAttempted;
        internal string TargetId{get;}
        internal SyntheticActivation(ProfileRecoveryCoordinator coordinator,string target,FileStream runtime,FileStream model)
        {this.coordinator=coordinator;TargetId=target;authorityRuntime=runtime;authorityModel=model;}
        internal void RequireMaintenance()
        {
            ObjectDisposedException.ThrowIf(disposed,this);coordinator.RequireSyntheticConsumerExited(TargetId);
            string root=coordinator.authority!.Root;
            foreach(var item in new[]{(authorityRuntime,Path.Combine(root,"evidence","runtime-admission.lock")),(authorityModel,Path.Combine(root,"model-state",".lock"))})
            {
                if(!item.Item1.CanRead||item.Item1.Length!=0||item.Item1.SafeFileHandle.IsClosed)throw Stop();
                _=SyntheticRecoveryPublication.FileIdentity(item.Item1.SafeFileHandle,item.Item2,false);
            }
            if(!authorityRuntime.CanWrite)throw Stop();
        }
        internal void Validate()
        {
            lock(sync)
            {
                RequireMaintenance();using var evidence=SyntheticRecoveryPublication.Inspect(TargetId);
                if(evidence.Stage is not ("ACTIVATION_AUTHORIZED" or "NATIVE_ATTEMPTED" or "LAUNCH_CLAIMED" or "ACTIVE"))throw Stop();
                using var current=Process.GetCurrentProcess();SyntheticRecoveryPublication.MatchProcess(evidence.Records["authorization"],"Owner",current);
                if(SyntheticRecoveryPublication.Text(evidence.Records["authorization"],"AuthorityProfileId")!=coordinator.authority!.RunId)throw Stop();
                if(child is not null)SyntheticRecoveryPublication.MatchProcess(evidence.Records["launch"],"Runtime",child);
            }
        }
        internal Mmf3AcceptanceLaunch Provision()
        {
            lock(sync)
            {
                Validate();if(provisionAttempted)throw Stop();provisionAttempted=true;
                using var sources=TrustedDevelopmentRuntimeSources.Open();
                Mmf3ProfileNative.ProvisionRecovery(this,sources.JnaJar);
                return Mmf3AcceptanceLaunch.ForSyntheticActivation(this);
            }
        }
        internal void ClaimLaunch(Process process,string jarSha256)
        {
            lock(sync)
            {
                Validate();if(!provisionAttempted||!launchAttempted||child is not null)throw Stop();RequireHex(jarSha256,64);
                using var evidence=SyntheticRecoveryPublication.Inspect(TargetId);if(evidence.Stage!="NATIVE_ATTEMPTED")throw Stop();
                _=process.SafeHandle;if(process.HasExited)throw Stop();
                var claim=new SyntheticLaunch(1,"SYNTHETIC_ACTIVATION_LAUNCH","LAUNCH_CLAIMED",SyntheticRecoveryPublication.Text(evidence.Offline,"OperationId"),
                    TargetId,evidence.Hashes["authorization"],process.Id,process.StartTime.ToUniversalTime().Ticks,process.SessionId,jarSha256);
                PublishRecord(evidence.Entry,"launch",SyntheticTargetEntropy(TargetId,"launch"),claim);
                child=process;Validate(); // Only now may this exact JVM leave its private-stdin barrier.
                byte[] release=Encoding.ASCII.GetBytes("SYNTHETIC_ACTIVATION_V1");
                process.StandardInput.BaseStream.Write(release);process.StandardInput.Close();
            }
        }
        internal void ConsumeLaunch()
        {
            lock(sync){Validate();if(!provisionAttempted||launchAttempted)throw Stop();launchAttempted=true;}
        }
        internal void PublishActive(DevelopmentRuntimeOwner runtime)
        {
            lock(sync)
            {
                Validate();if(child is null||!runtime.OwnsReadyProcess(child))throw Stop();
                using var evidence=SyntheticRecoveryPublication.Inspect(TargetId);if(evidence.Stage!="LAUNCH_CLAIMED")throw Stop();
                string native=Path.Combine(evidence.Root,"jna-5.17.0-win32-x86-64","jnidispatch.dll");
                using var dll=new FileStream(native,FileMode.Open,FileAccess.Read,FileShare.Read);
                if(Convert.ToHexStringLower(SHA256.HashData(dll))!=Mmf3ProfileNative.DllHash)throw Stop();
                var active=new SyntheticActive(1,"SYNTHETIC_RECOVERY_ACTIVE","ACTIVE",SyntheticRecoveryPublication.Text(evidence.Offline,"OperationId"),TargetId,
                    evidence.Hashes["launch"],SyntheticRecoveryPublication.FileIdentity(dll.SafeFileHandle,native,false),"WORKSPACE_V1_V2_READY");
                PublishRecord(evidence.Entry,"active",SyntheticTargetEntropy(TargetId,"active"),active);
                SyntheticRecoveryPublication.RequireActive(TargetId);
            }
        }
        public void Dispose(){lock(sync)disposed=true;} // Never restore READY or enable another launch.
    }

    private static byte[] SyntheticTargetEntropy(string target,string name) =>
        Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/synthetic-target/v1/"+target+"/"+name);
}
#endif
