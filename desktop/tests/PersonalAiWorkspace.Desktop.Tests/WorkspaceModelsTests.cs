using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.RuntimeClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WorkspaceModelsTests
{
    internal const string Handle = "33333333-3333-4333-8333-333333333333", Generation = "44444444-4444-4444-8444-444444444444";
    internal static readonly string Digest = new('a', 64);
    internal static object Status(long revision = 0) => new { configuredModel = "a:latest", configuredDigest = Digest, activeModel = (string?)null,
        activeDigest = (string?)null, selectionRevision = revision, installed = "TRUE", loaded = "UNKNOWN", ready = false,
        reserved = 0, queued = 0, executing = 0, draining = 0, switching = false, uncertain = false,
        recoveryGeneration = Generation, validationRequired = true, error = (string?)null };
    internal sealed class Confirmation : IModelConfirmation
    {
        internal TaskCompletionSource<bool> Choice = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal ModelIntent? Intent; internal CancellationToken Token;
        public Task<bool> ConfirmAsync(ModelIntent intent, CancellationToken token) { Intent = intent; Token = token; return Choice.Task; }
        internal void Reset() { Choice = new(TaskCreationOptions.RunContinuationsAsynchronously); Intent = null; }
    }
    internal sealed class Fixture : IWorkspaceNativeActions, IDisposable
    {
        internal const string Document = WorkspaceContentPolicy.ProductionOrigin + "/index.html";
        internal readonly Confirmation Dialog = new();
        internal readonly RuntimeClient Runtime;
        internal readonly WorkspaceModels Owner;
        internal readonly WorkspaceBridge Bridge;
        internal readonly List<string> Sent = [];
        internal int Posts, StatusReads; internal string? Posted; internal bool LosePost, Stale;
        internal TaskCompletionSource<HttpResponseMessage>? PendingPost;
        WorkspaceModels IWorkspaceNativeActions.Models => Owner;
        public Task<ShellStatus> StatusAsync(CancellationToken token) => throw new NotSupportedException();
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken token) => throw new NotSupportedException();
        internal Fixture()
        {
            Runtime = new(new Handler(async (request, token) => {
                Assert.Equal(new string('t', 43), request.Headers.Authorization!.Parameter);
                string route = request.RequestUri!.AbsolutePath;
                if (request.Method == HttpMethod.Post) {
                    Posts++; Posted = await request.Content!.ReadAsStringAsync(token);
                    if (LosePost) throw new HttpRequestException("private detail");
                    if (Stale) return Response(HttpStatusCode.Conflict, "{\"code\":\"MODEL_SELECTION_REVISION_CONFLICT\",\"message\":\"private\",\"phase\":\"MODEL\"}");
                    Assert.Equal("/api/v1/models/" + (Dialog.Intent!.Action == ModelAction.RELEASE ? "release" : "selection"), route);
                    if (PendingPost is not null) return await PendingPost.Task;
                    return Response(HttpStatusCode.OK, JsonSerializer.Serialize(Status(1)));
                }
                if (route == "/api/v1/models/catalog") return Response(HttpStatusCode.OK, JsonSerializer.Serialize(new { selectionRevision = 0,
                    models = new[] { new { handle = Handle, model = "a:latest", digest = Digest, contextLimit = 32768, completion = true, localSourceVerified = true, providerDeclaredVision = false } } }));
                Assert.Equal("/api/v1/models/status", route); StatusReads++;
                return Response(HttpStatusCode.OK, JsonSerializer.Serialize(Status(Posts > 0 && !Stale ? 1 : 0)));
            }), () => new string('t', 43));
            Owner = new(Runtime, Dialog); Bridge = new(new WorkspaceContentPolicy(), this, Sent.Add); Rotate();
        }
        internal void Rotate() { Bridge.BeginDocument(Document); Bridge.Ready(Document); Sent.Clear(); }
        internal string Request(string method, object payload) => JsonSerializer.Serialize(new { version = 1, sessionId = Bridge.SessionId,
            requestId = Guid.NewGuid().ToString("D"), method, payload });
        internal Task Receive(string method, object payload) => Bridge.ReceiveAsync(Document, Document, Request(method, payload));
        internal object Payload(string action = "SWITCH", long revision = 0) => new { action, handle = Handle, expectedSelectionRevision = revision, recoveryGeneration = (string?)null };
        internal async Task Inspect() { await Receive("models.inspect", new { }); Sent.Clear(); }
        public void Dispose() { Bridge.Dispose(); Runtime.Dispose(); }
    }
    [Fact] public async Task NativeAuthorityFreezesExactIntentRejectsStaleAndReplacementConsumesOnceAndReconcilesOnlyGet()
    {
        using var f = new Fixture(); await f.Inspect();
        await f.Bridge.ReceiveAsync("https://evil.invalid/", Fixture.Document, f.Request("models.mutate", f.Payload()));
        Assert.Null(f.Dialog.Intent);
        Task cancelled = f.Receive("models.mutate", f.Payload()); f.Dialog.Choice.SetResult(false); await cancelled;
        Assert.Equal(0, f.Posts); Assert.Contains("CANCELLED", Assert.Single(f.Sent)); f.Sent.Clear(); f.Dialog.Reset();
        await f.Receive("models.mutate", f.Payload(revision: 1)); Assert.Contains("ModelSelectionRevisionConflict", Assert.Single(f.Sent)); Assert.Null(f.Dialog.Intent);
        f.Sent.Clear(); Task replaced = f.Receive("models.mutate", f.Payload()); var frozen = f.Dialog.Intent!;
        Assert.Equal("a:latest", frozen.CandidateModel); Assert.Equal(Digest, frozen.CandidateDigest); Assert.Null(frozen.ExpectedActiveModel);
        Assert.False(frozen.ExternalConfirmed); f.Rotate(); Assert.True(f.Dialog.Token.IsCancellationRequested);
        f.Dialog.Choice.SetResult(true); await replaced; Assert.Empty(f.Sent); Assert.Equal(0, f.Posts);
        f.Dialog.Reset(); await f.Inspect(); f.LosePost = true;
        Task unknown = f.Receive("models.mutate", f.Payload("RELEASE")); Assert.True(f.Dialog.Intent!.ExternalConfirmed);
        Assert.Equal(ModelAction.RELEASE, f.Dialog.Intent.Action); int reads = f.StatusReads;
        f.Dialog.Choice.SetResult(true); await unknown;
        Assert.Equal(1, f.Posts); Assert.Equal(reads + 1, f.StatusReads); Assert.Contains("UNKNOWN", Assert.Single(f.Sent));
        using var body = JsonDocument.Parse(f.Posted!);
        Assert.Equal(new[] { "action", "catalogHandle", "candidateModel", "candidateDigest", "expectedSelectionRevision", "expectedActiveModel", "expectedActiveDigest", "recoveryGeneration", "externalConfirmed" }, body.RootElement.EnumerateObject().Select(p => p.Name));
        Assert.Equal("RELEASE", body.RootElement.GetProperty("action").GetString());
        f.Sent.Clear(); await f.Receive("models.mutate", f.Payload()); Assert.Equal(1, f.Posts); Assert.Contains("ModelCatalogStale", Assert.Single(f.Sent));
        using var stale = new Fixture { Stale = true }; await stale.Inspect();
        Task rejected = stale.Receive("models.mutate", stale.Payload()); stale.Dialog.Choice.SetResult(true); await rejected;
        Assert.Contains("ModelSelectionRevisionConflict", Assert.Single(stale.Sent)); Assert.Equal(1, stale.Posts);
        using var late = new Fixture { PendingPost = new(TaskCreationOptions.RunContinuationsAsynchronously) }; await late.Inspect();
        Task pending = late.Receive("models.mutate", late.Payload()); late.Dialog.Choice.SetResult(true);
        while (late.Posts == 0) await Task.Yield(); late.Rotate(); late.PendingPost.SetResult(Response(HttpStatusCode.OK, JsonSerializer.Serialize(Status(1))));
        await pending; Assert.Empty(late.Sent); Assert.Equal(1, late.Posts);
    }
    [Fact] public async Task UnansweredModelIntentExpiresAtSixtySecondsWithoutPostOrLateAuthority()
    {
        using var f = new Fixture(); await f.Inspect(); Assert.Equal(TimeSpan.FromSeconds(60), WorkspaceModels.ConfirmationLifetime);
        var elapsed = System.Diagnostics.Stopwatch.StartNew(); Task pending = f.Receive("models.mutate", f.Payload());
        await pending.WaitAsync(TimeSpan.FromSeconds(65)); Assert.True(elapsed.Elapsed >= TimeSpan.FromSeconds(59));
        Assert.True(f.Dialog.Token.IsCancellationRequested); Assert.Contains("CANCELLED", Assert.Single(f.Sent));
        f.Dialog.Choice.SetResult(true); Assert.Equal(0, f.Posts);
    }
    [Fact] public Task OwnedModelWindowHasDefaultFocusedCancelExactDigestWarningAndSeparateExternalConsent() => Sta(() => {
        var host = new Window { Width = 200, Height = 100, ShowInTaskbar = false }; host.Show();
        try {
            foreach (var action in new[] { ModelAction.SWITCH, ModelAction.RELEASE, ModelAction.RECOVER }) {
                var intent = new ModelIntent(action, Handle, "a:latest", Digest, 0, null, null, action == ModelAction.RECOVER ? Generation : null, action != ModelAction.SWITCH);
                var dialog = new ModelConfirmationWindow(host, intent);
                _ = dialog.Dispatcher.BeginInvoke(new Action(() => {
                    var panel = Assert.IsType<StackPanel>(Assert.IsType<ScrollViewer>(dialog.Content).Content);
                    Assert.Contains(Digest, panel.Children.OfType<TextBox>().First().Text);
                    Assert.All(panel.Children.OfType<TextBox>(), field => Assert.True(field.IsReadOnly));
                    var buttons = panel.Children.OfType<StackPanel>().Single().Children.OfType<Button>().ToArray();
                    Assert.True(buttons[0].IsCancel); Assert.True(buttons[0].IsDefault); Assert.True(buttons[0].IsKeyboardFocused); Assert.False(buttons[1].IsDefault);
                    if (action == ModelAction.SWITCH) { Assert.Empty(panel.Children.OfType<CheckBox>()); Assert.True(buttons[1].IsEnabled); Assert.Contains(panel.Children.OfType<TextBlock>(), field => field.Text == ModelConfirmationWindow.EvictionWarning); }
                    else { Assert.False(buttons[1].IsEnabled); Assert.NotEqual(true, Assert.Single(panel.Children.OfType<CheckBox>()).IsChecked); }
                    dialog.Close();
                }), DispatcherPriority.ApplicationIdle);
                Assert.NotEqual(true, dialog.ShowDialog());
            }
        } finally { host.Close(); }
        return Task.CompletedTask;
    });
    private static Task Sta(Func<Task> action)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() => {
            var dispatcher = Dispatcher.CurrentDispatcher; SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async () => { try { await action(); done.SetResult(); } catch (Exception e) { done.SetException(e); } finally { dispatcher.InvokeShutdown(); } }));
            Dispatcher.Run();
        }); thread.SetApartmentState(ApartmentState.STA); thread.Start(); return done.Task;
    }
}
