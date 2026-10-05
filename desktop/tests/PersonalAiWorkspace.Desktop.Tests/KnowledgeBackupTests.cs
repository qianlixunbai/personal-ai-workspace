using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class KnowledgeBackupTests
{
    private sealed class Handler:HttpMessageHandler
    {
        internal bool Lost,Large;internal int Calls;internal string? Target;internal byte[]? Payload;
        internal static object Metadata()=>new{formatVersion=1,schemaVersion=1,createdAt="2026-10-05T00:00:00Z",documentCount=1,revisionCount=1,sourceBytes=10,artifactBytes=10,contentDigest=new string('a',64)};
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken token)
        {
            Calls++;Assert.Equal("Bearer",request.Headers.Authorization!.Scheme);Assert.StartsWith("/api/v1/knowledge/backup",request.RequestUri!.AbsolutePath);
            if(request.Method==HttpMethod.Get){var response=new HttpResponseMessage(HttpStatusCode.OK){Content=new ByteArrayContent(Encoding.UTF8.GetBytes("small synthetic container"))};
                response.Content.Headers.ContentType=new("application/octet-stream");if(Large)response.Content.Headers.ContentLength=RuntimeClient.MaximumKnowledgeBackupBytes+1;return response;}
            Assert.Equal("application/octet-stream",request.Content!.Headers.ContentType!.MediaType);Payload=await request.Content.ReadAsByteArrayAsync(token);
            if(request.Headers.TryGetValues("X-Knowledge-Restore-Target",out var values))Target=values.Single();if(Lost)throw new HttpRequestException();
            return RuntimeClientTests.Response(HttpStatusCode.OK,JsonSerializer.Serialize(Metadata()));
        }
    }
    [Fact]public async Task BackupTransferUsesIndependentOctetStreamAndBorrowedHandleWithNativeTargetHeader()
    {
        var handler=new Handler();using var client=new RuntimeClient(handler,()=>new string('t',43));using var output=new MemoryStream();await client.DownloadKnowledgeBackupAsync(output,default);
        Assert.Equal("small synthetic container",Encoding.UTF8.GetString(output.ToArray()));var valid=await client.ValidateKnowledgeBackupAsync(output,default);Assert.True(output.CanRead);
        string target=Path.Combine(Path.GetTempPath(),"knowledge-target");var restored=await client.RestoreKnowledgeBackupAsync(output,target,default);Assert.Equal(valid,restored);
        string encoded=handler.Target!;Assert.Equal(target,Encoding.UTF8.GetString(Convert.FromBase64String(encoded.Replace('-','+').Replace('_','/').PadRight((encoded.Length+3)/4*4,'='))));
        Assert.Equal(output.ToArray(),handler.Payload);Assert.Equal(3,handler.Calls);
    }
    [Fact]public async Task OversizeAndUnknownRestoreFailWithoutRetryOrWholeCorpusRead()
    {
        var handler=new Handler{Large=true};using var client=new RuntimeClient(handler,()=>new string('t',43));using var output=new MemoryStream();
        Assert.Equal(DesktopError.KnowledgeBackupTooLarge,(await Assert.ThrowsAsync<DesktopException>(()=>client.DownloadKnowledgeBackupAsync(output,default))).Error);Assert.Equal(0,output.Length);
        handler.Large=false;handler.Lost=true;using var input=new MemoryStream(Encoding.UTF8.GetBytes("fixture"));
        Assert.Equal(DesktopError.OutcomeUnknown,(await Assert.ThrowsAsync<DesktopException>(()=>client.RestoreKnowledgeBackupAsync(input,Path.Combine(Path.GetTempPath(),"new-target"),default))).Error);
        Assert.Equal(2,handler.Calls);
    }
    [Fact]public void NewBackupFileIsAccountPrivateBeforeSensitiveContent()
    {
        string file=Path.Combine(Path.GetTempPath(),"knowledge-private-"+Guid.NewGuid().ToString("N"));try{
            using(var stream=new FileStream(file,FileMode.CreateNew,FileAccess.ReadWrite,FileShare.None)){KnowledgeBackupWindow.ProtectFile(file);Assert.Equal(0,stream.Length);stream.Write(Encoding.UTF8.GetBytes("synthetic private backup"));}
            var security=new FileInfo(file).GetAccessControl();var user=System.Security.Principal.WindowsIdentity.GetCurrent().User!;
            Assert.Equal(user,security.GetOwner(typeof(System.Security.Principal.SecurityIdentifier)));
            Assert.All(security.GetAccessRules(true,true,typeof(System.Security.Principal.SecurityIdentifier)).Cast<System.Security.AccessControl.FileSystemAccessRule>(),rule=>Assert.Equal(user,rule.IdentityReference));
        }finally{File.Delete(file);}
    }
}
