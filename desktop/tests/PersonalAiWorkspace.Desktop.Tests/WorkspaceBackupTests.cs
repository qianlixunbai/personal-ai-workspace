using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Windows;
using PersonalAiWorkspace.Core;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.MemoryClientTests;
using System.Windows.Threading;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WorkspaceBackupTests
{
    internal static string Metadata()=>JsonSerializer.Serialize(new { formatVersion=1,memorySchemaVersion=1,conversationSchemaVersion=1,
        createdAt="2026-10-04T10:00:00Z",memoryCount=1,conversationCount=2,turnCount=3,messageCount=5,contentDigest=new string('a',64) });
    [Fact] public async Task RawFileStreamingNativePreviewAndRestoreKeepHandleAndUseExplicitTarget()
    {
        int calls=0;byte[] bytes=Encoding.UTF8.GetBytes("synthetic opaque file");
        using var client=new RuntimeClient(new Handler(async(request,token)=>{
            Assert.Equal("Bearer",request.Headers.Authorization?.Scheme);Assert.False(request.Headers.Contains("Origin"));
            Assert.Equal(calls++==0?"/api/v1/workspace/backup/validate":"/api/v1/workspace/backup/restore",request.RequestUri!.AbsolutePath);
            var actual=await request.Content!.ReadAsByteArrayAsync(token);Assert.True(bytes.SequenceEqual(actual),"Raw streamed file differs.");
            if(calls==2)Assert.True(request.Headers.Contains("X-Workspace-Restore-Target"));
            return Response(Metadata());
        }),()=>Token);
        using var file=new MemoryStream(bytes);var preview=await client.ValidateWorkspaceBackupAsync(file,default);
        Assert.True(file.CanRead);Assert.Equal(preview,await client.RestoreWorkspaceBackupAsync(file,Path.GetTempPath(),default));Assert.True(file.CanRead);Safe(preview.ToString());
    }
    [Fact] public async Task StrictMetadataRejectsUnknownDuplicateMissingVersionsDigestCountsAndTimestamp()
    {
        foreach(string invalid in new[]{Metadata().Replace("\"formatVersion\":1","\"formatVersion\":2"),Metadata().Replace("\"memorySchemaVersion\":1","\"memorySchemaVersion\":2"),
            Metadata().Replace("\"conversationSchemaVersion\":1","\"conversationSchemaVersion\":2"),Metadata().Replace("\"formatVersion\":1","\"unknown\":0,\"formatVersion\":1"),
            Metadata().Replace("\"formatVersion\":1","\"formatVersion\":1,\"formatVersion\":1"),Metadata().Replace("\"memoryCount\":1","\"memoryCount\":1001"),
            Metadata().Replace("\"turnCount\":3","\"turnCount\":2001"),Metadata().Replace("\"messageCount\":5","\"messageCount\":2"),
            Metadata().Replace(new string('a',64),new string('A',64)),Metadata().Replace("2026-10-04T10:00:00Z","bad-time"),Metadata()+"{}","{}"})
        {
            using var client=Client(invalid);using var file=new MemoryStream([1]);var e=await Assert.ThrowsAsync<DesktopException>(()=>client.ValidateWorkspaceBackupAsync(file,default));Safe(e.ToString());
        }
    }
    [Fact] public async Task MetadataUsesCanonicalMillisecondTimeAcrossSQLiteRange()
    {
        foreach(string time in new[]{"0000-01-01T00:00:00Z","-0001-12-31T23:59:59.001Z","+10000-01-01T00:00:00Z","2026-10-04T10:00:00.010Z","-292275055-05-16T16:47:04.192Z","+292278994-08-17T07:12:55.807Z"})
        {using var client=Client(Metadata().Replace("2026-10-04T10:00:00Z",time));using var file=new MemoryStream([1]);Assert.Equal(time,(await client.ValidateWorkspaceBackupAsync(file,default)).CreatedAt);}
        foreach(string time in new[]{"2026-02-29T10:00:00Z","2026-10-04T10:00:00.000Z","2026-10-04T10:00:00.000001Z","2026-10-04T10:00:00+00:00","+999999999-01-01T00:00:00Z","-0000-01-01T00:00:00Z","-292275055-05-16T16:47:04.191Z","+292278994-08-17T07:12:55.808Z"})
        {using var client=Client(Metadata().Replace("2026-10-04T10:00:00Z",time));using var file=new MemoryStream([1]);await Assert.ThrowsAsync<DesktopException>(()=>client.ValidateWorkspaceBackupAsync(file,default));}
    }
    [Fact] public async Task LargeDownloadUsesChunksAndRejectsDeclaredOversizeAndFileBoundBeforeUpload()
    {
        using var client=new RuntimeClient(new Handler((_,_)=>Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK){Content=new StreamContent(new GeneratedStream(20*1024*1024))
            {Headers={ContentType=new System.Net.Http.Headers.MediaTypeHeaderValue("application/json")}}})),()=>Token);
        using var output=new CountingOutput();await client.DownloadWorkspaceBackupAsync(output,default);Assert.Equal(20*1024*1024,output.Count);Assert.InRange(output.MaximumChunk,1,65536);
        using var oversized=new GeneratedStream(RuntimeClient.MaximumWorkspaceBackupBytes+1);
        var e=await Assert.ThrowsAsync<DesktopException>(()=>client.ValidateWorkspaceBackupAsync(oversized,default));Assert.Equal(DesktopError.WorkspaceBackupTooLarge,e.Error);
    }
    private sealed class GeneratedStream(long length):Stream
    {
        private long position;
        public override bool CanRead=>true;public override bool CanSeek=>true;public override bool CanWrite=>false;
        public override long Length=>length;public override long Position{get=>position;set=>position=value;}
        public override int Read(byte[] b,int o,int c){int n=(int)Math.Min(c,length-position);Array.Fill(b,(byte)'x',o,n);position+=n;return n;}
        public override long Seek(long o,SeekOrigin origin)=>position=o;public override void Flush(){}public override void SetLength(long v)=>throw new NotSupportedException();public override void Write(byte[] b,int o,int c)=>throw new NotSupportedException();
    }
    private sealed class CountingOutput:Stream
    {
        public long Count;public int MaximumChunk;
        public override bool CanWrite=>true;public override bool CanRead=>false;public override bool CanSeek=>false;
        public override long Length=>Count;public override long Position{get=>Count;set=>throw new NotSupportedException();}
        public override void Write(byte[] b,int o,int c){Count+=c;MaximumChunk=Math.Max(MaximumChunk,c);}
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> b,CancellationToken token=default){Count+=b.Length;MaximumChunk=Math.Max(MaximumChunk,b.Length);return ValueTask.CompletedTask;}
        public override void Flush(){}public override int Read(byte[] b,int o,int c)=>throw new NotSupportedException();public override void SetLength(long v)=>throw new NotSupportedException();public override long Seek(long o,SeekOrigin s)=>throw new NotSupportedException();
    }
    [Fact] public async Task ExportValidatesBeforeNoReplacePublicationAndOverwriteRequiresApproval()
    {
        string directory=Path.Combine(Path.GetTempPath(),"workspace-desktop-test-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(directory);
        string target=Path.Combine(directory,"workspace-backup.json");File.WriteAllText(target,"synthetic existing");int calls=0;
        using var client=new RuntimeClient(new Handler((request,_)=>Task.FromResult(Response(request.Method==HttpMethod.Get?"synthetic raw backup":Metadata()))),()=>Token);
        var files=new NativeWorkspaceBackupFiles();
        try{
            await Assert.ThrowsAsync<DesktopException>(()=>files.ExportAsync(client,new(target,false),default));Assert.Equal("synthetic existing",File.ReadAllText(target));
            await files.ExportAsync(client,new(target,true),default);Assert.Equal("synthetic raw backup",File.ReadAllText(target));
            using var invalid=new RuntimeClient(new Handler((request,_)=>{calls++;return Task.FromResult(request.Method==HttpMethod.Get?Response("bad file"):Response(Error("WORKSPACE_BACKUP_INVALID"),HttpStatusCode.BadRequest));}),()=>Token);
            await Assert.ThrowsAsync<DesktopException>(()=>files.ExportAsync(invalid,new(target,true),default));Assert.Equal("synthetic raw backup",File.ReadAllText(target));Assert.Equal(2,calls);
            Assert.Single(Directory.GetFiles(directory));
        }finally{File.Delete(target);Directory.Delete(directory);}
    }
    private sealed class Files:IWorkspaceBackupFiles
    {
        public Stream File=new MemoryStream([1]);public string? Backup="synthetic selected";public string? Target=Path.GetTempPath();public int Targets;
        public MemoryExportDestination? PickExport(Window w)=>null;public string? PickBackup(Window w)=>Backup;public string? PickTarget(Window w){Targets++;return Target;}
        public Stream OpenRead(string f)=>File;
        public Task<WorkspaceBackupMetadata> ExportAsync(RuntimeClient r,MemoryExportDestination d,CancellationToken t)=>throw new InvalidOperationException();
    }
    [Fact] public Task ExplicitPreviewBeforeRestoreAndCloseSuppressesLateMetadata()=>StaAsync(async()=>{
        var completion=new TaskCompletionSource<HttpResponseMessage>();using var client=new RuntimeClient(new Handler((_,_)=>completion.Task),()=>Token);
        var files=new Files();var window=new WorkspaceBackupWindow(client,files);Assert.Contains("plaintext",window.PlaintextWarning.Text);Assert.Contains("does not merge or overwrite",window.RestoreWarning.Text);
        Assert.False(window.RestoreButton.IsEnabled);await window.RestoreAsync();Assert.Equal(0,files.Targets);
        var pending=window.ChooseAsync();window.Close();completion.SetResult(Response(Metadata()));await pending;
        Assert.Equal("",window.PreviewText.Text);Assert.Equal("",window.StatusText.Text);Assert.False(files.File.CanRead);
    });
    [Fact] public Task RestoreRequiresSeparateClickAndComparesValidatedMetadata()=>StaAsync(async()=>{
        int calls=0;using var client=new RuntimeClient(new Handler((_,_)=>{calls++;return Task.FromResult(Response(Metadata()));}),()=>Token);
        var files=new Files();var window=new WorkspaceBackupWindow(client,files);await window.ChooseAsync();Assert.Equal(1,calls);Assert.Equal(0,files.Targets);
        Assert.True(window.RestoreButton.IsEnabled);Assert.Contains("Turns 3",window.PreviewText.Text);await window.RestoreAsync();Assert.Equal(2,calls);
        Assert.Contains("Start Runtime/Desktop",window.StatusText.Text);Assert.DoesNotContain(files.Target!,window.StatusText.Text);Safe(window.StatusText.Text);window.Close();
    });
    private static Task StaAsync(Func<Task> action)
    {
        var done=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread=new Thread(()=>{
            var dispatcher=Dispatcher.CurrentDispatcher;SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async()=>{try{await action();done.SetResult();}catch(Exception e){done.SetException(e);}finally{dispatcher.InvokeShutdown();}}));Dispatcher.Run();
        });thread.SetApartmentState(ApartmentState.STA);thread.Start();return done.Task;
    }

}
