using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WorkspaceConversationsTests
{
    private const string Document = WorkspaceContentPolicy.ProductionOrigin + "/index.html";
    private static readonly Guid Id = Guid.NewGuid(), Other = Guid.NewGuid(), TurnId = Guid.NewGuid(), TaskId = Guid.NewGuid(), MemoryId = Guid.NewGuid();
    private static readonly MemorySelection[] Selection = [new(MemoryId, long.MaxValue, "Synthetic selected label", MemoryType.PROJECT_NOTE)];
    private sealed class Actions(WorkspaceConversations conversations) : IWorkspaceNativeActions
    {
        public WorkspaceConversations Conversations => conversations;
        public Task<ShellStatus> StatusAsync(CancellationToken ct) => Task.FromResult(new ShellStatus(1, "1.0.0.0", ShellRuntimeState.Available, ShellCredentialState.Ready, "Available", []));
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken ct) => Task.CompletedTask;
    }
    private sealed class Handler : HttpMessageHandler
    {
        internal int Posts, Cancels, Deletes, Gets;
        internal string Status = "ACTIVE", State = "PENDING", Title = "Synthetic conversation";
        internal bool Deleted;
        internal Guid CreatedId = Id;
        internal TaskCompletionSource? HoldGet;
        internal readonly List<int> Counts = [];
        internal long[] AdmittedSequences = [1];
        internal object Metadata(Guid? id = null) => new { id = id ?? Id, title = Title, status = Status, createdAt = "2026-10-04T00:00:00Z", updatedAt = "2026-10-04T00:00:01Z" };
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Assert.Equal(new string('t', 43), request.Headers.Authorization?.Parameter);
            var path = request.RequestUri!.AbsolutePath;
            if (path == "/api/v1/conversations")
            {
                var response = Reply(request.Method == HttpMethod.Post ? Metadata(CreatedId) : new { items = new[] { Metadata() }, total = 1, page = 0, limit = 10 }, request.Method == HttpMethod.Post ? HttpStatusCode.Created : HttpStatusCode.OK);
                if (request.Method == HttpMethod.Post) response.Headers.Location = new Uri($"/api/v1/conversations/{CreatedId:D}", UriKind.Relative);
                return response;
            }
            if (path.Contains("/tasks/"))
            {
                Assert.Equal(HttpMethod.Delete, request.Method); Assert.EndsWith(TaskId.ToString("D"), path); Cancels++;
                State = "CANCELLED";
                return Reply(new { taskId = TaskId, capability = "conversation", status = "CANCELLED", profile = new { id = "chat.balanced", version = "test-v1", locality = "LOCAL" }, promptVersion = "conversation-v1",
                    createdAt = "2026-10-04T00:00:00Z", finishedAt = "2026-10-04T00:00:01Z", result = (string?)null, error = new { code = "TASK_CANCELLED", message = "private provider body", phase = "EXECUTION" } });
            }
            if (request.Method == HttpMethod.Get)
            {
                Gets++;
                if (HoldGet is not null) await HoldGet.Task;
                if (Deleted) return Error("CONVERSATION_NOT_FOUND", HttpStatusCode.NotFound);
                int page = int.Parse(request.RequestUri.Query.Split('&')[0].Split('=')[1]);
                object Message(Guid turnId, string role) => new { id = Guid.NewGuid(), turnId, role, content = "Synthetic message", createdAt = role == "USER" ? "2026-10-04T00:00:00Z" : "2026-10-04T00:00:01Z" };
                var turns = Enumerable.Range(0, 1).Select(i => {
                    var turn = TurnId;
                    return new { id = turn, conversationId = Id, sequence = i + 1, status = State, createdAt = "2026-10-04T00:00:00Z", updatedAt = "2026-10-04T00:00:01Z",
                        userMessage = Message(turn, "USER"), assistantMessage = State == "SUCCEEDED" ? Message(turn, "ASSISTANT") : null,
                        taskId = TaskId, failureCode = State == "FAILED" ? "EXECUTION_INTERRUPTED" : null,
                        memories = Enumerable.Range(0, 4).Select(position => new { memoryId = Guid.NewGuid(), revision = long.MaxValue, position }).ToArray() };
                }).ToArray();
                return Reply(new { conversation = Metadata(), turns, totalTurns = 1, page, limit = 10 });
            }
            if (path.EndsWith("/turns"))
            {
                Posts++;
                var json = await request.Content!.ReadAsStringAsync(ct);
                Assert.DoesNotContain("retainMemories", json); // Desktop lifetime option never changes Runtime's contract.
                using var body = JsonDocument.Parse(json);
                var refs = body.RootElement.GetProperty("memories"); Counts.Add(refs.GetArrayLength());
                if (refs.GetArrayLength() > 0) { Assert.Equal(MemoryId, refs[0].GetProperty("id").GetGuid()); Assert.Equal(long.MaxValue, refs[0].GetProperty("revision").GetInt64()); }
                var reply = Reply(new { conversationId = Id, turnId = TurnId, taskId = TaskId, status = "QUEUED", memoryCount = refs.GetArrayLength(), admittedSequences = AdmittedSequences, inputCharacters = 12, inputBytes = 12 }, HttpStatusCode.Accepted);
                reply.Headers.Location = new Uri($"/api/v1/tasks/{TaskId:D}", UriKind.Relative); return reply;
            }
            if (request.Method == HttpMethod.Delete)
            {
                if (State == "PENDING") return Error("CONVERSATION_CONFLICT", HttpStatusCode.Conflict);
                Deletes++; Deleted = true; return new(HttpStatusCode.NoContent);
            }
            if (path.EndsWith("/archive")) Status = "ARCHIVED";
            else if (path.EndsWith("/unarchive")) Status = "ACTIVE";
            else if (request.Method == HttpMethod.Patch)
            {
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct)); Title = body.RootElement.GetProperty("title").GetString()!;
            }
            return Reply(Metadata());
        }
        private static HttpResponseMessage Reply(object body, HttpStatusCode status = HttpStatusCode.OK) => RuntimeClientTests.Response(status, JsonSerializer.Serialize(body));
        private static HttpResponseMessage Error(string code, HttpStatusCode status) => Reply(new { code, message = "private SQL/provider body", phase = "ADMISSION" }, status);
    }
    private sealed class Fixture : IDisposable
    {
        internal readonly Handler Handler = new();
        internal readonly RuntimeClient Runtime;
        internal readonly WorkspaceConversations Conversations;
        internal readonly WorkspaceBridge Bridge;
        internal readonly List<string> Sent = [];
        internal IReadOnlyList<MemorySelection>? NextSelection = Selection;
        internal Fixture()
        {
            Runtime = new RuntimeClient(Handler, () => new string('t', 43));
            Conversations = new(Runtime, _ => Task.FromResult<IReadOnlyList<MemorySelection>?>(NextSelection));
            Bridge = new(new WorkspaceContentPolicy(), new Actions(Conversations), Sent.Add); Rotate();
        }
        internal void Rotate() { Bridge.BeginDocument(Document); Bridge.Ready(Document); Sent.Clear(); }
        internal string Request(string method, object? payload = null, string? session = null) => JsonSerializer.Serialize(new { version = 1, sessionId = session ?? Bridge.SessionId, requestId = Guid.NewGuid().ToString("D"), method, payload = payload ?? new { } });
        internal Task Call(string method, object? payload = null) => Bridge.ReceiveAsync(Document, Document, Request(method, payload));
        internal Task List() => Call("conversations.list", new { status = Handler.Status, page = 0 });
        internal Task Get() => Call("conversations.get", new { conversationId = Id, page = 0 });
        internal Task Send(object[]? refs = null, string message = "Synthetic user", bool retainMemories = false) => Call("conversations.send", retainMemories
            ? new { conversationId = Id, message, selectedMemoryRefs = refs ?? [], retainMemories = true }
            : new { conversationId = Id, message, selectedMemoryRefs = refs ?? [] });
        internal static object[] Refs() => [new { memoryId = MemoryId, revision = long.MaxValue.ToString(), position = 0 }];
        internal JsonElement Last { get { using var parsed = JsonDocument.Parse(Sent.Last()); return parsed.RootElement.Clone(); } }
        internal string Code => Last.GetProperty("error").GetProperty("code").GetString()!;
        internal object Identity => new { conversationId = Id };
        public void Dispose() { Bridge.Dispose(); Runtime.Dispose(); }
    }
    [Fact] public async Task MemoryExactRevisionConsumedAndCannotCarryAcrossConversationOrSession()
    {
        using var f = new Fixture(); f.Handler.State = "SUCCEEDED"; await f.List(); await f.Call("conversations.selectMemories", f.Identity);
        Assert.Equal(long.MaxValue.ToString(), f.Last.GetProperty("result").GetProperty("selectedMemoryRefs")[0].GetProperty("revision").GetString());
        await f.Send(Fixture.Refs()); Assert.True(f.Last.GetProperty("ok").GetBoolean()); Assert.DoesNotContain("taskId", f.Sent.Last());
        var receipt = f.Last.GetProperty("result");
        Assert.Equal(new[] { "accepted", "conversationId", "turnId", "admittedSequences", "memoryCount" }, receipt.EnumerateObject().Select(x => x.Name));
        Assert.Equal(Id.ToString("D"), receipt.GetProperty("conversationId").GetString()); Assert.Equal(TurnId.ToString("D"), receipt.GetProperty("turnId").GetString());
        Assert.Equal(f.Handler.AdmittedSequences, receipt.GetProperty("admittedSequences").EnumerateArray().Select(x => x.GetInt64()));
        Assert.Equal(1, receipt.GetProperty("memoryCount").GetInt32());
        await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code);
        f.Handler.AdmittedSequences = []; await f.Send(); Assert.Equal(new[] { 1, 0 }, f.Handler.Counts);
        Assert.Equal(0, f.Last.GetProperty("result").GetProperty("memoryCount").GetInt32());
        Assert.Equal(0, f.Last.GetProperty("result").GetProperty("admittedSequences").GetArrayLength());
        f.Rotate(); await f.List(); await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code);
        await f.Get(); Assert.DoesNotContain("admittedSequences", f.Sent.Last()); Assert.DoesNotContain("memoryCount", f.Sent.Last());
    }
    [Fact] public async Task ClearingMemoryWhilePreflightIsInFlightPreventsRevokedReferencesFromBeingPosted()
    {
        using var f = new Fixture(); f.Handler.State = "SUCCEEDED"; await f.List(); await f.Call("conversations.selectMemories", f.Identity);
        f.Handler.HoldGet = new(); var send = f.Send(Fixture.Refs());
        await f.Call("conversations.clearMemories", f.Identity); f.Handler.HoldGet.SetResult(); await send;
        Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code); Assert.Equal(0, f.Handler.Posts);
    }
}
