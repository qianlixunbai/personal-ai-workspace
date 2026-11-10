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
    private static int RunMaintenanceRuntimeHandoff(string id,bool interrupt=false)
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
            boundary="NATIVE_HANDOFF";
            using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(150));
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
            int runtimePid=handoff.RuntimePid;
            boundary=interrupt?"OWNED_SESSION_INTERRUPTION":"SAFE_FINISH";
            if(interrupt){
                handoff.Dispose(); // Real pipe/owned Job termination; never synthesize FINISH.
                var records=Directory.GetFiles(Path.Combine(profile.Root,"evidence","sqlite-activation",handoff.Operation))
                    .Select(Path.GetFileName).Order().ToArray();
                if(!records.SequenceEqual(new[]{"00-pinned.dpapi","01-initializing.dpapi","02-verified.dpapi","intent.dpapi"}))throw new IOException();
            }else handoff.FinishAsync(deadline.Token).GetAwaiter().GetResult();
            if(handoff.VerifiedStopped==interrupt||!sources.Select(Hash).SequenceEqual(before))throw new IOException();
            if(Hash(Path.Combine(profile.Root,"data","database.dpapi")).ToLowerInvariant()!=handoff.ConfigurationVersion)throw new IOException();
            Console.WriteLine(JsonSerializer.Serialize(new{passed=true,profile=id,operation=handoff.Operation,runtimePid,
                port=handoff.Endpoint.Port,configuration=handoff.ConfigurationVersion,backends=handoff.BackendPids,
                laterRow=row,sourceUnchanged=true,verifiedStopped=handoff.VerifiedStopped,interrupted=interrupt,ordinaryStartupEnabled=false}));return 0;
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
