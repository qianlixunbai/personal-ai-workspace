using Microsoft.Win32.SafeHandles;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace PersonalAiWorkspace.WindowsSecurity;

// Only the isolated synthetic namespace. Personal recovery registrations remain blocked.
// This reader never publishes, provisions, decrypts database credentials or repairs a record.
internal static class SyntheticRecoveryPublication
{
    internal sealed class Evidence : IDisposable
    {
        internal readonly List<IDisposable> Pins=new();
        internal readonly Dictionary<string,JsonElement> Records=new(StringComparer.Ordinal);
        internal readonly Dictionary<string,string> Hashes=new(StringComparer.Ordinal);
        internal string Root="",Entry="",Stage="";
        internal JsonElement Offline=>Records["offline"];
        public void Dispose(){for(int i=Pins.Count-1;i>=0;i--)Pins[i].Dispose();}
    }
    internal static byte[] Entropy(string id,string name)=>Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/synthetic-target/v1/"+id+"/"+name);
    internal static string Text(JsonElement value,string name)=>value.GetProperty(name).GetString()??throw Stop();
    internal static uint Oid(JsonElement value,string name){uint result=value.GetProperty(name).GetUInt32();return result!=0?result:throw Stop();}
    private static void Hex(string value,int length){if(value.Length!=length||value.Any(c=>c is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))throw Stop();}
    private static void Equal(string actual,string expected){if(actual!=expected)throw Stop();}
    private static void Shape(JsonElement value,string fields)
    {
        if(value.ValueKind!=JsonValueKind.Object)throw Stop();
        var names=value.EnumerateObject().Select(p=>p.Name).ToArray();
        if(names.Length!=names.Distinct(StringComparer.Ordinal).Count()||!names.ToHashSet(StringComparer.Ordinal).SetEquals(fields.Split(' ')))throw Stop();
    }
    internal static Evidence Inspect(string id)
    {
        Hex(id,32);var evidence=new Evidence();
        try
        {
            using var user=WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            evidence.Root=WindowsProfileSecurity.State(id,false);
            string app=Path.GetDirectoryName(WindowsProfileSecurity.Parent())!;
            string recovery=Path.Combine(app,"Recovery"),targets=Path.Combine(recovery,"synthetic-targets");
            evidence.Entry=Path.Combine(targets,id);
            foreach(string path in new[]{app,recovery,targets,evidence.Entry,evidence.Root,Path.Combine(evidence.Root,"data")})
            {
                var pin=WindowsProfileSecurity.HoldDirectories(path);evidence.Pins.Add(pin);
                WindowsProfileSecurity.ValidatePrivate(pin.Handles[^1],user.User!,true);
            }
            string[] names=Directory.EnumerateFileSystemEntries(evidence.Entry).Select(Path.GetFileName).Order(StringComparer.Ordinal).ToArray()!;
            string[] mandatory=["attempt.dpapi","offline.dpapi","plan.dpapi","prepared.dpapi"];
            string[] stages=["OFFLINE_VERIFIED","READY","ACTIVATION_AUTHORIZED","NATIVE_ATTEMPTED","LAUNCH_CLAIMED","ACTIVE"];
            string[][] allowed=new string[stages.Length][];allowed[0]=mandatory;
            string[] additions=["ready.dpapi","authorization.dpapi","native-attempt","launch.dpapi","active.dpapi"];
            for(int i=1;i<allowed.Length;i++)allowed[i]=allowed[i-1].Append(additions[i-1]).Order(StringComparer.Ordinal).ToArray();
            int stage=Array.FindIndex(allowed,items=>items.SequenceEqual(names));if(stage<0)throw Stop();evidence.Stage=stages[stage];
            foreach(string name in names.Where(n=>n.EndsWith(".dpapi",StringComparison.Ordinal)).Select(n=>Path.GetFileNameWithoutExtension(n)))
            {
                using var document=JsonDocument.Parse(ReadProtected(evidence,name,id),new JsonDocumentOptions{MaxDepth=4});
                evidence.Records.Add(name,document.RootElement.Clone());
            }
            var plan=evidence.Records["plan"];Shape(plan,"Version Kind State OperationId TargetProfileId BackupId SourceRegistrationSha256 PayloadSha256 ManifestSha256 DatabaseName OwnerRole AppRole OwnershipReceipt");
            var prepared=evidence.Records["prepared"];Shape(prepared,"Version Kind OperationId TargetProfileId PlanSha256 RootIdentity DataIdentity");
            var attempt=evidence.Records["attempt"];Shape(attempt,"OperationId TargetProfileId PlanSha256 State");
            var offline=evidence.Offline;Shape(offline,"Version Kind State OperationId TargetProfileId PlanSha256 PreparedSha256 AttemptSha256 SourceRegistrationSha256 PayloadSha256 ManifestSha256 RootIdentity DataIdentity DatabaseName OwnerRole AppRole SystemIdentifier DatabaseOid OwnerRoleOid ApplicationRoleOid Port ConfigurationIdentity ConfigurationSha256 PermissionContract ValidationContract CredentialState PlaintextCleanup NativeGate");
            Equal(Text(plan,"Kind"),"SYNTHETIC_RECOVERY_TARGET");Equal(Text(plan,"State"),"PREPARING");
            Equal(Text(prepared,"Kind"),"SYNTHETIC_PREPARED_TARGET");Equal(Text(attempt,"State"),"RESTORING");
            Equal(Text(offline,"Kind"),"SYNTHETIC_RECOVERY_OFFLINE");Equal(Text(offline,"State"),"OFFLINE_VERIFIED");
            string operation=Text(plan,"OperationId");Hex(operation,32);Hex(Text(plan,"BackupId"),32);
            foreach(var record in new[]{plan,prepared,offline})if(record.GetProperty("Version").GetInt32()!=1)throw Stop();
            foreach(var record in new[]{plan,prepared,attempt,offline}){Equal(Text(record,"OperationId"),operation);Equal(Text(record,"TargetProfileId"),id);}
            foreach(var record in new[]{prepared,attempt,offline})Equal(Text(record,"PlanSha256"),evidence.Hashes["plan"]);
            Equal(Text(offline,"PreparedSha256"),evidence.Hashes["prepared"]);Equal(Text(offline,"AttemptSha256"),evidence.Hashes["attempt"]);
            foreach(string field in new[]{"SourceRegistrationSha256","PayloadSha256","ManifestSha256"}){Hex(Text(plan,field),64);Equal(Text(offline,field),Text(plan,field));}
            foreach(string field in new[]{"RootIdentity","DataIdentity"}){Hex(Text(prepared,field),48);Equal(Text(offline,field),Text(prepared,field));}
            Equal(Text(offline,"RootIdentity"),DirectoryIdentity(evidence.Root));Equal(Text(offline,"DataIdentity"),DirectoryIdentity(Path.Combine(evidence.Root,"data")));
            foreach(string field in new[]{"DatabaseName","OwnerRole","AppRole"})Equal(Text(offline,field),Text(plan,field));
            Equal(Text(plan,"DatabaseName"),"personal_ai_workspace_b3_"+operation[..12]);Equal(Text(plan,"OwnerRole"),"workspace_b3_owner_"+operation[..12]);Equal(Text(plan,"AppRole"),"workspace_b3_app_"+operation[..12]);
            string receipt=Text(plan,"OwnershipReceipt");if(!receipt.StartsWith("workspace-b3:",StringComparison.Ordinal)||!Guid.TryParseExact(receipt[13..],"D",out _))throw Stop();
            if(!ulong.TryParse(Text(offline,"SystemIdentifier"),out ulong system)||system==0||Oid(offline,"OwnerRoleOid")==Oid(offline,"ApplicationRoleOid"))throw Stop();
            _=Oid(offline,"DatabaseOid");int port=offline.GetProperty("Port").GetInt32();if(port is <1 or >65535 or 5432)throw Stop();
            Equal(Text(offline,"PermissionContract"),"WORKSPACE_APP_V1");Equal(Text(offline,"ValidationContract"),"WORKSPACE_OFFLINE_V1");
            Equal(Text(offline,"CredentialState"),"OWNER_DISABLED_APP_RETAINED_SYNTHETIC_ONLY");Equal(Text(offline,"PlaintextCleanup"),"COMPLETE");Equal(Text(offline,"NativeGate"),"PENDING_EXPLICIT_ACTIVATION");
            Hex(Text(offline,"ConfigurationIdentity"),48);Hex(Text(offline,"ConfigurationSha256"),64);
            var config=Open(evidence,Path.Combine(evidence.Root,"data","database.dpapi"));
            Equal(FileIdentity(config.SafeFileHandle,config.Name,false),Text(offline,"ConfigurationIdentity"));
            Equal(Convert.ToHexStringLower(SHA256.HashData(config)),Text(offline,"ConfigurationSha256"));
            if(stage>=1)
            {
                var ready=evidence.Records["ready"];Shape(ready,"Version Kind State OperationId TargetProfileId OfflineSha256 PlanSha256 PreparedSha256 AttemptSha256");
                State(ready,"SYNTHETIC_RECOVERY_READY","READY",operation,id);
                foreach(string name in new[]{"offline","plan","prepared","attempt"})Equal(Text(ready,char.ToUpperInvariant(name[0])+name[1..]+"Sha256"),evidence.Hashes[name]);
            }
            if(stage>=2)
            {
                var auth=evidence.Records["authorization"];
                Shape(auth,"Version Kind State OperationId TargetProfileId ReadySha256 RequestId Mode OwnerPid OwnerStartUtcTicks OwnerSessionId AuthorityProfileId");
                State(auth,"SYNTHETIC_ACTIVATION_AUTHORIZATION","ACTIVATION_AUTHORIZED",operation,id);
                Equal(Text(auth,"ReadySha256"),evidence.Hashes["ready"]);Hex(Text(auth,"RequestId"),32);
                Hex(Text(auth,"AuthorityProfileId"),32);if(Text(auth,"AuthorityProfileId")==id)throw Stop();
                Equal(Text(auth,"Mode"),"EXPLICIT_ISOLATED_SYNTHETIC");ProcessFields(auth,"Owner");
            }
            if(stage>=3)
            {
                var attemptFile=new FileStream(Path.Combine(evidence.Entry,"native-attempt"),FileMode.Open,FileAccess.Read,FileShare.Read);
                evidence.Pins.Add(attemptFile);_=FileIdentity(attemptFile.SafeFileHandle,attemptFile.Name,false);
                var acl=attemptFile.GetAccessControl();acl.SetAccessRuleProtection(true,true);WindowsProfileSecurity.ValidatePrivate(acl,user.User!);
                if(attemptFile.Length!=0)throw Stop();
            }
            if(stage>=4)
            {
                var launch=evidence.Records["launch"];Shape(launch,"Version Kind State OperationId TargetProfileId AuthorizationSha256 RuntimePid RuntimeStartUtcTicks RuntimeSessionId JarSha256");
                State(launch,"SYNTHETIC_ACTIVATION_LAUNCH","LAUNCH_CLAIMED",operation,id);
                Equal(Text(launch,"AuthorizationSha256"),evidence.Hashes["authorization"]);Hex(Text(launch,"JarSha256"),64);ProcessFields(launch,"Runtime");
                if(launch.GetProperty("RuntimePid").GetInt32()==evidence.Records["authorization"].GetProperty("OwnerPid").GetInt32()
                    ||launch.GetProperty("RuntimeSessionId").GetInt32()!=evidence.Records["authorization"].GetProperty("OwnerSessionId").GetInt32())throw Stop();
            }
            if(stage>=5)
            {
                var active=evidence.Records["active"];Shape(active,"Version Kind State OperationId TargetProfileId LaunchSha256 NativeIdentity StorageContract");
                State(active,"SYNTHETIC_RECOVERY_ACTIVE","ACTIVE",operation,id);Equal(Text(active,"LaunchSha256"),evidence.Hashes["launch"]);
                Equal(Text(active,"StorageContract"),"WORKSPACE_V1_V2_READY");Hex(Text(active,"NativeIdentity"),48);
                using var dll=new FileStream(Path.Combine(evidence.Root,"jna-5.17.0-win32-x86-64","jnidispatch.dll"),FileMode.Open,FileAccess.Read,FileShare.Read);
                Equal(FileIdentity(dll.SafeFileHandle,dll.Name,false),Text(active,"NativeIdentity"));
            }
            return evidence;
        }
        catch{evidence.Dispose();throw;}
    }
    internal static void RequireActive(string id){using var evidence=Inspect(id);if(evidence.Stage!="ACTIVE")throw Stop();}
    private static void ProcessFields(JsonElement value,string prefix)
    {if(value.GetProperty(prefix+"Pid").GetInt32()<=0||value.GetProperty(prefix+"StartUtcTicks").GetInt64()<=0||value.GetProperty(prefix+"SessionId").GetInt32()<0)throw Stop();}
    internal static void MatchProcess(JsonElement record,string prefix,Process process)
    {
        _=process.SafeHandle;
        if(process.HasExited||record.GetProperty(prefix+"Pid").GetInt32()!=process.Id
            ||record.GetProperty(prefix+"StartUtcTicks").GetInt64()!=process.StartTime.ToUniversalTime().Ticks
            ||record.GetProperty(prefix+"SessionId").GetInt32()!=process.SessionId)throw Stop();
    }
    // The caller identity comes from the OS, never a CLI PID or a receipt-selected process.
    // A replacement JVM/owner cannot borrow an interrupted authorization, even after PID reuse.
    internal static Evidence RequireHelperCaller(string id,bool runtime,bool provision)
    {
        var evidence=Inspect(id);
        try
        {
            if(runtime ? evidence.Stage is not ("LAUNCH_CLAIMED" or "ACTIVE")
                : provision ? evidence.Stage!="ACTIVATION_AUTHORIZED"
                : evidence.Stage is not ("NATIVE_ATTEMPTED" or "LAUNCH_CLAIMED" or "ACTIVE"))throw Stop();
            using var self=Process.GetCurrentProcess();
            if(NtQueryInformationProcess(self.SafeHandle,0,out var info,Marshal.SizeOf<BasicProcess>(),out _)!=0)throw Stop();
            using var caller=Process.GetProcessById(checked((int)info.Parent));
            MatchProcess(evidence.Records[runtime?"launch":"authorization"],runtime?"Runtime":"Owner",caller);
            var authorization=evidence.Records["authorization"];
            using var owner=Process.GetProcessById(authorization.GetProperty("OwnerPid").GetInt32());MatchProcess(authorization,"Owner",owner);
            if(caller.SessionId!=self.SessionId||owner.SessionId!=self.SessionId)throw Stop();
            return evidence;
        }
        catch{evidence.Dispose();throw;}
    }
    private static void State(JsonElement value,string kind,string state,string operation,string target)
    {if(value.GetProperty("Version").GetInt32()!=1)throw Stop();Equal(Text(value,"Kind"),kind);Equal(Text(value,"State"),state);Equal(Text(value,"OperationId"),operation);Equal(Text(value,"TargetProfileId"),target);}
    private static FileStream Open(Evidence evidence,string path)
    {
        var file=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);evidence.Pins.Add(file);
        _=FileIdentity(file.SafeFileHandle,path,false);
        using var user=WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        var acl=file.GetAccessControl();acl.SetAccessRuleProtection(true,true);WindowsProfileSecurity.ValidatePrivate(acl,user.User!);
        if(file.Length is <1 or >16384)throw Stop();return file;
    }
    private static byte[] ReadProtected(Evidence evidence,string name,string id)
    {
        var file=Open(evidence,Path.Combine(evidence.Entry,name+".dpapi"));byte[] bytes=new byte[checked((int)file.Length)];file.ReadExactly(bytes);
        evidence.Hashes[name]=Convert.ToHexStringLower(SHA256.HashData(bytes));return Unprotect(bytes,Entropy(id,name));
    }
    internal static string DirectoryIdentity(string path){using var pins=WindowsProfileSecurity.HoldDirectories(path);return FileIdentity(pins.Handles[^1],path,true);}
    internal static string FileIdentity(SafeFileHandle handle,string path,bool directory)
    {
        var final=new StringBuilder(32768);uint length=GetFinalPathNameByHandleW(handle,final,(uint)final.Capacity,0);
        if(length==0||length>=final.Capacity||!string.Equals(final.ToString(),@"\\?\"+path,StringComparison.OrdinalIgnoreCase)
            ||!GetFileInformationByHandleEx(handle,9,out Tag tag,8)||(tag.Attributes&0x400)!=0||((tag.Attributes&0x10)!=0)!=directory
            ||!GetFileInformationByHandleEx(handle,1,out Standard standard,24)||standard.DeletePending!=0||(standard.Directory!=0)!=directory||(!directory&&standard.Links!=1)
            ||!GetFileInformationByHandleEx(handle,18,out FileId id,24))throw Stop();
        Span<byte> bytes=stackalloc byte[24];System.Buffers.Binary.BinaryPrimitives.WriteUInt64LittleEndian(bytes,id.Volume);
        System.Buffers.Binary.BinaryPrimitives.WriteUInt64LittleEndian(bytes[8..],id.Low);System.Buffers.Binary.BinaryPrimitives.WriteUInt64LittleEndian(bytes[16..],id.High);return Convert.ToHexStringLower(bytes);
    }
    private static byte[] Unprotect(byte[] bytes,byte[] entropy)
    {
        var input=new Blob{Length=bytes.Length,Data=Marshal.AllocHGlobal(bytes.Length)};var optional=new Blob{Length=entropy.Length,Data=Marshal.AllocHGlobal(entropy.Length)};Blob output=default;
        try{Marshal.Copy(bytes,0,input.Data,bytes.Length);Marshal.Copy(entropy,0,optional.Data,entropy.Length);
            if(!CryptUnprotectData(ref input,IntPtr.Zero,ref optional,IntPtr.Zero,IntPtr.Zero,1,out output)||output.Length is <1 or >16384||output.Data==IntPtr.Zero)throw Stop();
            byte[] result=new byte[output.Length];Marshal.Copy(output.Data,result,0,result.Length);return result;}
        finally{Marshal.FreeHGlobal(input.Data);Marshal.FreeHGlobal(optional.Data);if(output.Data!=IntPtr.Zero){if(output.Length is >0 and <=16384)Marshal.Copy(new byte[output.Length],0,output.Data,output.Length);_ = LocalFree(output.Data);}}
    }
    private static IOException Stop()=>new("Synthetic recovery publication unavailable.");
    [StructLayout(LayoutKind.Sequential)] private struct BasicProcess{public IntPtr Exit,Peb,Affinity,Priority,Pid,Parent;}
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("ntdll.dll")] private static extern int NtQueryInformationProcess(SafeProcessHandle process,int kind,out BasicProcess info,int size,out int returned);
    [StructLayout(LayoutKind.Sequential)] private struct Blob{public int Length;public IntPtr Data;}
    [StructLayout(LayoutKind.Sequential)] private struct FileId{public ulong Volume,Low,High;}
    [StructLayout(LayoutKind.Sequential)] private struct Tag{public uint Attributes,ReparseTag;}
    [StructLayout(LayoutKind.Sequential)] private struct Standard{public long Allocation,End;public uint Links;public byte DeletePending,Directory;}
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("crypt32.dll",SetLastError=true)] private static extern bool CryptUnprotectData(ref Blob input,IntPtr description,ref Blob entropy,IntPtr reserved,IntPtr prompt,int flags,out Blob output);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true,SetLastError=true)] private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle,StringBuilder path,uint size,uint flags);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("kernel32.dll",SetLastError=true)] private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle,int kind,out FileId info,uint size);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("kernel32.dll",SetLastError=true)] private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle,int kind,out Standard info,uint size);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)] [DllImport("kernel32.dll",SetLastError=true)] private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle,int kind,out Tag info,uint size);
}
