using System.Net;
using System.Net.Http;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WorkspaceBridgeTests
{
    private const string Document = WorkspaceContentPolicy.ProductionOrigin + "/index.html";
    private sealed class Actions : IWorkspaceNativeActions
    {
        internal readonly List<NativeWorkspaceEntry> Opened = [];
        internal TaskCompletionSource<ShellStatus>? Waiting;
        public Task<ShellStatus> StatusAsync(CancellationToken cancellation) => Waiting?.Task ?? Task.FromResult(Status());
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken cancellation) { Opened.Add(entry); return Task.CompletedTask; }
        internal static ShellStatus Status() => new(1, "1.0.0.0", ShellRuntimeState.Available, ShellCredentialState.Ready,
            "Available", WorkspaceBridge.NativeMethods.Keys.ToArray());
    }
    private sealed class Fixture : IDisposable
    {
        internal readonly Actions Native = new();
        internal readonly List<string> Sent = [];
        internal readonly WorkspaceBridge Bridge;
        internal Fixture() { Bridge = new(new WorkspaceContentPolicy(), Native, Sent.Add); Bridge.BeginDocument(Document); Bridge.Ready(Document); Sent.Clear(); }
        internal string Request(string method = "shell.bootstrap", string? id = null, string? session = null, object? payload = null, int version = 1) =>
            JsonSerializer.Serialize(new { version, sessionId = session ?? Bridge.SessionId, requestId = id ?? Guid.NewGuid().ToString("D"), method, payload = payload ?? new { } });
        internal Task Receive(string json, string source = Document, string current = Document) => Bridge.ReceiveAsync(source, current, json);
        public void Dispose() => Bridge.Dispose();
    }
    [Fact] public async Task ExactOriginAndDocumentReturnOnlySafeStatus()
    {
        using var f = new Fixture(); await f.Receive(f.Request());
        var root = JsonDocument.Parse(Assert.Single(f.Sent)).RootElement;
        Assert.True(root.GetProperty("ok").GetBoolean());
        Assert.Equal("Ready", root.GetProperty("result").GetProperty("credential").GetString());
        Assert.Equal(6, root.GetProperty("result").EnumerateObject().Count());
        foreach (string forbidden in new[] { "bearer", "Authorization", "token", "path", "secret", "memoryContent" }) Assert.DoesNotContain(forbidden, f.Sent[0]);
    }
    [Theory]
    [InlineData("https://evil.invalid/index.html")]
    [InlineData("https://workspace.personal-ai.invalid.evil.invalid/index.html")]
    [InlineData("http://workspace.personal-ai.invalid/index.html")]
    [InlineData("https://workspace.personal-ai.invalid:444/index.html")]
    [InlineData("https://user@workspace.personal-ai.invalid/index.html")]
    [InlineData("https://workspace.personal-ai.invalid/other.html")]
    [InlineData("https://workspace.personal-ai.invalid/index.html?document=old")]
    public async Task WrongOriginOrDocumentIsDropped(string source)
    { using var f = new Fixture(); await f.Receive(f.Request(), source); Assert.Empty(f.Sent); }
    [Fact] public async Task WrongCurrentTopLevelDocumentAndUnreadyNavigationAreDropped()
    {
        using var f = new Fixture(); await f.Receive(f.Request(), current: Document.Replace("index.html", "other.html"));
        f.Bridge.BeginDocument(Document); await f.Receive(f.Request()); Assert.Empty(f.Sent);
    }
    [Fact] public async Task FakeSessionAndUnsupportedVersionAreDropped()
    { using var f = new Fixture(); await f.Receive(f.Request(session: Guid.NewGuid().ToString("D"))); await f.Receive(f.Request(version: 2)); Assert.Empty(f.Sent); }
    [Theory]
    [InlineData("native.openLegacyAssistant")][InlineData("native.openConversations")][InlineData("native.openMemory")]
    [InlineData("native.openWindow")][InlineData("native.fetch")][InlineData("native.openPath")]
    [InlineData("conversation.send")][InlineData("memory.invoke")][InlineData("task.cancel")]
    public async Task GenericAndFutureDomainMethodsAreAbsent(string method)
    { using var f = new Fixture(); await f.Receive(f.Request(method)); Assert.Empty(f.Sent); Assert.Empty(f.Native.Opened); }
    [Fact] public async Task PayloadMustBeExactlyEmptyAndNoArbitraryUrlPathOrExecutableIsAccepted()
    {
        using var f = new Fixture();
        foreach (object value in new object[] { new { url = "http://127.0.0.1:8765" }, new { path = "C:\\private" }, new { executable = "cmd.exe" }, new { token = "discover" }, "{}", new object[0] })
            await f.Receive(f.Request("native.openCredentialFlow", payload: value));
        Assert.Empty(f.Sent); Assert.Empty(f.Native.Opened);
    }
    [Fact] public async Task MalformedUnknownDuplicateAndMissingFieldsAreRejected()
    {
        using var f = new Fixture(); string request = f.Request();
        foreach (var json in new[] { "null", "[]", "{", request.Replace("\"version\":1", "\"version\":1,\"unknown\":1"),
            request.Replace("\"version\":1", "\"version\":1,\"version\":1"), request.Replace("\"version\":1,", ""),
            request.Replace("\"version\":1", "\"version\":\"1\""), request.Replace("\"payload\":{}", "\"payload\":null") }) await f.Receive(json);
        Assert.Empty(f.Sent);
    }
    [Theory][InlineData("")][InlineData("bad")][InlineData("00000000-0000-0000-0000-000000000000")][InlineData("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")]
    public async Task InvalidRequestIdsAreDropped(string id)
    { using var f = new Fixture(); await f.Receive(f.Request(id: id)); Assert.Empty(f.Sent); }
    [Fact] public async Task DuplicateRequestIdCannotReplayNativeOperation()
    {
        using var f = new Fixture(); string request = f.Request("native.openMemoryBackup"); await f.Receive(request); await f.Receive(request);
        Assert.Single(f.Native.Opened); Assert.Single(f.Sent);
    }
    [Fact] public async Task BothUtf8BytesAndEnvelopeLengthAreBounded()
    {
        using var f = new Fixture(); await f.Receive(new string(' ', WorkspaceBridge.MaximumBytes) + f.Request());
        await f.Receive(f.Request(payload: new { text = new string('界', 12000) })); Assert.Empty(f.Sent);
    }
    [Fact] public async Task ReloadRotatesSessionCancelsPendingAndSuppressesLateResponse()
    {
        using var f = new Fixture(); f.Native.Waiting = new(); string old = f.Bridge.SessionId;
        Task pending = f.Receive(f.Request()); f.Bridge.BeginDocument(Document); f.Bridge.Ready(Document);
        Assert.NotEqual(old, f.Bridge.SessionId); f.Sent.Clear(); f.Native.Waiting.SetResult(Actions.Status()); await pending;
        await f.Receive(f.Request(session: old)); Assert.Empty(f.Sent);
    }
    [Fact] public async Task PendingAndSessionRequestCountStayBounded()
    {
        using var f = new Fixture(); f.Native.Waiting = new();
        var pending = Enumerable.Range(0, WorkspaceBridge.MaximumPending).Select(_ => f.Receive(f.Request())).ToArray();
        await f.Receive(f.Request("native.openMemoryBackup")); Assert.Empty(f.Native.Opened);
        f.Native.Waiting.SetResult(Actions.Status()); await Task.WhenAll(pending); f.Native.Waiting = null; f.Sent.Clear();
        for (int i = WorkspaceBridge.MaximumPending; i < WorkspaceBridge.MaximumRequestsPerSession; i++) await f.Receive(f.Request("native.openMemoryBackup"));
        int before = f.Native.Opened.Count; await f.Receive(f.Request("native.openMemoryBackup")); Assert.Equal(before, f.Native.Opened.Count);
    }
    [Theory]
    [InlineData("native.openCredentialFlow")][InlineData("native.openBrowserPairing")]
    [InlineData("native.openMemoryBackup")][InlineData("native.openWorkspaceBackup")]
    [InlineData("native.openKnowledgeBackup")]
    public async Task MaintenanceEntriesStillRequireTrustedOriginAndCurrentSession(string method)
    {
        using var f = new Fixture();
        await f.Receive(f.Request(method), source: "https://evil.invalid/index.html");
        await f.Receive(f.Request(method, session: Guid.NewGuid().ToString("D")));
        Assert.Empty(f.Sent); Assert.Empty(f.Native.Opened);
        await f.Receive(f.Request(method));
        Assert.Single(f.Native.Opened); Assert.Single(f.Sent);
    }
    private sealed class Handler(HttpStatusCode credentialCode) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellation)
        {
            bool health = request.RequestUri!.AbsolutePath == "/actuator/health";
            if (!health) Assert.Equal(new string('t', 43), request.Headers.Authorization!.Parameter);
            return Task.FromResult(new HttpResponseMessage(health ? HttpStatusCode.OK : credentialCode)
            { Content = new StringContent(health ? "{\"status\":\"UP\"}" : "{\"provider\":\"ollama\",\"profile\":\"translate.fast\",\"available\":false,\"modelAvailable\":false}", System.Text.Encoding.UTF8, "application/json") });
        }
    }
    [Theory][InlineData(HttpStatusCode.OK, "Ready")][InlineData(HttpStatusCode.Unauthorized, "Invalid")]
    public async Task ReadinessEstablishesOnlyCredentialValidityAndNeverReturnsBearer(HttpStatusCode code, string expected)
    {
        using var runtime = new RuntimeClient(new Handler(code), () => new string('t', 43));
        var status = await new WorkspaceStatusProbe(runtime, () => new string('t', 43)).ReadAsync(default);
        Assert.Equal(expected, status.Credential.ToString()); Assert.Equal(ShellRuntimeState.Available, status.Runtime);
        Assert.DoesNotContain(new string('t', 43), JsonSerializer.Serialize(status));
    }
    [Fact] public async Task MissingMalformedAndStorageFailureHaveDistinctSafeStates()
    {
        using var runtime = new RuntimeClient(new Handler(HttpStatusCode.OK), () => null);
        Assert.Equal(ShellCredentialState.Missing, (await new WorkspaceStatusProbe(runtime, () => null).ReadAsync(default)).Credential);
        foreach (var error in new[] { DesktopError.CredentialInvalid, DesktopError.CredentialStorage })
            Assert.Equal(error == DesktopError.CredentialInvalid ? ShellCredentialState.Invalid : ShellCredentialState.Unavailable,
                (await new WorkspaceStatusProbe(runtime, () => throw new DesktopException(error)).ReadAsync(default)).Credential);
    }
    [Fact] public void ProductionNavigationResourcesFramesPopupsPermissionsAndDownloadsFailClosed()
    {
        var policy = new WorkspaceContentPolicy(["assets/shell.js"]);
        Assert.True(policy.Document(Document)); Assert.True(policy.Document(Document + "#/memory")); Assert.True(policy.Document(Document + "#/knowledge"));
        foreach (string uri in new[] { "file:///C:/private", "http://127.0.0.1:8765", "https://external.invalid", Document + "#/unknown", Document + "?url=x" }) Assert.False(policy.Document(uri));
        Assert.True(policy.Resource(WorkspaceContentPolicy.ProductionOrigin + "/assets/shell.js", "GET", "Script"));
        Assert.False(policy.Resource(WorkspaceContentPolicy.ProductionOrigin + "/assets/secret.js", "GET", "Script"));
        Assert.False(policy.Resource(Document, "POST", "Document")); Assert.False(policy.Resource(Document, "GET", "Fetch"));
        Assert.False(WorkspaceContentPolicy.AllowFrame); Assert.False(WorkspaceContentPolicy.AllowPopup);
        Assert.False(WorkspaceContentPolicy.AllowPermission); Assert.False(WorkspaceContentPolicy.AllowDownload);
    }
}
