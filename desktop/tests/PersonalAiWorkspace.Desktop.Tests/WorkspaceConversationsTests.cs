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
        internal bool Stale, Unknown, Large, ChangedTask, Deleted, GenericUnknown;
        internal Guid CreatedId = Id;
        internal string? ErrorCode;
        internal TaskCompletionSource? Hold, HoldGet;
        internal readonly List<int> Counts = [];
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
                object Message(Guid turnId, string role) => new { id = Guid.NewGuid(), turnId, role, content = Large ? new string('\u0001', 8192) : "Synthetic message", createdAt = role == "USER" ? "2026-10-04T00:00:00Z" : "2026-10-04T00:00:01Z" };
                var turns = Enumerable.Range(0, Large ? 10 : 1).Select(i => {
                    var turn = Large ? Guid.NewGuid() : TurnId;
                    return new { id = turn, conversationId = Id, sequence = i + 1, status = Large ? "SUCCEEDED" : State, createdAt = "2026-10-04T00:00:00Z", updatedAt = "2026-10-04T00:00:01Z",
                        userMessage = Message(turn, "USER"), assistantMessage = Large || State == "SUCCEEDED" ? Message(turn, "ASSISTANT") : null,
                        taskId = ChangedTask ? Guid.NewGuid() : TaskId, failureCode = State == "FAILED" ? "EXECUTION_INTERRUPTED" : null,
                        memories = Enumerable.Range(0, 4).Select(position => new { memoryId = Guid.NewGuid(), revision = long.MaxValue, position }).ToArray() };
                }).ToArray();
                return Reply(new { conversation = Metadata(), turns, totalTurns = Large ? 10 : 1, page, limit = 10 });
            }
            if (path.EndsWith("/turns"))
            {
                Posts++; if (Hold is not null) await Hold.Task;
                if (Unknown) throw new HttpRequestException("private SQL/body/token");
                if (GenericUnknown) throw new InvalidOperationException("private unexpected body");
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                var refs = body.RootElement.GetProperty("memories"); Counts.Add(refs.GetArrayLength());
                if (refs.GetArrayLength() > 0) Assert.Equal(long.MaxValue, refs[0].GetProperty("revision").GetInt64());
                if (Stale) return Error("MEMORY_SELECTION_STALE", HttpStatusCode.Conflict);
                if (ErrorCode is not null) return Error(ErrorCode, ErrorCode == "QUEUE_FULL" ? HttpStatusCode.TooManyRequests : ErrorCode == "POLICY_DENIED" ? HttpStatusCode.Forbidden : ErrorCode == "INTERNAL_ERROR" ? HttpStatusCode.InternalServerError : HttpStatusCode.ServiceUnavailable);
                var reply = Reply(new { conversationId = Id, turnId = TurnId, taskId = TaskId, status = "QUEUED", memoryCount = refs.GetArrayLength(), admittedSequences = new[] { 1 }, inputCharacters = 12, inputBytes = 12 }, HttpStatusCode.Accepted);
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
        internal Task Send(object[]? refs = null, string message = "Synthetic user") => Call("conversations.send", new { conversationId = Id, message, selectedMemoryRefs = refs ?? [] });
        internal static object[] Refs() => [new { memoryId = MemoryId, revision = long.MaxValue.ToString(), position = 0 }];
        internal JsonElement Last { get { using var parsed = JsonDocument.Parse(Sent.Last()); return parsed.RootElement.Clone(); } }
        internal string Code => Last.GetProperty("error").GetProperty("code").GetString()!;
        internal object Identity => new { conversationId = Id };
        public void Dispose() { Bridge.Dispose(); Runtime.Dispose(); }
    }
    [Fact] public void ExplicitAllowlistHasExactlyElevenCapabilities()
    { Assert.Equal(new[] { "conversations.archive", "conversations.cancelPending", "conversations.clearMemories", "conversations.create", "conversations.delete", "conversations.get", "conversations.list", "conversations.rename", "conversations.selectMemories", "conversations.send", "conversations.unarchive" }, WorkspaceBridge.ConversationMethods.Order()); }
    [Theory][InlineData("conversations.get")][InlineData("conversations.rename")][InlineData("conversations.archive")][InlineData("conversations.unarchive")][InlineData("conversations.delete")][InlineData("conversations.selectMemories")][InlineData("conversations.clearMemories")][InlineData("conversations.send")][InlineData("conversations.cancelPending")]
    public async Task UnlistedIdentityCannotReadOrMutate(string method)
    {
        using var f = new Fixture(); object payload = method switch {
            "conversations.get" => new { conversationId = Id, page = 0 }, "conversations.rename" => new { conversationId = Id, title = "Synthetic title" },
            "conversations.send" => new { conversationId = Id, message = "Synthetic", selectedMemoryRefs = new object[0] },
            "conversations.cancelPending" => new { conversationId = Id, turnId = TurnId }, _ => f.Identity };
        await f.Call(method, payload); Assert.Equal("ConversationNotFound", f.Code); Assert.Equal(0, f.Handler.Gets + f.Handler.Posts + f.Handler.Deletes + f.Handler.Cancels);
    }
    [Fact] public async Task ListAndCreateAuthorizeOnlyReturnedIdsAndReloadResetsAuthorization()
    {
        using var f = new Fixture(); await f.List(); await f.Get(); Assert.True(f.Last.GetProperty("ok").GetBoolean());
        await f.Call("conversations.get", new { conversationId = Other, page = 0 }); Assert.Equal("ConversationNotFound", f.Code);
        f.Rotate(); await f.Get(); Assert.Equal("ConversationNotFound", f.Code); await f.Call("conversations.create"); await f.Get(); Assert.True(f.Last.GetProperty("ok").GetBoolean());
    }
    [Fact] public async Task DetailProjectionNeverExposesTaskOrProviderAndRebuildsCancelAuthorityAfterReload()
    {
        using var f = new Fixture(); await f.List(); await f.Get(); Assert.DoesNotContain("taskId", f.Sent.Last());
        Assert.True(f.Last.GetProperty("result").GetProperty("turns")[0].GetProperty("canCancel").GetBoolean());
        f.Rotate(); await f.List(); await f.Call("conversations.cancelPending", new { conversationId = Id, turnId = TurnId }); Assert.Equal("ConversationNotFound", f.Code);
        await f.Get(); await f.Call("conversations.cancelPending", new { conversationId = Id, turnId = TurnId }); Assert.Equal(1, f.Handler.Cancels);
        await f.Get(); Assert.Equal("CANCELLED", f.Last.GetProperty("result").GetProperty("turns")[0].GetProperty("status").GetString());
    }
    [Theory][InlineData("SUCCEEDED")][InlineData("FAILED")][InlineData("CANCELLED")][InlineData("TIMED_OUT")]
    public async Task CancelRaceUsesDurableTerminalTruthWithoutSendingCancel(string terminal)
    { using var f = new Fixture(); await f.List(); await f.Get(); f.Handler.State = terminal; await f.Call("conversations.cancelPending", new { conversationId = Id, turnId = TurnId }); Assert.True(f.Last.GetProperty("ok").GetBoolean()); Assert.Equal(0, f.Handler.Cancels); }
    [Fact] public async Task ForgedTurnAndChangedTaskBindingCannotCancel()
    {
        using var f = new Fixture(); await f.List(); await f.Get(); await f.Call("conversations.cancelPending", new { conversationId = Id, turnId = Guid.NewGuid() }); Assert.Equal("ConversationNotFound", f.Code);
        f.Handler.ChangedTask = true; await f.Call("conversations.cancelPending", new { conversationId = Id, turnId = TurnId }); Assert.Equal("ConversationNotFound", f.Code); Assert.Equal(0, f.Handler.Cancels);
    }
    [Fact] public async Task ArchiveDoesNotCancelAndArchivedSendIsRejectedUntilUnarchive()
    {
        using var f = new Fixture(); await f.List(); await f.Get(); await f.Call("conversations.archive", f.Identity); Assert.Equal(0, f.Handler.Cancels);
        await f.Send(); Assert.Equal("ConversationConflict", f.Code); Assert.Equal(0, f.Handler.Posts);
        await f.Call("conversations.unarchive", f.Identity); await f.Send(); Assert.True(f.Last.GetProperty("ok").GetBoolean());
    }
    [Fact] public async Task PendingDeleteConflictAndSuccessfulDeleteRevokesAuthorization()
    {
        using var f = new Fixture(); await f.List(); await f.Call("conversations.delete", f.Identity); Assert.Equal("ConversationConflict", f.Code); Assert.Equal(0, f.Handler.Cancels);
        f.Handler.State = "SUCCEEDED"; await f.Call("conversations.delete", f.Identity); Assert.Equal(1, f.Handler.Deletes); await f.Get(); Assert.Equal("ConversationNotFound", f.Code);
    }
    [Fact] public async Task MemoryExactRevisionConsumedAndCannotCarryAcrossConversationOrSession()
    {
        using var f = new Fixture(); f.Handler.State = "SUCCEEDED"; await f.List(); await f.Call("conversations.selectMemories", f.Identity);
        Assert.Equal(long.MaxValue.ToString(), f.Last.GetProperty("result").GetProperty("selectedMemoryRefs")[0].GetProperty("revision").GetString());
        await f.Send(Fixture.Refs()); Assert.True(f.Last.GetProperty("ok").GetBoolean()); Assert.DoesNotContain("taskId", f.Sent.Last());
        await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code); await f.Send(); Assert.Equal(new[] { 1, 0 }, f.Handler.Counts);
        f.Rotate(); await f.List(); await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code);
    }
    [Fact] public async Task StaleMemoryRequiresReviewAndClearRevokesPickerAuthorization()
    {
        using var f = new Fixture(); f.Handler.State = "SUCCEEDED"; await f.List(); await f.Call("conversations.selectMemories", f.Identity); f.Handler.Stale = true;
        await f.Send(Fixture.Refs()); Assert.Equal("MemorySelectionStale", f.Code); f.Handler.Stale = false;
        await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code); Assert.Equal(1, f.Handler.Posts);
        await f.Call("conversations.clearMemories", f.Identity); await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code); await f.Send(); Assert.True(f.Last.GetProperty("ok").GetBoolean());
    }
    [Theory][InlineData(false)][InlineData(true)] public async Task UnknownOutcomeNeverRetriesAndConsumesMemoryAuthorization(bool unexpected)
    {
        using var f = new Fixture(); f.Handler.State = "SUCCEEDED"; await f.List(); await f.Call("conversations.selectMemories", f.Identity); f.Handler.Unknown = !unexpected; f.Handler.GenericUnknown = unexpected;
        await f.Send(Fixture.Refs()); Assert.Equal("OutcomeUnknown", f.Code); Assert.DoesNotContain("private", f.Sent.Last());
        f.Handler.Unknown = false; f.Handler.GenericUnknown = false; await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code); Assert.Equal(1, f.Handler.Posts);
    }
    [Fact] public async Task MemoryAuthorizationCannotBeUsedForAnotherAuthorizedConversation()
    {
        using var f = new Fixture(); f.Handler.State = "SUCCEEDED"; await f.List(); await f.Call("conversations.selectMemories", f.Identity);
        f.Handler.CreatedId = Other; await f.Call("conversations.create");
        await f.Call("conversations.send", new { conversationId = Other, message = "Synthetic", selectedMemoryRefs = Fixture.Refs() });
        Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code); Assert.Equal(0, f.Handler.Posts);
        await f.Call("conversations.clearMemories", f.Identity); await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code);
    }
    [Fact] public async Task ClearingMemoryWhilePreflightIsInFlightPreventsRevokedReferencesFromBeingPosted()
    {
        using var f = new Fixture(); f.Handler.State = "SUCCEEDED"; await f.List(); await f.Call("conversations.selectMemories", f.Identity);
        f.Handler.HoldGet = new(); var send = f.Send(Fixture.Refs());
        await f.Call("conversations.clearMemories", f.Identity); f.Handler.HoldGet.SetResult(); await send;
        Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code); Assert.Equal(0, f.Handler.Posts);
    }
    [Theory][InlineData("QUEUE_FULL", "QueueFull")][InlineData("CONVERSATION_STORAGE_UNAVAILABLE", "ConversationStorageUnavailable")][InlineData("INTERNAL_ERROR", "InternalError")][InlineData("POLICY_DENIED", "PolicyDenied")]
    public async Task DurableRejectionErrorsStayControlledAndConsumeUnsafeSelection(string code, string expected)
    {
        using var f = new Fixture(); f.Handler.State = "SUCCEEDED"; await f.List(); await f.Call("conversations.selectMemories", f.Identity); f.Handler.ErrorCode = code;
        await f.Send(Fixture.Refs()); Assert.Equal(expected, f.Code); f.Handler.ErrorCode = null; await f.Send(Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Code);
    }
    [Theory][InlineData(-1)][InlineData(100)][InlineData(2147483647)] public async Task PageBoundsRejectBeforeRuntime(int page)
    { using var f = new Fixture(); await f.Call("conversations.list", new { status = "ACTIVE", page }); Assert.Empty(f.Sent); }
    [Fact] public async Task WrongOriginSessionUnknownFieldsRawTaskAndBadSchemasAreDropped()
    {
        using var f = new Fixture(); await f.Bridge.ReceiveAsync("https://evil.invalid/index.html", Document, f.Request("conversations.create"));
        await f.Bridge.ReceiveAsync(Document, Document, f.Request("conversations.create", session: Guid.NewGuid().ToString()));
        await f.Call("conversations.create", new { title = "unknown" }); await f.Call("conversations.list", new { status = "OTHER", page = 0 });
        await f.Call("conversations.list", new { status = "ACTIVE", page = 0, limit = 100 });
        await f.Call("conversations.get", new { conversationId = "bad", page = 0 });
        await f.Call("conversations.cancelPending", new { conversationId = Id, taskId = TaskId });
        await f.Call("conversations.send", new { conversationId = Id, message = new string('x', 3001), selectedMemoryRefs = new object[0] });
        await f.Call("conversations.send", new { conversationId = Id, message = "Synthetic", selectedMemoryRefs = new[] { new { memoryId = MemoryId, revision = "9223372036854775808", position = 0 } } });
        Assert.Empty(f.Sent); Assert.Equal(0, f.Handler.Posts);
    }
    [Fact] public async Task UnicodeTitleAndCoreMessageBoundsAreFinalAuthority()
    {
        using var f = new Fixture(); await f.List(); await f.Call("conversations.rename", new { conversationId = Id, title = string.Concat(Enumerable.Repeat("😀", 160)) }); Assert.True(f.Last.GetProperty("ok").GetBoolean());
        await f.Call("conversations.rename", new { conversationId = Id, title = new string('x', 161) }); Assert.Equal("ConversationInvalid", f.Code);
        await f.Send(message: new string('中', 2000)); Assert.Equal("InvalidRequest", f.Code); Assert.Equal(0, f.Handler.Posts);
    }
    [Fact] public async Task ReloadDuringAdmissionSuppressesOldReplyWithoutCancellingOrReplay()
    {
        using var f = new Fixture(); await f.List(); f.Handler.Hold = new(); var posting = f.Send(); Assert.Equal(1, f.Handler.Posts);
        f.Rotate(); f.Handler.Hold.SetResult(); await posting; Assert.Empty(f.Sent); Assert.Equal(1, f.Handler.Posts); Assert.Equal(0, f.Handler.Cancels);
        await f.List(); await f.Get(); Assert.True(f.Last.GetProperty("ok").GetBoolean());
    }
    [Fact] public async Task MaximumLegalEscapedPageParsesProjectsSerializesWithinSpecificBudgetWithoutTruncation()
    {
        using var f = new Fixture(); f.Handler.Large = true; f.Handler.State = "SUCCEEDED"; f.Handler.Title = new string('\u0001', 160);
        await f.List(); await f.Get(); var body = f.Sent.Last(); int bytes = Encoding.UTF8.GetByteCount(body);
        Assert.InRange(bytes, 983040, WorkspaceBridge.MaximumConversationResponseBytes); Assert.Equal(64 * 1024, WorkspaceBridge.MaximumResponseBytes);
        var turns = f.Last.GetProperty("result").GetProperty("turns"); Assert.Equal(10, turns.GetArrayLength());
        foreach (var turn in turns.EnumerateArray()) { Assert.Equal(new string('\u0001', 8192), turn.GetProperty("userMessage").GetProperty("content").GetString()); Assert.Equal(new string('\u0001', 8192), turn.GetProperty("assistantMessage").GetProperty("content").GetString()); }
        Assert.DoesNotContain("taskId", body);
    }
}
