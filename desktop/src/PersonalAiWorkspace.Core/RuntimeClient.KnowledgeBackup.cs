using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed record KnowledgeBackupMetadata(int FormatVersion,int SchemaVersion,string CreatedAt,int DocumentCount,int RevisionCount,long SourceBytes,long ArtifactBytes,string ContentDigest);
public sealed partial class RuntimeClient
{
    public const long MaximumKnowledgeBackupBytes=2L*1024*1024*1024+256L*1024*1024+32L*1024*1024;
    public Task<KnowledgeBackupMetadata> ValidateKnowledgeBackupAsync(Stream file,CancellationToken token)=>KnowledgeBackupRequest(file,null,token);
    public Task<KnowledgeBackupMetadata> RestoreKnowledgeBackupAsync(Stream file,string target,CancellationToken token)=>KnowledgeBackupRequest(file,target,token);
    private async Task<KnowledgeBackupMetadata> KnowledgeBackupRequest(Stream file,string? target,CancellationToken token)
    {
        if(!file.CanRead||!file.CanSeek||file.Length<=0)throw new DesktopException(DesktopError.KnowledgeFileUnavailable);
        if(file.Length>MaximumKnowledgeBackupBytes)throw new DesktopException(DesktopError.KnowledgeBackupTooLarge);
        if(target is not null&&(!Path.IsPathFullyQualified(target)||target.Length>8192||target.Contains('\0')))throw new DesktopException(DesktopError.KnowledgeRestoreFailed);
        file.Position=0;using var request=KnowledgeRequest(HttpMethod.Post,target is null?"/backup/validate":"/backup/restore");
        request.Content=new StreamContent(new BorrowedStream(file),65536);request.Content.Headers.ContentType=new MediaTypeHeaderValue("application/octet-stream");
        if(target is not null)request.Headers.Add("X-Knowledge-Restore-Target",KEncode(target));
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(token);deadline.CancelAfter(TimeSpan.FromHours(2));
        try{using var response=await http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead,deadline.Token);using var body=await KnowledgeSmallResponse(response,deadline.Token);return KBackupMetadata(body.RootElement);}
        catch(HttpRequestException){throw new DesktopException(target is null?DesktopError.RuntimeUnavailable:DesktopError.OutcomeUnknown);}
        catch(IOException){throw new DesktopException(target is null?DesktopError.KnowledgeFileUnavailable:DesktopError.OutcomeUnknown);}
        catch(OperationCanceledException)when(!token.IsCancellationRequested){throw new DesktopException(target is null?DesktopError.ClientTimeout:DesktopError.OutcomeUnknown);}
    }
    public async Task DownloadKnowledgeBackupAsync(Stream output,CancellationToken token)
    {
        using var request=KnowledgeRequest(HttpMethod.Get,"/backup");using var deadline=CancellationTokenSource.CreateLinkedTokenSource(token);deadline.CancelAfter(TimeSpan.FromHours(2));
        try{
            using var response=await http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead,deadline.Token);
            if(response.StatusCode!=HttpStatusCode.OK){using var body=await KnowledgeSmallResponse(response,deadline.Token);throw Invalid();}
            if(response.Content.Headers.ContentType?.MediaType!="application/octet-stream")throw Invalid();
            if(response.Content.Headers.ContentLength>MaximumKnowledgeBackupBytes)throw new DesktopException(DesktopError.KnowledgeBackupTooLarge);
            using var input=await response.Content.ReadAsStreamAsync(deadline.Token);byte[] buffer=new byte[65536];long total=0;int n;
            while((n=await input.ReadAsync(buffer,deadline.Token))!=0){if((total+=n)>MaximumKnowledgeBackupBytes)throw new DesktopException(DesktopError.KnowledgeBackupTooLarge);await output.WriteAsync(buffer.AsMemory(0,n),deadline.Token);}
        }catch(HttpRequestException){throw new DesktopException(DesktopError.RuntimeUnavailable);}catch(IOException){throw new DesktopException(DesktopError.KnowledgeFileUnavailable);}
        catch(OperationCanceledException)when(!token.IsCancellationRequested){throw new DesktopException(DesktopError.ClientTimeout);}
    }
    private static KnowledgeBackupMetadata KBackupMetadata(JsonElement r)
    {
        MemoryFields(r,"formatVersion","schemaVersion","createdAt","documentCount","revisionCount","sourceBytes","artifactBytes","contentDigest");
        if(KNumber(r,"formatVersion",1,1)!=1||KNumber(r,"schemaVersion",1,1)!=1)throw Invalid();KTime(r,"createdAt");KDigest(r,"contentDigest");
        int docs=(int)KNumber(r,"documentCount",0,500),revs=(int)KNumber(r,"revisionCount",0,Math.Min(2000,docs*10));
        return new(1,1,String(r,"createdAt"),docs,revs,KNumber(r,"sourceBytes",0,2L*1024*1024*1024),KNumber(r,"artifactBytes",0,256L*1024*1024),String(r,"contentDigest"));
    }
}
