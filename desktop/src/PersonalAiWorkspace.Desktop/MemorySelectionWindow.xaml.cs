using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

public partial class MemorySelectionWindow : Window
{
    private readonly RuntimeClient runtime;
    private readonly CancellationTokenSource lifetime = new();
    private readonly List<MemoryItem> selected = new();
    private MemoryItem? preview;
    private MemoryPage? page;
    private MemoryQuery view = new();
    private bool initialized, busy, closed, rendering;
    internal IReadOnlyList<MemorySelection>? Selection { get; private set; }
    internal bool IsClosed => closed;
    internal MemorySelectionWindow(RuntimeClient runtime)
    {
        this.runtime = runtime;
        InitializeComponent(); initialized = true;
        Loaded += async (_, _) => await RefreshAsync();
        Closed += (_, _) =>
        {
            closed = true; lifetime.Cancel(); rendering = true;
            selected.Clear(); preview = null; page = null; view = new();
            PreviewTitle.Clear(); PreviewContent.Clear(); SearchBox.Clear();
            MemoryList.ItemsSource = SelectedList.ItemsSource = null;
            StatusText.Text = PageText.Text = SelectedCountText.Text = "";
            lifetime.Dispose();
        };
        UpdateControls();
    }
    private void UpdateControls()
    {
        if (!initialized || closed) return;
        SearchButton.IsEnabled = TypeFilter.IsEnabled = MemoryList.IsEnabled = SelectedList.IsEnabled = !busy;
        SearchBox.IsReadOnly = busy;
        AddButton.IsEnabled = !busy && preview is not null && selected.Count < 4 && selected.All(x => x.Id != preview.Id);
        RemoveButton.IsEnabled = !busy && SelectedList.SelectedItem is MemoryItem;
        UseButton.IsEnabled = !busy && selected.Count is > 0 and <= 4;
        PreviousButton.IsEnabled = !busy && page?.HasPrevious == true;
        NextButton.IsEnabled = !busy && page?.HasNext == true;
        SelectedCountText.Text = $"Selected: {selected.Count} / 4";
    }
    private void ShowPreview(MemoryItem? item)
    {
        preview = item; PreviewTitle.Text = item?.Title ?? ""; PreviewContent.Text = item?.Content ?? "";
        UpdateControls();
    }
    private async Task RunAsync(Func<Task> work)
    {
        if (busy || closed) return;
        busy = true; UpdateControls();
        try { await work(); }
        catch (DesktopException error) { if (!closed) StatusText.Text = ErrorText.For(error.Error); }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!closed) StatusText.Text = ErrorText.For(DesktopError.InternalError); }
        finally { if (!closed) { busy = false; UpdateControls(); } }
    }
    internal Task RefreshAsync(bool search = false, int? pageNumber = null) => RunAsync(async () =>
    {
        var next = view with { Query = search ? SearchBox.Text : view.Query, Status = MemoryStatus.ACTIVE,
            Type = TypeFilter.SelectedIndex switch { 1 => MemoryType.PREFERENCE, 2 => MemoryType.PROJECT_NOTE, _ => (MemoryType?)null },
            Page = search ? 0 : pageNumber ?? view.Page };
        next.Validate(); view = next;
        var result = await runtime.ListMemoryAsync(view, lifetime.Token);
        if (closed) return;
        page = result; rendering = true; MemoryList.ItemsSource = result.Items; rendering = false;
        ShowPreview(null); PageText.Text = $"Page {result.Page + 1} · {result.Total}";
        StatusText.Text = "Select an item to view its full title and content.";
    });
    internal Task PreviewAsync(MemoryItem item) => RunAsync(async () =>
    {
        ShowPreview(null);
        var loaded = await runtime.GetMemoryAsync(item.Id, lifetime.Token);
        if (closed) return;
        if (loaded.Status != MemoryStatus.ACTIVE || loaded.Revision != item.Revision)
        { StatusText.Text = ErrorText.For(DesktopError.MemorySelectionStale); return; }
        ShowPreview(loaded); StatusText.Text = "Review this complete context before adding it.";
    });
    internal void AddPreview()
    {
        if (busy || closed || preview is null || selected.Count >= 4 || selected.Any(x => x.Id == preview.Id)) return;
        selected.Add(preview); RenderSelected();
    }
    private void RenderSelected()
    {
        rendering = true; SelectedList.ItemsSource = selected.ToArray(); rendering = false; UpdateControls();
    }
    internal void RemoveSelected()
    {
        if (busy || closed || SelectedList.SelectedItem is not MemoryItem item) return;
        selected.Remove(item); RenderSelected();
    }
    internal void ConfirmSelection()
    {
        if (busy || closed || selected.Count is < 1 or > 4) return;
        Selection = Array.AsReadOnly(selected.Select(x => new MemorySelection(x.Id, x.Revision, x.Title, x.Type)).ToArray());
        Close();
    }
    private async void Search(object sender, RoutedEventArgs e) => await RefreshAsync(search: true);
    private async void SearchKeyDown(object sender, KeyEventArgs e)
    { if (e.Key == Key.Enter) { e.Handled = true; await RefreshAsync(search: true); } }
    private async void FilterChanged(object sender, SelectionChangedEventArgs e)
    { if (initialized && !rendering) await RefreshAsync(pageNumber: 0); }
    private async void Previous(object sender, RoutedEventArgs e)
    { if (page?.HasPrevious == true) await RefreshAsync(pageNumber: view.Page - 1); }
    private async void Next(object sender, RoutedEventArgs e)
    { if (page?.HasNext == true) await RefreshAsync(pageNumber: view.Page + 1); }
    private async void ItemSelected(object sender, SelectionChangedEventArgs e)
    { if (initialized && !rendering && MemoryList.SelectedItem is MemoryItem item) await PreviewAsync(item); }
    private void SelectedPreview(object sender, SelectionChangedEventArgs e)
    { if (initialized && !rendering && !busy && SelectedList.SelectedItem is MemoryItem item) ShowPreview(item); }
    private void Add(object sender, RoutedEventArgs e) => AddPreview();
    private void Remove(object sender, RoutedEventArgs e) => RemoveSelected();
    private void Use(object sender, RoutedEventArgs e) => ConfirmSelection();
    private void Cancel(object sender, RoutedEventArgs e) => Close();
}
