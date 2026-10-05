using System.Net;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class KnowledgeTests
{
    private const string Document=WorkspaceContentPolicy.ProductionOrigin+"/index.html";
    private sealed class Handler:HttpMessageHandler
    {
        internal string Id=Guid.NewGuid().ToString("D"),Request=Guid.NewGuid().ToString("D"),Version="9007199254740993",Status="ACTIVE";
        internal int Calls,Uploads,Lookups;internal bool Lost,Deleted,Malformed;internal byte[]? Uploaded;internal TaskCompletionSource? Hold;
        internal object Doc()=>new{documentId=Id,title="fixture.txt",status=Status,metadataVersion=Version,currentReadyRevision="1",createdAt="2026-10-05T00:00:00Z",updatedAt="2026-10-05T00:00:00Z",processingState="READY",requestId=Request};
        internal object Job()=>new{requestId=Request,documentId=Id,state="READY",errorCode=(string?)null,sourceRevision="1"};
        internal object Revision()=>new{documentId=Id,sourceRevision="1",sourceDigest=new string('a',64),originalFilename="fixture.txt",sourceType="TXT",byteLength=11,importedAt="2026-10-05T00:00:00Z",parserVersion="text-1",normalizationVersion="lf-1",representationDigest=new string('b',64),lineCount=1};
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)
        {
            Calls++;if(Hold is not null)await Hold.Task;
            Assert.Equal("Bearer",request.Headers.Authorization?.Scheme);Assert.Null(request.Headers.Referrer);
            string path=request.RequestUri!.AbsolutePath;
            if(path.EndsWith("/imports")&&request.Method==HttpMethod.Post){Uploads++;Request=request.Headers.GetValues("X-Knowledge-Request").Single();
                Assert.Equal("application/octet-stream",request.Content!.Headers.ContentType!.MediaType);Uploaded=await request.Content.ReadAsByteArrayAsync(ct);
                if(Lost)throw new HttpRequestException();return Reply(Job());}
            if(path.Contains("/imports/")){Lookups++;return Reply(Job());}
            if(path.EndsWith("/documents"))return Reply(new{items=Deleted?Array.Empty<object>():new[]{Doc()},total=Deleted?0:1,page=0,limit=20});
            if(request.Method==HttpMethod.Delete){Deleted=true;return Reply(new{deleted=true});}
            if(path.EndsWith("/archive")||path.EndsWith("/restore")){using var p=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                Assert.Equal(Version,p.RootElement.GetProperty("expectedMetadataVersion").GetString());Version=(long.Parse(Version)+1).ToString();Status=path.EndsWith("/archive")?"ARCHIVED":"ACTIVE";return Reply(Doc());}
            if(path.EndsWith("/preview"))return Reply(new{documentId=Id,sourceRevision="1",offset=0,text="<script>x</script>",nextOffset=(int?)null,
                locators=new[]{new{type="TXT_LINES",startLine=1,endLine=1,startOffset=0,endOffset=18,section=(string?)null,heading=(string?)null}},parserVersion="text-1",normalizationVersion="lf-1"});
            return Malformed?Reply(new{document=Doc(),revisions=new[]{Revision()},job=Job(),path=@"C:\private"}):Reply(new{document=Doc(),revisions=new[]{Revision()},job=Job()});
        }
        static HttpResponseMessage Reply(object value)=>RuntimeClientTests.Response(HttpStatusCode.OK,JsonSerializer.Serialize(value));
    }
    private sealed class Files:IKnowledgeSourceFiles
    {internal MemoryStream? Selected;public Task<KnowledgeSourceFile?> PickAsync(CancellationToken ct){Selected=new(Encoding.UTF8.GetBytes("native text"));return Task.FromResult<KnowledgeSourceFile?>(new(Selected,"fixture.txt"));}}
    private sealed class Actions(WorkspaceKnowledge knowledge):IWorkspaceNativeActions
    {public WorkspaceKnowledge Knowledge=>knowledge;public Task<ShellStatus> StatusAsync(CancellationToken ct)=>Task.FromResult(new ShellStatus(1,"1.0.0",ShellRuntimeState.Available,ShellCredentialState.Ready,"Available",[]));public Task OpenAsync(NativeWorkspaceEntry entry,CancellationToken ct)=>Task.CompletedTask;}
    private sealed class Fixture:IDisposable
    {
        internal readonly Handler Handler=new();internal readonly Files Files=new();internal readonly RuntimeClient Runtime;internal readonly WorkspaceKnowledge Knowledge;internal readonly WorkspaceBridge Bridge;internal readonly List<string> Sent=[];
        internal Fixture(){Runtime=new(Handler,()=>new string('t',43));Knowledge=new(Runtime,Files);Bridge=new(new WorkspaceContentPolicy(),new Actions(Knowledge),Sent.Add);Rotate();}
        internal void Rotate(){Bridge.BeginDocument(Document);Bridge.Ready(Document);Sent.Clear();}
        internal string Request(string method,object payload)=>JsonSerializer.Serialize(new{version=1,sessionId=Bridge.SessionId,requestId=Guid.NewGuid().ToString("D"),method,payload});
        internal async Task<JsonElement> Send(string method,object payload){Sent.Clear();await Bridge.ReceiveAsync(Document,Document,Request(method,payload));return JsonDocument.Parse(Assert.Single(Sent)).RootElement.Clone();}
        internal Task<JsonElement> List()=>Send("knowledge.list",new{status="ACTIVE",page=0});
        public void Dispose(){Bridge.Dispose();Runtime.Dispose();}
    }
    [Fact]public async Task SessionAuthorityRequiresListOrImportClearsOnRotationAndDeleteRevokes()
    {
        using var f=new Fixture();var denied=await f.Send("knowledge.get",new{documentId=f.Handler.Id});Assert.Equal("KnowledgeNotFound",denied.GetProperty("error").GetProperty("code").GetString());Assert.Equal(0,f.Handler.Calls);
        var listed=await f.List();Assert.Equal("9007199254740993",listed.GetProperty("result").GetProperty("items")[0].GetProperty("metadataVersion").GetString());
        await f.Send("knowledge.get",new{documentId=f.Handler.Id});f.Rotate();await f.Send("knowledge.get",new{documentId=f.Handler.Id});Assert.Equal(2,f.Handler.Calls);
        await f.List();await f.Send("knowledge.delete",new{documentId=f.Handler.Id,expectedMetadataVersion=f.Handler.Version});Assert.Equal(0,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
        await f.Send("knowledge.get",new{documentId=f.Handler.Id});Assert.Equal(4,f.Handler.Calls);
    }
    [Fact]public async Task NativeUploadStreamsOnceAndUnknownOutcomeUsesReadOnlyLookup()
    {
        using var f=new Fixture();f.Handler.Lost=true;var result=await f.Send("knowledge.import",new{documentId=(string?)null,expectedMetadataVersion=(string?)null});
        Assert.Equal("ACCEPTED",result.GetProperty("result").GetProperty("outcome").GetString());Assert.Equal(1,f.Handler.Uploads);Assert.Equal(1,f.Handler.Lookups);
        Assert.Equal("native text",Encoding.UTF8.GetString(f.Handler.Uploaded!));Assert.False(f.Files.Selected!.CanRead);Assert.Equal(1,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
        Assert.DoesNotContain("native text",Assert.Single(f.Sent));Assert.DoesNotContain("Bearer",f.Sent[0]);Assert.DoesNotContain(@"C:\",f.Sent[0]);
    }
    [Fact]public async Task BridgeRejectsPathsBytesUnknownFieldsAndNumericVersionsWithoutCallingRuntime()
    {
        using var f=new Fixture();foreach(var payload in new object[]{new{path=@"C:\private.txt"},new{documentId=(string?)null,expectedMetadataVersion=(string?)null,base64="eA=="},new{documentId=f.Handler.Id,expectedMetadataVersion=1}})
        {f.Sent.Clear();await f.Bridge.ReceiveAsync(Document,Document,f.Request("knowledge.import",payload));Assert.Empty(f.Sent);}
        Assert.Equal(0,f.Handler.Calls);
    }
    [Fact]public async Task LateListCannotAuthorizeNewSessionOrSendStaleResponse()
    {
        using var f=new Fixture();f.Handler.Hold=new();var pending=f.Bridge.ReceiveAsync(Document,Document,f.Request("knowledge.list",new{status="ACTIVE",page=0}));
        f.Rotate();f.Handler.Hold.SetResult();await pending;Assert.Empty(f.Sent);Assert.Equal(0,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
    }
    [Fact]public async Task StrictRuntimeDtosRejectExtraFieldsAndPreviewUsesOrdinaryBudget()
    {
        using var f=new Fixture();await f.List();f.Handler.Malformed=true;
        var invalid=await f.Send("knowledge.get",new{documentId=f.Handler.Id});Assert.Equal("InvalidResponse",invalid.GetProperty("error").GetProperty("code").GetString());
        var preview=await f.Send("knowledge.preview",new{documentId=f.Handler.Id,sourceRevision="1",offset=0});Assert.Equal("<script>x</script>",preview.GetProperty("result").GetProperty("text").GetString());
        Assert.True(Encoding.UTF8.GetByteCount(f.Sent[0])<WorkspaceBridge.MaximumResponseBytes);
    }
    [Fact]public void NativeOpenedObjectValidationRejectsDirectoryUncEmptyOversizeAndKeepsOriginalHandle()
    {
        string root=Path.Combine(Path.GetTempPath(),"workspace-knowledge-test-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            string file=Path.Combine(root,"source.txt");File.WriteAllText(file,"source text",new UTF8Encoding(false));
            using(var selected=NativeKnowledgeSourceFiles.Open(file)){Assert.Equal("source.txt",selected.Filename);Assert.Equal(11,selected.Stream.Length);Assert.Throws<IOException>(()=>File.Delete(file));}
            Assert.Throws<DesktopException>(()=>NativeKnowledgeSourceFiles.Open(root));Assert.Throws<DesktopException>(()=>NativeKnowledgeSourceFiles.Open(@"\\server\share\source.txt"));
            File.WriteAllBytes(file,[]);Assert.Equal(DesktopError.KnowledgeInvalidSource,Assert.Throws<DesktopException>(()=>NativeKnowledgeSourceFiles.Open(file)).Error);
            using(var output=File.OpenWrite(file))output.SetLength(RuntimeClient.MaximumKnowledgeSourceBytes+1L);
            Assert.Equal(DesktopError.KnowledgeSourceTooLarge,Assert.Throws<DesktopException>(()=>NativeKnowledgeSourceFiles.Open(file)).Error);
        }finally{File.Delete(Path.Combine(root,"source.txt"));Directory.Delete(root);}
    }
}
