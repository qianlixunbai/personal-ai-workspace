using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record KnowledgeDocument(string DocumentId,string Title,string Status,string MetadataVersion,string? CurrentReadyRevision,
    string CreatedAt,string UpdatedAt,string ProcessingState,string? RequestId)
{ public override string ToString()=>$"KnowledgeDocument[documentId={DocumentId},status={Status}]"; }
public sealed record KnowledgeRevision(string DocumentId,string SourceRevision,string SourceDigest,string OriginalFilename,string SourceType,
    long ByteLength,string ImportedAt,string ParserVersion,string NormalizationVersion,string RepresentationDigest,int LineCount)
{ public override string ToString()=>$"KnowledgeRevision[documentId={DocumentId},sourceRevision={SourceRevision}]"; }
public sealed record KnowledgeJob(string RequestId,string DocumentId,string State,string? ErrorCode,string? SourceRevision);
public sealed record KnowledgeList(KnowledgeDocument[] Items,long Total,int Page,int Limit);
public sealed record KnowledgeDetail(KnowledgeDocument Document,KnowledgeRevision[] Revisions,KnowledgeJob? Job);
public sealed record KnowledgeLocator(string Type,int StartLine,int EndLine,int StartOffset,int EndOffset,string? Section,string? Heading)
{ public override string ToString()=>$"KnowledgeLocator[type={Type},startLine={StartLine},endLine={EndLine}]"; }
public sealed record KnowledgePreview(string DocumentId,string SourceRevision,int Offset,string Text,int? NextOffset,KnowledgeLocator[] Locators,string ParserVersion,string NormalizationVersion)
{ public override string ToString()=>$"KnowledgePreview[documentId={DocumentId},offset={Offset}]"; }

