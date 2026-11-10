#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private static int RunMaintenanceRuntimeHandoff(string id,bool interrupt=false,bool reopen=false)
    {
        string boundary="PROFILE";
        try
        {
            var profile=Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument,id]);
            using var pins=Mmf3ProfileState.Pin(id);
            byte[] configuration=Encoding.UTF8.GetBytes(Console.In.ReadToEnd());
            if(configuration.Length is <1 or >4096||File.Exists(Path.Combine(profile.Root,"data","database.dpapi")))throw new IOException();
            string[] sources=new[]{Path.Combine(profile.Root,"data","memory.db"),Path.Combine(profile.Root,"data","knowledge","knowledge.db")}
                .Concat(Directory.GetFiles(Path.Combine(profile.Root,"data","knowledge","sources"),"*.source",SearchOption.AllDirectories)).ToArray();
            string Hash(string path)=>Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path)));
            var before=sources.Select(Hash).ToArray();
            string modelSelection=Path.Combine(profile.Root,"model-state","active-model.json");
            string? modelHash=File.Exists(modelSelection)?Hash(modelSelection):null;
            boundary="NATIVE_HANDOFF";
            using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(reopen?300:150));
            bool pendingModelRejected=false;
            if(modelHash is not null){
                boundary="PENDING_MODEL_PREFLIGHT";
                string guard=Path.Combine(profile.Root,"model-state","execution-guard.pending");
                byte[] marker=Encoding.UTF8.GetBytes("synthetic unresolved execution");
                using(var file=new FileStream(guard,FileMode.CreateNew,FileAccess.Write,FileShare.None)){file.Write(marker);file.Flush(true);}
                try{using var unexpected=MaintenanceRuntimeHandoff.StartAsync(profile,configuration.ToArray(),deadline.Token).GetAwaiter().GetResult();}
                catch(IOException rejected) when(rejected.Message.StartsWith("Maintenance child STOP",StringComparison.Ordinal)) {pendingModelRejected=true;}
                if(!pendingModelRejected||Directory.Exists(Path.Combine(profile.Root,"evidence","sqlite-maintenance"))
                    ||File.Exists(Path.Combine(profile.Root,"data","database.dpapi"))||!File.ReadAllBytes(guard).SequenceEqual(marker))throw new IOException();
                File.Delete(guard); // Fixture removes only the exact synthetic marker it created.
            }
            boundary="NATIVE_HANDOFF";
            using var handoff=MaintenanceRuntimeHandoff.StartAsync(profile,configuration,deadline.Token).GetAwaiter().GetResult();
            boundary="LIVE_HTTP_BACKEND";
            using var http=new HttpClient(new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false}){BaseAddress=handoff.Endpoint,Timeout=TimeSpan.FromSeconds(20)};
            string credential=CredentialStore.ReadPrivateFile(profile.ValidateTokenImport(profile.TokenFile),requireProtected:true);
            http.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",credential);
            using var status=http.GetAsync("/api/v1/storage/status").GetAwaiter().GetResult();
            if(status.StatusCode!=HttpStatusCode.OK)throw new IOException();
            using(var body=JsonDocument.Parse(status.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())){
                var value=body.RootElement;
                if(value.GetProperty("backend").GetString()!="POSTGRESQL"||value.GetProperty("configurationTrust").GetString()!="TRUSTED_LOCAL"
                    ||value.GetProperty("connection").GetString()!="CONNECTED"||value.GetProperty("workspaceSchema").GetString()!="V1_V2_READY"
                    ||value.GetProperty("knowledgeSchema").GetString()!="READY")throw new IOException();
            }
            // One new synthetic row proves real post-initialization Runtime writes are possible.
            // End must preserve it, rather than compare/revert to the original import snapshot.
            using var content=new StringContent(JsonSerializer.Serialize(new{type="PROJECT_NOTE",title="After native takeover",content="Preserve later Runtime data"}),Encoding.UTF8,"application/json");
            using var saved=http.PostAsync("/api/v1/memory/items",content).GetAwaiter().GetResult();
            if(saved.StatusCode!=HttpStatusCode.Created)throw new IOException();
            using var savedBody=JsonDocument.Parse(saved.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult());
            string row=savedBody.RootElement.GetProperty("id").GetString()??throw new IOException();
            using var read=http.GetAsync("/api/v1/memory/items/"+row).GetAwaiter().GetResult();
            if(read.StatusCode!=HttpStatusCode.OK)throw new IOException();
            using(var body=JsonDocument.Parse(read.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()))
                if(body.RootElement.GetProperty("content").GetString()!="Preserve later Runtime data")throw new IOException();
            using var modelRequest=http.GetAsync("/api/v1/models").GetAwaiter().GetResult();
            if(modelRequest.StatusCode!=HttpStatusCode.ServiceUnavailable)throw new IOException();
            int runtimePid=handoff.RuntimePid;
            boundary=interrupt?"OWNED_SESSION_INTERRUPTION":"SAFE_FINISH";
            if(interrupt){
                handoff.Dispose(); // Real pipe/owned Job termination; never synthesize FINISH.
                var records=Directory.GetFiles(Path.Combine(profile.Root,"evidence","sqlite-activation",handoff.Operation))
                    .Select(Path.GetFileName).Order().ToArray();
                if(!records.SequenceEqual(new[]{"00-pinned.dpapi","01-initializing.dpapi","02-verified.dpapi","intent.dpapi"}))throw new IOException();
            }else handoff.FinishAsync(deadline.Token).GetAwaiter().GetResult();
            if(interrupt){
                using var released=new FileStream(Path.Combine(profile.Root,"model-state",".lock"),FileMode.Open,FileAccess.ReadWrite,FileShare.None);
                if(released.Length!=0)throw new IOException(); // Actual writer handle released; no file mutation.
            }
            if(handoff.VerifiedStopped==interrupt||!sources.Select(Hash).SequenceEqual(before)
                ||modelHash!=(File.Exists(modelSelection)?Hash(modelSelection):null))throw new IOException();
            if(Hash(Path.Combine(profile.Root,"data","database.dpapi")).ToLowerInvariant()!=handoff.ConfigurationVersion)throw new IOException();
            object? reopening=null;
            if(reopen){
                boundary="NATIVE_REOPEN";handoff.Dispose();
                using var resumed=MaintenanceRuntimeHandoff.ReopenAsync(profile,handoff.Operation,deadline.Token).GetAwaiter().GetResult();
                if(resumed.Session!="00000001"||resumed.ConfigurationVersion!=handoff.ConfigurationVersion)throw new IOException();
                using var resumedHttp=new HttpClient(new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false})
                    {BaseAddress=resumed.Endpoint,Timeout=TimeSpan.FromSeconds(20)};
                boundary="REOPEN_SESSION_CREDENTIAL";
                resumedHttp.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",credential);
                using var stale=resumedHttp.GetAsync("/api/v1/memory/items/"+row).GetAwaiter().GetResult();
                if(stale.StatusCode!=HttpStatusCode.Unauthorized)throw new IOException();
                string resumedCredential=CredentialStore.ReadPrivateFile(profile.ValidateTokenImport(profile.TokenFile),requireProtected:true);
                if(resumedCredential==credential)throw new IOException();
                resumedHttp.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",resumedCredential);
                boundary="REOPEN_PRESERVED_DATA";
                using var oldRow=resumedHttp.GetAsync("/api/v1/memory/items/"+row).GetAwaiter().GetResult();
                if(oldRow.StatusCode!=HttpStatusCode.OK)throw new IOException();
                using var laterContent=new StringContent(JsonSerializer.Serialize(new{type="PROJECT_NOTE",title="After maintenance reopen",content="Second session persists"}),Encoding.UTF8,"application/json");
                using var later=resumedHttp.PostAsync("/api/v1/memory/items",laterContent).GetAwaiter().GetResult();
                if(later.StatusCode!=HttpStatusCode.Created)throw new IOException();
                using var laterBody=JsonDocument.Parse(later.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult());
                string secondRow=laterBody.RootElement.GetProperty("id").GetString()??throw new IOException();
                using var blocked=resumedHttp.GetAsync("/api/v1/models").GetAwaiter().GetResult();
                if(blocked.StatusCode!=HttpStatusCode.ServiceUnavailable)throw new IOException();
                resumed.FinishAsync(deadline.Token).GetAwaiter().GetResult();int resumedPid=resumed.RuntimePid;resumed.Dispose();
                boundary="REOPEN_INTERRUPTION";
                using var interrupted=MaintenanceRuntimeHandoff.ReopenAsync(profile,handoff.Operation,deadline.Token).GetAwaiter().GetResult();
                if(interrupted.Session!="00000002")throw new IOException();
                using var interruptedHttp=new HttpClient(new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false})
                    {BaseAddress=interrupted.Endpoint,Timeout=TimeSpan.FromSeconds(20)};
                string interruptedCredential=CredentialStore.ReadPrivateFile(profile.ValidateTokenImport(profile.TokenFile),requireProtected:true);
                if(interruptedCredential==resumedCredential)throw new IOException();
                interruptedHttp.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",interruptedCredential);
                using var preserved=interruptedHttp.GetAsync("/api/v1/memory/items/"+secondRow).GetAwaiter().GetResult();
                if(preserved.StatusCode!=HttpStatusCode.OK)throw new IOException();
                using(var body=JsonDocument.Parse(preserved.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()))
                    if(body.RootElement.GetProperty("content").GetString()!="Second session persists")throw new IOException();
                int interruptedPid=interrupted.RuntimePid;interrupted.Dispose();
                string activation=Path.Combine(profile.Root,"evidence","sqlite-activation",handoff.Operation);
                var records=Directory.GetFiles(Path.Combine(activation,"sessions","00000002")).Select(Path.GetFileName).Order().ToArray();
                if(!records.SequenceEqual(new[]{"00-pinned.dpapi","01-initializing.dpapi","02-verified.dpapi","intent.dpapi"}))throw new IOException();
                var evidenceBefore=Directory.GetFiles(activation,"*",SearchOption.AllDirectories).Order()
                    .Select(path=>path+":"+Hash(path)).ToArray();
                boundary="OLD_STOP_REUSE_REJECTED";bool rejected=false;
                try{using var unexpected=MaintenanceRuntimeHandoff.ReopenAsync(profile,handoff.Operation,deadline.Token).GetAwaiter().GetResult();}
                catch(IOException refusal) when(refusal.Message.StartsWith("Maintenance child STOP",StringComparison.Ordinal)){rejected=true;}
                if(!rejected||!Directory.GetFiles(activation,"*",SearchOption.AllDirectories).Order().Select(path=>path+":"+Hash(path)).SequenceEqual(evidenceBefore)
                    ||!sources.Select(Hash).SequenceEqual(before)||modelHash!=(File.Exists(modelSelection)?Hash(modelSelection):null))throw new IOException();
                using var released=new FileStream(Path.Combine(profile.Root,"model-state",".lock"),FileMode.Open,FileAccess.ReadWrite,FileShare.None);
                if(released.Length!=0)throw new IOException();
                reopening=new{resumedPid,interruptedPid,secondRow,completedSession="00000001",interruptedSession="00000002",
                    laterRowsPreserved=true,oldStopReuseRejected=rejected,failedReopenEvidenceUnchanged=true,modelExecutionBlocked=true,
                    sessionCredentialsRenewed=true,previousCredentialRejected=true};
            }
            Console.WriteLine(JsonSerializer.Serialize(new{passed=true,profile=id,operation=handoff.Operation,runtimePid,
                port=handoff.Endpoint.Port,configuration=handoff.ConfigurationVersion,backends=handoff.BackendPids,
                laterRow=row,sourceUnchanged=true,verifiedStopped=handoff.VerifiedStopped,interrupted=interrupt,ordinaryStartupEnabled=false,
                modelExecutionBlocked=true,modelStateUnchanged=true,syntheticSelectionPresent=modelHash is not null,
                pendingModelRejected,restartBlockers=handoff.RestartBlockers,reopening}));return 0;
        }
        catch(Exception failure)
        {
            Console.WriteLine("MAINTENANCE_HANDOFF_PROBE_STOP "+boundary+" "+failure.GetType().Name);
            if(failure.Message.StartsWith("Maintenance child STOP",StringComparison.Ordinal))Console.WriteLine(failure.Message);
            return 10;
        }
    }
}
#endif
