#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    internal sealed record SyntheticTargetPlan(int Version, string Kind, string State, string OperationId,
        string TargetProfileId, string BackupId, string SourceRegistrationSha256, string PayloadSha256,
        string ManifestSha256, string DatabaseName, string OwnerRole, string AppRole, string OwnershipReceipt);
    internal sealed record PreparedTargetIdentity(int Version, string Kind, string OperationId, string TargetProfileId,
        string PlanSha256, string RootIdentity, string DataIdentity);

    // Called only by the isolated native fixture after its own child's authenticated-source
    // response. There is no UI/CLI/product mutation entry: full personal stop proof is absent.
    // Synthetic sources remain in their own namespace and can never reserve a product plan.
    private SyntheticTargetPlan PrepareSyntheticTarget(string syntheticApplication, string backupId)
    {
        ObjectDisposedException.ThrowIf(disposed,this);
        if(authority is null || !Path.GetFileName(syntheticApplication).StartsWith("paw-source-producer-",StringComparison.Ordinal))throw Stop();
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
        return plan; // No JNA provision, credentials, database, READY or selection mutation.
    }

    private static byte[] SyntheticTargetEntropy(string target,string name) =>
        Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/synthetic-target/v1/"+target+"/"+name);
}
#endif
