#if RUNTIME_LIFECYCLE_PROBE
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private sealed class LifecycleExchange(HttpMethod method, string path)
    {
        internal readonly TaskCompletionSource Entered = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal readonly TaskCompletionSource<HttpResponseMessage> Reply = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal CancellationToken Token;
        internal Task<HttpResponseMessage> Send(HttpRequestMessage request, CancellationToken token)
        {
            Assert.Equal(method, request.Method); Assert.Equal(path, request.RequestUri!.AbsolutePath);
            Token = token; Entered.SetResult(); return Reply.Task;
        }
    }
    private sealed class LifecycleHandler : HttpMessageHandler
    {
        private readonly Queue<LifecycleExchange> expected = new();
        internal int Requests;
        internal LifecycleExchange Expect(HttpMethod method, string path)
        { var exchange = new LifecycleExchange(method, path); expected.Enqueue(exchange); return exchange; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        { Requests++; return expected.Dequeue().Send(request, token); }
        internal void Drained() => Assert.Empty(expected);
    }
    private static HttpResponseMessage LifecycleReply(Guid id, string status, bool post = false)
    {
        var response = RuntimeClientTests.Response(post ? HttpStatusCode.Accepted : HttpStatusCode.OK, JsonSerializer.Serialize(new
        {
            taskId = id, capability = "translate", status,
            profile = new { id = "translate.fast", version = "fixture-1", locality = "LOCAL" }, promptVersion = "translate-v1",
            createdAt = "2026-10-31T00:00:00Z", finishedAt = status is "QUEUED" or "RUNNING" ? null : "2026-10-31T00:00:01Z",
            result = status == "SUCCEEDED" ? "Synthetic result " + id : null,
            error = status == "CANCELLED" ? new { code = "TASK_CANCELLED", message = "cancelled", phase = "CANCELLATION" } : null
        }));
        if (post) response.Headers.Location = new Uri($"/api/v1/tasks/{id:D}", UriKind.Relative);
        return response;
    }
    private static int RunX1ExecutionLifecycleFixture()
    {
        // Separate process: WPF permits only one Application. No OnStartup, real hotkeys,
        // Profile, native confirmation, WebView, JVM, or network-backed handler is started.
        using var single = new SingleInstance(".x1-lifecycle." + Guid.NewGuid().ToString("N"));
        var dispatcher = Dispatcher.CurrentDispatcher;
        var startup = new List<DispatcherOperation>();
        void ObserveStartup(object? sender, DispatcherHookEventArgs e)
        { if (e.Operation.Priority == DispatcherPriority.Send) startup.Add(e.Operation); }
        dispatcher.Hooks.OperationPosted += ObserveStartup;
        AssistantApp app;
        try { app = new AssistantApp(single); }
        finally { dispatcher.Hooks.OperationPosted -= ObserveStartup; }
        // Application schedules OnStartup in its constructor, even without Application.Run.
        // Abort only that constructor's startup operation before pumping the isolated UI.
        Assert.True(Assert.Single(startup).Abort());
        var fields = BindingFlags.Instance | BindingFlags.NonPublic;
        var runtimeField = typeof(AssistantApp).GetField("runtime", fields)!;
        using var unusedRuntime = (RuntimeClient)runtimeField.GetValue(app)!;
        var handler = new LifecycleHandler();
        using var runtime = new RuntimeClient(handler, () => new string('t', 43));
        runtimeField.SetValue(app, runtime);
        var window = new AssistantWindow(app, runtime) { ShowActivated = false };
        typeof(AssistantApp).GetField("window", fields)!.SetValue(app, window);
        SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
        int exit = 1;
        dispatcher.BeginInvoke(new Action(async () =>
        {
            try
            {
                window.Show();
                Assert.Null(app.Workspace); Assert.False(app.TrayVisible);
                await VerifyLifecycleAsync(app, window, handler);
                handler.Drained(); exit = 0;
                Console.WriteLine("X1_EXECUTION_LIFECYCLE_FIXTURE_PASS");
            }
            catch (Exception error) { Console.WriteLine(error); }
            finally { app.Cleanup(); window.Hide(); dispatcher.InvokeShutdown(); }
        }));
        Dispatcher.Run();
        return exit;
    }
    private static async Task VerifyLifecycleAsync(AssistantApp app, AssistantWindow window, LifecycleHandler handler)
    {
        static Task Bounded(Task task) => task.WaitAsync(TimeSpan.FromSeconds(4));
        void Idle()
        {
            Assert.False(app.Busy); Assert.True(window.TranslateButton.IsEnabled);
            Assert.False(window.CancelButton.IsEnabled);
        }
        void BusyHotkeysCannotCaptureOrSubmit()
        {
            int version = window.ContentVersion, requests = handler.Requests;
            foreach (int id in new[] { HotkeyRegistration.Id, HotkeyRegistration.QuickActionsId })
            {
                object[] args = [IntPtr.Zero, 0x0312, new IntPtr(id), IntPtr.Zero, false];
                typeof(AssistantApp).GetMethod("WindowMessage", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(app, args);
                Assert.True((bool)args[4]);
            }
            Assert.Equal(version, window.ContentVersion); Assert.Equal(requests, handler.Requests);
            Assert.Null(typeof(AssistantApp).GetField("activeCapture", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(app));
        }
        // Cancel before POST returns: retain POST, obtain UUID, then DELETE exactly that task.
        window.InputText.Text = "Synthetic pending admission";
        Guid pendingId = Guid.NewGuid();
        var post = handler.Expect(HttpMethod.Post, "/api/v1/translate/tasks");
        Task pending = app.SubmitAsync(); await Bounded(post.Entered.Task);
        Assert.True(app.Busy); Assert.False(window.TranslateButton.IsEnabled);
        int beforeCancel = window.ContentVersion;
        app.CancelOperation(); Assert.True(window.ContentVersion > beforeCancel);
        Assert.Empty(window.InputText.Text); Assert.False(window.CancelButton.IsEnabled);
        Assert.False(post.Token.IsCancellationRequested); Assert.False(pending.IsCompleted);
        BusyHotkeysCannotCaptureOrSubmit(); await app.SubmitAsync(); Assert.Equal(1, handler.Requests);
        var delete = handler.Expect(HttpMethod.Delete, $"/api/v1/tasks/{pendingId:D}");
        post.Reply.SetResult(LifecycleReply(pendingId, "QUEUED", true)); await Bounded(delete.Entered.Task);
        delete.Reply.SetResult(LifecycleReply(pendingId, "CANCELLED")); await Bounded(pending);
        Assert.Equal(ErrorText.For(DesktopError.Cancelled), window.StatusText.Text);
        Assert.Empty(window.ResultText.Text); Assert.False(window.CopyButton.IsEnabled); Idle();
        Assert.Equal(2, handler.Requests);
        Console.WriteLine("X1_PASS POST-pending-cancel-confirmed-and-busy-Q-T-deduplicated");

        // A successful Runtime terminal returned by DELETE wins over the user's cancel intent.
        window.InputText.Text = "Synthetic completed-before-cancel";
        Guid completedId = Guid.NewGuid();
        post = handler.Expect(HttpMethod.Post, "/api/v1/translate/tasks");
        var get = handler.Expect(HttpMethod.Get, $"/api/v1/tasks/{completedId:D}");
        pending = app.SubmitAsync(); post.Reply.SetResult(LifecycleReply(completedId, "RUNNING", true));
        await Bounded(get.Entered.Task); Assert.Equal("正在处理…", window.StatusText.Text);
        app.CancelOperation(); BusyHotkeysCannotCaptureOrSubmit();
        delete = handler.Expect(HttpMethod.Delete, $"/api/v1/tasks/{completedId:D}");
        get.Reply.SetResult(LifecycleReply(completedId, "RUNNING")); await Bounded(delete.Entered.Task);
        delete.Reply.SetResult(LifecycleReply(completedId, "SUCCEEDED")); await Bounded(pending);
        Assert.Equal("已完成，可复制结果。", window.StatusText.Text);
        Assert.Equal("Synthetic result " + completedId, window.ResultText.Text); Idle();
        Console.WriteLine("X1_PASS cancel-after-UUID-preserves-Runtime-success");

        // Close while a poll is in flight, reopen without submitting: suppress late progress,
        // success and transport error, then allow a fresh capture/task only after old work ends.
        foreach (bool lateError in new[] { false, true })
        {
            window.ClearText(); window.SetCapturedText("Synthetic source before close");
            Guid oldId = Guid.NewGuid();
            post = handler.Expect(HttpMethod.Post, "/api/v1/translate/tasks");
            get = handler.Expect(HttpMethod.Get, $"/api/v1/tasks/{oldId:D}");
            pending = app.SubmitAsync(); post.Reply.SetResult(LifecycleReply(oldId, "RUNNING", true));
            await Bounded(get.Entered.Task);
            Assert.Equal("正在处理…", window.StatusText.Text);
            int runningVersion = window.ContentVersion;
            window.Close(); Assert.False(window.IsVisible); Assert.True(window.ContentVersion > runningVersion);
            window.Show(); Assert.Empty(window.InputText.Text); Assert.Empty(window.ResultText.Text);
            string reopenedStatus = window.StatusText.Text;
            int requests = handler.Requests;
            BusyHotkeysCannotCaptureOrSubmit(); await app.SubmitAsync(); Assert.Equal(requests, handler.Requests);
            if (lateError)
            {
                delete = handler.Expect(HttpMethod.Delete, $"/api/v1/tasks/{oldId:D}");
                get.Reply.SetException(new HttpRequestException("Synthetic late transport error"));
                await Bounded(delete.Entered.Task); delete.Reply.SetResult(LifecycleReply(oldId, "CANCELLED"));
            }
            else
            {
                delete = handler.Expect(HttpMethod.Delete, $"/api/v1/tasks/{oldId:D}");
                get.Reply.SetResult(LifecycleReply(oldId, "RUNNING")); await Bounded(delete.Entered.Task);
                Assert.Equal(reopenedStatus, window.StatusText.Text);
                delete.Reply.SetResult(LifecycleReply(oldId, "SUCCEEDED"));
            }
            await Bounded(pending); Idle();
            Assert.Equal(reopenedStatus, window.StatusText.Text);
            Assert.Empty(window.InputText.Text); Assert.Empty(window.QuestionText.Text); Assert.Empty(window.ResultText.Text);
            window.ClearText(); window.SetCapturedText("Synthetic fresh capture");
            Guid newId = Guid.NewGuid();
            post = handler.Expect(HttpMethod.Post, "/api/v1/translate/tasks");
            pending = app.SubmitAsync(); post.Reply.SetResult(LifecycleReply(newId, "SUCCEEDED", true));
            await Bounded(pending); Idle();
            Assert.Equal("Synthetic fresh capture", window.InputText.Text);
            Assert.Equal("Synthetic result " + newId, window.ResultText.Text);
            Console.WriteLine("X1_PASS close-reopen-isolates-late-" + (lateError ? "error" : "progress-and-result") + "-then-new-capture");
        }
    }
}
#endif
