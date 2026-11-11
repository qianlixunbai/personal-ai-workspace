using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record KnowledgeAnswerLocator(string Type,int StartLine,int EndLine,int StartOffset,int EndOffset,string? Section)
{ public override string ToString()=>"KnowledgeAnswerLocator[redacted]"; }
public sealed record KnowledgeAnswerCitation(string DocumentId,string Title,string SourceRevision,string SourceType,
    int StartOffset,int EndOffset,int StartLine,int EndLine,string? Heading,KnowledgeAnswerLocator Locator)
{ public override string ToString()=>"KnowledgeAnswerCitation[redacted]"; }
public sealed record KnowledgeAnswerResult(string Answer,IReadOnlyList<KnowledgeAnswerCitation> Citations)
{ public override string ToString()=>$"KnowledgeAnswerResult[count={Citations.Count},redacted]"; }
public sealed record KnowledgeAnswerTask(Guid TaskId,TaskState Status,KnowledgeAnswerResult? Result,DesktopError? Error)
{ public override string ToString()=>$"KnowledgeAnswerTask[taskId={TaskId},status={Status}]"; }
public sealed record KnowledgeAnswerSummary(string TaskId,string Question,string Status,string CreatedAt)
{ public override string ToString()=>"KnowledgeAnswerSummary[redacted]"; }
public sealed record KnowledgeAnswerHistoryPage(IReadOnlyList<KnowledgeAnswerSummary> Items,int Total,int Page,int Limit);
public sealed record KnowledgeAnswerSearchPage(string Query,KnowledgeAnswerHistoryPage Results)
{ public override string ToString()=>"KnowledgeAnswerSearchPage[redacted]"; }
public sealed record KnowledgeAnswerHistoryEntry(string Question,string Query,KnowledgeAnswerTask Task,KnowledgeSourceScope? Scope=null)
{ public override string ToString()=>"KnowledgeAnswerHistoryEntry[redacted]"; }

