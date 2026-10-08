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
    [Theory] [InlineData("SUCCEEDED")] [InlineData("FAILED")] [InlineData("CANCELLED")] [InlineData("TIMED_OUT")] [InlineData("RUNNING")]
    public async Task PollAndCancelValidateConversationPromptAndControlledTerminalErrors(string state) {
        using var client=Client(TaskBody(state));var polled=await client.GetConversationTaskAsync(TaskId,default);var cancelled=await client.CancelConversationTaskAsync(TaskId,default);
        Assert.Equal(Enum.Parse<TaskState>(state),polled.Status);Assert.Equal(polled,cancelled);Safe(polled.ToString());
        if(state=="FAILED")Assert.Equal(DesktopError.ProviderUnavailable,polled.Error);
        using var wrong=Client(TaskBody(state).Replace("conversation-v1","ask-v1"));Assert.Equal(DesktopError.InvalidResponse,(await Assert.ThrowsAsync<DesktopException>(()=>wrong.GetConversationTaskAsync(TaskId,default))).Error);
    }
    [Fact] public async Task AdmissionWrongLocationOwnershipOrEvidenceBudgetFailsClosed() {
        foreach(string body in new[]{Admission().Replace(ConversationId.ToString(),Guid.NewGuid().ToString()),Admission().Replace("\"inputBytes\":150","\"inputBytes\":5633"),Admission().Replace("\"memoryCount\":0","\"memoryCount\":1")}) {
            using var client=new RuntimeClient(new Handler((_,_)=>{var response=Response(body,HttpStatusCode.Accepted);response.Headers.Location=new Uri($"/api/v1/tasks/{TaskId:D}",UriKind.Relative);return Task.FromResult(response);}),()=>Token);
            Assert.Equal(DesktopError.InvalidResponse,(await Assert.ThrowsAsync<DesktopException>(()=>client.SubmitConversationTurnAsync(ConversationId,"USER",[],default))).Error);
        }
    }
    [Fact] public async Task ModelAdmissionErrorsPreserveEachCapabilityAndRejectInvalidStatusOrManagementCodes() {
        var errors = new (int Status, string Code, DesktopError Error)[] {
            (409, "MODEL_SWITCH_CONFLICT", DesktopError.ModelSwitchConflict),
            (409, "MODEL_SELECTION_REVISION_CONFLICT", DesktopError.ModelSelectionRevisionConflict),
            (409, "MODEL_EXECUTION_UNCERTAIN", DesktopError.ModelExecutionUncertain),
            (503, "MODEL_STATE_UNAVAILABLE", DesktopError.ModelStateUnavailable),
            (503, "MODEL_CONFIGURATION_INVALID", DesktopError.ModelConfigurationInvalid),
            (503, "MODEL_IDENTITY_CHANGED", DesktopError.ModelIdentityChanged)
        };
        foreach (var (status, code, expected) in errors) {
            using var client = Rejection(status, code);
            Assert.Equal(expected, (await Assert.ThrowsAsync<DesktopException>(() =>
                client.SubmitMemoryAskAsync(new("question", [new(Id, 1)]), default))).Error);
            Assert.Equal(expected, (await Assert.ThrowsAsync<DesktopException>(() =>
                client.SubmitConversationTurnAsync(ConversationId, "question", [], default))).Error);
            Assert.Equal(expected, (await Assert.ThrowsAsync<DesktopException>(() =>
                client.SubmitKnowledgeAnswerAsync("question", "budget", default))).Error);
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() =>
                client.GetConversationAsync(ConversationId, 0, 10, default))).Error);
        }
        foreach (var (status, code) in new[] { (503, "MODEL_EXECUTION_UNCERTAIN"), (409, "MODEL_IDENTITY_CHANGED"),
                (409, "MODEL_CATALOG_STALE"), (409, "MODEL_UNKNOWN") }) {
            using var client = Rejection(status, code);
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() =>
                client.SubmitMemoryAskAsync(new("question", [new(Id, 1)]), default))).Error);
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() =>
                client.SubmitConversationTurnAsync(ConversationId, "question", [], default))).Error);
            // Keep Knowledge's existing lost/invalid POST outcome contract; never replay it.
            Assert.Equal(DesktopError.OutcomeUnknown, (await Assert.ThrowsAsync<DesktopException>(() =>
                client.SubmitKnowledgeAnswerAsync("question", "budget", default))).Error);
        }
        foreach (string code in new[] { "MODEL_STATE_UNAVAILABLE", "MODEL_IDENTITY_CHANGED", "MODEL_EXECUTION_UNCERTAIN" }) {
            string body = TaskBody("FAILED").Replace("conversation", "knowledge-answer").Replace("PROVIDER_UNAVAILABLE", code);
            using var client = Client(body);
            Assert.NotNull((await client.GetKnowledgeAnswerAsync(TaskId, default)).Error);
        }
        static RuntimeClient Rejection(int status, string code) => new(new Handler((_, _) => Task.FromResult(
            Response(JsonSerializer.Serialize(new { code, message = PrivateContent, phase = "MODEL" }), (HttpStatusCode)status))), () => Token);
    }
}
