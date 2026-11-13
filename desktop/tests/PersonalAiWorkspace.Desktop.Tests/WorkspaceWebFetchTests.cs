using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.RuntimeClientTests;
using static PersonalAiWorkspace.Desktop.Tests.RuntimeClientWebFetchTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WorkspaceWebFetchTests
{
    internal sealed class Confirmation : IWebFetchConfirmation
    {
        internal TaskCompletionSource<bool> Choice = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal WebFetchTarget? Target;
        internal CancellationToken Token;
        internal int Shown;
        public Task<bool> ConfirmAsync(WebFetchTarget target, CancellationToken cancellation)
        { Target = target; Token = cancellation; Shown++; return Choice.Task; }
        internal void Reset() { Choice = new(TaskCreationOptions.RunContinuationsAsynchronously); Target = null; }
    }
    internal sealed class Fixture : IWorkspaceNativeActions, IDisposable
    {
        internal const string Document = WorkspaceContentPolicy.ProductionOrigin + "/index.html";
        internal readonly Confirmation Dialog = new();
        internal readonly RuntimeClient Runtime;
        internal readonly WorkspaceWebFetch Web;
        internal readonly WorkspaceKnowledge Knowledge;
        internal readonly WorkspaceBridge Bridge;
        internal readonly List<string> Sent = [];
        internal int Posts, Gets, Deletes;
        internal Guid Id;
        internal string? PostedUrl;
        WorkspaceWebFetch IWorkspaceNativeActions.WebFetch => Web;
        WorkspaceKnowledge IWorkspaceNativeActions.Knowledge => Knowledge;
        private sealed class NoFilePicker : IKnowledgeSourceFiles
        { public Task<KnowledgeSourceFile?> PickAsync(CancellationToken token) => throw new InvalidOperationException("Web capture must not pick a local file."); }
        public Task<ShellStatus> StatusAsync(CancellationToken token) => throw new NotSupportedException();
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken token) => throw new NotSupportedException();
        internal Fixture()
        {
            Runtime = new(new Handler(async (request, ct) =>
            {
                Assert.Equal(new string('t', 43), request.Headers.Authorization!.Parameter);
                if (request.Method == HttpMethod.Post)
                {
                    Posts++;
                    using var parsed = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                    Assert.Equal(new[] { "operationId", "url" }, parsed.RootElement.EnumerateObject().Select(p => p.Name));
                    Id = Guid.ParseExact(parsed.RootElement.GetProperty("operationId").GetString()!, "D");
                    PostedUrl = parsed.RootElement.GetProperty("url").GetString();
                    return Reply(Id, Envelope(Id), HttpStatusCode.Accepted);
                }
                Assert.Equal($"/api/v1/web/fetches/{Id:D}", request.RequestUri!.AbsolutePath);
                if (request.Method == HttpMethod.Get)
                {
                    Gets++;
                    return Reply(Id, Envelope(Id, "SUCCEEDED", Result(PostedUrl!)));
                }
                Deletes++; return Reply(Id, Envelope(Id, "CANCELLED"));
            }), () => new string('t', 43));
            Web = new(Runtime, Dialog); Knowledge = new(Runtime,new NoFilePicker()); Bridge = new(new WorkspaceContentPolicy(), this, Sent.Add); Rotate();
        }
        internal void Rotate() { Bridge.BeginDocument(Document); Bridge.Ready(Document); Sent.Clear(); }
        internal string Request(string method, object payload, string? session = null) => JsonSerializer.Serialize(new
        { version = 1, sessionId = session ?? Bridge.SessionId, requestId = Guid.NewGuid().ToString("D"), method, payload });
        internal Task Receive(string request, string source = Document) => Bridge.ReceiveAsync(source, Document, request);
        public void Dispose() { Bridge.Dispose(); Runtime.Dispose(); }
    }
    [Fact] public async Task TrustedBridgeNativeApprovalExactSubmissionPollingAndReplacementFormOneAuthorityFlow()
    {
        using var f = new Fixture(); string supplied = "https://Example.COM.:443/a%2fb?x=1&x=2&q=a+b";
        string request = f.Request("web.fetchSubmit", new { url = supplied });
        await f.Receive(request, "https://evil.invalid/index.html"); Assert.Equal(0, f.Dialog.Shown);
        Task cancelled = f.Receive(request); Assert.Equal(Url, f.Dialog.Target!.Url);
        f.Dialog.Choice.SetResult(false); await cancelled; Assert.Equal(0, f.Posts);
        Assert.Contains("CANCELLED", Assert.Single(f.Sent)); f.Sent.Clear(); f.Dialog.Reset();
        request = f.Request("web.fetchSubmit", new { url = supplied });
        Task accepted = f.Receive(request); await f.Receive(request);
        await f.Receive(f.Request("web.fetchSubmit", new { url = supplied }));
        Assert.Equal(2, f.Dialog.Shown); Assert.Equal(0, f.Posts);
        Assert.Contains("QueueFull", Assert.Single(f.Sent)); f.Sent.Clear();
        Assert.True(f.Dialog.Choice.TrySetResult(true)); Assert.False(f.Dialog.Choice.TrySetResult(true)); await accepted;
        Assert.Equal(1, f.Posts); Assert.Equal(Url, f.PostedUrl); Assert.NotEqual(Guid.Empty, f.Id);
        var admission = JsonDocument.Parse(Assert.Single(f.Sent)).RootElement.GetProperty("result");
        Assert.Equal("ACCEPTED", admission.GetProperty("outcome").GetString());
        Assert.Equal(f.Id.ToString("D"), admission.GetProperty("operationId").GetString()); f.Sent.Clear();
        await f.Receive(f.Request("web.fetchGet", new { operationId = f.Id.ToString("D") }));
        var result = JsonDocument.Parse(Assert.Single(f.Sent)).RootElement.GetProperty("result").GetProperty("result");
        Assert.Equal("Public evidence", result.GetProperty("text").GetString()); Assert.Equal(Url, result.GetProperty("requestedUrl").GetString());
        Assert.DoesNotContain(new string('t', 43), f.Sent[0]); f.Sent.Clear();
        await f.Receive(f.Request("web.fetchCancel", new { operationId = f.Id.ToString("D") })); Assert.Equal(1, f.Deletes);
        f.Sent.Clear(); f.Dialog.Reset();
        string old = f.Bridge.SessionId; Task pending = f.Receive(f.Request("web.fetchSubmit", new { url = supplied }));
        f.Rotate(); Assert.True(f.Dialog.Token.IsCancellationRequested);
        f.Dialog.Choice.SetResult(true); await pending; Assert.Empty(f.Sent); Assert.Equal(1, f.Posts);
        await f.Receive(f.Request("web.fetchSubmit", new { url = supplied }, old)); Assert.Empty(f.Sent);
    }
    private static Task Sta(Func<Task> action)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async () =>
            { try { await action(); done.SetResult(); } catch (Exception e) { done.SetException(e); } finally { dispatcher.InvokeShutdown(); } }));
            Dispatcher.Run();
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); return done.Task;
    }
}