public sealed partial class RuntimeClient
{
    public async Task<KnowledgeAnswerHistoryPage> ListKnowledgeAnswersAsync(int page,CancellationToken token)
    {
        if(page is <0 or >=50)throw new DesktopException(DesktopError.InvalidRequest);
        using var body=await SendAsync(HttpMethod.Get,$"/api/v1/knowledge/answer/tasks?page={page}",null,true,HttpStatusCode.OK,token,endpointErrorMap:KnowledgeAnswerError,maximumResponse:65536);
        return ParseKnowledgeAnswerPage(body.RootElement,page);
    }
    public async Task<KnowledgeAnswerSearchPage> SearchKnowledgeAnswersAsync(string query,int page,CancellationToken token)
    {
        ValidateKnowledgeQuery(query);if(page is <0 or >=50)throw new DesktopException(DesktopError.InvalidRequest);
        using var body=await SendAsync(HttpMethod.Get,$"/api/v1/knowledge/answer/tasks/search?query={Uri.EscapeDataString(query)}&page={page}",null,true,HttpStatusCode.OK,token,endpointErrorMap:KnowledgeAnswerError,maximumResponse:65536);
        var r=body.RootElement;MemoryFields(r,"query","results");if(String(r,"query")!=query)throw Invalid();
        return new(query,ParseKnowledgeAnswerPage(Property(r,"results"),page));
    }
    private static KnowledgeAnswerHistoryPage ParseKnowledgeAnswerPage(JsonElement r,int page)
    {
        MemoryFields(r,"items","total","page","limit");
        if(KNumber(r,"page",0,49)!=page||KNumber(r,"limit",20,20)!=20)throw Invalid();int total=(int)KNumber(r,"total",0,1000);
        var items=Property(r,"items");if(items.ValueKind!=JsonValueKind.Array||items.GetArrayLength()!=Math.Clamp(total-page*20,0,20))throw Invalid();
        var rows=items.EnumerateArray().Select(row=>{
            MemoryFields(row,"taskId","question","status","createdAt");string id=String(row,"taskId"),question=String(row,"question"),status=String(row,"status");KId(id);ValidateUnicode(question);KTime(row,"createdAt");
            if(string.IsNullOrWhiteSpace(question)||question.EnumerateRunes().Count()>120||status is not ("QUEUED" or "SUCCEEDED" or "FAILED" or "CANCELLED" or "TIMED_OUT"))throw Invalid();
            return new KnowledgeAnswerSummary(id,question,status,String(row,"createdAt"));
        }).ToArray();if(rows.Select(x=>x.TaskId).Distinct().Count()!=rows.Length)throw Invalid();
        return new(Array.AsReadOnly(rows),total,page,20);
    }
    public async Task<KnowledgeAnswerHistoryEntry> GetKnowledgeAnswerHistoryAsync(Guid id,CancellationToken token)
    {
        KId(id.ToString("D"));using var body=await SendAsync(HttpMethod.Get,$"/api/v1/knowledge/answer/tasks/{id:D}",null,true,HttpStatusCode.OK,token,endpointErrorMap:KnowledgeAnswerError,maximumResponse:131072);
        var r=body.RootElement;MemoryFields(r,r.TryGetProperty("scope",out var selected)?["question","query","task","scope"]:["question","query","task"]);string question=String(r,"question"),query=String(r,"query");
        try{new AskInput(question).Validate();ValidateUnicode(question);ValidateKnowledgeQuery(query);}catch(DesktopException){throw Invalid();}
        var scope=selected.ValueKind==JsonValueKind.Undefined?null:ReadKnowledgeScope(selected);var task=ParseKnowledgeAnswerTask(Property(r,"task"),id);
        if(scope is not null&&task.Result is not null&&task.Result.Citations.Any(c=>c.DocumentId!=scope.DocumentId||c.SourceRevision!=scope.SourceRevision))throw Invalid();
        return new(question,query,task,scope);
    }
    public async Task DeleteKnowledgeAnswerHistoryAsync(Guid id,CancellationToken token)
    {
        KId(id.ToString("D"));using var body=await SendAsync(HttpMethod.Delete,$"/api/v1/knowledge/answer/tasks/{id:D}",null,true,HttpStatusCode.OK,token,endpointErrorMap:KnowledgeAnswerError,maximumResponse:1024);
        MemoryFields(body.RootElement,"deleted");if(Property(body.RootElement,"deleted").ValueKind!=JsonValueKind.True)throw Invalid();
    }
    public async Task<KnowledgeAnswerTask> SubmitKnowledgeAnswerAsync(string question,string query,CancellationToken token,KnowledgeSourceScope? scope=null)
    {
        new AskInput(question).Validate();ValidateUnicode(question);ValidateKnowledgeQuery(query);
        ValidateKnowledgeScope(scope);
        object request=scope is null?new{question,query}:new{question,query,scope=KnowledgeScopePayload(scope)};
        try {
            using var body=await SendAsync(HttpMethod.Post,"/api/v1/knowledge/answer/tasks",JsonSerializer.SerializeToUtf8Bytes(request),true,HttpStatusCode.Accepted,token,
                (response,document)=>{var task=ParseKnowledgeAnswerTask(document.RootElement,null);if(response.Headers.Location?.OriginalString!=$"/api/v1/tasks/{task.TaskId:D}")throw Invalid();
                    if(scope is not null&&task.Result is not null&&task.Result.Citations.Any(c=>c.DocumentId!=scope.DocumentId||c.SourceRevision!=scope.SourceRevision))throw Invalid();},
                endpointErrorMap:KnowledgeAnswerError,maximumResponse:65536);
            return ParseKnowledgeAnswerTask(body.RootElement,null);
        } catch(DesktopException e)when(e.Error is DesktopError.RuntimeUnavailable or DesktopError.ClientTimeout or DesktopError.InvalidResponse) {
            // History can discover the receipt later. Never replay this POST.
            throw new DesktopException(DesktopError.OutcomeUnknown);
        }
    }
    public Task<KnowledgeAnswerTask> GetKnowledgeAnswerAsync(Guid id,CancellationToken token)=>KnowledgeAnswerTaskRequest(HttpMethod.Get,id,token);
    public Task<KnowledgeAnswerTask> CancelKnowledgeAnswerAsync(Guid id,CancellationToken token)=>KnowledgeAnswerTaskRequest(HttpMethod.Delete,id,token);
    private async Task<KnowledgeAnswerTask> KnowledgeAnswerTaskRequest(HttpMethod method,Guid id,CancellationToken token)
    {
        KId(id.ToString("D"));using var body=await SendAsync(method,$"/api/v1/tasks/{id:D}",null,true,HttpStatusCode.OK,token,endpointErrorMap:KnowledgeAnswerError,maximumResponse:65536);
        return ParseKnowledgeAnswerTask(body.RootElement,id);
    }
    private static DesktopError KnowledgeAnswerError(HttpStatusCode status,JsonElement root)
    {
        MemoryFields(root,"code","message","phase");string code=String(root,"code");_=String(root,"message");_=String(root,"phase");
        if(code.StartsWith("KNOWLEDGE_",StringComparison.Ordinal))return KnowledgeError(status,root);
        int expected=code switch {"INVALID_REQUEST"=>400,"POLICY_DENIED"=>403,"QUEUE_FULL"=>429,
            "TASK_NOT_FOUND"=>404,"MODEL_SWITCH_CONFLICT" or "MODEL_SELECTION_REVISION_CONFLICT" or "MODEL_EXECUTION_UNCERTAIN"=>409,
            "PROVIDER_UNAVAILABLE" or "MODEL_UNAVAILABLE" or "MODEL_STATE_UNAVAILABLE" or "MODEL_CONFIGURATION_INVALID" or "MODEL_IDENTITY_CHANGED"=>503,
            "TASK_TIMEOUT"=>504,"INTERNAL_ERROR" or "PROVIDER_RESPONSE_INVALID"=>500,_=>throw Invalid()};
        if((int)status!=expected)throw Invalid();return MapError(code);
    }
    private static KnowledgeAnswerTask ParseKnowledgeAnswerTask(JsonElement r,Guid? expected)
    {
        MemoryFields(r,"taskId","capability","status","profile","promptVersion","createdAt","finishedAt","result","error");
        string taskId=String(r,"taskId");KId(taskId);Guid id=Guid.ParseExact(taskId,"D");if(expected is not null&&expected!=id)throw Invalid();
        if(String(r,"capability")!="knowledge-answer"||String(r,"promptVersion") is not ("knowledge-answer-v2" or "knowledge-answer-v3"))throw Invalid();
        var profile=Property(r,"profile");MemoryFields(profile,"id","version","locality");
        if(String(profile,"id")!="chat.balanced"||String(profile,"locality")!="LOCAL"||string.IsNullOrWhiteSpace(String(profile,"version")))throw Invalid();
        string state=String(r,"status");if(!Enum.TryParse<TaskState>(state,false,out var status)||!Enum.IsDefined(status)||status.ToString()!=state)throw Invalid();
        KTime(r,"createdAt");var created=DateTimeOffset.Parse(String(r,"createdAt"));bool terminal=status is not (TaskState.QUEUED or TaskState.RUNNING);
        if(terminal){KTime(r,"finishedAt");if(DateTimeOffset.Parse(String(r,"finishedAt"))<created)throw Invalid();}else if(Property(r,"finishedAt").ValueKind!=JsonValueKind.Null)throw Invalid();
        var result=Property(r,"result");var error=Property(r,"error");
        if(status==TaskState.SUCCEEDED){if(error.ValueKind!=JsonValueKind.Null)throw Invalid();return new(id,status,KAnswerResult(result),null);}
        if(result.ValueKind!=JsonValueKind.Null)throw Invalid();
        if(!terminal){if(error.ValueKind!=JsonValueKind.Null)throw Invalid();return new(id,status,null,null);}
        MemoryFields(error,"code","message","phase");_=String(error,"message");_=String(error,"phase");string code=String(error,"code");
        if(status==TaskState.CANCELLED?code!="TASK_CANCELLED":status==TaskState.TIMED_OUT?code!="TASK_TIMEOUT":
            code is not ("PROVIDER_UNAVAILABLE" or "MODEL_UNAVAILABLE" or "MODEL_STATE_UNAVAILABLE" or "MODEL_IDENTITY_CHANGED" or "MODEL_EXECUTION_UNCERTAIN"
                or "PROVIDER_RESPONSE_INVALID" or "POLICY_DENIED" or "INVALID_REQUEST" or "INTERNAL_ERROR" or "QUEUE_FULL"
                or "KNOWLEDGE_INTERRUPTED" or "KNOWLEDGE_STORAGE_UNAVAILABLE" or "KNOWLEDGE_LIMIT_EXCEEDED"))throw Invalid();
        return new(id,status,null,code.StartsWith("KNOWLEDGE_",StringComparison.Ordinal)?KError(code):MapError(code));
    }
    private static KnowledgeAnswerResult KAnswerResult(JsonElement r)
    {
        MemoryFields(r,"answer","citations");string answer=String(r,"answer");ValidateUnicode(answer);
        if(string.IsNullOrWhiteSpace(answer)||answer.Length>2048)throw Invalid();
        var citations=Property(r,"citations");if(citations.ValueKind!=JsonValueKind.Array||citations.GetArrayLength() is <1 or >10)throw Invalid();
        var rows=citations.EnumerateArray().Select(KAnswerCitation).ToArray();
        if(rows.Select(c=>(c.DocumentId,c.SourceRevision,c.StartOffset)).Distinct().Count()!=rows.Length)throw Invalid();
        return new(answer,Array.AsReadOnly(rows));
    }
    private static KnowledgeAnswerCitation KAnswerCitation(JsonElement r)
    {
        MemoryFields(r,"documentId","title","sourceRevision","sourceType","startOffset","endOffset","startLine","endLine","heading","locator");
        string id=String(r,"documentId"),title=String(r,"title"),revision=String(r,"sourceRevision"),type=String(r,"sourceType");KId(id);
        try{KFilename(title);}catch(DesktopException){throw Invalid();}
        if(KVersion(revision)>10||type is not ("TXT" or "MARKDOWN"))throw Invalid();
        int start=(int)KNumber(r,"startOffset",0,1000000),end=(int)KNumber(r,"endOffset",start+1,Math.Min(start+4096,1000000));
        int first=(int)KNumber(r,"startLine",1,100000),last=(int)KNumber(r,"endLine",first,100000);
        string? heading=KNullable(r,"heading");if(heading is not null){ValidateUnicode(heading);if(heading.EnumerateRunes().Count()>160)throw Invalid();}
        var l=Property(r,"locator");MemoryFields(l,"type","startLine","endLine","startOffset","endOffset","section");string locatorType=String(l,"type");
        if(locatorType!=(type=="TXT"?"TXT_LINES":"MARKDOWN_SECTION_LINES"))throw Invalid();
        int from=(int)KNumber(l,"startOffset",0,start),to=(int)KNumber(l,"endOffset",end,1000000),line=(int)KNumber(l,"startLine",1,first),lineEnd=(int)KNumber(l,"endLine",last,100000);
        string? section=KNullable(l,"section");
        if(type=="TXT"&&(heading is not null||section is not null)||section is not null&&(!Regex.IsMatch(section,@"\Aline-[1-9][0-9]{0,5}\z")||int.Parse(section[5..])>line))throw Invalid();
        return new(id,title,revision,type,start,end,first,last,heading,new(locatorType,line,lineEnd,from,to,section));
    }
}
