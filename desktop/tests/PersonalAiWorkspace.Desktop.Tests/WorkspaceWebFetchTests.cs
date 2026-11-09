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
        internal bool LosePost, LoseGet;
        internal TaskCompletionSource<HttpResponseMessage>? PendingGet;
        internal int Saves, SaveReads;
        internal string? SaveRequest;
        internal readonly string SavedDocument = Guid.NewGuid().ToString("D");
        internal bool LoseSave, LoseSaveRead;
        internal TaskCompletionSource? HoldSave;
        WorkspaceWebFetch IWorkspaceNativeActions.WebFetch => Web;
        WorkspaceKnowledge IWorkspaceNativeActions.Knowledge => Knowledge;
        private sealed class NoFilePicker : IKnowledgeSourceFiles
        { public Task<KnowledgeSourceFile?> PickAsync(CancellationToken token) => throw new InvalidOperationException("Web capture must not pick a local file."); }
        internal object SavedJob() => new { requestId = SaveRequest, documentId = SavedDocument, state = "READY", errorCode = (string?)null, sourceRevision = "1" };
        public Task<ShellStatus> StatusAsync(CancellationToken token) => throw new NotSupportedException();
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken token) => throw new NotSupportedException();
        internal Fixture()
        {
            Runtime = new(new Handler(async (request, ct) =>
            {
                Assert.Equal(new string('t', 43), request.Headers.Authorization!.Parameter);
                if(request.RequestUri!.AbsolutePath.EndsWith("/knowledge")) {
                    Assert.Equal(HttpMethod.Post,request.Method);Saves++;
                    using var body=JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                    Assert.Equal(new[]{"requestId"},body.RootElement.EnumerateObject().Select(p=>p.Name));
                    SaveRequest=body.RootElement.GetProperty("requestId").GetString();
                    if(HoldSave is not null)await HoldSave.Task;
                    if(LoseSave)throw new HttpRequestException();
                    return Response(HttpStatusCode.OK,JsonSerializer.Serialize(SavedJob()));
                }
                if(request.RequestUri.AbsolutePath.StartsWith("/api/v1/knowledge/imports/")) {
                    Assert.Equal(HttpMethod.Get,request.Method);Assert.EndsWith(SaveRequest!,request.RequestUri.AbsolutePath);SaveReads++;
                    if(LoseSaveRead)throw new HttpRequestException();
                    return Response(HttpStatusCode.OK,JsonSerializer.Serialize(SavedJob()));
                }
                if (request.Method == HttpMethod.Post)
                {
                    Posts++;
                    using var parsed = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                    Assert.Equal(new[] { "operationId", "url" }, parsed.RootElement.EnumerateObject().Select(p => p.Name));
                    Id = Guid.ParseExact(parsed.RootElement.GetProperty("operationId").GetString()!, "D");
                    PostedUrl = parsed.RootElement.GetProperty("url").GetString();
                    if (LosePost) throw new HttpRequestException("private transport body");
                    return Reply(Id, Envelope(Id), HttpStatusCode.Accepted);
                }
                Assert.Equal($"/api/v1/web/fetches/{Id:D}", request.RequestUri!.AbsolutePath);
                if (request.Method == HttpMethod.Get)
                {
                    Gets++; if (LoseGet) throw new HttpRequestException("private DNS details");
                    if (PendingGet is not null) return await PendingGet.Task;
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
    [Fact] public async Task WebCaptureOnlyAcceptsOwnedObservedResultsAndReusesOneImportRequest()
    {
        using var f=new Fixture();
        await f.Receive(f.Request("web.saveKnowledge",new{operationId=Guid.NewGuid().ToString("D")}));
        Assert.Equal(0,f.Saves);Assert.Contains("WebFetchNotFound",Assert.Single(f.Sent));f.Sent.Clear();
        var submit=f.Receive(f.Request("web.fetchSubmit",new{url=Url}));f.Dialog.Choice.SetResult(true);await submit;f.Sent.Clear();
        await f.Receive(f.Request("web.saveKnowledge",new{operationId=f.Id.ToString("D")}));
        Assert.Equal(0,f.Saves);Assert.Contains("WebContentInvalid",Assert.Single(f.Sent));f.Sent.Clear();
        await f.Receive(f.Request("web.fetchGet",new{operationId=f.Id.ToString("D")}));f.Sent.Clear();
        await f.Receive(f.Request("web.saveKnowledge",new{operationId=f.Id.ToString("D"),text="forged body"}));
        Assert.Equal(0,f.Saves);f.Sent.Clear();
        await f.Receive(f.Request("web.saveKnowledge",new{operationId=f.Id.ToString("D")}));
        Assert.Equal(1,f.Saves);Assert.Contains("ACCEPTED",Assert.Single(f.Sent));
        Assert.Equal(1,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));string request=f.SaveRequest!;f.Sent.Clear();
        await f.Receive(f.Request("web.saveKnowledge",new{operationId=f.Id.ToString("D")}));
        await f.Receive(f.Request("web.knowledgeState",new{operationId=f.Id.ToString("D")}));
        Assert.Equal(1,f.Saves);Assert.Equal(2,f.SaveReads);Assert.Equal(request,f.SaveRequest);
        f.Rotate();await f.Receive(f.Request("web.saveKnowledge",new{operationId=f.Id.ToString("D")}));
        Assert.Equal(1,f.Saves);Assert.Equal(0,f.Knowledge.AuthorizationCount(f.Bridge.SessionId));
    }
    [Fact] public async Task UnknownWebSaveIsReadOnlyReconciledAndLateSaveCannotAuthorizeNewSession()
    {
        using var f=new Fixture();
        var submit=f.Receive(f.Request("web.fetchSubmit",new{url=Url}));f.Dialog.Choice.SetResult(true);await submit;
        await f.Receive(f.Request("web.fetchGet",new{operationId=f.Id.ToString("D")}));f.Sent.Clear();
        f.LoseSave=true;f.LoseSaveRead=true;
        await f.Receive(f.Request("web.saveKnowledge",new{operationId=f.Id.ToString("D")}));
        Assert.Equal(1,f.Saves);Assert.Equal(1,f.SaveReads);Assert.Contains("UNKNOWN",Assert.Single(f.Sent));f.Sent.Clear();
        f.LoseSaveRead=false;
        await f.Receive(f.Request("web.knowledgeState",new{operationId=f.Id.ToString("D")}));
        Assert.Equal(1,f.Saves);Assert.Equal(2,f.SaveReads);Assert.Contains("ACCEPTED",Assert.Single(f.Sent));
        using var late=new Fixture();
        var accepted=late.Receive(late.Request("web.fetchSubmit",new{url=Url}));late.Dialog.Choice.SetResult(true);await accepted;
        await late.Receive(late.Request("web.fetchGet",new{operationId=late.Id.ToString("D")}));late.Sent.Clear();late.HoldSave=new();
        var saving=late.Receive(late.Request("web.saveKnowledge",new{operationId=late.Id.ToString("D")}));
        Assert.Equal(1,late.Saves);late.Rotate();late.HoldSave.SetResult();await saving;
        Assert.Empty(late.Sent);Assert.Equal(0,late.Knowledge.AuthorizationCount(late.Bridge.SessionId));
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
    [Fact] public async Task UncertainPostReconcilesSameIdWithoutReplayAndLateReadCannotAuthorizeReplacement()
    {
        using var f = new Fixture(); f.LosePost = true;
        Task submit = f.Receive(f.Request("web.fetchSubmit", new { url = Url })); f.Dialog.Choice.SetResult(true); await submit;
        Assert.Equal(1, f.Posts); Assert.Equal(1, f.Gets); Assert.Contains("ACCEPTED", Assert.Single(f.Sent));
        f.PendingGet = new(TaskCreationOptions.RunContinuationsAsynchronously); f.Sent.Clear();
        Task read = f.Receive(f.Request("web.fetchGet", new { operationId = f.Id.ToString("D") }));
        f.Rotate(); f.PendingGet.SetResult(Reply(f.Id, Envelope(f.Id, "SUCCEEDED", Result()))); await read;
        Assert.Empty(f.Sent);
        using var unknown = new Fixture { LosePost = true, LoseGet = true };
        Task uncertain = unknown.Receive(unknown.Request("web.fetchSubmit", new { url = Url }));
        unknown.Dialog.Choice.SetResult(true); await uncertain;
        Assert.Contains("UNKNOWN", Assert.Single(unknown.Sent)); Assert.Equal(1, unknown.Posts); Assert.Equal(1, unknown.Gets);
        unknown.LoseGet = false; unknown.Sent.Clear();
        await unknown.Receive(unknown.Request("web.fetchGet", new { operationId = unknown.Id.ToString("D") }));
        Assert.Contains("SUCCEEDED", Assert.Single(unknown.Sent)); Assert.Equal(1, unknown.Posts);
    }
    [Fact] public async Task ApprovalAndSessionInvalidationHaveOneSerializedWinner()
    {
        // Race an already queued approval completion against invalidation.
        using var denied = new Fixture(); var task = denied.Web.SubmitAsync(denied.Bridge.SessionId, Url, default);
        denied.Dialog.Choice.SetResult(true); denied.Web.EndSession(denied.Bridge.SessionId);
        // Depending on scheduling approval may already have consumed: at most one POST, no late authority.
        try { await task; } catch (OperationCanceledException) { }
        Assert.InRange(denied.Posts, 0, 1);
        int afterInvalidation = denied.Posts;
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => denied.Web.SubmitAsync(denied.Bridge.SessionId, Url, default));
        Assert.Equal(afterInvalidation, denied.Posts);
        // Deterministic invalidation-first ordering must produce zero POST.
        using var first = new Fixture(); var pending = first.Web.SubmitAsync(first.Bridge.SessionId, Url, default);
        first.Web.EndSession(first.Bridge.SessionId); first.Dialog.Choice.SetResult(true);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending); Assert.Equal(0, first.Posts);
    }
    [Fact] public async Task UnansweredNativeIntentExpiresAtSixtySecondsAndLateAllowCannotPost()
    {
        using var f = new Fixture();
        Assert.Equal(TimeSpan.FromSeconds(60), WorkspaceWebFetch.ConfirmationLifetime);
        var elapsed = System.Diagnostics.Stopwatch.StartNew();
        Task submit = f.Receive(f.Request("web.fetchSubmit", new { url = Url }));
        await submit.WaitAsync(TimeSpan.FromSeconds(65));
        Assert.True(elapsed.Elapsed >= TimeSpan.FromSeconds(59));
        Assert.True(f.Dialog.Token.IsCancellationRequested); Assert.Contains("CANCELLED", Assert.Single(f.Sent));
        f.Dialog.Choice.SetResult(true); Assert.Equal(0, f.Posts); Assert.Equal(0, f.Gets); Assert.Equal(0, f.Deletes);
    }
    [Fact] public Task OwnedNativeDialogUsesFullCapturedTargetSafeDefaultAndCancellationClosesIt() => Sta(async () =>
    {
        var host = new Window { Width = 200, Height = 100, ShowInTaskbar = false }; host.Show();
        try
        {
            var target = WebFetchTarget.Parse("https://Example.COM.:443/" + new string('a', 500));
            var native = new NativeWebFetchConfirmation(() => host);
            using var cancellation = new CancellationTokenSource();
            _ = host.Dispatcher.BeginInvoke(new Action(() =>
            {
                var window = Assert.IsType<WebFetchConfirmationWindow>(Assert.Single(host.OwnedWindows.Cast<Window>()));
                Assert.Equal("Public Web access / 公共网络访问", window.Title);
                var panel = Assert.IsType<StackPanel>(window.Content);
                var fields = panel.Children.OfType<TextBox>().ToArray();
                Assert.Equal(new[] { target.Hostname, target.Url }, fields.Select(t => t.Text));
                Assert.All(fields, field => { Assert.True(field.IsReadOnly); Assert.Equal(TextWrapping.Wrap, field.TextWrapping); });
                var buttons = panel.Children.OfType<StackPanel>().Single().Children.OfType<Button>().ToArray();
                Assert.True(buttons[0].IsCancel); Assert.True(buttons[0].IsDefault); Assert.True(buttons[0].IsKeyboardFocused);
                Assert.False(buttons[1].IsDefault); cancellation.Cancel();
            }), DispatcherPriority.ApplicationIdle);
            Assert.False(await native.ConfirmAsync(target, cancellation.Token)); Assert.Empty(host.OwnedWindows.Cast<Window>());
            var close = new WebFetchConfirmationWindow(host, target);
            _ = close.Dispatcher.BeginInvoke(new Action(close.Close), DispatcherPriority.ApplicationIdle);
            Assert.NotEqual(true, close.ShowDialog());
        }
        finally { host.Close(); }
    });
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
