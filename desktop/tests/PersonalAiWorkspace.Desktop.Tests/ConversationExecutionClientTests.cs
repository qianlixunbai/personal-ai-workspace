using System.Net;
using System.Net.Http;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.MemoryClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;
public sealed class ConversationExecutionClientTests
{
    private static readonly Guid ConversationId=Guid.NewGuid(),TurnId=Guid.NewGuid(),TaskId=Guid.NewGuid();
    private static string Admission(int count=0) => JsonSerializer.Serialize(new {conversationId=ConversationId,turnId=TurnId,taskId=TaskId,status="QUEUED",memoryCount=count,admittedSequences=new[]{1},inputCharacters=123,inputBytes=150});
    private static string TaskBody(string state) => JsonSerializer.Serialize(new {
        taskId=TaskId,capability="conversation",status=state,profile=new{id="chat.balanced",version="test-v1",locality="LOCAL"},promptVersion="conversation-v1",
        createdAt="2026-10-04T00:00:00Z",finishedAt=state=="RUNNING"?null:"2026-10-04T00:00:01Z",
        result=state=="SUCCEEDED"?PrivateContent:null,error=state is "SUCCEEDED" or "RUNNING"?null:new{code=state=="CANCELLED"?"TASK_CANCELLED":state=="TIMED_OUT"?"TASK_TIMEOUT":"PROVIDER_UNAVAILABLE",message=PrivateContent,phase="EXECUTION"}});
    [Theory] [InlineData(0)] [InlineData(1)] [InlineData(4)]
    public async Task SendReferenceOnlyBodyUsesNativeTransportAndParsesIds(int count) {
        var refs=Enumerable.Range(0,count).Select(_=>new MemoryReference(Guid.NewGuid(),1)).ToArray();
        using var client=new RuntimeClient(new Handler(async(request,ct)=>{
            Assert.Equal($"/api/v1/conversations/{ConversationId:D}/turns",request.RequestUri!.AbsolutePath);Assert.Equal(HttpMethod.Post,request.Method);
            Assert.Equal(Token,request.Headers.Authorization?.Parameter);Assert.False(request.Headers.Contains("Origin"));
            using var json=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));var root=json.RootElement;Assert.Equal(2,root.EnumerateObject().Count());
            Assert.Equal(PrivateContent,root.GetProperty("message").GetString());Assert.Equal(count,root.GetProperty("memories").GetArrayLength());
            Assert.All(root.GetProperty("memories").EnumerateArray(),x=>Assert.Equal(2,x.EnumerateObject().Count()));
            var response=Response(Admission(count),HttpStatusCode.Accepted);response.Headers.Location=new Uri($"/api/v1/tasks/{TaskId:D}",UriKind.Relative);return response;
        }),()=>Token);
        var accepted=await client.SubmitConversationTurnAsync(ConversationId,PrivateContent,refs,default);Assert.Equal(TaskId,accepted.TaskId);Assert.Equal(TurnId,accepted.TurnId);Safe(accepted.ToString());
    }
    [Theory] [InlineData("SUCCEEDED")] [InlineData("FAILED")] [InlineData("CANCELLED")] [InlineData("TIMED_OUT")] [InlineData("RUNNING")]
    public async Task PollAndCancelValidateConversationPromptAndControlledTerminalErrors(string state) {
        using var client=Client(TaskBody(state));var polled=await client.GetConversationTaskAsync(TaskId,default);var cancelled=await client.CancelConversationTaskAsync(TaskId,default);
        Assert.Equal(Enum.Parse<TaskState>(state),polled.Status);Assert.Equal(polled,cancelled);Safe(polled.ToString());
        if(state=="FAILED")Assert.Equal(DesktopError.ProviderUnavailable,polled.Error);
        using var wrong=Client(TaskBody(state).Replace("conversation-v1","ask-v1"));Assert.Equal(DesktopError.InvalidResponse,(await Assert.ThrowsAsync<DesktopException>(()=>wrong.GetConversationTaskAsync(TaskId,default))).Error);
    }
    [Fact] public async Task StaleMemoryQueueAndStorageErrorsAreSafeAndInvalidReferencesNeverSend() {
        foreach(var(status,code,error) in new[]{(409,"MEMORY_SELECTION_STALE",DesktopError.MemorySelectionStale),(429,"QUEUE_FULL",DesktopError.QueueFull),(503,"CONVERSATION_STORAGE_UNAVAILABLE",DesktopError.ConversationStorageUnavailable)}) {
            using var client=Client(Error(code),(HttpStatusCode)status);var failed=await Assert.ThrowsAsync<DesktopException>(()=>client.SubmitConversationTurnAsync(ConversationId,"USER",[],default));Assert.Equal(error,failed.Error);Safe(failed.ToString());
        }
        int calls=0;using var invalid=new RuntimeClient(new Handler((_,_)=>{calls++;return Task.FromResult(Response("{}"));}),()=>Token);
        foreach(var refs in new IReadOnlyList<MemoryReference>[] {new[]{new MemoryReference(Guid.Empty,1)},new[]{new MemoryReference(Id,0)},new[]{new MemoryReference(Id,1),new MemoryReference(Id,1)}})
            await Assert.ThrowsAsync<DesktopException>(()=>invalid.SubmitConversationTurnAsync(ConversationId,"USER",refs,default));Assert.Equal(0,calls);
    }
    [Fact] public async Task AdmissionWrongLocationOwnershipOrEvidenceBudgetFailsClosed() {
        foreach(string body in new[]{Admission().Replace(ConversationId.ToString(),Guid.NewGuid().ToString()),Admission().Replace("\"inputBytes\":150","\"inputBytes\":5633"),Admission().Replace("\"memoryCount\":0","\"memoryCount\":1")}) {
            using var client=new RuntimeClient(new Handler((_,_)=>{var response=Response(body,HttpStatusCode.Accepted);response.Headers.Location=new Uri($"/api/v1/tasks/{TaskId:D}",UriKind.Relative);return Task.FromResult(response);}),()=>Token);
            Assert.Equal(DesktopError.InvalidResponse,(await Assert.ThrowsAsync<DesktopException>(()=>client.SubmitConversationTurnAsync(ConversationId,"USER",[],default))).Error);
        }
    }
}
