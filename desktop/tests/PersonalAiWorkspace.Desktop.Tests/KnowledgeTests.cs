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
        internal string Id=Guid.NewGuid().ToString("D"),Request=Guid.NewGuid().ToString("D");
        internal int Calls,Uploads,Lookups;internal bool Lost;internal byte[]? Uploaded;
        internal string AnswerTaskId=Guid.NewGuid().ToString("D"),AnswerState="SUCCEEDED";
        internal string AnswerPromptVersion="knowledge-answer-v2";
        internal JsonElement? LastScope;
        internal object AnswerCitation(int ordinal)=>new{documentId=Id,title="fixture.md",sourceRevision="1",sourceType="MARKDOWN",
            startOffset=ordinal*4096,endOffset=ordinal*4096+4096,startLine=99999,endLine=100000,heading="Heading",
            locator=new{type="MARKDOWN_SECTION_LINES",startLine=99999,endLine=100000,startOffset=ordinal*4096,endOffset=ordinal*4096+4096,section="line-99999"}};
        internal object AnswerTask()=>new{taskId=AnswerTaskId,capability="knowledge-answer",status=AnswerState,profile=new{id="chat.balanced",version="m1.5-1",locality="LOCAL"},promptVersion=AnswerPromptVersion,
            createdAt="2026-10-06T00:00:00Z",finishedAt=AnswerState is "QUEUED" or "RUNNING"?null:"2026-10-06T00:00:01Z",
            result=AnswerState=="SUCCEEDED"?new{answer="<script>private answer</script>",citations=Enumerable.Range(0,1).Select(AnswerCitation).ToArray()}:null,
            error=AnswerState is "SUCCEEDED" or "QUEUED" or "RUNNING"?null:new{code=AnswerState=="CANCELLED"?"TASK_CANCELLED":AnswerState=="TIMED_OUT"?"TASK_TIMEOUT":"PROVIDER_RESPONSE_INVALID",message="safe",phase="RESPONSE"}};
        internal object SearchHit(int ordinal=0)=>new {documentId=Id,title="fixture.txt",sourceRevision="1",sourceType="TXT",
            startOffset=ordinal*2048,endOffset=ordinal*2048+1000,startLine=1,endLine=1,heading=(string?)null,
            snippet="<script>x</script>",highlightRanges=Array.Empty<object>()};
        internal object Doc()=>new{documentId=Id,title="fixture.txt",status="ACTIVE",metadataVersion="9007199254740993",currentReadyRevision="1",createdAt="2026-10-05T00:00:00Z",updatedAt="2026-10-05T00:00:00Z",processingState="READY",requestId=Request};
        internal object Job()=>new{requestId=Request,documentId=Id,state="READY",errorCode=(string?)null,sourceRevision="1"};
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)
        {
            Calls++;
            Assert.Equal("Bearer",request.Headers.Authorization?.Scheme);Assert.Null(request.Headers.Referrer);
            string path=request.RequestUri!.AbsolutePath;
            if(path=="/api/v1/knowledge/answer/tasks"||path=="/api/v1/tasks/"+AnswerTaskId){
                if(path.EndsWith("/answer/tasks")){
                    using var payload=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));LastScope=payload.RootElement.TryGetProperty("scope",out var selected)?selected.Clone():null;Assert.Equal(LastScope is null?2:3,payload.RootElement.EnumerateObject().Count());Assert.Equal("question",payload.RootElement.GetProperty("question").GetString());Assert.Equal("budget",payload.RootElement.GetProperty("query").GetString());
                }
                string json=JsonSerializer.Serialize(AnswerTask());
                var response=RuntimeClientTests.Response(path.EndsWith("/answer/tasks")?HttpStatusCode.Accepted:HttpStatusCode.OK,json);
                if(path.EndsWith("/answer/tasks"))response.Headers.Location=new Uri("/api/v1/tasks/"+AnswerTaskId,UriKind.Relative);return response;
            }
            if(path.EndsWith("/search/status")||path.EndsWith("/search/rebuild"))return Reply(new{state="READY",indexedDocuments=1,indexedChunks=1});
            if(path.EndsWith("/search")){
                Assert.Equal(HttpMethod.Post,request.Method);Assert.Equal("",request.RequestUri.Query);
                using var search=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));Assert.Equal("budget",search.RootElement.GetProperty("query").GetString());
                LastScope=search.RootElement.TryGetProperty("scope",out var selected)?selected.Clone():null;
                return Reply(new { hits = new[] { SearchHit() } });
            }
            if(path.EndsWith("/imports")&&request.Method==HttpMethod.Post){Uploads++;Request=request.Headers.GetValues("X-Knowledge-Request").Single();
                Assert.Equal("application/octet-stream",request.Content!.Headers.ContentType!.MediaType);Uploaded=await request.Content.ReadAsByteArrayAsync(ct);
                if(Lost)throw new HttpRequestException();return Reply(Job());}
            if(path.Contains("/imports/")){Lookups++;return Reply(Job());}
            Assert.Equal("/api/v1/knowledge/documents", path);
            return Reply(new { items = new[] { Doc() }, total = 1, page = 0, limit = 20 });
        }
        static HttpResponseMessage Reply(object value)=>RuntimeClientTests.Response(HttpStatusCode.OK,JsonSerializer.Serialize(value));
    }
    [Fact]public async Task DocumentScopeNeedsExistingGrantAndRejectsEscapingSearchOrAnswer()
    {
        using var f=new Fixture();string selected=f.Handler.Id;var scope=new{documentId=selected,sourceRevision="1"};
        foreach(var method in new[]{"knowledge.search","knowledge.answerSubmit"}){
            object request=method=="knowledge.search"?new{query="budget",limit=10,scope}:new{question="question",query="budget",scope};
            Assert.Equal("KnowledgeNotFound",(await f.Send(method,request)).GetProperty("error").GetProperty("code").GetString());
        }
        Assert.Equal(0,f.Handler.Calls);await f.Send("knowledge.list",new{status="ACTIVE",page=0});
        Assert.True((await f.Send("knowledge.search",new{query="budget",limit=10,scope})).GetProperty("ok").GetBoolean());
        Assert.Equal(selected,f.Handler.LastScope!.Value.GetProperty("documentId").GetString());
        f.Handler.Id=Guid.NewGuid().ToString("D");
        Assert.Equal("InvalidResponse",(await f.Send("knowledge.search",new{query="budget",limit=10,scope})).GetProperty("error").GetProperty("code").GetString());
        f.Handler.Id=selected;f.Handler.AnswerState="QUEUED";
        Assert.True((await f.Send("knowledge.answerSubmit",new{question="question",query="budget",scope})).GetProperty("ok").GetBoolean());
        Assert.Equal("1",f.Handler.LastScope!.Value.GetProperty("sourceRevision").GetString());
        f.Handler.Id=Guid.NewGuid().ToString("D");f.Handler.AnswerState="SUCCEEDED";
        Assert.Equal("InvalidResponse",(await f.Send("knowledge.answerGet",new{taskId=f.Handler.AnswerTaskId})).GetProperty("error").GetProperty("code").GetString());
        Assert.Equal(1,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
        int calls=f.Handler.Calls;f.Handler.AnswerTaskId=Guid.NewGuid().ToString("D");
        Assert.Equal("OutcomeUnknown",(await f.Send("knowledge.answerSubmit",new{question="question",query="budget",scope})).GetProperty("error").GetProperty("code").GetString());
        Assert.Equal(calls+1,f.Handler.Calls);
    }
    private sealed class Files:IKnowledgeSourceFiles
    {
        internal MemoryStream? Selected;
        public Task<KnowledgeSourceFile?> PickAsync(CancellationToken ct){Selected=new(Encoding.UTF8.GetBytes("native text"));return Task.FromResult<KnowledgeSourceFile?>(new(Selected,"fixture.txt"));}
    }
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
    [Fact]public async Task NativeUploadStreamsOnceAndUnknownOutcomeUsesReadOnlyLookup()
    {
        using var f=new Fixture();f.Handler.Lost=true;var result=await f.Send("knowledge.import",new{documentId=(string?)null,expectedMetadataVersion=(string?)null});
        Assert.Equal("ACCEPTED",result.GetProperty("result").GetProperty("outcome").GetString());Assert.Equal(1,f.Handler.Uploads);Assert.Equal(1,f.Handler.Lookups);
        Assert.Equal("native text",Encoding.UTF8.GetString(f.Handler.Uploaded!));Assert.False(f.Files.Selected!.CanRead);Assert.Equal(1,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
        Assert.DoesNotContain("native text",Assert.Single(f.Sent));Assert.DoesNotContain("Bearer",f.Sent[0]);Assert.DoesNotContain(@"C:\",f.Sent[0]);
    }
}
