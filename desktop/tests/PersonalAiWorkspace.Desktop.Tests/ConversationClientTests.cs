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
    public async Task NativeCrudAndListUseExistingTransportAndExactContracts()
    {
        string path = $"/api/v1/conversations/{ConversationId:D}";
        string[] paths = ["/api/v1/conversations", path, path, path+"/archive",path+"/unarchive","/api/v1/conversations",path];
        HttpMethod[] methods = [HttpMethod.Post,HttpMethod.Get,HttpMethod.Patch,HttpMethod.Post,HttpMethod.Post,HttpMethod.Get,HttpMethod.Delete];
        int calls=0;
        using var client = new RuntimeClient(new Handler(async (request,ct) =>
        {
            int i=calls++; Assert.Equal(paths[i],request.RequestUri!.AbsolutePath); Assert.Equal(methods[i],request.Method);
            Assert.Equal("127.0.0.1",request.RequestUri.Host); Assert.Equal(8765,request.RequestUri.Port);
            Assert.Equal("Bearer",request.Headers.Authorization?.Scheme); Assert.True(Token==request.Headers.Authorization?.Parameter);
            Assert.False(request.Headers.Contains("Origin"));
            if (i is 0 or 2) {
                using var input=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                Assert.Single(input.RootElement.EnumerateObject()); Assert.True(input.RootElement.GetProperty("title").GetString()==PrivateTitle);
            } else Assert.Null(request.Content);
            if(i==6) return new HttpResponseMessage(HttpStatusCode.NoContent);
            var response=Response(i==1?Detail():i==5?"{\"items\":["+Metadata()+"],\"total\":1,\"page\":0,\"limit\":10}":Metadata(i==3?"ARCHIVED":"ACTIVE"),i==0?HttpStatusCode.Created:HttpStatusCode.OK);
            if(i==0) response.Headers.Location=new Uri(path,UriKind.Relative); return response;
        }),()=>Token);
        var created=await client.CreateConversationAsync(PrivateTitle,default);
        var detail=await client.GetConversationAsync(ConversationId,0,10,default);
        await client.RenameConversationAsync(ConversationId,PrivateTitle,default);
        await client.ArchiveConversationAsync(ConversationId,default); await client.UnarchiveConversationAsync(ConversationId,default);
        var page=await client.ListConversationsAsync(ConversationStatus.ACTIVE,0,10,default); await client.DeleteConversationAsync(ConversationId,default);
        Assert.Equal(7,calls); Assert.Single(page.Items); Assert.Single(detail.Turns);
        foreach(var value in new object[]{created,detail,page,detail.Turns[0],detail.Turns[0].UserMessage,detail.Turns[0].AssistantMessage!}) Safe(value.ToString()!);
    }
    [Fact]
    public async Task AllTurnStatusesAndOptionalAssistantParseWithImmutableViews()
    {
        foreach(var status in Enum.GetValues<ConversationTurnStatus>()) {
            using var client=Client(Detail(status.ToString())); var detail=await client.GetConversationAsync(ConversationId,0,10,default);
            Assert.Equal(status,detail.Turns[0].Status); Assert.Equal(status==ConversationTurnStatus.SUCCEEDED,detail.Turns[0].AssistantMessage is not null);
            Assert.Throws<NotSupportedException>(()=>((IList<ConversationTurn>)detail.Turns).Clear());
        }
    }
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
    [Fact]
    public async Task ClassifiedErrorsAndDiagnosticsExcludeRawBodies()
    {
        var cases=new[]{(404,"CONVERSATION_NOT_FOUND",DesktopError.ConversationNotFound),(400,"CONVERSATION_INVALID",DesktopError.ConversationInvalid),
            (409,"CONVERSATION_CONFLICT",DesktopError.ConversationConflict),(409,"CONVERSATION_LIMIT_EXCEEDED",DesktopError.ConversationLimitExceeded),
            (503,"CONVERSATION_STORAGE_UNAVAILABLE",DesktopError.ConversationStorageUnavailable),(403,"POLICY_DENIED",DesktopError.PolicyDenied),
            (401,"UNAUTHORIZED",DesktopError.Unauthorized)};
        foreach(var (status,code,expected) in cases) {
            using var client=Client(Error(code),(HttpStatusCode)status);
            var failure=await Assert.ThrowsAsync<DesktopException>(()=>client.GetConversationAsync(ConversationId,0,10,default));
            Assert.Equal(expected,failure.Error); Safe(failure.ToString());
        }
    }
    [Fact]
    public async Task InvalidInputRejectedBeforeHttpAndWrongLocationRejected()
    {
        int calls=0;
        using var client=new RuntimeClient(new Handler((_,_)=>{calls++;return Task.FromResult(Response("{}"));}),()=>Token);
        foreach(string title in new[]{" ",new string('x',161),"\uD800","x\0y"})
            Assert.Equal(DesktopError.ConversationInvalid,(await Assert.ThrowsAsync<DesktopException>(()=>client.CreateConversationAsync(title,default))).Error);
        await Assert.ThrowsAsync<DesktopException>(()=>client.GetConversationAsync(Guid.Empty,0,10,default));
        await Assert.ThrowsAsync<DesktopException>(()=>client.GetConversationAsync(ConversationId,0,11,default));
        Assert.Equal(0,calls);
        using var wrong=new RuntimeClient(new Handler((_,_)=>Task.FromResult(Response(Metadata(),HttpStatusCode.Created))),()=>Token);
        Assert.Equal(DesktopError.InvalidResponse,(await Assert.ThrowsAsync<DesktopException>(()=>wrong.CreateConversationAsync(null,default))).Error);
    }
}
