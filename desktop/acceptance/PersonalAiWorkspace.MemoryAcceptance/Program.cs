using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

namespace PersonalAiWorkspace.MemoryAcceptance;

internal static class Program
{
    private static readonly List<string> checks = [];
    private static string stage = "configuration";
    private static bool approveDelete, approveReload;
    private static int deleteDecisions, reloadDecisions;
    private static MemoryWindow? current;

    [STAThread]
    private static int Main()
    {
        int code = 1;
        var dispatcher = Dispatcher.CurrentDispatcher;
        SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
        dispatcher.BeginInvoke(new Action(async () =>
        {
            try
            {
                await AcceptanceAsync(); code = 0;
                Console.WriteLine(JsonSerializer.Serialize(new { result = "PASS", realWpf = true, realHttp = true, checks }));
            }
            catch (Exception)
            { Console.WriteLine(JsonSerializer.Serialize(new { result = "FAIL", check = stage })); }
            finally
            {
                // Test-owned windows and synthetic input only; no blocking confirmation during cleanup.
                approveDelete = approveReload = true; current?.TryClose(); dispatcher.InvokeShutdown();
            }
        }));
        Dispatcher.Run(); return code;
    }
    private static bool Confirm(MemoryConfirmation decision)
    {
        if (decision == MemoryConfirmation.Delete) { deleteDecisions++; return approveDelete; }
        if (decision == MemoryConfirmation.Reload) { reloadDecisions++; return approveReload; }
        return true;
    }
    private static void Require(bool condition, string check)
    { stage = check; if (!condition) throw new InvalidOperationException(); checks.Add(check); }
    private static async Task IdleAsync(MemoryWindow window)
    {
        await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
        var until = DateTime.UtcNow.AddSeconds(12);
        while (!window.RefreshButton.IsEnabled)
        { if (DateTime.UtcNow > until) throw new InvalidOperationException(); await Task.Delay(20); }
    }
    private static async Task ClickAsync(MemoryWindow window, Button button)
    { button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await IdleAsync(window); }
    private static async Task<MemoryWindow> OpenAsync(RuntimeClient runtime)
    {
        current = new MemoryWindow(runtime, Confirm); current.Show(); await IdleAsync(current); return current;
    }
    private static void Close(MemoryWindow window)
    {
        Require(window.TryClose() && window.IsClosed && window.TitleBox.Text.Length == 0 && window.ContentBox.Text.Length == 0
            && window.SearchBox.Text.Length == 0 && window.MemoryList.Items.Count == 0, "close-clears-ui");
    }
    private static async Task AcceptanceAsync()
    {
        string tokenPath = Environment.GetEnvironmentVariable("M3B_TEST_TOKEN_FILE") ?? throw new InvalidOperationException();
        string marker = Environment.GetEnvironmentVariable("M3B_SYNTHETIC_MARKER") ?? throw new InvalidOperationException();
        Require(marker.StartsWith("m3b-synthetic-", StringComparison.Ordinal), "synthetic-input-only");
        string token = File.ReadAllText(tokenPath).Trim();
        using var runtime = new RuntimeClient(() => token);
        var window = await OpenAsync(runtime); Require(window.MemoryList.Items.Count == 0, "open-empty-isolated-memory");
        window.NewButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        window.TitleBox.Text = "Synthetic note " + marker; window.ContentBox.Text = "Synthetic content " + marker;
        Require((await runtime.ListMemoryAsync(new(), default)).Total == 0, "new-and-edit-do-not-autosave");
        await ClickAsync(window, window.SaveButton);
        var item = (MemoryItem)window.MemoryList.Items[0];
        Require(item.Revision == 1 && !window.Dirty, "create-project-note"); Close(window);
        window = await OpenAsync(runtime);
        Require(window.MemoryList.Items.Count == 1 && ((MemoryItem)window.MemoryList.Items[0]).Id == item.Id, "close-reopen-persists");
        window.SearchBox.Text = marker; await ClickAsync(window, window.SearchButton);
        Require(window.MemoryList.Items.Count == 1, "explicit-search-finds-item");
        window.MemoryList.SelectedIndex = 0; await IdleAsync(window);
        window.ContentBox.Text += " edited"; await ClickAsync(window, window.SaveButton);
        var edited = await runtime.GetMemoryAsync(item.Id, default);
        Require(edited.Revision == 2 && !window.Dirty, "explicit-edit-advances-revision");
        await ClickAsync(window, window.LifecycleButton);
        Require(window.MemoryList.Items.Count == 0 && (await runtime.GetMemoryAsync(item.Id, default)).Status == MemoryStatus.ARCHIVED,
            "archive-leaves-active");
        window.StatusFilter.SelectedIndex = 1; await IdleAsync(window);
        Require(window.MemoryList.Items.Count == 1, "archived-filter-finds-item");
        await ClickAsync(window, window.LifecycleButton);
        Require((await runtime.GetMemoryAsync(item.Id, default)).Status == MemoryStatus.ACTIVE, "explicit-restore");
        window.StatusFilter.SelectedIndex = 0; await IdleAsync(window);
        var prior = await runtime.GetMemoryAsync(item.Id, default);
        window.ContentBox.Text += " unsaved local"; string local = window.ContentBox.Text;
        var newer = await runtime.UpdateMemoryAsync(item.Id,
            new(prior.Revision, prior.Type, prior.Title, prior.Content + " external newer"), default);
        await ClickAsync(window, window.SaveButton);
        Require(window.Stale && window.Dirty && local == window.ContentBox.Text && !window.SaveButton.IsEnabled
            && !window.LifecycleButton.IsEnabled && !window.DeleteButton.IsEnabled, "conflict-preserves-and-blocks");
        await window.SaveAsync();
        Require((await runtime.GetMemoryAsync(item.Id, default)).Revision == newer.Revision, "stale-ui-does-not-overwrite");
        await ClickAsync(window, window.ReloadButton);
        Require(reloadDecisions == 1 && window.Stale && local == window.ContentBox.Text, "reload-refusal-keeps-local-edits");
        approveReload = true; await ClickAsync(window, window.ReloadButton);
        Require(reloadDecisions == 2 && !window.Stale && !window.Dirty && newer.Content == window.ContentBox.Text, "confirmed-reload-latest");
        await ClickAsync(window, window.DeleteButton);
        Require(deleteDecisions == 1 && (await runtime.ListMemoryAsync(new(), default)).Total == 1, "delete-confirmation-refusal");
        approveDelete = true; await ClickAsync(window, window.DeleteButton);
        Require(deleteDecisions == 2 && window.ContentBox.Text.Length == 0 && window.MemoryList.Items.Count == 0, "confirmed-delete");
        Close(window); window = await OpenAsync(runtime);
        Require(window.MemoryList.Items.Count == 0 && (await runtime.ListMemoryAsync(new(marker), default)).Total == 0, "close-reopen-deleted-item-absent");
        Require(!window.StatusText.Text.Contains(marker, StringComparison.Ordinal) && !item.ToString().Contains(marker, StringComparison.Ordinal),
            "safe-status-and-diagnostics"); Close(window);
    }
}
