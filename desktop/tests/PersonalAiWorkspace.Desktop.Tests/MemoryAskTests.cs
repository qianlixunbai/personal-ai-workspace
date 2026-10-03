using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.MemoryClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class MemoryAskTests
{
    private static readonly Guid TaskId = Guid.NewGuid();
    private static string Envelope(string version = "memory-ask-v1", string status = "QUEUED", string? error = null) => JsonSerializer.Serialize(new
    {
        taskId = TaskId, capability = "ask", status,
        profile = new { id = "chat.balanced", version = "m1.5-1", locality = "LOCAL" }, promptVersion = version,
        createdAt = "2026-10-03T10:00:00Z", finishedAt = status is "QUEUED" or "RUNNING" ? null : "2026-10-03T10:00:01Z",
        result = status == "SUCCEEDED" ? "synthetic answer" : null,
        error = error is null ? null : new { code = error, message = PrivateContent, phase = "EXECUTION" }
    });
    private static HttpResponseMessage Accepted(string body)
    {
        var response = Response(body, HttpStatusCode.Accepted);
        response.Headers.Location = new Uri($"/api/v1/tasks/{TaskId:D}", UriKind.Relative); return response;
    }
    [Fact]
    public async Task MemoryAdmissionOnlySendsReferencesAndPollsWithExactVersionWhileEmptyUsesOrdinaryAsk()
    {
        foreach (bool selected in new[] { true, false })
        {
            int calls = 0;
            string version = selected ? "memory-ask-v1" : "ask-v1";
            using var client = new RuntimeClient(new Handler(async (request, token) =>
            {
                calls++;
                if (request.Method == HttpMethod.Post)
                {
                    Assert.Equal(selected ? "/api/v1/memory/ask/tasks" : "/api/v1/ask/tasks", request.RequestUri!.AbsolutePath);
                    string raw = await request.Content!.ReadAsStringAsync(token); Safe(raw.Replace(Token, ""));
                    using var body = JsonDocument.Parse(raw); var root = body.RootElement;
                    Assert.Equal(selected ? 3 : 2, root.EnumerateObject().Count());
                    Assert.Equal("synthetic question", root.GetProperty("question").GetString());
                    if (selected)
                    {
                        var reference = Assert.Single(root.GetProperty("memories").EnumerateArray());
                        Assert.Equal(2, reference.EnumerateObject().Count()); Assert.Equal(Id, reference.GetProperty("id").GetGuid());
                        Assert.Equal(3, reference.GetProperty("revision").GetInt64());
                    }
                    Assert.False(raw.Contains(PrivateContent, StringComparison.Ordinal));
                    return Accepted(Envelope(version));
                }
                return Response(Envelope(version, "SUCCEEDED"));
            }), () => Token);
            var operation = new AssistantOperation(client, TimeSpan.FromMilliseconds(1));
            var result = await operation.RunAsync(new(AssistantAction.Ask, "synthetic question"), _ => { }, default,
                selected ? [new(Id, 3)] : []);
            Assert.True(operation.Accepted); Assert.Equal(TaskState.SUCCEEDED, result.Status); Assert.Equal(2, calls);
        }
        Safe(new MemorySelection(Id, 3, PrivateTitle, MemoryType.PROJECT_NOTE).ToString());
        Safe(new MemoryAskInput(PrivateContent, [new(Id, 3)]).ToString());
    }
    [Fact]
    public async Task WrongPromptVersionFailsClosedAtAdmissionPollAndCancel()
    {
        foreach (string stage in new[] { "ordinary", "memory", "poll", "cancel" })
        {
            using var client = new RuntimeClient(new Handler((request, _) => Task.FromResult(
                request.Method == HttpMethod.Post ? Accepted(Envelope(stage is "memory" or "ordinary" ? (stage == "memory" ? "ask-v1" : "memory-ask-v1") : "memory-ask-v1"))
                    : Response(Envelope("ask-v1", "SUCCEEDED")))), () => Token);
            var operation = new AssistantOperation(client, TimeSpan.FromMilliseconds(1));
            if (stage == "cancel") operation.RequestCancel();
            var failure = await Assert.ThrowsAsync<DesktopException>(() => operation.RunAsync(new(AssistantAction.Ask, "q"), _ => { }, default,
                stage == "ordinary" ? [] : [new(Id, 1)]));
            Assert.Equal(DesktopError.InvalidResponse, failure.Error);
        }
    }
    [Fact]
    public async Task MemoryErrorsAreStrictEndpointSpecificAndNeverExposeRawContext()
    {
        (HttpStatusCode Status, string Code, DesktopError Expected)[] cases =
        [
            (HttpStatusCode.Conflict, "MEMORY_SELECTION_STALE", DesktopError.MemorySelectionStale),
            (HttpStatusCode.BadRequest, "INVALID_REQUEST", DesktopError.MemoryAskBudget),
            (HttpStatusCode.ServiceUnavailable, "MEMORY_STORAGE_UNAVAILABLE", DesktopError.MemoryStorageUnavailable),
            (HttpStatusCode.ServiceUnavailable, "MEMORY_SCHEMA_UNSUPPORTED", DesktopError.MemorySchemaUnsupported),
            (HttpStatusCode.TooManyRequests, "QUEUE_FULL", DesktopError.QueueFull),
            (HttpStatusCode.ServiceUnavailable, "PROVIDER_UNAVAILABLE", DesktopError.ProviderUnavailable),
            (HttpStatusCode.ServiceUnavailable, "MODEL_UNAVAILABLE", DesktopError.ModelUnavailable),
            (HttpStatusCode.Unauthorized, "UNAUTHORIZED", DesktopError.Unauthorized),
            (HttpStatusCode.Conflict, "MEMORY_REVISION_CONFLICT", DesktopError.InvalidResponse),
            (HttpStatusCode.BadRequest, "MEMORY_SELECTION_STALE", DesktopError.InvalidResponse)
        ];
        foreach (var row in cases)
        {
            using var client = Client(Error(row.Code), row.Status);
            var operation = new AssistantOperation(client);
            var failure = await Assert.ThrowsAsync<DesktopException>(() => operation.RunAsync(new(AssistantAction.Ask, "q"), _ => { }, default, [new(Id, 1)]));
            Assert.Equal(row.Expected, failure.Error); Assert.False(operation.Accepted); Safe(failure.ToString());
        }
        foreach (var references in new IReadOnlyList<MemoryReference>[] { [], [new(Id, 0)], [new(Guid.Empty, 1)], [new(Id, 1), new(Id, 1)],
            Enumerable.Range(0, 5).Select(_ => new MemoryReference(Guid.NewGuid(), 1)).ToArray() })
            Assert.Throws<DesktopException>(() => new MemoryAskInput("q", references).Validate());
    }
    [Theory]
    [InlineData("SUCCEEDED", null)]
    [InlineData("FAILED", "PROVIDER_UNAVAILABLE")]
    [InlineData("CANCELLED", "TASK_CANCELLED")]
    [InlineData("TIMED_OUT", "TASK_TIMEOUT")]
    public Task PerTurnStateClearsOnTerminalButAdmissionFailurePreservesAndStaleBlocksRetry(string state, string? error) => StaAsync(async () =>
    {
        using var client = new RuntimeClient(new Handler((request, _) => Task.FromResult(
            request.Method == HttpMethod.Post ? Accepted(Envelope()) : Response(Envelope(status: state, error: error)))), () => Token);
        var window = new AssistantWindow(new Controller(), client); window.Show(); window.ActionSelector.SelectedIndex = 2;
        Assert.Equal(Visibility.Visible, window.MemoryAskPanel.Visibility); Assert.Empty(window.MemoryReferences);
        window.ApplyMemorySelection([new(Id, 1, PrivateTitle, MemoryType.PROJECT_NOTE)]);
        Assert.Contains("1 selected", window.MemoryCountText.Text); Assert.Contains(PrivateTitle, window.MemorySelectionText.Text);
        window.MemoryAdmissionFailed(DesktopError.MemoryAskBudget); window.MemoryOperationEnded(false); Assert.Single(window.MemoryReferences);
        window.MemoryAdmissionFailed(DesktopError.MemorySelectionStale); Assert.True(window.MemoryNeedsReview); Assert.False(window.TranslateButton.IsEnabled);
        window.ApplyMemorySelection([new(Id, 2, PrivateTitle, MemoryType.PROJECT_NOTE)]); Assert.False(window.MemoryNeedsReview);
        var operation = new AssistantOperation(client, TimeSpan.FromMilliseconds(1));
        if (state == "CANCELLED") operation.RequestCancel();
        await operation.RunAsync(new(AssistantAction.Ask, "q"), _ => { }, default, window.MemoryReferences);
        window.MemoryOperationEnded(operation.Accepted); Assert.Empty(window.MemoryReferences);
        window.ApplyMemorySelection([new(Id, 1, PrivateTitle, MemoryType.PROJECT_NOTE)]);
        window.ActionSelector.SelectedIndex = 1; Assert.Empty(window.MemoryReferences); Assert.Equal(Visibility.Collapsed, window.MemoryAskPanel.Visibility);
        window.ActionSelector.SelectedIndex = 2; window.ApplyMemorySelection([new(Id, 1, PrivateTitle, MemoryType.PROJECT_NOTE)]);
        window.ClearMemorySelection(); Assert.Empty(window.MemoryReferences);
        window.ApplyMemorySelection([new(Id, 1, PrivateTitle, MemoryType.PROJECT_NOTE)]); window.Close(); Assert.Empty(window.MemoryReferences);
    });
    [Fact]
    public Task SelectorIsReadOnlyActiveExplicitSearchPreviewMaxFourAndCancelLeavesOwnerUntouched() => StaAsync(async () =>
    {
        var ids = Enumerable.Range(0, 5).Select(_ => Guid.NewGuid()).ToArray(); int reads = 0;
        using var client = new RuntimeClient(new Handler((request, _) =>
        {
            Assert.Equal(HttpMethod.Get, request.Method); reads++;
            string body;
            if (request.RequestUri!.AbsolutePath == "/api/v1/memory/items")
            {
                Assert.Contains("status=ACTIVE", request.RequestUri.Query);
                body = Page(ids.Select(x => Item(x)).ToArray());
            }
            else body = Item(Guid.Parse(request.RequestUri.AbsolutePath.Split('/').Last()));
            return Task.FromResult(Response(body));
        }), () => Token);
        var picker = new MemorySelectionWindow(client); await picker.RefreshAsync();
        picker.SearchBox.Text = PrivateTitle; int before = reads; Assert.Equal(before, reads);
        await picker.RefreshAsync(search: true); Assert.Equal(before + 1, reads);
        foreach (var item in picker.MemoryList.Items.Cast<MemoryItem>().ToArray())
        {
            await picker.PreviewAsync(item); Assert.Equal(PrivateTitle, picker.PreviewTitle.Text); Assert.Equal(PrivateContent, picker.PreviewContent.Text);
            picker.AddPreview(); picker.AddPreview();
        }
        Assert.Equal(4, picker.SelectedList.Items.Count); Assert.False(picker.AddButton.IsEnabled);
        picker.ConfirmSelection(); Assert.Equal(4, picker.Selection!.Count); Assert.All(picker.Selection, x => Assert.Equal(1, x.Revision));
        Assert.True(picker.IsClosed); Assert.Empty(picker.PreviewContent.Text); Assert.Empty(picker.MemoryList.Items);
        var owner = new AssistantWindow(new Controller(), client); owner.ActionSelector.SelectedIndex = 2;
        owner.ApplyMemorySelection([new(Id, 1, PrivateTitle, MemoryType.PROJECT_NOTE)]);
        var canceled = new MemorySelectionWindow(client); await canceled.RefreshAsync(); canceled.Close(); Assert.Null(canceled.Selection);
        Assert.Single(owner.MemoryReferences); owner.Close();
    });
    [Fact]
    public Task ClosedPickerCannotBeRepopulatedByLateHttpResponse() => StaAsync(async () =>
    {
        var pending = new TaskCompletionSource<HttpResponseMessage>(); CancellationToken requestToken = default;
        using var client = new RuntimeClient(new Handler((_, token) => { requestToken = token; return pending.Task; }), () => Token);
        var picker = new MemorySelectionWindow(client); var request = picker.RefreshAsync(); picker.Close();
        Assert.True(requestToken.IsCancellationRequested); pending.SetResult(Response(Page())); await request;
        Assert.True(picker.IsClosed); Assert.Empty(picker.MemoryList.Items); Assert.Empty(picker.PreviewContent.Text); Assert.Null(picker.Selection);
    });
    [Fact]
    public Task TransportBeforeAcceptancePreservesSelectionAndLatePreviewCannotReturnIt() => StaAsync(async () =>
    {
        using var unavailable = new RuntimeClient(new Handler((_, _) => throw new HttpRequestException()), () => Token);
        var owner = new AssistantWindow(new Controller(), unavailable); owner.ActionSelector.SelectedIndex = 2;
        owner.ApplyMemorySelection([new(Id, 1, PrivateTitle, MemoryType.PROJECT_NOTE)]);
        var operation = new AssistantOperation(unavailable);
        var failure = await Assert.ThrowsAsync<DesktopException>(() => operation.RunAsync(new(AssistantAction.Ask, "q"), _ => { }, default, owner.MemoryReferences));
        Assert.Equal(DesktopError.RuntimeUnavailable, failure.Error); Assert.False(operation.Accepted);
        owner.MemoryAdmissionFailed(failure.Error); owner.MemoryOperationEnded(operation.Accepted);
        Assert.Single(owner.MemoryReferences); owner.Close();
        var pending = new TaskCompletionSource<HttpResponseMessage>();
        using var runtime = new RuntimeClient(new Handler((request, _) => request.RequestUri!.AbsolutePath == "/api/v1/memory/items"
            ? Task.FromResult(Response(Page())) : pending.Task), () => Token);
        var picker = new MemorySelectionWindow(runtime); await picker.RefreshAsync();
        var preview = picker.PreviewAsync((MemoryItem)picker.MemoryList.Items[0]); picker.Close();
        pending.SetResult(Response(Item())); await preview; picker.AddPreview(); picker.ConfirmSelection();
        Assert.Null(picker.Selection); Assert.Empty(picker.PreviewTitle.Text); Assert.Empty(picker.PreviewContent.Text);
    });
    private sealed class Controller : IAssistantController
    {
        public bool Busy => false; public bool Exiting => false;
        public Task SubmitAsync() => Task.CompletedTask; public void CancelOperation() { }
        public Task CheckHealthAsync() => Task.CompletedTask; public Task ImportCredentialAsync(string file) => Task.CompletedTask;
        public void ForgetCredential() { }
    }
    private static Task StaAsync(Func<Task> action)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async () =>
            { try { await action(); done.SetResult(); } catch (Exception ex) { done.SetException(ex); } finally { dispatcher.InvokeShutdown(); } }));
            Dispatcher.Run();
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); return done.Task;
    }
}
