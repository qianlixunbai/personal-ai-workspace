using Microsoft.Win32.SafeHandles;
using System;
using System.Collections.Generic;
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
            string[] stages=["OFFLINE_VERIFIED","READY"];
            // Pending, authorization, active and unknown files are deliberately not
            // recognized. An interrupted publication cannot be replayed or auto-promoted.
            string[][] allowed=[mandatory,mandatory.Append("ready.dpapi").Order(StringComparer.Ordinal).ToArray()];
            int stage=Array.FindIndex(allowed,items=>items.SequenceEqual(names));if(stage<0)throw Stop();evidence.Stage=stages[stage];
            foreach(string name in names.Select(n=>Path.GetFileNameWithoutExtension(n)))
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
