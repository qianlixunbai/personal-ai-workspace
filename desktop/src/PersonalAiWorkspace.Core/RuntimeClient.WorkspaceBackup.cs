using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed record WorkspaceBackupMetadata(int FormatVersion, int MemorySchemaVersion, int ConversationSchemaVersion,
    string CreatedAt, long MemoryCount, long ConversationCount, long TurnCount, long MessageCount, string ContentDigest)
{
    public override string ToString() => $"WorkspaceBackupMetadata[memoryCount={MemoryCount},conversationCount={ConversationCount},turnCount={TurnCount}]";
}

public sealed partial class RuntimeClient
{
    public const long MaximumWorkspaceBackupBytes = MaximumBackupBytes + 4096L + 1000L *
        (160L * 12 + 1024 + 1000L * (2L * 8192 * 6 + 4L * 256 + 2048));
    // Complete logical validation and canonical digest stay in Runtime; Desktop never creates/reads SQLite.
    public Task<WorkspaceBackupMetadata> ValidateWorkspaceBackupAsync(Stream file, CancellationToken token) =>
        WorkspaceMetadataRequest(file, null, token);
    public Task<WorkspaceBackupMetadata> RestoreWorkspaceBackupAsync(Stream file, string targetDirectory, CancellationToken token) =>
        WorkspaceMetadataRequest(file, targetDirectory, token);
    private async Task<WorkspaceBackupMetadata> WorkspaceMetadataRequest(Stream file, string? target, CancellationToken token)
    {
        if (!file.CanRead || !file.CanSeek) throw new DesktopException(DesktopError.WorkspaceBackupFileUnavailable);
        if (file.Length > MaximumWorkspaceBackupBytes) throw new DesktopException(DesktopError.WorkspaceBackupTooLarge);
        if (target is not null && (!Path.IsPathFullyQualified(target) || target.Length > 8192 || target.Contains('\0')))
            throw new DesktopException(DesktopError.WorkspaceRestoreFailed);
        file.Position = 0;
        using var request = WorkspaceRequest(HttpMethod.Post, target is null ? "/validate" : "/restore");
        // Keep the same read-only handle across preview/restore. Request disposal must not release it.
        request.Content = new StreamContent(new BorrowedStream(file), 65536);
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        if (target is not null) request.Headers.Add("X-Workspace-Restore-Target",
            Convert.ToBase64String(new UTF8Encoding(false, true).GetBytes(target)).TrimEnd('=').Replace('+','-').Replace('/','_'));
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token); deadline.CancelAfter(TimeSpan.FromHours(2));
        try
        {
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
            using var body = await WorkspaceSmallResponse(response, deadline.Token);
            return ParseWorkspaceMetadata(body.RootElement);
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested) { throw new DesktopException(DesktopError.ClientTimeout); }
        catch (HttpRequestException) { throw new DesktopException(DesktopError.RuntimeUnavailable); }
        catch (IOException) { throw new DesktopException(DesktopError.WorkspaceBackupFileUnavailable); }
    }
    public async Task DownloadWorkspaceBackupAsync(Stream output, CancellationToken token)
    {
        using var request = WorkspaceRequest(HttpMethod.Get, "");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token); deadline.CancelAfter(TimeSpan.FromHours(2));
        try
        {
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
            if (response.StatusCode != HttpStatusCode.OK) { using var body = await WorkspaceSmallResponse(response, deadline.Token); throw Invalid(); }
            if (response.Content.Headers.ContentType?.MediaType != "application/json") throw Invalid();
            if (response.Content.Headers.ContentLength > MaximumWorkspaceBackupBytes) throw new DesktopException(DesktopError.WorkspaceBackupTooLarge);
            using var input = await response.Content.ReadAsStreamAsync(deadline.Token);
            byte[] chunk = new byte[65536]; long total = 0; int count;
            while ((count = await input.ReadAsync(chunk, deadline.Token)) != 0)
            {
                if ((total += count) > MaximumWorkspaceBackupBytes) throw new DesktopException(DesktopError.WorkspaceBackupTooLarge);
                await output.WriteAsync(chunk.AsMemory(0,count), deadline.Token);
            }
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested) { throw new DesktopException(DesktopError.ClientTimeout); }
        catch (HttpRequestException) { throw new DesktopException(DesktopError.RuntimeUnavailable); }
        catch (IOException) { throw new DesktopException(DesktopError.WorkspaceBackupFileUnavailable); }
    }
    private HttpRequestMessage WorkspaceRequest(HttpMethod method, string suffix)
    {
        string? secret = credential();
        if (secret is null) throw new DesktopException(DesktopError.CredentialMissing);
        if (!CredentialFormat.Valid(secret)) throw new DesktopException(DesktopError.CredentialInvalid);
        var request = new HttpRequestMessage(method, "/api/v1/workspace/backup" + suffix);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", secret);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json")); return request;
    }
    private static async Task<JsonDocument> WorkspaceSmallResponse(HttpResponseMessage response, CancellationToken token)
    {
        if (response.StatusCode == HttpStatusCode.Unauthorized) throw new DesktopException(DesktopError.Unauthorized);
        if (response.Content.Headers.ContentType?.MediaType != "application/json" || response.Content.Headers.ContentLength > MaximumResponse) throw Invalid();
        using var stream = await response.Content.ReadAsStreamAsync(token); using var buffer = new MemoryStream(); byte[] chunk = new byte[8192]; int count;
        while ((count = await stream.ReadAsync(chunk,token)) != 0) { if (buffer.Length+count > MaximumResponse) throw Invalid(); await buffer.WriteAsync(chunk.AsMemory(0,count),token); }
        JsonDocument body;
        try { body = JsonDocument.Parse(buffer.ToArray(),new JsonDocumentOptions { MaxDepth=12 }); }
        catch (JsonException) { throw Invalid(); }
        try
        {
            RejectDuplicates(body.RootElement);
            if (response.StatusCode != HttpStatusCode.OK)
            {
                MemoryFields(body.RootElement,"code","message","phase"); _ = String(body.RootElement,"message"); _ = String(body.RootElement,"phase");
                throw new DesktopException(((int)response.StatusCode,String(body.RootElement,"code")) switch
                {
                    (400,"WORKSPACE_BACKUP_INVALID") => DesktopError.WorkspaceBackupInvalid,
                    (400,"WORKSPACE_BACKUP_UNSUPPORTED") => DesktopError.WorkspaceBackupUnsupported,
                    (413,"WORKSPACE_BACKUP_TOO_LARGE") => DesktopError.WorkspaceBackupTooLarge,
                    (409,"WORKSPACE_BACKUP_CONFLICT") => DesktopError.WorkspaceBackupConflict,
                    (409,"WORKSPACE_RESTORE_TARGET_NOT_EMPTY") => DesktopError.WorkspaceRestoreTargetNotEmpty,
                    (500,"WORKSPACE_EXPORT_FAILED") => DesktopError.WorkspaceExportFailed,
                    (500,"WORKSPACE_RESTORE_FAILED") => DesktopError.WorkspaceRestoreFailed,
                    (403,"POLICY_DENIED") => DesktopError.PolicyDenied,
                    _ => throw Invalid()
                });
            }
            return body;
        }
        catch { body.Dispose(); throw; }
    }
    private static WorkspaceBackupMetadata ParseWorkspaceMetadata(JsonElement root)
    {
        MemoryFields(root,"formatVersion","memorySchemaVersion","conversationSchemaVersion","createdAt","memoryCount","conversationCount","turnCount","messageCount","contentDigest");
        if (MemoryInteger(root,"formatVersion") != 1 || MemoryInteger(root,"memorySchemaVersion") != 1 || MemoryInteger(root,"conversationSchemaVersion") != 1)
            throw new DesktopException(DesktopError.WorkspaceBackupUnsupported);
        long Number(string name,long maximum) { var n=Property(root,name); if(n.ValueKind!=JsonValueKind.Number || !n.TryGetInt64(out long value) || value<0 || value>maximum) throw Invalid(); return value; }
        long memories=Number("memoryCount",1000), conversations=Number("conversationCount",1000), turns=Number("turnCount",conversations*1000), messages=Number("messageCount",turns*2);
        if(messages<turns) throw Invalid(); string digest=String(root,"contentDigest"),created=String(root,"createdAt");
        if(!Regex.IsMatch(digest,"\\A[0-9a-f]{64}\\z")) throw Invalid();
        WorkspaceTime(created); return new(1,1,1,created,memories,conversations,turns,messages,digest);
    }
    // Java Instant/SQLite milliseconds have a wider range than DateTimeOffset. Validate the full contract.
    private static void WorkspaceTime(string text)
    {
        var match=Regex.Match(text,@"\A(?<y>\d{4}|-\d{4,9}|\+\d{5,9})-(?<m>\d{2})-(?<d>\d{2})T(?<h>\d{2}):(?<n>\d{2}):(?<s>\d{2})(?:\.(?<f>\d{3}))?Z\z");
        if(!match.Success)throw Invalid();
        try
        {
            long Part(string name)=>long.Parse(match.Groups[name].Value,System.Globalization.CultureInfo.InvariantCulture);
            long y=Part("y"),m=Part("m"),d=Part("d"),h=Part("h"),n=Part("n"),s=Part("s"),f=match.Groups["f"].Success?Part("f"):0;
            string year=y is >=0 and <=9999?y.ToString("D4",System.Globalization.CultureInfo.InvariantCulture):
                y>9999?"+"+y.ToString(System.Globalization.CultureInfo.InvariantCulture):"-"+(-y).ToString("D4",System.Globalization.CultureInfo.InvariantCulture);
            bool leap=y%4==0&&(y%100!=0||y%400==0);int[] months=[31,leap?29:28,31,30,31,30,31,31,30,31,30,31];
            if(year!=match.Groups["y"].Value||m is <1 or >12||d<1||d>months[m-1]||h>23||n>59||s>59||match.Groups["f"].Success&&f==0)throw Invalid();
            long Floor(long value,long divisor)=>value>=0?value/divisor:(value-divisor+1)/divisor;
            long previous=y-1,days=365*previous+Floor(previous,4)-Floor(previous,100)+Floor(previous,400)-719162+d-1;
            for(int i=0;i<m-1;i++)days+=months[i];
            Int128 epoch=(Int128)days*86400000+(h*3600+n*60+s)*1000+f;
            if(epoch<long.MinValue||epoch>long.MaxValue)throw Invalid();
        }
        catch(DesktopException){throw;}
        catch(Exception){throw Invalid();}
    }
    private sealed class BorrowedStream(Stream inner) : Stream
    {
        public override bool CanRead=>inner.CanRead; public override bool CanWrite=>false; public override bool CanSeek=>inner.CanSeek;
        public override long Length=>inner.Length; public override long Position { get=>inner.Position; set=>inner.Position=value; }
        public override int Read(byte[] buffer,int offset,int count)=>inner.Read(buffer,offset,count);
        public override ValueTask<int> ReadAsync(Memory<byte> buffer,CancellationToken token=default)=>inner.ReadAsync(buffer,token);
        public override Task<int> ReadAsync(byte[] buffer,int offset,int count,CancellationToken token)=>inner.ReadAsync(buffer,offset,count,token);
        public override long Seek(long offset,SeekOrigin origin)=>inner.Seek(offset,origin);
        public override void Flush()=>throw new NotSupportedException(); public override void SetLength(long value)=>throw new NotSupportedException();
        public override void Write(byte[] buffer,int offset,int count)=>throw new NotSupportedException();
    }
}
