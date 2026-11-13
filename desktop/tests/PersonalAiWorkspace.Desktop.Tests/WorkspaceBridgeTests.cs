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
    [Fact] public async Task DuplicateRequestIdCannotReplayNativeOperation()
    {
        using var f = new Fixture(); string request = f.Request("native.openMemoryBackup"); await f.Receive(request); await f.Receive(request);
        Assert.Single(f.Native.Opened); Assert.Single(f.Sent);
    }
    [Fact] public async Task ReloadRotatesSessionCancelsPendingAndSuppressesLateResponse()
    {
        using var f = new Fixture(); f.Native.Waiting = new(); string old = f.Bridge.SessionId;
        Task pending = f.Receive(f.Request()); f.Bridge.BeginDocument(Document); f.Bridge.Ready(Document);
        Assert.NotEqual(old, f.Bridge.SessionId); f.Sent.Clear(); f.Native.Waiting.SetResult(Actions.Status()); await pending;
        await f.Receive(f.Request(session: old)); Assert.Empty(f.Sent);
        Assert.False(f.Bridge.HasBootstrapped);
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
    [Fact] public void ProductionNavigationResourcesFramesPopupsPermissionsAndDownloadsFailClosed()
    {
        var policy = new WorkspaceContentPolicy(["assets/shell.js"]);
        Assert.True(policy.Document(Document)); Assert.True(policy.Document(Document + "#/memory")); Assert.True(policy.Document(Document + "#/knowledge")); Assert.True(policy.Document(Document + "#/finance"));
        Assert.False(policy.Document(Document + "#/finance/3"));
        foreach (string uri in new[] { "file:///C:/private", "http://127.0.0.1:8765", "https://external.invalid", Document + "#/unknown", Document + "?url=x" }) Assert.False(policy.Document(uri));
        Assert.True(policy.Resource(WorkspaceContentPolicy.ProductionOrigin + "/assets/shell.js", "GET", "Script"));
        Assert.False(policy.Resource(WorkspaceContentPolicy.ProductionOrigin + "/assets/secret.js", "GET", "Script"));
        Assert.False(policy.Resource(Document, "POST", "Document")); Assert.False(policy.Resource(Document, "GET", "Fetch"));
        Assert.False(WorkspaceContentPolicy.AllowFrame); Assert.False(WorkspaceContentPolicy.AllowPopup);
        Assert.False(WorkspaceContentPolicy.AllowPermission); Assert.False(WorkspaceContentPolicy.AllowDownload);
    }
}
