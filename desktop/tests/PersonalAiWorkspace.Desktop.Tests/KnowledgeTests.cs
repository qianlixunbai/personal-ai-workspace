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
        internal bool SearchMalformed,SearchWorstCase;
        internal string AnswerTaskId=Guid.NewGuid().ToString("D"),AnswerState="SUCCEEDED";internal bool AnswerWorstCase,AnswerLost;internal string? AnswerInvalid;
        internal object AnswerCitation(int ordinal)=>new{documentId=Id,title=AnswerWorstCase?string.Concat(Enumerable.Repeat("😀",157))+".md":"fixture.md",sourceRevision="1",sourceType="MARKDOWN",
            startOffset=ordinal*4096,endOffset=ordinal*4096+4096,startLine=99999,endLine=100000,heading=AnswerWorstCase?string.Concat(Enumerable.Repeat("😀",160)):"Heading",
            locator=new{type="MARKDOWN_SECTION_LINES",startLine=99999,endLine=100000,startOffset=ordinal*4096,endOffset=ordinal*4096+4096,section="line-99999"}};
        internal object AnswerTask()=>new{taskId=AnswerTaskId,capability="knowledge-answer",status=AnswerState,profile=new{id="chat.balanced",version="m1.5-1",locality="LOCAL"},promptVersion="knowledge-answer-v1",
            createdAt="2026-10-06T00:00:00Z",finishedAt=AnswerState is "QUEUED" or "RUNNING"?null:"2026-10-06T00:00:01Z",
            result=AnswerState=="SUCCEEDED"?new{answer=AnswerWorstCase?new string('中',2048):"<script>private answer</script>",citations=Enumerable.Range(0,AnswerWorstCase?10:1).Select(AnswerCitation).ToArray()}:null,
            error=AnswerState is "SUCCEEDED" or "QUEUED" or "RUNNING"?null:new{code=AnswerState=="CANCELLED"?"TASK_CANCELLED":AnswerState=="TIMED_OUT"?"TASK_TIMEOUT":"PROVIDER_RESPONSE_INVALID",message="safe",phase="RESPONSE"}};
        internal object SearchHit(int ordinal=0)=>new {documentId=Id,title=SearchWorstCase?new string('中',160):"fixture.txt",sourceRevision="1",sourceType="TXT",
            startOffset=ordinal*2048,endOffset=ordinal*2048+1000,startLine=1,endLine=1,heading=SearchWorstCase?new string('中',96):null,
            snippet=SearchWorstCase?new string('中',384):"<script>x</script>",highlightRanges=SearchWorstCase?Enumerable.Range(0,16).Select(i=>new{start=i*2,end=i*2+1}).ToArray():[]};
        internal object Doc()=>new{documentId=Id,title="fixture.txt",status=Status,metadataVersion=Version,currentReadyRevision="1",createdAt="2026-10-05T00:00:00Z",updatedAt="2026-10-05T00:00:00Z",processingState="READY",requestId=Request};
        internal object Job()=>new{requestId=Request,documentId=Id,state="READY",errorCode=(string?)null,sourceRevision="1"};
        internal object Revision()=>new{documentId=Id,sourceRevision="1",sourceDigest=new string('a',64),originalFilename="fixture.txt",sourceType="TXT",byteLength=11,importedAt="2026-10-05T00:00:00Z",parserVersion="text-1",normalizationVersion="lf-1",representationDigest=new string('b',64),lineCount=1};
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)
        {
            Calls++;if(Hold is not null)await Hold.Task;
            Assert.Equal("Bearer",request.Headers.Authorization?.Scheme);Assert.Null(request.Headers.Referrer);
            string path=request.RequestUri!.AbsolutePath;
            if(path=="/api/v1/knowledge/answer/tasks"||path=="/api/v1/tasks/"+AnswerTaskId){
                if(path.EndsWith("/answer/tasks")){
                    using var payload=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));Assert.Equal(2,payload.RootElement.EnumerateObject().Count());Assert.Equal("question",payload.RootElement.GetProperty("question").GetString());Assert.Equal("budget",payload.RootElement.GetProperty("query").GetString());if(AnswerLost)throw new HttpRequestException();
                }
                string json=JsonSerializer.Serialize(AnswerTask());if(AnswerInvalid is not null)json=AnswerInvalid;
                var response=RuntimeClientTests.Response(path.EndsWith("/answer/tasks")?HttpStatusCode.Accepted:HttpStatusCode.OK,json);
                if(path.EndsWith("/answer/tasks"))response.Headers.Location=new Uri("/api/v1/tasks/"+AnswerTaskId,UriKind.Relative);return response;
            }
            if(path.EndsWith("/search/status")||path.EndsWith("/search/rebuild"))return Reply(new{state="READY",indexedDocuments=1,indexedChunks=1});
            if(path.EndsWith("/search")){
                Assert.Equal(HttpMethod.Post,request.Method);Assert.Equal("",request.RequestUri.Query);
                using var search=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));Assert.Equal("budget",search.RootElement.GetProperty("query").GetString());
                return SearchMalformed?Reply(new{hits=new[]{SearchHit()},corpusFingerprint=new string('a',64)}):Reply(new{hits=Enumerable.Range(0,SearchWorstCase?10:1).Select(i=>SearchHit(i)).ToArray()});
            }
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
    [Fact]public async Task AnswerStrictProjectionBoundsAndUnknownPostNeverReplays()
    {
        using var f=new Fixture();f.Handler.AnswerWorstCase=true;
        var response=await f.Send("knowledge.answerSubmit",new{question="question",query="budget"});Assert.True(response.GetProperty("ok").GetBoolean());
        Assert.Equal(10,response.GetProperty("result").GetProperty("result").GetProperty("citations").GetArrayLength());Assert.True(Encoding.UTF8.GetByteCount(f.Sent.Single())<65536);
        foreach(string hidden in new[]{"sourceDigest","representationDigest","fingerprint","rowid","score","path","label","text"})Assert.DoesNotContain("\""+hidden+"\"",f.Sent.Single());
        f.Handler.AnswerWorstCase=false;string valid=JsonSerializer.Serialize(f.Handler.AnswerTask());
        foreach(string invalid in new[]{valid.Replace("\"answer\":","\"extra\":1,\"answer\":"),valid.Replace("\"citations\":[","\"citations\":[],\"other\":["),valid.Replace("knowledge-answer-v1","ask-v1"),valid.Replace("chat.balanced","translate.fast"),valid.Replace("MARKDOWN_SECTION_LINES","TXT_LINES"),valid.Replace("\"endOffset\":4096","\"endOffset\":0"),valid+" {}"}) {
            f.Handler.AnswerInvalid=invalid;var ex=await Assert.ThrowsAsync<DesktopException>(()=>f.Runtime.GetKnowledgeAnswerAsync(Guid.Parse(f.Handler.AnswerTaskId),default));Assert.Equal(DesktopError.InvalidResponse,ex.Error);
        }
        f.Handler.AnswerInvalid=null;foreach(string state in new[]{"QUEUED","RUNNING","FAILED","CANCELLED","TIMED_OUT"}){f.Handler.AnswerState=state;Assert.Equal(state,(await f.Runtime.GetKnowledgeAnswerAsync(Guid.Parse(f.Handler.AnswerTaskId),default)).Status.ToString());}
        f.Handler.AnswerState="SUCCEEDED";f.Handler.AnswerLost=true;int calls=f.Handler.Calls;
        var lost=await f.Send("knowledge.answerSubmit",new{question="question",query="budget"});Assert.Equal("OutcomeUnknown",lost.GetProperty("error").GetProperty("code").GetString());Assert.Equal(calls+1,f.Handler.Calls);
    }
    [Fact]public async Task AnswerTaskAuthorityIsSessionBoundAndLateSubmitOrGetCannotAuthorizeNewSession()
    {
        using var f=new Fixture();string id=f.Handler.AnswerTaskId;
        foreach(string method in new[]{"knowledge.answerGet","knowledge.answerCancel"}){var denied=await f.Send(method,new{taskId=id});Assert.Equal("TaskNotFound",denied.GetProperty("error").GetProperty("code").GetString());}Assert.Equal(0,f.Handler.Calls);
        f.Handler.AnswerState="QUEUED";await f.Send("knowledge.answerSubmit",new{question="question",query="budget"});Assert.Equal(1,f.Knowledge.AnswerAuthorizationCount(f.Bridge.SessionId));Assert.Equal(0,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
        f.Handler.AnswerState="SUCCEEDED";await f.Send("knowledge.answerGet",new{taskId=id});Assert.Equal(1,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
        Assert.DoesNotContain("private answer",(await f.Runtime.GetKnowledgeAnswerAsync(Guid.Parse(id),default)).ToString());
        f.Rotate();Assert.Equal(0,f.Knowledge.AnswerAuthorizationCount(f.Bridge.SessionId));
        foreach(string method in new[]{"knowledge.answerSubmit","knowledge.answerGet"}){
            f.Handler.Hold=null;f.Handler.AnswerState="QUEUED";if(method=="knowledge.answerGet")await f.Send("knowledge.answerSubmit",new{question="question",query="budget"});
            f.Handler.AnswerState="SUCCEEDED";f.Handler.Hold=new();var payload=method=="knowledge.answerSubmit"?(object)new{question="question",query="budget"}:new{taskId=id};
            var pending=f.Bridge.ReceiveAsync(Document,Document,f.Request(method,payload));f.Rotate();f.Handler.Hold.SetResult();await pending;Assert.Empty(f.Sent);Assert.Equal(0,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));Assert.Equal(0,f.Knowledge.AnswerAuthorizationCount(f.Bridge.SessionId));
        }
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
    [Fact]public async Task SearchAuthorizesGetPreviewAndRotationClearsAuthority()
    {
        using var f=new Fixture();var response=await f.Send("knowledge.search",new{query="budget",limit=10});Assert.True(response.GetProperty("ok").GetBoolean());
        Assert.Equal(1,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));await f.Send("knowledge.get",new{documentId=f.Handler.Id});
        await f.Send("knowledge.preview",new{documentId=f.Handler.Id,sourceRevision="1",offset=0});f.Rotate();
        var denied=await f.Send("knowledge.get",new{documentId=f.Handler.Id});Assert.Equal("KnowledgeNotFound",denied.GetProperty("error").GetProperty("code").GetString());
    }
    [Fact]public async Task LateSearchCannotAuthorizeReplacementSession()
    {
        using var f=new Fixture();f.Handler.Hold=new();var pending=f.Bridge.ReceiveAsync(Document,Document,f.Request("knowledge.search",new{query="budget",limit=10}));
        f.Rotate();f.Handler.Hold.SetResult();await pending;Assert.Empty(f.Sent);Assert.Equal(0,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
    }
    [Fact]public async Task SearchRejectsExtraIndexFieldsAndWorstCaseEscapingFitsOrdinaryBridge()
    {
        using var f=new Fixture();f.Handler.SearchMalformed=true;var rejected=await f.Send("knowledge.search",new{query="budget",limit=10});
        Assert.Equal("InvalidResponse",rejected.GetProperty("error").GetProperty("code").GetString());Assert.Equal(0,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
        f.Handler.SearchMalformed=false;f.Handler.SearchWorstCase=true;var response=await f.Send("knowledge.search",new{query="budget",limit=10});
        Assert.Equal(10,response.GetProperty("result").GetProperty("hits").GetArrayLength());Assert.True(Encoding.UTF8.GetByteCount(f.Sent.Single())<65536);
        foreach(string hidden in new[]{"sourceDigest","representationDigest","corpusFingerprint","rowid","score","tokens","path"})Assert.DoesNotContain("\""+hidden+"\"",f.Sent.Single());
        Assert.DoesNotContain("budget",(await f.Runtime.SearchKnowledgeAsync("budget",10,default)).ToString());
    }
    [Fact]public async Task SearchBridgeRejectsUnknownPayloadAndOutOfRangeLimits()
    {
        using var f=new Fixture();foreach(var payload in new object[]{new{query="budget",limit=11},new{query="budget",limit="1"},new{query="budget",limit=1,path="private"},new{query="x"+new string('中',128),limit=1}})
        {await f.Bridge.ReceiveAsync(Document,Document,f.Request("knowledge.search",payload));Assert.Empty(f.Sent);}Assert.Equal(0,f.Handler.Calls);
    }
    [Fact]public async Task NativeUploadStreamsOnceAndUnknownOutcomeUsesReadOnlyLookup()
    {
        using var f=new Fixture();f.Handler.Lost=true;var result=await f.Send("knowledge.import",new{documentId=(string?)null,expectedMetadataVersion=(string?)null});
        Assert.Equal("ACCEPTED",result.GetProperty("result").GetProperty("outcome").GetString());Assert.Equal(1,f.Handler.Uploads);Assert.Equal(1,f.Handler.Lookups);
        Assert.Equal("native text",Encoding.UTF8.GetString(f.Handler.Uploaded!));Assert.False(f.Files.Selected!.CanRead);Assert.Equal(1,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
        Assert.DoesNotContain("native text",Assert.Single(f.Sent));Assert.DoesNotContain("Bearer",f.Sent[0]);Assert.DoesNotContain(@"C:\",f.Sent[0]);
    }
    [Fact]public async Task KnowledgeGetSerializesOnlyUiRevisionMetadata()
    {
        using var f=new Fixture();await f.List();var response=await f.Send("knowledge.get",new{documentId=f.Handler.Id});
        var revision=Assert.Single(response.GetProperty("result").GetProperty("revisions").EnumerateArray());
        Assert.Equal(new[]{"byteLength","sourceRevision","sourceType"},revision.EnumerateObject().Select(p=>p.Name).OrderBy(n=>n,StringComparer.Ordinal).ToArray());
        Assert.Equal("1",revision.GetProperty("sourceRevision").GetString());Assert.Equal("TXT",revision.GetProperty("sourceType").GetString());Assert.Equal(11,revision.GetProperty("byteLength").GetInt64());
        string serialized=Assert.Single(f.Sent);
        foreach(string field in new[]{"sourceDigest","representationDigest","parserVersion","normalizationVersion","lineCount","originalFilename","importedAt","path","sourcePath","sourceBytes","backupPath","base64","text"})
            Assert.DoesNotContain("\""+field+"\"",serialized);
        foreach(string value in new[]{new string('a',64),new string('b',64),@"C:\","native text"})Assert.DoesNotContain(value,serialized);
        var native=await f.Runtime.GetKnowledgeAsync(f.Handler.Id,default);
        Assert.Equal(new string('a',64),native.Revisions[0].SourceDigest);Assert.Equal(new string('b',64),native.Revisions[0].RepresentationDigest);
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