public sealed partial class RuntimeClient
{
    public const int MaximumKnowledgeSourceBytes=8*1024*1024;
    public static void ValidateKnowledgeFilename(string filename)=>KFilename(filename);
    private static readonly string[] KnowledgeStates=["PENDING","PARSING","READY","FAILED","CANCELLED","INTERRUPTED"];
    public async Task<KnowledgeList> ListKnowledgeAsync(string status,int page,CancellationToken token)
    {
        if(status is not ("ACTIVE" or "ARCHIVED")||page is <0 or >=25)throw new DesktopException(DesktopError.InvalidRequest);
        using var body=await KnowledgeJson(HttpMethod.Get,$"/documents?status={status}&page={page}",null,token);
        var root=body.RootElement;MemoryFields(root,"items","total","page","limit");var items=Property(root,"items");
        if(items.ValueKind!=JsonValueKind.Array||items.GetArrayLength()>20||MemoryInteger(root,"page")!=page||MemoryInteger(root,"limit")!=20)throw Invalid();
        long total=KNumber(root,"total",0,500);var rows=items.EnumerateArray().Select(KDocument).ToArray();
        if(rows.Any(x=>x.Status!=status)||rows.Select(x=>x.DocumentId).Distinct().Count()!=rows.Length||rows.Length>total)throw Invalid();
        return new(rows,total,page,20);
    }
    public async Task<KnowledgeDetail> GetKnowledgeAsync(string id,CancellationToken token)
    {
        KId(id);using var body=await KnowledgeJson(HttpMethod.Get,"/documents/"+id,null,token);var root=body.RootElement;
        MemoryFields(root,"document","revisions","job");var doc=KDocument(Property(root,"document"));if(doc.DocumentId!=id)throw Invalid();
        var revisions=Property(root,"revisions");if(revisions.ValueKind!=JsonValueKind.Array||revisions.GetArrayLength()>10)throw Invalid();
        var rows=revisions.EnumerateArray().Select(KRevision).ToArray();if(rows.Any(x=>x.DocumentId!=id)||rows.Select(x=>x.SourceRevision).Distinct().Count()!=rows.Length
            ||doc.CurrentReadyRevision is not null&&!rows.Any(x=>x.SourceRevision==doc.CurrentReadyRevision))throw Invalid();
        var job=Property(root,"job").ValueKind==JsonValueKind.Null?null:KJob(Property(root,"job"));
        if(job is not null&&(job.DocumentId!=id||job.RequestId!=doc.RequestId||job.State!=doc.ProcessingState))throw Invalid();return new(doc,rows,job);
    }
    public async Task<KnowledgeJob> GetKnowledgeImportAsync(string request,CancellationToken token)
    {KId(request);using var body=await KnowledgeJson(HttpMethod.Get,"/imports/"+request,null,token);var result=KJob(body.RootElement);if(result.RequestId!=request)throw Invalid();return result;}
    public async Task<KnowledgeJob> CancelKnowledgeImportAsync(string request,string id,CancellationToken token)
    {KId(request);KId(id);using var body=await KnowledgeJson(HttpMethod.Post,"/imports/"+request+"/cancel",new{documentId=id},token);var result=KJob(body.RootElement);if(result.DocumentId!=id||result.RequestId!=request)throw Invalid();return result;}
    public async Task<KnowledgeDocument> KnowledgeLifecycleAsync(string id,string expected,string action,CancellationToken token)
    {
        KId(id);KVersion(expected);if(action is not ("archive" or "restore"))throw Invalid();
        using var body=await KnowledgeJson(HttpMethod.Post,"/documents/"+id+"/"+action,new{expectedMetadataVersion=expected},token);
        var result=KDocument(body.RootElement);if(result.DocumentId!=id||KVersion(result.MetadataVersion)<=KVersion(expected)||result.Status!=(action=="archive"?"ARCHIVED":"ACTIVE"))throw Invalid();return result;
    }
    public async Task DeleteKnowledgeAsync(string id,string expected,CancellationToken token)
    {KId(id);KVersion(expected);using var body=await KnowledgeJson(HttpMethod.Delete,"/documents/"+id,new{expectedMetadataVersion=expected},token);MemoryFields(body.RootElement,"deleted");if(Property(body.RootElement,"deleted").ValueKind!=JsonValueKind.True)throw Invalid();}
    public async Task<KnowledgePreview> PreviewKnowledgeAsync(string id,string revision,int offset,CancellationToken token)
    {
        KId(id);KVersion(revision);if(offset<0||offset>1_000_000)throw Invalid();
        using var body=await KnowledgeJson(HttpMethod.Get,$"/documents/{id}/preview?revision={revision}&offset={offset}",null,token);var r=body.RootElement;
        MemoryFields(r,"documentId","sourceRevision","offset","text","nextOffset","locators","parserVersion","normalizationVersion");
        if(String(r,"documentId")!=id||String(r,"sourceRevision")!=revision||KNumber(r,"offset",0,1_000_000)!=offset)throw Invalid();
        string text=String(r,"text");if(text.Length>4096)throw Invalid();ValidateUnicode(text);
        int? next=Property(r,"nextOffset").ValueKind==JsonValueKind.Null?null:(int)KNumber(r,"nextOffset",0,1_000_000);
        if(next is not null&&next!=offset+text.Length)throw Invalid();var locators=Property(r,"locators");
        if(locators.ValueKind!=JsonValueKind.Array||locators.GetArrayLength()>1)throw Invalid();
        var rows=locators.EnumerateArray().Select(x=>{
            MemoryFields(x,"type","startLine","endLine","startOffset","endOffset","section","heading");string type=String(x,"type");
            if(type is not ("TXT_LINES" or "MARKDOWN_SECTION_LINES"))throw Invalid();
            int start=(int)KNumber(x,"startLine",1,100000),end=(int)KNumber(x,"endLine",start,100000),from=(int)KNumber(x,"startOffset",0,offset),to=(int)KNumber(x,"endOffset",offset+1,1_000_000);
            string? section=KNullable(x,"section"),heading=KNullable(x,"heading");
            if(type=="TXT_LINES"&&(section is not null||heading is not null)||section is not null&&!Regex.IsMatch(section,@"\Aline-[1-9][0-9]{0,5}\z")||heading is not null&&heading.EnumerateRunes().Count()>160)throw Invalid();
            if(heading is not null)ValidateUnicode(heading);return new KnowledgeLocator(type,start,end,from,to,section,heading);
        }).ToArray();KVersions(r);return new(id,revision,offset,text,next,rows,String(r,"parserVersion"),String(r,"normalizationVersion"));
    }
    // No JSON/base64 source body. Caller keeps ownership of the validated native handle.
    public async Task<KnowledgeJob> UploadKnowledgeAsync(Stream source,string filename,string requestId,string? documentId,string? expectedVersion,CancellationToken token)
    {
        KId(requestId);KFilename(filename);if(documentId is not null){KId(documentId);KVersion(expectedVersion!);}else if(expectedVersion is not null)throw Invalid();
        if(!source.CanRead||!source.CanSeek||source.Length<=0)throw new DesktopException(DesktopError.KnowledgeInvalidSource);
        if(source.Length>MaximumKnowledgeSourceBytes)throw new DesktopException(DesktopError.KnowledgeSourceTooLarge);
        source.Position=0;using var request=KnowledgeRequest(HttpMethod.Post,"/imports");
        request.Headers.Add("X-Knowledge-Request",requestId);request.Headers.Add("X-Knowledge-Size",source.Length.ToString(CultureInfo.InvariantCulture));
        request.Headers.Add("X-Knowledge-Filename",KEncode(filename));
        if(documentId is not null){request.Headers.Add("X-Knowledge-Document",documentId);request.Headers.Add("X-Knowledge-Version",expectedVersion);}
        request.Content=new StreamContent(new BorrowedStream(source),65536);request.Content.Headers.ContentType=new MediaTypeHeaderValue("application/octet-stream");
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(token);deadline.CancelAfter(TimeSpan.FromMinutes(2));
        try {
            using var response=await http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead,deadline.Token);using var body=await KnowledgeSmallResponse(response,deadline.Token);
            var result=KJob(body.RootElement);if(result.RequestId!=requestId||documentId is not null&&result.DocumentId!=documentId)throw Invalid();return result;
        }catch(HttpRequestException){throw new DesktopException(DesktopError.OutcomeUnknown);}
        catch(IOException){throw new DesktopException(DesktopError.OutcomeUnknown);}
        catch(OperationCanceledException)when(!token.IsCancellationRequested){throw new DesktopException(DesktopError.OutcomeUnknown);}
    }
    private Task<JsonDocument> KnowledgeJson(HttpMethod method,string suffix,object? payload,CancellationToken token)=>SendAsync(method,"/api/v1/knowledge"+suffix,
        payload is null?null:JsonSerializer.SerializeToUtf8Bytes(payload),true,HttpStatusCode.OK,token,endpointErrorMap:KnowledgeError);
    private HttpRequestMessage KnowledgeRequest(HttpMethod method,string suffix)
    {
        var secret=credential();if(secret is null)throw new DesktopException(DesktopError.CredentialMissing);if(!CredentialFormat.Valid(secret))throw new DesktopException(DesktopError.CredentialInvalid);
        var request=new HttpRequestMessage(method,"/api/v1/knowledge"+suffix);request.Headers.Authorization=new AuthenticationHeaderValue("Bearer",secret);return request;
    }
    private static async Task<JsonDocument> KnowledgeSmallResponse(HttpResponseMessage response,CancellationToken token)
    {
        if(response.StatusCode==HttpStatusCode.Unauthorized)throw new DesktopException(DesktopError.Unauthorized);
        if(response.Content.Headers.ContentType?.MediaType!="application/json"||response.Content.Headers.ContentLength>65536)throw Invalid();
        using var stream=await response.Content.ReadAsStreamAsync(token);using var output=new MemoryStream();byte[] buffer=new byte[8192];int n;
        while((n=await stream.ReadAsync(buffer,token))!=0){if(output.Length+n>65536)throw Invalid();output.Write(buffer,0,n);}
        JsonDocument body;try{body=JsonDocument.Parse(output.ToArray(),new JsonDocumentOptions{MaxDepth=12});}catch(JsonException){throw Invalid();}
        try{RejectDuplicates(body.RootElement);if(response.StatusCode!=HttpStatusCode.OK)throw new DesktopException(KnowledgeError(response.StatusCode,body.RootElement));return body;}
        catch{body.Dispose();throw;}
    }
    private static DesktopError KnowledgeError(HttpStatusCode status,JsonElement root)
    {
        MemoryFields(root,"code","message","phase");string code=String(root,"code");_=String(root,"message");_=String(root,"phase");
        int expected=code switch{
            "KNOWLEDGE_SEARCH_INVALID" or "KNOWLEDGE_QUERY_TOO_COMPLEX"=>400,
            "KNOWLEDGE_INDEX_LIMIT_EXCEEDED"=>409,
            "KNOWLEDGE_INDEX_NOT_READY" or "KNOWLEDGE_INDEX_UNAVAILABLE" or "KNOWLEDGE_INDEX_REBUILD_FAILED"=>503,
            "KNOWLEDGE_INVALID_SOURCE" or "KNOWLEDGE_UNSUPPORTED_TYPE" or "KNOWLEDGE_INVALID_UTF8" or "KNOWLEDGE_BACKUP_INVALID" or "KNOWLEDGE_BACKUP_UNSUPPORTED"=>400,
            "KNOWLEDGE_SOURCE_TOO_LARGE" or "KNOWLEDGE_BACKUP_TOO_LARGE"=>413,"KNOWLEDGE_NOT_FOUND"=>404,"KNOWLEDGE_QUEUE_FULL"=>429,
            "KNOWLEDGE_LIMIT_EXCEEDED" or "KNOWLEDGE_DUPLICATE_SOURCE" or "KNOWLEDGE_REVISION_CONFLICT" or "KNOWLEDGE_DELETE_INCOMPLETE" or "KNOWLEDGE_BACKUP_CONFLICT" or "KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY"=>409,
            "KNOWLEDGE_STORAGE_UNAVAILABLE" or "KNOWLEDGE_SCHEMA_UNSUPPORTED" or "KNOWLEDGE_INTERRUPTED"=>503,
            "KNOWLEDGE_INGESTION_FAILED" or "KNOWLEDGE_CANCELLED" or "KNOWLEDGE_EXPORT_FAILED" or "KNOWLEDGE_RESTORE_FAILED"=>500,
            "POLICY_DENIED"=>403,_=>throw Invalid()};
        if((int)status!=expected)throw Invalid();return KError(code);
    }
    private static DesktopError KError(string code)=>code switch{
        "KNOWLEDGE_SEARCH_INVALID"=>DesktopError.KnowledgeSearchInvalid,"KNOWLEDGE_QUERY_TOO_COMPLEX"=>DesktopError.KnowledgeQueryTooComplex,
        "KNOWLEDGE_INDEX_NOT_READY"=>DesktopError.KnowledgeIndexNotReady,"KNOWLEDGE_INDEX_UNAVAILABLE"=>DesktopError.KnowledgeIndexUnavailable,
        "KNOWLEDGE_INDEX_LIMIT_EXCEEDED"=>DesktopError.KnowledgeIndexLimitExceeded,"KNOWLEDGE_INDEX_REBUILD_FAILED"=>DesktopError.KnowledgeIndexRebuildFailed,
        "KNOWLEDGE_INVALID_SOURCE"=>DesktopError.KnowledgeInvalidSource,"KNOWLEDGE_UNSUPPORTED_TYPE"=>DesktopError.KnowledgeUnsupportedType,"KNOWLEDGE_INVALID_UTF8"=>DesktopError.KnowledgeInvalidUtf8,
        "KNOWLEDGE_SOURCE_TOO_LARGE"=>DesktopError.KnowledgeSourceTooLarge,"KNOWLEDGE_LIMIT_EXCEEDED"=>DesktopError.KnowledgeLimitExceeded,"KNOWLEDGE_DUPLICATE_SOURCE"=>DesktopError.KnowledgeDuplicateSource,
        "KNOWLEDGE_REVISION_CONFLICT"=>DesktopError.KnowledgeRevisionConflict,"KNOWLEDGE_NOT_FOUND"=>DesktopError.KnowledgeNotFound,"KNOWLEDGE_QUEUE_FULL"=>DesktopError.KnowledgeQueueFull,
        "KNOWLEDGE_INGESTION_FAILED"=>DesktopError.KnowledgeIngestionFailed,"KNOWLEDGE_INTERRUPTED"=>DesktopError.KnowledgeInterrupted,"KNOWLEDGE_CANCELLED"=>DesktopError.KnowledgeCancelled,
        "KNOWLEDGE_STORAGE_UNAVAILABLE"=>DesktopError.KnowledgeStorageUnavailable,"KNOWLEDGE_SCHEMA_UNSUPPORTED"=>DesktopError.KnowledgeSchemaUnsupported,"KNOWLEDGE_DELETE_INCOMPLETE"=>DesktopError.KnowledgeDeleteIncomplete,
        "KNOWLEDGE_BACKUP_INVALID"=>DesktopError.KnowledgeBackupInvalid,"KNOWLEDGE_BACKUP_UNSUPPORTED"=>DesktopError.KnowledgeBackupUnsupported,"KNOWLEDGE_BACKUP_TOO_LARGE"=>DesktopError.KnowledgeBackupTooLarge,
        "KNOWLEDGE_BACKUP_CONFLICT"=>DesktopError.KnowledgeBackupConflict,"KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY"=>DesktopError.KnowledgeRestoreTargetNotEmpty,"KNOWLEDGE_RESTORE_FAILED"=>DesktopError.KnowledgeRestoreFailed,
        "KNOWLEDGE_EXPORT_FAILED"=>DesktopError.KnowledgeExportFailed,"POLICY_DENIED"=>DesktopError.PolicyDenied,_=>throw Invalid()};
    private static KnowledgeDocument KDocument(JsonElement r)
    {
        MemoryFields(r,"documentId","title","status","metadataVersion","currentReadyRevision","createdAt","updatedAt","processingState","requestId");
        string id=String(r,"documentId"),title=String(r,"title"),status=String(r,"status"),version=String(r,"metadataVersion"),state=String(r,"processingState");
        KId(id);KFilename(title);KVersion(version);string? current=KNullable(r,"currentReadyRevision"),request=KNullable(r,"requestId");
        if(current is not null&&KVersion(current)>10)throw Invalid();if(request is not null)KId(request);
        if(status is not ("ACTIVE" or "ARCHIVED")||!KnowledgeStates.Contains(state))throw Invalid();KTime(r,"createdAt");KTime(r,"updatedAt");
        return new(id,title,status,version,current,String(r,"createdAt"),String(r,"updatedAt"),state,request);
    }
    private static KnowledgeRevision KRevision(JsonElement r)
    {
        MemoryFields(r,"documentId","sourceRevision","sourceDigest","originalFilename","sourceType","byteLength","importedAt","parserVersion","normalizationVersion","representationDigest","lineCount");
        string id=String(r,"documentId"),revision=String(r,"sourceRevision"),filename=String(r,"originalFilename"),type=String(r,"sourceType");KId(id);if(KVersion(revision)>10)throw Invalid();KFilename(filename);
        if(type!=(filename.EndsWith(".txt",StringComparison.OrdinalIgnoreCase)?"TXT":"MARKDOWN"))throw Invalid();KDigest(r,"sourceDigest");KDigest(r,"representationDigest");KTime(r,"importedAt");KVersions(r);
        return new(id,revision,String(r,"sourceDigest"),filename,type,KNumber(r,"byteLength",1,MaximumKnowledgeSourceBytes),String(r,"importedAt"),String(r,"parserVersion"),String(r,"normalizationVersion"),String(r,"representationDigest"),(int)KNumber(r,"lineCount",1,100000));
    }
    private static KnowledgeJob KJob(JsonElement r)
    {
        MemoryFields(r,"requestId","documentId","state","errorCode","sourceRevision");string request=String(r,"requestId"),doc=String(r,"documentId"),state=String(r,"state");KId(request);KId(doc);
        string? code=KNullable(r,"errorCode"),revision=KNullable(r,"sourceRevision");if(!KnowledgeStates.Contains(state))throw Invalid();
        if(state=="READY"){if(code is not null||revision is null||KVersion(revision)>10)throw Invalid();}
        else{if(revision is not null)throw Invalid();if(state is "PENDING" or "PARSING"){if(code is not null)throw Invalid();}else{if(code is null)throw Invalid();_=KError(code);}}
        return new(request,doc,state,code,revision);
    }
    internal static void KId(string id){if(!Guid.TryParseExact(id,"D",out var guid)||guid==Guid.Empty||guid.ToString("D")!=id)throw Invalid();}
    internal static long KVersion(string text){if(text is null||!Regex.IsMatch(text,@"\A[1-9][0-9]{0,18}\z")||!long.TryParse(text,NumberStyles.None,CultureInfo.InvariantCulture,out long value))throw Invalid();return value;}
    private static long KNumber(JsonElement r,string name,long min,long max){var n=Property(r,name);if(n.ValueKind!=JsonValueKind.Number||!n.TryGetInt64(out long value)||value<min||value>max)throw Invalid();return value;}
    private static string? KNullable(JsonElement r,string name)=>Property(r,name).ValueKind==JsonValueKind.Null?null:String(r,name);
    private static void KTime(JsonElement r,string name){if(!DateTimeOffset.TryParse(String(r,name),CultureInfo.InvariantCulture,DateTimeStyles.RoundtripKind,out _))throw Invalid();}
    private static void KDigest(JsonElement r,string name){if(!Regex.IsMatch(String(r,name),@"\A[0-9a-f]{64}\z"))throw Invalid();}
    private static void KVersions(JsonElement r){if(String(r,"parserVersion")!="text-1"||String(r,"normalizationVersion")!="lf-1")throw Invalid();}
    internal static void KFilename(string name){ValidateUnicode(name);if(string.IsNullOrWhiteSpace(name)||name.EnumerateRunes().Count()>160||name.IndexOfAny(['/', '\\', ':'])>=0||name.Any(char.IsControl)
        ||!new[]{".txt",".md",".markdown"}.Any(x=>name.EndsWith(x,StringComparison.OrdinalIgnoreCase)))throw new DesktopException(DesktopError.KnowledgeInvalidSource);}
    private static void ValidateUnicode(string text){try{_=new UTF8Encoding(false,true).GetByteCount(text);}catch(EncoderFallbackException){throw Invalid();}}
    private static string KEncode(string text)=>Convert.ToBase64String(new UTF8Encoding(false,true).GetBytes(text)).TrimEnd('=').Replace('+','-').Replace('/','_');
}
