using System.Net;
using System.Net.Http;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using PersonalAiWorkspace.Desktop.Bridge;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.RuntimeClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WorkspaceVisionTests
{
    private sealed class Images:IVisionImages {
        internal TaskCompletionSource<PickedVisionImage?>? Pending;
        internal int Shown;
        public Task<PickedVisionImage?> PickAsync(CancellationToken token)=>Pending?.Task??Task.FromResult<PickedVisionImage?>(new(new byte[]{251,255,255},"image/png",1,1,null!));
        public void ShowPreview(PickedVisionImage image) {Shown++;}
        public void ClearPreview() {}
    }
    [Fact] public async Task UnknownPostKeepsFrozenIdWithoutResendingAndReplacementSuppressesLateResult()
    {
        Guid? frozen=null;int posts=0,reads=0;bool lose=true;
        var knownIds=new HashSet<Guid>();
        var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var late=new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var runtime=new RuntimeClient(new Handler(async (request,token)=> {
            string route=request.RequestUri!.AbsolutePath;
            if(route.EndsWith("/readiness")) return Response(HttpStatusCode.OK,JsonSerializer.Serialize(new {
                providerDeclaredVision=true,runtimeValidatedText=true,workspaceVisionExecutionValidated=true,available=true,selectionRevision=1,reason=(string?)null }));
            if(request.Method==HttpMethod.Post) {
                posts++;string wire=await request.Content!.ReadAsStringAsync(token);Assert.Contains("\"dataBase64\":\"+///\"",wire);using var body=JsonDocument.Parse(wire);
                frozen=body.RootElement.GetProperty("taskId").GetGuid();lock(knownIds)knownIds.Add(frozen.Value);Assert.Equal("image/png",body.RootElement.GetProperty("image").GetProperty("mediaType").GetString());
                if(lose)throw new HttpRequestException("redacted fixture transport failure");entered.TrySetResult();return await late.Task;
            }
            reads++;lock(knownIds)Assert.Contains(Guid.Parse(request.RequestUri.Segments[^1]),knownIds);
            return Response(HttpStatusCode.NotFound,"{\"code\":\"TASK_NOT_FOUND\",\"message\":\"not found\",\"phase\":\"TASK\"}");
        }),()=>new string('t',43));
        var picker=new Images();var owner=new WorkspaceVision(runtime,picker,(_,_)=>Task.FromResult(false));
        string session=Guid.NewGuid().ToString("D");owner.BeginSession(session);
        using var picked=JsonDocument.Parse(JsonSerializer.Serialize(await owner.PickAsync(session,CancellationToken.None)));
        string handle=picked.RootElement.GetProperty("handle").GetString()!;
        using var unknown=JsonDocument.Parse(JsonSerializer.Serialize(await owner.SubmitAsync(session,handle,"synthetic",CancellationToken.None)));
        Assert.Equal("UNKNOWN",unknown.RootElement.GetProperty("status").GetString());Assert.Equal(frozen!.Value.ToString("D"),unknown.RootElement.GetProperty("taskId").GetString());
        Assert.Equal(1,posts);Assert.Equal(1,reads);
        await Assert.ThrowsAsync<DesktopException>(()=>owner.SubmitAsync(session,handle,"synthetic",CancellationToken.None));Assert.Equal(1,posts);
        lose=false;owner.Clear(session);
        using var second=JsonDocument.Parse(JsonSerializer.Serialize(await owner.PickAsync(session,CancellationToken.None)));
        var submitting=owner.SubmitAsync(session,second.RootElement.GetProperty("handle").GetString()!,"synthetic",CancellationToken.None);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(3));Guid old=frozen!.Value;owner.EndSession(session);
        string next=Guid.NewGuid().ToString("D");owner.BeginSession(next);
        var response=Response(HttpStatusCode.Accepted,JsonSerializer.Serialize(new {
            taskId=old.ToString("D"),capability="vision",status="SUCCEEDED",profile=new {id="vision.single",version="am1-"+Guid.NewGuid().ToString("D"),locality="LOCAL"},promptVersion="vision-v1",
            createdAt="2026-10-12T00:00:00Z",finishedAt="2026-10-12T00:00:01Z",result="synthetic late response",error=(object?)null }));response.Headers.Location=new Uri("/api/v1/tasks/"+old.ToString("D"),UriKind.Relative);late.SetResult(response);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(()=>submitting);
        await Assert.ThrowsAsync<DesktopException>(()=>owner.TaskAsync(next,old.ToString("D"),false,CancellationToken.None));Assert.Equal(2,posts);
        picker.Pending=new(TaskCreationOptions.RunContinuationsAsynchronously);int shown=picker.Shown;
        var picking=owner.PickAsync(next,CancellationToken.None);owner.Clear(next);
        await Assert.ThrowsAsync<DesktopException>(()=>owner.PickAsync(next,CancellationToken.None));
        picker.Pending.SetResult(new(new byte[]{1},"image/png",1,1,null!));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(()=>picking);Assert.Equal(shown,picker.Shown);
        owner.EndSession(next);
    }
}
