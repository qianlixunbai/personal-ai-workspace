using System.Net;
using System.Net.Http;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.MemoryClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class ConversationClientTests
{
    private static readonly Guid ConversationId = Guid.NewGuid(), TurnId = Guid.NewGuid(), UserId = Guid.NewGuid(), AssistantId = Guid.NewGuid();
    private static string Metadata(string status = "ACTIVE") => JsonSerializer.Serialize(new
    { id = ConversationId, title = PrivateTitle, status, createdAt = "2026-10-04T00:00:00Z", updatedAt = "2026-10-04T00:00:01Z" });
    private static string Message(Guid id, string role) => JsonSerializer.Serialize(new
    { id, turnId = TurnId, role, content = PrivateContent, createdAt = role == "USER" ? "2026-10-04T00:00:00Z" : "2026-10-04T00:00:01Z" });
    private static string Detail(string status = "SUCCEEDED", string? role = "USER", long sequence = 1) =>
        "{\"conversation\":" + Metadata() + ",\"turns\":[{\"id\":\""+TurnId+"\",\"conversationId\":\""+ConversationId
        +"\",\"sequence\":"+sequence+",\"status\":\""+status+"\",\"createdAt\":\"2026-10-04T00:00:00Z\",\"updatedAt\":\"2026-10-04T00:00:01Z\",\"userMessage\":"
        +Message(UserId,role!)+",\"assistantMessage\":"+(status=="SUCCEEDED"?Message(AssistantId,"ASSISTANT"):"null")+",\"taskId\":null,\"failureCode\":null,\"memories\":[]}],\"totalTurns\":1,\"page\":0,\"limit\":10}";

    [Fact]
    public async Task MalformedOwnershipRolesStatusOrderingAndFieldsFailClosed()
    {
        string valid=Detail();
        string[] bad=[Detail(role:"SYSTEM"),Detail(sequence:2),Detail(status:"UNKNOWN"),
            valid.Replace(TurnId.ToString(),Guid.Empty.ToString()),valid.Replace("\"role\":\"ASSISTANT\"","\"role\":\"USER\""),
            valid.Replace("\"status\":\"SUCCEEDED\"","\"status\":\"FAILED\""),
            valid.Replace("\"totalTurns\":1","\"totalTurns\":2"),valid.Replace("\"sequence\":1","\"sequence\":1,\"branchId\":\"x\""),
            valid.Replace("\"role\":\"USER\"","\"role\":\"USER\",\"role\":\"USER\""),
            valid.Replace("\"conversationId\":\""+ConversationId+"\"","\"conversationId\":\""+Guid.NewGuid()+"\"")];
        foreach(string body in bad) {
            using var client=Client(body); var failure=await Assert.ThrowsAsync<DesktopException>(()=>client.GetConversationAsync(ConversationId,0,10,default));
            Assert.Equal(DesktopError.InvalidResponse,failure.Error); Safe(failure.ToString());
        }
    }
}
