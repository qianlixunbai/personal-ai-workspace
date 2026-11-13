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
        internal bool Many, Deleted;
        internal TaskCompletionSource? Hold;
        internal object Item(Guid? id = null) => new { id = id ?? Id, type = "PROJECT_NOTE", title = Title,
            content = Content, status = Status, revision = Revision, source = "MANUAL", createdAt = "2026-10-05T00:00:00Z", updatedAt = "2026-10-05T00:00:00Z" };
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls++; if (Hold is not null) await Hold.Task;
            if (request.RequestUri!.AbsolutePath == "/api/v1/memory/recall")
            {
                Assert.Equal(HttpMethod.Post, request.Method);
                return Reply(new { hits = new[] { new { item = Item(), score = 1, matchedTerms = new[] { "java" } } }, total = 1, limit = 20 });
            }
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
    [Fact] public async Task AuthorizationStaysBoundedAndLateOldSessionListCannotAuthorizeNewSession()
    {
        using var f = new Fixture(); f.Handler.Many = true; for (int i = 0; i < 51; i++) await f.List(); Assert.Equal(1000, f.Memory.AuthorizationCount(f.Bridge.SessionId));
        f.Handler.Hold = new(); var pending = f.Bridge.ReceiveAsync(Document, Document, f.Request("memory.list", new { query = "", status = "ACTIVE", type = (string?)null, page = 0 })); f.Rotate(); f.Handler.Hold.SetResult();
        // The bridge suppresses its old-session response as well as old authorization.
        await pending; Assert.Empty(f.Sent);
        Assert.Equal(0, f.Memory.AuthorizationCount(f.Bridge.SessionId));
    }
}
