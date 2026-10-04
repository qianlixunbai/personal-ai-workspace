using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WorkspaceMemoryTests
{
    private const string Document = WorkspaceContentPolicy.ProductionOrigin + "/index.html";
    private sealed class Handler : HttpMessageHandler
    {
        internal Guid Id = Guid.NewGuid();
        internal long Revision = 9007199254740993;
        internal string Title = "Synthetic title", Content = "Synthetic body", Status = "ACTIVE";
        internal int Calls, PageSize, Page;
        internal bool Large, Many, Deleted;
        internal string? Failure;
        internal TaskCompletionSource? Hold;
        internal object Item(Guid? id = null) => new { id = id ?? Id, type = "PROJECT_NOTE", title = Large ? string.Concat(Enumerable.Repeat("😀", 160)) : Title,
            content = Large ? new string('\u0001', 2000) : Content, status = Status, revision = Revision, source = "MANUAL", createdAt = "2026-10-05T00:00:00Z", updatedAt = "2026-10-05T00:00:00Z" };
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls++; if (Hold is not null) await Hold.Task;
            if (Failure is not null) return Reply(new { code = Failure, message = "private SQL token content", phase = "STORAGE" }, Failure == "MEMORY_NOT_FOUND" ? HttpStatusCode.NotFound : HttpStatusCode.Conflict);
            if (request.Method == HttpMethod.Get && request.RequestUri!.AbsolutePath.EndsWith("/items"))
            {
                var query = request.RequestUri.Query[1..].Split('&').Select(x => x.Split('=')).ToDictionary(x => x[0], x => Uri.UnescapeDataString(x[1]));
                PageSize = int.Parse(query["limit"]); Page = int.Parse(query["page"]); Status = query["status"];
                return Reply(new { items = Deleted ? Array.Empty<object>() : Many ? Enumerable.Range(0, 20).Select(_ => Item(Guid.NewGuid())).ToArray() : new[] { Item() }, total = Deleted ? 0 : Many ? 20 : 1, page = Page, limit = PageSize });
            }
            if (request.Method == HttpMethod.Get) return Reply(Item());
            if (request.Method == HttpMethod.Delete) { Deleted = true; return new(HttpStatusCode.NoContent); }
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            if (request.RequestUri!.AbsolutePath.EndsWith("/archive")) Status = "ARCHIVED";
            else if (request.RequestUri.AbsolutePath.EndsWith("/restore")) Status = "ACTIVE";
            else { Title = body.RootElement.GetProperty("title").GetString()!; Content = body.RootElement.GetProperty("content").GetString()!; }
            bool create = request.Method == HttpMethod.Post && request.RequestUri.AbsolutePath.EndsWith("/items");
            Revision = create ? 1 : Revision + 1;
            var response = Reply(Item(), create ? HttpStatusCode.Created : HttpStatusCode.OK);
            if (create) response.Headers.Location = new Uri($"/api/v1/memory/items/{Id:D}", UriKind.Relative);
            return response;
        }
        private static HttpResponseMessage Reply(object value, HttpStatusCode status = HttpStatusCode.OK) => RuntimeClientTests.Response(status, JsonSerializer.Serialize(value));
    }
    private sealed class Actions(WorkspaceMemory memory) : IWorkspaceNativeActions
    {
        public WorkspaceMemory Memory => memory;
        public Task<ShellStatus> StatusAsync(CancellationToken ct) => Task.FromResult(new ShellStatus(1, "1.0.0.0", ShellRuntimeState.Available, ShellCredentialState.Ready, "Available", []));
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken ct) => Task.CompletedTask;
    }
    private sealed class Fixture : IDisposable
    {
        internal readonly Handler Handler = new();
        internal readonly RuntimeClient Runtime;
        internal readonly WorkspaceMemory Memory;
        internal readonly WorkspaceBridge Bridge;
        internal readonly List<string> Sent = [];
        internal Fixture() { Runtime = new(Handler, () => new string('t', 43)); Memory = new(Runtime); Bridge = new(new WorkspaceContentPolicy(), new Actions(Memory), Sent.Add); Rotate(); }
        internal void Rotate() { Bridge.BeginDocument(Document); Bridge.Ready(Document); Sent.Clear(); }
        internal string Request(string method, object payload, string? session = null) => JsonSerializer.Serialize(new { version = 1, sessionId = session ?? Bridge.SessionId, requestId = Guid.NewGuid().ToString("D"), method, payload });
        internal async Task<JsonElement> Send(string method, object payload) { Sent.Clear(); await Bridge.ReceiveAsync(Document, Document, Request(method, payload)); return JsonDocument.Parse(Assert.Single(Sent)).RootElement.Clone(); }
        internal Task<JsonElement> List() => Send("memory.list", new { query = "", status = "ACTIVE", type = (string?)null, page = 0 });
        public void Dispose() { Bridge.Dispose(); Runtime.Dispose(); }
    }
    [Fact] public async Task ListIsFixedTwentyAndMetadataOnlyGetIsExplicitAndRevisionIsExactDecimal()
    {
        using var f = new Fixture(); var list = await f.List(); Assert.Equal(20, f.Handler.PageSize);
        var item = list.GetProperty("result").GetProperty("items")[0]; Assert.Equal(8, item.EnumerateObject().Count()); Assert.False(item.TryGetProperty("content", out _));
        Assert.Equal("9007199254740993", item.GetProperty("revision").GetString()); Assert.Equal("MANUAL", item.GetProperty("source").GetString());
        var full = await f.Send("memory.get", new { memoryId = f.Handler.Id }); Assert.Equal("Synthetic body", full.GetProperty("result").GetProperty("content").GetString());
        var source = await f.Runtime.GetMemoryAsync(f.Handler.Id, default);
        Assert.DoesNotContain(source.Title, WorkspaceMemory.Metadata(source).ToString());
        Assert.DoesNotContain(source.Title, WorkspaceMemory.Project(source).ToString());
        Assert.DoesNotContain(source.Content, WorkspaceMemory.Project(source).ToString());
    }
    [Fact] public async Task UnknownIdsCannotReadOrMutateAndReloadRequiresNewListAuthorization()
    {
        using var f = new Fixture(); foreach (var method in new[] { "memory.get", "memory.update", "memory.archive", "memory.restore", "memory.delete" })
        {
            object payload = method == "memory.get" ? new { memoryId = f.Handler.Id } : method == "memory.update" ? new { memoryId = f.Handler.Id, expectedRevision = "1", type = "PROJECT_NOTE", title = "Synthetic", content = "Synthetic" } : new { memoryId = f.Handler.Id, expectedRevision = "1" };
            Assert.Equal("MemoryNotFound", (await f.Send(method, payload)).GetProperty("error").GetProperty("code").GetString());
        }
        Assert.Equal(0, f.Handler.Calls); await f.List(); f.Rotate(); await f.Send("memory.get", new { memoryId = f.Handler.Id }); Assert.Equal(1, f.Handler.Calls);
    }
    [Fact] public async Task CreateAuthorizesAndDeleteRevokesWithoutSendingSecrets()
    {
        using var f = new Fixture(); await f.Send("memory.create", new { type = "PROJECT_NOTE", title = "Synthetic", content = "Synthetic" });
        await f.Send("memory.get", new { memoryId = f.Handler.Id }); await f.Send("memory.delete", new { memoryId = f.Handler.Id, expectedRevision = "1" });
        Assert.Equal(0, f.Memory.AuthorizationCount(f.Bridge.SessionId)); var missing = await f.Send("memory.get", new { memoryId = f.Handler.Id }); Assert.False(missing.GetProperty("ok").GetBoolean()); Assert.Equal(3, f.Handler.Calls);
        Assert.DoesNotContain("Bearer", f.Sent[0]); Assert.DoesNotContain("private SQL", f.Sent[0]);
    }
    [Theory][InlineData("0")][InlineData("-1")][InlineData("+1")][InlineData("01")][InlineData("1.0")][InlineData("1e2")][InlineData("9223372036854775808")]
    public async Task NoncanonicalOrOverflowingRevisionsAreDropped(string revision)
    {
        using var f = new Fixture(); await f.Bridge.ReceiveAsync(Document, Document, f.Request("memory.delete", new { memoryId = f.Handler.Id, expectedRevision = revision })); Assert.Empty(f.Sent); Assert.Equal(0, f.Handler.Calls);
    }
    [Fact] public async Task Int64MaximumIsAcceptedWithoutNumericCoercion()
    {
        using var f = new Fixture(); await f.List(); f.Handler.Revision = long.MaxValue;
        var result = await f.Send("memory.delete", new { memoryId = f.Handler.Id, expectedRevision = long.MaxValue.ToString() }); Assert.True(result.GetProperty("ok").GetBoolean());
    }
    [Fact] public async Task ExactSchemasEnumsUnicodeAndPageBoundsAreRequired()
    {
        using var f = new Fixture();
        var bad = new (string Method, object Payload)[] {
            ("memory.list", new { query = "", status = "ACTIVE", type = (string?)null, page = 0, limit = 100 }),
            ("memory.list", new { query = new string('界', 161), status = "ACTIVE", type = (string?)null, page = 0 }),
            ("memory.list", new { query = "", status = "ACTIVE", type = (string?)null, page = 50 }),
            ("memory.list", new { query = "", status = "DELETED", type = (string?)null, page = 0 }),
            ("memory.get", new { memoryId = Guid.Empty }),
            ("memory.create", new { type = "AUTO", title = "Synthetic", content = "Synthetic" }),
            ("memory.create", new { type = "PROJECT_NOTE", title = new string('界', 161), content = "Synthetic" }),
            ("memory.create", new { type = "PROJECT_NOTE", title = "Synthetic", content = new string('界', 2001) }),
            ("memory.create", new { type = "PROJECT_NOTE", title = "Synthetic", content = "\0body" }),
            ("memory.editorState", new { dirty = true, content = "private" }),
            ("memory.editorState", new { dirty = "true" }),
            ("memory.invoke", new { }), ("runtime.fetch", new { }), ("memory.querySql", new { }) };
        foreach (var (method, payload) in bad) await f.Bridge.ReceiveAsync(Document, Document, f.Request(method, payload));
        Assert.Empty(f.Sent); Assert.Equal(0, f.Handler.Calls);
        await f.Send("memory.create", new { type = "PROJECT_NOTE", title = string.Concat(Enumerable.Repeat("😀", 160)), content = string.Concat(Enumerable.Repeat("😀", 1000)) }); Assert.Equal(1, f.Handler.Calls);
    }
    [Theory][InlineData("MEMORY_REVISION_CONFLICT", "MemoryRevisionConflict")][InlineData("MEMORY_NOT_FOUND", "MemoryNotFound")]
    public async Task ConflictAndMissingMapToControlledErrors(string runtimeCode, string code)
    {
        using var f = new Fixture(); await f.List(); f.Handler.Failure = runtimeCode;
        var result = await f.Send("memory.archive", new { memoryId = f.Handler.Id, expectedRevision = "9007199254740993" });
        Assert.Equal(code, result.GetProperty("error").GetProperty("code").GetString()); Assert.DoesNotContain("private SQL", f.Sent[0]);
    }
    [Fact] public async Task LifecycleAdvancesRevisionAndDoesNotReceiveEditorDraft()
    {
        using var f = new Fixture(); await f.List(); var archived = await f.Send("memory.archive", new { memoryId = f.Handler.Id, expectedRevision = "9007199254740993" });
        Assert.Equal("ARCHIVED", archived.GetProperty("result").GetProperty("status").GetString()); Assert.Equal("9007199254740994", archived.GetProperty("result").GetProperty("revision").GetString());
        var restored = await f.Send("memory.restore", new { memoryId = f.Handler.Id, expectedRevision = "9007199254740994" }); Assert.Equal("ACTIVE", restored.GetProperty("result").GetProperty("status").GetString()); Assert.Equal("Synthetic body", f.Handler.Content);
    }
    [Fact] public async Task MemoryMethodsAreExactAllowlistAndDirtySignalIsSessionCheckedAndSurvivesFailure()
    {
        Assert.Equal(new[] { "memory.archive", "memory.create", "memory.delete", "memory.editorState", "memory.get", "memory.list", "memory.restore", "memory.update" }, WorkspaceBridge.MemoryMethods.Order());
        using var f = new Fixture(); await f.Bridge.ReceiveAsync("https://evil.invalid", Document, f.Request("memory.editorState", new { dirty = true }));
        await f.Bridge.ReceiveAsync(Document, Document, f.Request("memory.editorState", new { dirty = true }, Guid.NewGuid().ToString("D"))); Assert.False(f.Bridge.EditorDirty);
        await f.Send("memory.editorState", new { dirty = true }); Assert.True(f.Bridge.EditorDirty);
        f.Bridge.Invalidate(); Assert.True(f.Bridge.EditorDirty); f.Rotate(); Assert.False(f.Bridge.EditorDirty);
    }
    [Fact] public async Task AuthorizationStaysBoundedAndLateOldSessionListCannotAuthorizeNewSession()
    {
        using var f = new Fixture(); f.Handler.Many = true; for (int i = 0; i < 51; i++) await f.List(); Assert.Equal(1000, f.Memory.AuthorizationCount(f.Bridge.SessionId));
        f.Handler.Hold = new(); var pending = f.Bridge.ReceiveAsync(Document, Document, f.Request("memory.list", new { query = "", status = "ACTIVE", type = (string?)null, page = 0 })); f.Rotate(); f.Handler.Hold.SetResult();
        // The bridge suppresses its old-session response as well as old authorization.
        await pending; Assert.Empty(f.Sent);
        Assert.Equal(0, f.Memory.AuthorizationCount(f.Bridge.SessionId));
    }
    [Fact] public async Task WorstEscapedSingleAndTwentyMetadataItemsFitOrdinary64KiB()
    {
        using var f = new Fixture(); f.Handler.Large = true; f.Handler.Many = true; await f.List();
        Assert.True(Encoding.UTF8.GetByteCount(Assert.Single(f.Sent)) < WorkspaceBridge.MaximumResponseBytes);
        f.Handler.Many = false; await f.List(); await f.Send("memory.get", new { memoryId = f.Handler.Id });
        Assert.True(Encoding.UTF8.GetByteCount(Assert.Single(f.Sent)) < WorkspaceBridge.MaximumResponseBytes); Assert.Equal(65536, WorkspaceBridge.MaximumResponseBytes);
    }
}
