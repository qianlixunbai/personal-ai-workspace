using System.Net;
using System.Net.Http;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class StorageStatusBridgeTests
{
    private sealed class Actions(RuntimeClient runtime):IWorkspaceNativeActions
    {
        public Task<StorageStatus> StorageStatusAsync(CancellationToken cancellation)=>runtime.GetStorageStatusAsync(cancellation);
        public Task<ShellStatus> StatusAsync(CancellationToken cancellation)=>throw new NotSupportedException();
        public Task OpenAsync(NativeWorkspaceEntry entry,CancellationToken cancellation)=>throw new NotSupportedException();
    }
    [Fact] public async Task SessionBoundReadOnlyStatusUsesNativeBearerAndRejectsSecretBearingResponses()
    {
        string token=new('t',43);int requests=0;bool hostile=false;
        var status=new StorageStatus("2026-10-18T00:00:00Z","SQLITE",false,"NOT_CONFIGURED","NOT_CHECKED","NOT_CHECKED",false,"DISABLED",
            "EXPLICIT_APPROVAL_REQUIRED","MAINTENANCE_ONLY","BLOCKED_SECURITY_REQUIREMENTS",
            ["WINDOWS_PROFILE_APPROVAL_REQUIRED","VOLUME_PROTECTION_REQUIRED","FINANCE_IDENTITY_CONFIRMATION_REQUIRED","CROSS_DEVICE_VERIFICATION_REQUIRED"],"NONE");
        using var runtime=new RuntimeClient(new RuntimeClientTests.Handler((request,_)=>{
            requests++;Assert.Equal(HttpMethod.Get,request.Method);Assert.Equal(new Uri(RuntimeClient.Endpoint,"/api/v1/storage/status"),request.RequestUri);
            Assert.Equal(token,request.Headers.Authorization?.Parameter);Assert.Null(request.Content);
            string json=JsonSerializer.Serialize(status,new JsonSerializerOptions(JsonSerializerDefaults.Web));
            if(hostile)json=json[..^1]+",\"password\":\"private-response-marker\"}";
            return Task.FromResult(RuntimeClientTests.Response(HttpStatusCode.OK,json));
        }),()=>token);
        const string document=WorkspaceContentPolicy.ProductionOrigin+"/index.html";
        var sent=new List<string>();using var bridge=new WorkspaceBridge(new WorkspaceContentPolicy(),new Actions(runtime),sent.Add);
        bridge.BeginDocument(document);bridge.Ready(document);sent.Clear();
        string Request(object payload,string? session=null)=>JsonSerializer.Serialize(new{version=1,sessionId=session??bridge.SessionId,requestId=Guid.NewGuid().ToString("D"),method="storage.status",payload});
        await bridge.ReceiveAsync(document,document,Request(new{url="http://remote.invalid"}));
        await bridge.ReceiveAsync(document,document,Request(new{},Guid.NewGuid().ToString("D")));
        Assert.Empty(sent);Assert.Equal(0,requests);
        await bridge.ReceiveAsync(document,document,Request(new{}));
        using(var parsed=JsonDocument.Parse(Assert.Single(sent))) {
            var result=parsed.RootElement.GetProperty("result");Assert.Equal("SQLITE",result.GetProperty("backend").GetString());
            Assert.Equal("NOT_CONFIGURED",result.GetProperty("connection").GetString());Assert.False(result.TryGetProperty("password",out _));
        }
        Assert.DoesNotContain(token,sent[0]);sent.Clear();hostile=true;
        await bridge.ReceiveAsync(document,document,Request(new{}));
        Assert.Contains("InvalidResponse",Assert.Single(sent));Assert.DoesNotContain("private-response-marker",sent[0]);Assert.Equal(2,requests);
    }
}
