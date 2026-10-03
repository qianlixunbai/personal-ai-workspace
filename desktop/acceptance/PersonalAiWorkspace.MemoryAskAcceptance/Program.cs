using System.IO;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

namespace PersonalAiWorkspace.MemoryAskAcceptance;

internal static class Program
{
    private static readonly List<string> checks = [];
    private static string stage = "configuration";
    private static AssistantWindow? assistant;
    private static MemorySelectionWindow? picker;
    [STAThread]
    private static int Main()
    {
        int code = 1;
        var dispatcher = Dispatcher.CurrentDispatcher;
        SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
        dispatcher.BeginInvoke(new Action(async () =>
        {
            try { await RunAsync(); code = 0; Console.WriteLine(JsonSerializer.Serialize(new { result = "PASS", realWpf = true, realHttp = true, realOllama = true, checks })); }
            catch (Exception) { Console.WriteLine(JsonSerializer.Serialize(new { result = "FAIL", check = stage })); }
            finally { picker?.Close(); assistant?.Close(); dispatcher.InvokeShutdown(); }
        }));
        Dispatcher.Run(); return code;
    }
    private static void Require(bool condition, string check)
    { stage = check; if (!condition) throw new InvalidOperationException(); checks.Add(check); }
    private sealed class TraceHandler : DelegatingHandler
    {
        internal readonly List<string> Admissions = [];
        internal string? PromptVersion;
        internal int Accepted;
        public TraceHandler() : base(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false, UseCookies = false }) { }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            string path = request.RequestUri!.AbsolutePath;
            if (request.Method == HttpMethod.Post && path.EndsWith("/tasks", StringComparison.Ordinal))
            {
                Admissions.Add(path);
                using var payload = JsonDocument.Parse(await request.Content!.ReadAsByteArrayAsync(token));
                var root = payload.RootElement;
                if (path == "/api/v1/memory/ask/tasks")
                {
                    Require(root.EnumerateObject().Count() == 3 && root.GetProperty("memories").EnumerateArray()
                        .All(x => x.EnumerateObject().Count() == 2 && x.TryGetProperty("id", out _) && x.TryGetProperty("revision", out _)), "http-reference-only-body");
                }
                else Require(root.EnumerateObject().Count() == 2 && !root.TryGetProperty("memories", out _), "ordinary-http-question-profile-only");
            }
            var response = await base.SendAsync(request, token);
            if (response.StatusCode == HttpStatusCode.Accepted)
            {
                using var body = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(token));
                PromptVersion = body.RootElement.GetProperty("promptVersion").GetString(); Accepted++;
                Require(body.RootElement.GetProperty("capability").GetString() == "ask"
                    && body.RootElement.GetProperty("profile").GetProperty("id").GetString() == "chat.balanced", "shared-ask-task-profile");
            }
            return response;
        }
    }
    // Drive the production WPF event path, Core operation and shared window lifecycle helpers with a test-owned credential.
    private sealed class Controller(RuntimeClient runtime) : IAssistantController
    {
        internal AssistantWindow Window = null!;
        internal Task Active = Task.CompletedTask;
        internal RuntimeTask? Last;
        internal DesktopError? Error;
        public bool Busy { get; private set; }
        public bool Exiting => false;
        public Task SubmitAsync() { Active = SubmitCoreAsync(); return Active; }
        private async Task SubmitCoreAsync()
        {
            if (Busy || Window.MemoryNeedsReview) return;
            Busy = true; Window.SetBusy(true); Error = null; Last = null;
            var operation = new AssistantOperation(runtime);
            try
            {
                Last = await operation.RunAsync(new(Window.SelectedAction, Window.InputText.Text), _ => { }, default, Window.MemoryReferences);
                if (Last.Status == TaskState.SUCCEEDED) Window.ResultText.Text = Last.Result!;
            }
            catch (DesktopException ex) { Error = ex.Error; Window.MemoryAdmissionFailed(ex.Error); Window.StatusText.Text = ErrorText.For(ex.Error); }
            finally { Busy = false; Window.MemoryOperationEnded(operation.Accepted); Window.SetBusy(false); }
        }
        public void CancelOperation() { }
        public Task CheckHealthAsync() => Task.CompletedTask;
        public Task ImportCredentialAsync(string file) => Task.CompletedTask;
        public void ForgetCredential() { }
    }
    private static async Task IdlePickerAsync()
    {
        await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
        var until = DateTime.UtcNow.AddSeconds(12);
        while (!picker!.SearchButton.IsEnabled)
        { if (DateTime.UtcNow > until) throw new InvalidOperationException(); await Task.Delay(20); }
    }
    private static async Task RunAsync()
    {
        var tokenPath = Environment.GetEnvironmentVariable("M3C1_TEST_TOKEN_FILE") ?? throw new InvalidOperationException();
        string token = File.ReadAllText(tokenPath).Trim();
        string codename = "ORCHID-7319", content = "The synthetic project codename is " + codename + ".";
        var trace = new TraceHandler(); using var runtime = new RuntimeClient(trace, () => token);
        stage = "synthetic-create";
        var memory = await runtime.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, "Synthetic Project Context", content), default);
        var controller = new Controller(runtime);
        assistant = controller.Window = new AssistantWindow(controller, runtime); assistant.Show(); assistant.ActionSelector.SelectedIndex = 2;
        Require(assistant.MemoryReferences.Count == 0 && assistant.MemoryAskPanel.Visibility == Visibility.Visible, "ask-default-zero-memory");
        // Open the actual modal via its button; Dispatcher callback explicitly previews and selects using controls.
        var selectionDone = new TaskCompletionSource();
        _ = assistant.Dispatcher.BeginInvoke(new Action(async () =>
        {
            try
            {
                stage = "picker-open";
                picker = assistant.OwnedWindows.OfType<MemorySelectionWindow>().Single(); await IdlePickerAsync();
                Require(picker.MemoryList.Items.Count == 1, "active-selector-list");
                picker.MemoryList.SelectedIndex = 0; await IdlePickerAsync();
                Require(picker.PreviewTitle.Text == memory.Title && picker.PreviewContent.Text == content, "complete-synthetic-preview");
                picker.AddButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Require(picker.SelectedList.Items.Count == 1, "explicit-selection");
                picker.UseButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); selectionDone.SetResult();
            }
            catch (Exception ex) { picker?.Close(); selectionDone.SetException(ex); }
        }));
        assistant.UseMemoryButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await selectionDone.Task;
        Require(assistant.MemoryReferences.Count == 1, "selector-return-id-revision");
        assistant.InputText.Text = "What is the synthetic project codename?";
        stage = "real-ollama-memory-answer";
        assistant.TranslateButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await controller.Active;
        Require(trace.Accepted == 1 && trace.PromptVersion == "memory-ask-v1", "runtime-202-memory-prompt-version");
        Require(controller.Last?.Status == TaskState.SUCCEEDED && assistant.ResultText.Text.Contains(codename, StringComparison.Ordinal), "real-ollama-uses-synthetic-context");
        Require(assistant.MemoryReferences.Count == 0, "terminal-clears-selection");
        assistant.InputText.Text = "Reply with exactly READY.";
        stage = "ordinary-ask";
        assistant.TranslateButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await controller.Active;
        Require(trace.Admissions.Last() == "/api/v1/ask/tasks" && trace.PromptVersion == "ask-v1" && controller.Last?.Status == TaskState.SUCCEEDED, "ordinary-ask-no-automatic-memory");
        var oldSelection = new MemorySelection(memory.Id, memory.Revision, memory.Title, memory.Type);
        assistant.ApplyMemorySelection([oldSelection]);
        memory = await runtime.UpdateMemoryAsync(memory.Id, new(memory.Revision, memory.Type, memory.Title, content + " Synthetic edit."), default);
        assistant.InputText.Text = "What is the synthetic project codename?";
        int accepted = trace.Accepted;
        assistant.TranslateButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await controller.Active;
        Require(controller.Error == DesktopError.MemorySelectionStale && trace.Accepted == accepted
            && assistant.MemoryNeedsReview && !assistant.TranslateButton.IsEnabled, "edited-selection-stale-no-admission-review-required");
        int admissions = trace.Admissions.Count; await controller.SubmitAsync();
        Require(trace.Admissions.Count == admissions, "stale-direct-retry-blocked");
        assistant.ApplyMemorySelection([new(memory.Id, memory.Revision, memory.Title, memory.Type)]);
        memory = await runtime.ArchiveMemoryAsync(memory.Id, memory.Revision, default);
        assistant.TranslateButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await controller.Active;
        Require(controller.Error == DesktopError.MemorySelectionStale && trace.Accepted == accepted, "archived-selection-rejected");
        picker = new MemorySelectionWindow(runtime); picker.Show(); await IdlePickerAsync();
        Require(picker.MemoryList.Items.Count == 0, "archived-absent-from-selector"); picker.Close();
        await runtime.DeleteMemoryAsync(memory.Id, memory.Revision, default);
        Require((await runtime.ListMemoryAsync(new(), default)).Total == 0, "synthetic-memory-deleted");
        assistant.Close(); Require(assistant.MemoryReferences.Count == 0 && assistant.MemorySelectionText.Text.Length == 0, "close-clears-ui");
    }
}
