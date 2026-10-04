using System.Net;
using System.Net.Http;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WorkspaceOperationsTests
{
    private const string Document = WorkspaceContentPolicy.ProductionOrigin + "/index.html";
    private static readonly Guid MemoryId = Guid.NewGuid();
    private static readonly MemorySelection[] Selection = [new(MemoryId, 9007199254740993L, "Synthetic selected label", MemoryType.PROJECT_NOTE)];
    private sealed class Actions(WorkspaceOperations operations) : IWorkspaceNativeActions
    {
        public WorkspaceOperations Operations => operations;
        public Task<ShellStatus> StatusAsync(CancellationToken cancellation) => Task.FromResult(new ShellStatus(1, "1.0.0.0", ShellRuntimeState.Available, ShellCredentialState.Ready, "Available", []));
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken cancellation) => Task.CompletedTask;
    }
    private sealed class Handler : HttpMessageHandler
    {
        internal int Posts, Deletes;
        internal bool Finish = true, Stale, TransportFailure, WrongPoll;
        internal string Result = "Synthetic workspace result";
        internal TaskCompletionSource? Hold;
        internal CancellationToken PostToken;
        internal readonly List<string> Paths = [];
        internal readonly List<int> MemoryCounts = [];
        private readonly Dictionary<Guid, (string Capability, string Version)> tasks = [];
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellation)
        {
            Assert.Equal(new string('t', 43), request.Headers.Authorization!.Parameter);
            string path = request.RequestUri!.AbsolutePath;
            Guid id;
            lock (tasks) Paths.Add(path);
            if (request.Method == HttpMethod.Post)
            {
                Posts++; PostToken = cancellation;
                if (TransportFailure) throw new HttpRequestException("private provider / raw body / secret");
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(cancellation));
                var root = body.RootElement;
                bool memory = path == "/api/v1/memory/ask/tasks";
                string capability = path.Contains("summarize") ? "summarize" : path.Contains("translate") ? "translate" : "ask";
                Assert.Equal(memory || capability == "translate" ? 3 : 2, root.EnumerateObject().Count());
                MemoryCounts.Add(memory ? root.GetProperty("memories").GetArrayLength() : 0);
                if (memory) Assert.Equal(9007199254740993L, root.GetProperty("memories")[0].GetProperty("revision").GetInt64());
                if (Stale && memory) return RuntimeClientTests.Response(HttpStatusCode.Conflict,
                    "{\"code\":\"MEMORY_SELECTION_STALE\",\"message\":\"private raw memory\",\"phase\":\"ADMISSION\"}");
                id = Guid.NewGuid(); lock (tasks) tasks.Add(id, (capability, memory ? "memory-ask-v1" : capability + "-v1"));
                if (Hold is not null) await Hold.Task;
            }
            else id = Guid.Parse(path.Split('/').Last());
            var identity = tasks[id];
            string state = request.Method == HttpMethod.Delete ? "CANCELLED" : request.Method == HttpMethod.Post || !Finish ? "QUEUED" : "SUCCEEDED";
            if (request.Method == HttpMethod.Delete) Deletes++;
            string profile = identity.Capability switch { "translate" => "translate.fast", "summarize" => "summarize.fast", _ => "chat.balanced" };
            string json = JsonSerializer.Serialize(new
            {
                taskId = id, capability = WrongPoll && request.Method == HttpMethod.Get ? "conversation" : identity.Capability, status = state,
                profile = new { id = profile, version = "test-1", locality = "LOCAL" }, promptVersion = identity.Version,
                createdAt = "2026-10-04T01:00:00Z", finishedAt = state == "QUEUED" ? null : "2026-10-04T01:00:01Z",
                result = state == "SUCCEEDED" ? Result : null,
                error = state == "CANCELLED" ? new { code = "TASK_CANCELLED", message = "private raw provider text", phase = "EXECUTION" } : null
            });
            var response = RuntimeClientTests.Response(request.Method == HttpMethod.Post ? HttpStatusCode.Accepted : HttpStatusCode.OK, json);
            if (request.Method == HttpMethod.Post) response.Headers.Location = new Uri($"/api/v1/tasks/{id:D}", UriKind.Relative);
            return response;
        }
    }
    private sealed class Fixture : IDisposable
    {
        internal readonly Handler Handler = new();
        internal readonly RuntimeClient Runtime;
        internal readonly WorkspaceOperations Operations;
        internal readonly WorkspaceBridge Bridge;
        internal readonly List<string> Sent = [];
        internal readonly List<string> Copied = [];
        internal DateTimeOffset Now = DateTimeOffset.UtcNow;
        internal IReadOnlyList<MemorySelection>? NextSelection = Selection;
        internal Fixture()
        {
            Runtime = new RuntimeClient(Handler, () => new string('t', 43));
            Operations = new WorkspaceOperations(Runtime, _ => Task.FromResult<IReadOnlyList<MemorySelection>?>(NextSelection), Copied.Add, () => Now, pollingInterval: TimeSpan.FromMilliseconds(2));
            Bridge = new WorkspaceBridge(new WorkspaceContentPolicy(), new Actions(Operations), Sent.Add);
            Bridge.BeginDocument(Document); Bridge.Ready(Document); Sent.Clear();
        }
        internal string Request(string method, object? payload = null, string? requestId = null, string? session = null) => JsonSerializer.Serialize(new
        { version = 1, sessionId = session ?? Bridge.SessionId, requestId = requestId ?? Guid.NewGuid().ToString("D"), method, payload = payload ?? new { } });
        internal Task Receive(string request, string source = Document) => Bridge.ReceiveAsync(source, Document, request);
        internal Task Call(string method, object? payload = null) => Receive(Request(method, payload));
        internal static object[] Refs() => [new { memoryId = MemoryId.ToString("D"), revision = "9007199254740993", position = 0 }];
        internal Task Assistant(string mode = "Ask", object[]? refs = null, string text = "Synthetic workspace input") => Call("assistant.submit", new { mode, text, selectedMemoryRefs = refs ?? [] });
        internal JsonElement Last => JsonDocument.Parse(Sent.Last()).RootElement;
        internal string Id => Last.GetProperty("result").GetProperty("operationId").GetString()!;
        internal async Task<WorkspaceOperationView> Terminal(string id)
        {
            var end = DateTime.UtcNow.AddSeconds(3);
            while (DateTime.UtcNow < end)
            {
                var view = Operations.Get(Bridge.SessionId, id);
                if (view.Status is not (TaskState.QUEUED or TaskState.RUNNING)) return view;
                await Task.Delay(5);
            }
            throw new TimeoutException();
        }
        public void Dispose() { Bridge.Dispose(); Operations.Dispose(); Runtime.Dispose(); }
    }
    [Fact] public void BusinessAllowlistIsExactlyTheSixM5BCapabilities()
    { Assert.Equal(new[] { "assistant.selectMemories", "assistant.submit", "operations.cancel", "operations.copyResult", "operations.get", "translate.submit" }, WorkspaceBridge.BusinessMethods.Order()); }
    [Theory][InlineData("Ask")][InlineData("Summarize")]
    public async Task AssistantReusesCoreContractsAndOwnedOpaqueOperations(string mode)
    {
        using var f = new Fixture(); await f.Assistant(mode); string id = f.Id;
        Assert.True(WorkspaceBridge.CanonicalId(id)); Assert.Equal(TaskState.SUCCEEDED, (await f.Terminal(id)).Status);
        await f.Call("operations.get", new { operationId = id });
        foreach (string secret in new[] { "taskId", "profile", "promptVersion", "Authorization", new string('t', 43) }) Assert.DoesNotContain(secret, f.Sent.Last());
        Assert.Equal([0], f.Handler.MemoryCounts); Assert.All(f.Handler.Paths, path => Assert.DoesNotContain("conversation", path));
    }
    [Fact] public async Task TranslateUsesNativeSingleContractAndCopyOnlyVerifiedOwnedResult()
    {
        using var f = new Fixture(); await f.Call("translate.submit", new { text = "Synthetic translate source", targetLanguage = "ja" }); string id = f.Id;
        await f.Terminal(id); await f.Call("operations.copyResult", new { operationId = id });
        Assert.True(f.Last.GetProperty("result").GetProperty("copied").GetBoolean()); Assert.Single(f.Copied);
        Assert.Equal("/api/v1/translate/tasks", f.Handler.Paths[0]); Assert.DoesNotContain("batch", string.Join(',', f.Handler.Paths));
    }
    [Fact] public async Task ExactSelectedRevisionIsStringAndAdmissionConsumesSelection()
    {
        using var f = new Fixture(); await f.Call("assistant.selectMemories");
        var selection = f.Last.GetProperty("result").GetProperty("selectedMemoryRefs")[0];
        Assert.Equal("9007199254740993", selection.GetProperty("revision").GetString()); Assert.Equal(4, selection.EnumerateObject().Count());
        await f.Assistant(refs: Fixture.Refs()); string id = f.Id; await f.Terminal(id);
        await f.Assistant(refs: Fixture.Refs()); Assert.Equal("MEMORY_SELECTION_REQUIRED", f.Last.GetProperty("error").GetProperty("code").GetString());
        await f.Assistant(); await f.Terminal(f.Id); Assert.Equal(new[] { 1, 0 }, f.Handler.MemoryCounts);
    }
    [Fact] public async Task SelectorCancelPreservesSelectionAndStaleAdmissionNeverSubstitutesOrConsumesIt()
    {
        using var f = new Fixture(); await f.Call("assistant.selectMemories"); f.NextSelection = null; await f.Call("assistant.selectMemories");
        Assert.False(f.Last.GetProperty("result").GetProperty("changed").GetBoolean());
        f.Handler.Stale = true; await f.Assistant(refs: Fixture.Refs());
        Assert.Equal("MemorySelectionStale", f.Last.GetProperty("error").GetProperty("code").GetString()); Assert.DoesNotContain("private raw", f.Sent.Last());
        f.Handler.Stale = false; await f.Assistant(refs: Fixture.Refs()); await f.Terminal(f.Id); Assert.Equal(2, f.Handler.Posts);
    }
    [Fact] public async Task ForgedMemoryReferenceCannotTriggerRuntimeAdmission()
    {
        using var f = new Fixture(); await f.Assistant(refs: Fixture.Refs()); Assert.Equal(0, f.Handler.Posts);
        await f.Call("assistant.selectMemories"); await f.Assistant(refs: [new { memoryId = Guid.NewGuid().ToString("D"), revision = "9007199254740993", position = 0 }]); Assert.Equal(0, f.Handler.Posts);
    }
    [Theory][InlineData("0")][InlineData("-1")][InlineData("01")][InlineData("1e3")][InlineData(" 1")][InlineData("9223372036854775808")][InlineData("１２")]
    public async Task RevisionMustBePositiveCanonicalDecimalInt64(string revision)
    {
        using var f = new Fixture(); await f.Assistant(refs: [new { memoryId = MemoryId.ToString("D"), revision, position = 0 }]);
        Assert.Empty(f.Sent); Assert.Equal(0, f.Handler.Posts);
    }
    [Fact] public async Task NumericRevisionDuplicateIdsUnknownFieldsAndSummarizeMemoryAreDropped()
    {
        using var f = new Fixture();
        await f.Assistant(refs: [new { memoryId = MemoryId.ToString("D"), revision = 1, position = 0 }]);
        await f.Assistant(refs: [Fixture.Refs()[0], Fixture.Refs()[0]]);
        await f.Assistant("Summarize", Fixture.Refs());
        await f.Call("assistant.submit", new { mode = "Ask", text = "x", selectedMemoryRefs = new object[0], model = "secret" });
        await f.Call("translate.submit", new { text = "x", targetLanguage = "en", profile = "translate.fast" });
        await f.Call("operations.get", new { taskId = Guid.NewGuid().ToString("D") });
        await f.Call("operations.copyResult", new { operationId = Guid.NewGuid().ToString("D"), text = "arbitrary clipboard" });
        await f.Receive(f.Request("assistant.submit", new { mode = "Ask", text = "x", selectedMemoryRefs = new object[0] }).Replace("\"text\":\"x\"", "\"text\":\"x\",\"text\":\"y\""));
        Assert.Empty(f.Sent); Assert.Equal(0, f.Handler.Posts);
    }
    [Fact] public async Task InputCharacterAndUtf8BudgetsRejectWithoutSubmitting()
    {
        using var f = new Fixture(); await f.Assistant(text: new string('x', 3001)); Assert.Empty(f.Sent);
        await f.Assistant(text: new string('中', 2000)); Assert.Equal("InvalidRequest", f.Last.GetProperty("error").GetProperty("code").GetString());
        await f.Call("translate.submit", new { text = "x", targetLanguage = "../../private" }); Assert.Equal(0, f.Handler.Posts);
    }
    [Theory][InlineData("operations.get")][InlineData("operations.cancel")][InlineData("operations.copyResult")]
    public async Task UnknownAndForeignOperationsShareTheSameControlledDenial(string method)
    {
        using var f = new Fixture(); await f.Assistant(); string id = f.Id; await f.Terminal(id);
        string old = f.Bridge.SessionId; f.Bridge.BeginDocument(Document); f.Bridge.Ready(Document); f.Sent.Clear();
        await f.Call(method, new { operationId = id }); string error = f.Last.GetProperty("error").GetRawText();
        await f.Call(method, new { operationId = Guid.NewGuid().ToString("D") }); Assert.Equal(error, f.Last.GetProperty("error").GetRawText());
        int responses = f.Sent.Count; await f.Receive(f.Request(method, new { operationId = id }, session: old)); Assert.Equal(responses, f.Sent.Count);
    }
    [Fact] public async Task CancelDoesNotCopyNonterminalOrOverrideTerminalRuntimeTruth()
    {
        using var f = new Fixture(); f.Handler.Finish = false; await f.Assistant(); string id = f.Id;
        await f.Call("operations.copyResult", new { operationId = id }); Assert.Empty(f.Copied);
        await f.Call("operations.cancel", new { operationId = id }); Assert.True(f.Last.GetProperty("result").GetProperty("requested").GetBoolean());
        Assert.Equal(TaskState.CANCELLED, (await f.Terminal(id)).Status); int deletes = f.Handler.Deletes;
        await f.Call("operations.cancel", new { operationId = id }); Assert.False(f.Last.GetProperty("ok").GetBoolean()); Assert.Equal(deletes, f.Handler.Deletes);
        await f.Call("operations.copyResult", new { operationId = id }); Assert.Empty(f.Copied);
    }
    [Fact] public async Task DuplicateSubmitRequestCannotReplayAndWrongOriginIsDenied()
    {
        using var f = new Fixture(); string request = f.Request("assistant.submit", new { mode = "Ask", text = "x", selectedMemoryRefs = new object[0] });
        await f.Receive(request, "https://evil.invalid/index.html"); Assert.Equal(0, f.Handler.Posts);
        await f.Receive(request); string id = f.Id; await f.Terminal(id); await f.Receive(request); Assert.Equal(1, f.Handler.Posts);
    }
    [Fact] public async Task ReloadSuppressesPendingSubmitReplyWithoutCancellingOrReplayingAdmission()
    {
        using var f = new Fixture(); f.Handler.Hold = new(); Task pending = f.Assistant(); Assert.Equal(1, f.Handler.Posts);
        string old = f.Bridge.SessionId; f.Bridge.BeginDocument(Document); f.Bridge.Ready(Document); f.Sent.Clear();
        Assert.False(f.Handler.PostToken.IsCancellationRequested); f.Handler.Hold.SetResult(); await pending; await Task.Delay(20);
        Assert.Empty(f.Sent); Assert.Equal(1, f.Handler.Posts); Assert.Equal(0, f.Handler.Deletes);
        await f.Receive(f.Request("assistant.submit", new { mode = "Ask", text = "x", selectedMemoryRefs = new object[0] }, session: old)); Assert.Equal(1, f.Handler.Posts);
    }
    [Fact] public async Task RegistryCapacityAndTerminalExpiryAreBoundedWithoutEvictingLiveTasks()
    {
        using var f = new Fixture();
        for (int index = 0; index < WorkspaceOperations.Capacity; index++) { await f.Assistant(); await f.Terminal(f.Id); }
        await f.Assistant(); Assert.Equal("OPERATION_CAPACITY", f.Last.GetProperty("error").GetProperty("code").GetString()); Assert.Equal(WorkspaceOperations.Capacity, f.Handler.Posts);
        f.Now += TimeSpan.FromMinutes(3); await f.Assistant(); await f.Terminal(f.Id); Assert.Equal(WorkspaceOperations.Capacity + 1, f.Handler.Posts);
    }
    [Fact] public async Task LostPOSTOutcomeIsSafeAndNeverRetried()
    {
        using var f = new Fixture(); f.Handler.TransportFailure = true; await f.Assistant();
        Assert.Equal("OutcomeUnknown", f.Last.GetProperty("error").GetProperty("code").GetString()); Assert.Equal(1, f.Handler.Posts);
        Assert.DoesNotContain("private provider", f.Sent.Last()); Assert.DoesNotContain(new string('t', 43), f.Sent.Last());
    }
    [Theory][InlineData(false)][InlineData(true)]
    public async Task SharedRunnerDistinguishesUnknownTransportAdmissionFromKnownCredentialFailure(bool missingCredential)
    {
        int calls = 0;
        using var runtime = new RuntimeClient(new RuntimeClientTests.Handler((_, _) =>
        { calls++; throw new HttpRequestException("private transport diagnostics"); }), () => missingCredential ? null : new string('t', 43));
        var runner = new AssistantOperation(runtime);
        var error = await Assert.ThrowsAsync<DesktopException>(() => runner.RunAsync(new(AssistantAction.Ask, "Synthetic input"), _ => throw new InvalidOperationException(), default));
        Assert.False(runner.Accepted); Assert.Equal(!missingCredential, runner.AdmissionOutcomeUnknown);
        Assert.Equal(missingCredential ? DesktopError.CredentialMissing : DesktopError.RuntimeUnavailable, error.Error);
        Assert.Equal(missingCredential ? 0 : 1, calls); Assert.DoesNotContain("private transport", error.Message);
    }
    [Fact] public void RegistryDtoDiagnosticsNeverContainSelectedTitleOrVerifiedResult()
    {
        const string privateValue = "Synthetic diagnostics exclusion";
        Assert.DoesNotContain(privateValue, new SelectedMemoryMetadata(MemoryId.ToString("D"), "1", 0, privateValue).ToString());
        Assert.DoesNotContain(privateValue, new WorkspaceOperationView(Guid.NewGuid().ToString("D"), TaskState.SUCCEEDED, privateValue, null).ToString());
    }
    [Fact] public async Task PollResponseCannotSwitchToConversationOrLeakUnverifiedResult()
    {
        using var f = new Fixture(); f.Handler.WrongPoll = true; await f.Assistant(); string id = f.Id; await Task.Delay(60);
        await f.Call("operations.get", new { operationId = id }); Assert.Equal("InvalidResponse", f.Last.GetProperty("error").GetProperty("code").GetString());
        Assert.DoesNotContain(f.Handler.Result, f.Sent.Last());
    }
    [Fact] public async Task MaximumEscapedResultFitsResponseWithoutSilentTruncation()
    {
        using var f = new Fixture(); f.Handler.Result = new string('\u0001', 8192); await f.Assistant(); string id = f.Id; await f.Terminal(id);
        await f.Call("operations.get", new { operationId = id }); Assert.Equal(8192, f.Last.GetProperty("result").GetProperty("result").GetString()!.Length);
        Assert.True(System.Text.Encoding.UTF8.GetByteCount(f.Sent.Last()) <= WorkspaceBridge.MaximumResponseBytes);
    }
}
