using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

namespace PersonalAiWorkspace.MemoryBackupAcceptance;

internal static class Program
{
    private static readonly List<string> checks = [];
    private static string stage = "configuration";
    private const string ProjectTitle = "Synthetic Recovery Project";
    private const string ProjectContent = "The synthetic recovery project codename is M3RECOVERY-4821. 中文恢复搜索";
    private const string Question = "What is the synthetic recovery project codename? Reply with only the codename.";
    private static readonly List<Window> windows = [];
    [STAThread] private static int Main()
    {
        int code = 1; var dispatcher = Dispatcher.CurrentDispatcher;
        SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
        dispatcher.BeginInvoke(new Action(async () =>
        {
            try { await RunAsync(); code = 0; Console.WriteLine(JsonSerializer.Serialize(new { result = "PASS", realWpf = true, realHttp = true, checks })); }
            catch (Exception) { Console.WriteLine(JsonSerializer.Serialize(new { result = "FAIL", check = stage })); }
            finally { foreach (var window in windows.AsEnumerable().Reverse()) window.Close(); dispatcher.InvokeShutdown(); }
        })); Dispatcher.Run(); return code;
    }
    private static void Require(bool value, string name)
    { stage = name; if (!value) throw new InvalidOperationException(); checks.Add(name); }
    private static string Setting(string name) => Environment.GetEnvironmentVariable("M3C2_" + name) ?? throw new InvalidOperationException();
    private static async Task IdleAsync(Func<bool> ready)
    {
        await Dispatcher.Yield(DispatcherPriority.ApplicationIdle); var until = DateTime.UtcNow.AddSeconds(15);
        while (!ready()) { if (DateTime.UtcNow > until) throw new InvalidOperationException(); await Task.Delay(20); }
    }
    private static void Click(Button button) => button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
    private sealed class Files : IMemoryBackupFiles
    {
        private readonly NativeMemoryBackupFiles native = new();
        // Synthetic native-picker choices injected for unattended acceptance; production uses OS dialogs.
        public MemoryExportDestination? PickExport(Window _) => new(Setting("BACKUP_FILE"), false);
        public string? PickBackup(Window _) => Setting("BACKUP_FILE");
        public string? PickTarget(Window _) => Setting("TARGET_DIRECTORY");
        public Task<byte[]> ReadAsync(string file, CancellationToken token) => native.ReadAsync(file, token);
        public Task WriteAsync(MemoryExportDestination file, ReadOnlyMemory<byte> bytes, CancellationToken token) => native.WriteAsync(file, bytes, token);
    }
    private sealed class Controller(RuntimeClient runtime) : IAssistantController
    {
        internal AssistantWindow Window = null!;
        internal Task Active = Task.CompletedTask;
        internal RuntimeTask? Last;
        public bool Busy { get; private set; }
        public bool Exiting => false;
        public Task SubmitAsync() { Active = SubmitCoreAsync(); return Active; }
        private async Task SubmitCoreAsync()
        {
            if (Busy || Window.MemoryNeedsReview) return;
            Busy = true; Window.SetBusy(true); var operation = new AssistantOperation(runtime);
            try { Last = await operation.RunAsync(new(Window.SelectedAction, Window.InputText.Text), _ => { }, default, Window.MemoryReferences); }
            finally { Busy = false; Window.MemoryOperationEnded(operation.Accepted); Window.SetBusy(false); }
        }
        public void CancelOperation() { }
        public Task CheckHealthAsync() => Task.CompletedTask;
        public Task ImportCredentialAsync(string _) => Task.CompletedTask;
        public void ForgetCredential() { }
    }
    private static async Task RealAskAsync(RuntimeClient runtime)
    {
        var controller = new Controller(runtime); var assistant = controller.Window = new AssistantWindow(controller, runtime);
        windows.Add(assistant); assistant.Show(); assistant.ActionSelector.SelectedIndex = 2;
        var done = new TaskCompletionSource();
        _ = assistant.Dispatcher.BeginInvoke(new Action(async () =>
        {
            MemorySelectionWindow? picker = null;
            try
            {
                stage = "active-only-wpf-picker"; picker = assistant.OwnedWindows.OfType<MemorySelectionWindow>().Single();
                await IdleAsync(() => picker.SearchButton.IsEnabled);
                Require(picker.MemoryList.Items.Count == 2 && picker.MemoryList.Items.OfType<MemoryItem>().All(x => x.Status == MemoryStatus.ACTIVE), "active-only-selector-excludes-archived");
                picker.MemoryList.SelectedItem = picker.MemoryList.Items.OfType<MemoryItem>().Single(x => x.Title == ProjectTitle);
                await IdleAsync(() => picker.AddButton.IsEnabled);
                Require(picker.PreviewContent.Text == ProjectContent, "exact-restored-context-preview");
                Click(picker.AddButton); Click(picker.UseButton); done.SetResult();
            }
            catch (Exception error) { picker?.Close(); done.SetException(error); }
        }));
        Click(assistant.UseMemoryButton); await done.Task;
        Require(assistant.MemoryReferences.Count == 1, "explicit-single-memory-selection");
        assistant.InputText.Text = Question; stage = "real-ollama-memory-ask";
        Click(assistant.TranslateButton); await controller.Active;
        Require(controller.Last?.Status == TaskState.SUCCEEDED && controller.Last.Result!.Contains("M3RECOVERY-4821", StringComparison.Ordinal), "real-ollama-restored-context-answer");
        Require(assistant.MemoryReferences.Count == 0, "terminal-clears-selection");
        assistant.ClearText(); assistant.Close();
    }
    private static async Task RunAsync()
    {
        string credential = File.ReadAllText(Setting("TOKEN_FILE")).Trim(); using var runtime = new RuntimeClient(() => credential);
        string mode = Setting("PHASE");
        if (mode == "restore")
        {
            var backupWindow = new MemoryBackupWindow(runtime, new Files()); windows.Add(backupWindow); backupWindow.Show();
            stage = "wpf-restore-new-directory"; Click(backupWindow.RestoreButton); await IdleAsync(() => backupWindow.RestoreButton.IsEnabled);
            Require(backupWindow.StatusText.Text.StartsWith("Restore complete", StringComparison.Ordinal), "wpf-maintenance-restore-success");
            Require(backupWindow.StatusText.Text.Contains("Start Runtime"), "explicit-restart-guidance"); backupWindow.Close(); return;
        }
        var manage = new MemoryWindow(runtime, _ => true); windows.Add(manage); manage.Show(); await IdleAsync(() => manage.NewButton.IsEnabled);
        if (mode == "seed")
        {
            foreach (var fixture in new[] { ("Synthetic Recovery Preference", "Use concise synthetic replies.", 0), (ProjectTitle, ProjectContent, 1), ("Synthetic Recovery Archived", "Archived synthetic recovery record. 中文归档搜索", 1) })
            {
                manage.NewItem(); manage.EditorType.SelectedIndex = fixture.Item3; manage.TitleBox.Text = fixture.Item1; manage.ContentBox.Text = fixture.Item2;
                stage = "wpf-explicit-save"; Click(manage.SaveButton); await IdleAsync(() => manage.NewButton.IsEnabled);
                Require(manage.StatusText.Text.Contains("已保存"), "wpf-explicit-save");
                await Task.Delay(60);
                if (fixture.Item3 == 0)
                {
                    manage.ContentBox.Text = fixture.Item2 + " Synthetic revision update."; Click(manage.SaveButton); await IdleAsync(() => manage.NewButton.IsEnabled);
                }
                if (fixture.Item1.EndsWith("Archived", StringComparison.Ordinal))
                { stage = "wpf-explicit-archive"; Click(manage.LifecycleButton); await IdleAsync(() => manage.NewButton.IsEnabled); }
            }
            var active = await runtime.ListMemoryAsync(new(), default); var archived = await runtime.ListMemoryAsync(new(Status: MemoryStatus.ARCHIVED), default);
            Require(active.Total == 2 && archived.Total == 1, "synthetic-active-archived-lifecycle");
            Require(active.Items.Concat(archived.Items).Any(x => x.Revision > 1 && x.UpdatedAt > x.CreatedAt), "nontrivial-revisions-and-timestamps");
            manage.Close(); return;
        }
        Require(manage.MemoryList.Items.Count == 2, "restart-wpf-active-list");
        await manage.SelectItemAsync(manage.MemoryList.Items.OfType<MemoryItem>().Single(x => x.Title == ProjectTitle));
        Require(manage.ContentBox.Text == ProjectContent && manage.EditorType.SelectedIndex == 1, "restart-wpf-exact-source-management");
        manage.SearchBox.Text = "Recovery"; await manage.RefreshAsync(true); Require(manage.MemoryList.Items.Count == 2, "english-restored-search");
        manage.SearchBox.Text = "中文恢复搜索"; await manage.RefreshAsync(true); Require(manage.MemoryList.Items.Count == 1, "chinese-restored-search");
        manage.StatusFilter.SelectedIndex = 1; await IdleAsync(() => manage.NewButton.IsEnabled);
        manage.SearchBox.Text = ""; await manage.RefreshAsync(true); Require(manage.MemoryList.Items.Count == 1, "archived-filter-preserved");
        var archivedItem = manage.MemoryList.Items.OfType<MemoryItem>().Single();
        try { await runtime.SubmitMemoryAskAsync(new(Question, [new(archivedItem.Id, archivedItem.Revision)]), default); throw new InvalidOperationException(); }
        catch (DesktopException error) { Require(error.Error == DesktopError.MemorySelectionStale, "archived-memory-ask-rejected"); }
        manage.Close(); await RealAskAsync(runtime);
        if (mode == "export")
        {
            var backupWindow = new MemoryBackupWindow(runtime, new Files()); windows.Add(backupWindow); backupWindow.Show();
            stage = "wpf-explicit-export"; Click(backupWindow.ExportButton); await IdleAsync(() => backupWindow.ExportButton.IsEnabled);
            Require(backupWindow.StatusText.Text.StartsWith("Export complete", StringComparison.Ordinal) && File.Exists(Setting("BACKUP_FILE")), "wpf-plaintext-export-success"); backupWindow.Close();
        }
        else if (mode == "restored")
        {
            using var backup = JsonDocument.Parse(await File.ReadAllBytesAsync(Setting("BACKUP_FILE")));
            foreach (var expected in backup.RootElement.GetProperty("items").EnumerateArray())
            {
                var item = await runtime.GetMemoryAsync(expected.GetProperty("id").GetGuid(), default);
                Require(item.Id == expected.GetProperty("id").GetGuid() && item.Revision == expected.GetProperty("revision").GetInt64()
                    && item.Type.ToString() == expected.GetProperty("type").GetString() && item.Status.ToString() == expected.GetProperty("status").GetString()
                    && item.Source.ToString() == expected.GetProperty("source").GetString() && item.Title == expected.GetProperty("title").GetString()
                    && item.Content == expected.GetProperty("content").GetString()
                    && item.CreatedAt == DateTimeOffset.Parse(expected.GetProperty("createdAt").GetString()!)
                    && item.UpdatedAt == DateTimeOffset.Parse(expected.GetProperty("updatedAt").GetString()!), "exact-all-source-fields-preserved");
                await runtime.DeleteMemoryAsync(item.Id, item.Revision, default);
            }
            Require((await runtime.ListMemoryAsync(new(), default)).Total == 0, "synthetic-restored-cleanup");
        }
        else throw new InvalidOperationException();
    }
}
