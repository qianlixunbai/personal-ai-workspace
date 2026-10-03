using System;
using System.ComponentModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal enum MemoryConfirmation { Discard, Reload, Delete }

public partial class MemoryWindow : Window
{
    private readonly RuntimeClient runtime;
    private readonly Func<MemoryConfirmation, bool> confirm;
    private readonly CancellationTokenSource lifetime = new();
    private MemoryItem? loaded;
    private MemoryPage? results;
    private MemoryQuery view = new();
    private bool busy, closed, rendering, initialized, stale, missing;
    internal bool Dirty => initialized && (TitleBox.Text != (loaded?.Title ?? "")
        || ContentBox.Text != (loaded?.Content ?? "") || SelectedType != (loaded?.Type ?? MemoryType.PROJECT_NOTE));
    internal bool Stale => stale;
    internal bool IsClosed => closed;
    private MemoryType SelectedType => EditorType.SelectedIndex == 0 ? MemoryType.PREFERENCE : MemoryType.PROJECT_NOTE;

    internal MemoryWindow(RuntimeClient runtime, Func<MemoryConfirmation, bool>? confirmation = null)
    {
        this.runtime = runtime;
        confirm = confirmation ?? Confirm;
        InitializeComponent();
        initialized = true;
        Loaded += Opened;
        Closing += WindowClosing;
        Closed += WindowClosed;
        UpdateControls();
    }
    private bool Confirm(MemoryConfirmation decision) => MessageBox.Show(this, decision switch
    {
        MemoryConfirmation.Reload => "重新加载将丢弃本地未保存的修改。继续？",
        MemoryConfirmation.Delete => "删除此 Memory？\n它将不再出现在后续 Memory 读取和搜索中。\n这不保证磁盘取证级擦除。未保存的修改也将丢弃。",
        _ => "丢弃未保存的修改？"
    }, "Memory", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No) == MessageBoxResult.Yes;

    private void UpdateControls()
    {
        if (!initialized || closed) return;
        bool available = !busy;
        SearchButton.IsEnabled = RefreshButton.IsEnabled = NewButton.IsEnabled = available;
        StatusFilter.IsEnabled = TypeFilter.IsEnabled = MemoryList.IsEnabled = available;
        SearchBox.IsReadOnly = TitleBox.IsReadOnly = ContentBox.IsReadOnly = busy;
        EditorType.IsEnabled = available;
        SaveButton.IsEnabled = available && !stale && !missing && Dirty;
        LifecycleButton.IsEnabled = DeleteButton.IsEnabled = available && loaded is not null && !stale && !missing;
        ReloadButton.IsEnabled = available && loaded is not null && !missing;
        PreviousButton.IsEnabled = available && results?.HasPrevious == true;
        NextButton.IsEnabled = available && results?.HasNext == true;
        LifecycleButton.Content = loaded?.Status == MemoryStatus.ARCHIVED ? "Restore" : "Archive";
        MetadataText.Text = loaded is null ? "New · 尚未保存" :
            $"{loaded.Id:D}\n{loaded.Status} · Revision {loaded.Revision}\nCreated {loaded.CreatedAt:yyyy-MM-dd HH:mm:ss zzz}\nUpdated {loaded.UpdatedAt:yyyy-MM-dd HH:mm:ss zzz}";
        if (Dirty) MetadataText.Text += " · 未保存";
        if (stale) MetadataText.Text += " · 需重新加载";
        if (missing) MetadataText.Text += " · 已不存在";
    }
    private void LoadEditor(MemoryItem? item)
    {
        rendering = true;
        loaded = item; stale = missing = false;
        EditorType.SelectedIndex = item?.Type == MemoryType.PREFERENCE ? 0 : 1;
        TitleBox.Text = item?.Title ?? ""; ContentBox.Text = item?.Content ?? "";
        rendering = false;
        UpdateControls();
    }
    private void SelectLoaded()
    {
        rendering = true;
        MemoryList.SelectedItem = results?.Items.FirstOrDefault(x => x.Id == loaded?.Id);
        rendering = false;
    }
    private async Task RunAsync(Func<Task> operation, bool affectsLoaded = true)
    {
        if (busy || closed) return;
        busy = true; UpdateControls(); StatusText.Text = "正在处理 Memory…";
        try { await operation(); }
        catch (DesktopException error)
        {
            if (closed) return;
            if (affectsLoaded && error.Error == DesktopError.MemoryRevisionConflict) stale = true;
            if (affectsLoaded && error.Error == DesktopError.MemoryNotFound) missing = true;
            StatusText.Text = ErrorText.For(error.Error);
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!closed) StatusText.Text = ErrorText.For(DesktopError.InternalError); }
        finally { if (!closed) { busy = false; UpdateControls(); } }
    }
    private async Task FetchListAsync()
    {
        var page = await runtime.ListMemoryAsync(view, lifetime.Token);
        if (closed) return;
        // Deleting/archiving the last row or concurrent deletion can make the requested page empty.
        if (page.Page > 0 && (long)page.Page * page.Limit >= page.Total)
        {
            view = view with { Page = Math.Max(0, (page.Total - 1) / page.Limit) };
            page = await runtime.ListMemoryAsync(view, lifetime.Token);
            if (closed) return;
        }
        results = page;
        rendering = true; MemoryList.ItemsSource = page.Items; rendering = false;
        SelectLoaded();
        PageText.Text = $"Page {page.Page + 1} / {Math.Max(1, (page.Total + page.Limit - 1) / page.Limit)} · {page.Total}";
    }
    internal Task RefreshAsync(bool search = false, int? page = null) => RunAsync(async () =>
    {
        var next = view with
        {
            Query = search ? SearchBox.Text : view.Query,
            Status = StatusFilter.SelectedIndex == 1 ? MemoryStatus.ARCHIVED : MemoryStatus.ACTIVE,
            Type = TypeFilter.SelectedIndex switch { 1 => MemoryType.PREFERENCE, 2 => MemoryType.PROJECT_NOTE, _ => (MemoryType?)null },
            Page = search ? 0 : page ?? view.Page
        };
        next.Validate(); view = next;
        // Keep the editor, including dirty text, across list/search/filter refreshes.
        results = null; rendering = true; MemoryList.ItemsSource = null; rendering = false;
        await FetchListAsync();
        if (!closed) StatusText.Text = stale ? ErrorText.For(DesktopError.MemoryRevisionConflict)
            : missing ? ErrorText.For(DesktopError.MemoryNotFound) : "Memory 列表已刷新。";
    }, affectsLoaded: false);
    internal async Task SelectItemAsync(MemoryItem item)
    {
        if (busy || closed || item.Id == loaded?.Id) return;
        if (Dirty && !confirm(MemoryConfirmation.Discard)) { SelectLoaded(); return; }
        await RunAsync(async () =>
        {
            var latest = await runtime.GetMemoryAsync(item.Id, lifetime.Token);
            if (closed) return;
            LoadEditor(latest); SelectLoaded(); StatusText.Text = "Memory 已加载。";
        }, affectsLoaded: false);
        if (!closed) SelectLoaded();
    }
    internal void NewItem()
    {
        if (busy || closed || Dirty && !confirm(MemoryConfirmation.Discard)) return;
        LoadEditor(null); SelectLoaded(); StatusText.Text = "New · 点击 Save 才会创建 Memory。";
    }
    internal Task SaveAsync()
    {
        if (busy || closed || stale || missing || !Dirty) return Task.CompletedTask;
        return RunAsync(async () =>
        {
            var saved = loaded is null
                ? await runtime.CreateMemoryAsync(new(SelectedType, TitleBox.Text, ContentBox.Text), lifetime.Token)
                : await runtime.UpdateMemoryAsync(loaded.Id, new(loaded.Revision, SelectedType, TitleBox.Text, ContentBox.Text), lifetime.Token);
            if (closed) return;
            LoadEditor(saved);
            StatusText.Text = "Memory 已保存。";
            await RefreshAfterMutationAsync();
        });
    }
    internal Task ChangeStatusAsync()
    {
        if (busy || closed || loaded is null || stale || missing) return Task.CompletedTask;
        return RunAsync(async () =>
        {
            bool keepEdits = Dirty;
            var saved = loaded.Status == MemoryStatus.ACTIVE
                ? await runtime.ArchiveMemoryAsync(loaded.Id, loaded.Revision, lifetime.Token)
                : await runtime.RestoreMemoryAsync(loaded.Id, loaded.Revision, lifetime.Token);
            if (closed) return;
            if (keepEdits) loaded = saved; else LoadEditor(saved);
            StatusText.Text = "Memory 状态已更新。" + (keepEdits ? "本地修改仍未保存。" : "");
            await RefreshAfterMutationAsync();
        });
    }
    internal Task DeleteAsync()
    {
        if (busy || closed || loaded is null || stale || missing || !confirm(MemoryConfirmation.Delete)) return Task.CompletedTask;
        return RunAsync(async () =>
        {
            await runtime.DeleteMemoryAsync(loaded.Id, loaded.Revision, lifetime.Token);
            if (closed) return;
            LoadEditor(null); StatusText.Text = "Memory 已删除。";
            await RefreshAfterMutationAsync();
        });
    }
    private async Task RefreshAfterMutationAsync()
    {
        // A failed follow-up list must not make a confirmed save/delete look unsuccessful.
        try { await FetchListAsync(); }
        catch (DesktopException error)
        { if (!closed) StatusText.Text += " 列表刷新失败：" + ErrorText.For(error.Error); }
    }
    internal Task ReloadAsync()
    {
        if (busy || closed || loaded is null || missing || Dirty && !confirm(MemoryConfirmation.Reload)) return Task.CompletedTask;
        return RunAsync(async () =>
        {
            var latest = await runtime.GetMemoryAsync(loaded.Id, lifetime.Token);
            if (closed) return;
            LoadEditor(latest); StatusText.Text = "已重新加载最新 Memory。";
        });
    }
    internal bool TryClose()
    {
        if (!closed) Close();
        return closed;
    }
    private async void Opened(object sender, RoutedEventArgs e) => await RefreshAsync();
    private async void Search(object sender, RoutedEventArgs e) => await RefreshAsync(search: true);
    private async void Refresh(object sender, RoutedEventArgs e) => await RefreshAsync();
    private async void Previous(object sender, RoutedEventArgs e)
    { if (results?.HasPrevious == true) await RefreshAsync(page: view.Page - 1); }
    private async void Next(object sender, RoutedEventArgs e)
    { if (results?.HasNext == true) await RefreshAsync(page: view.Page + 1); }
    private async void SearchKeyDown(object sender, KeyEventArgs e)
    { if (e.Key == Key.Enter) { e.Handled = true; await RefreshAsync(search: true); } }
    private async void FilterChanged(object sender, SelectionChangedEventArgs e)
    { if (initialized && !rendering) await RefreshAsync(page: 0); }
    private async void ItemSelected(object sender, SelectionChangedEventArgs e)
    { if (initialized && !rendering && MemoryList.SelectedItem is MemoryItem item) await SelectItemAsync(item); }
    private void EditorTextChanged(object sender, TextChangedEventArgs e) { if (!rendering) UpdateControls(); }
    private void EditorTypeChanged(object sender, SelectionChangedEventArgs e) { if (!rendering) UpdateControls(); }
    private void New(object sender, RoutedEventArgs e) => NewItem();
    private async void Save(object sender, RoutedEventArgs e) => await SaveAsync();
    private async void Lifecycle(object sender, RoutedEventArgs e) => await ChangeStatusAsync();
    private async void Delete(object sender, RoutedEventArgs e) => await DeleteAsync();
    private async void Reload(object sender, RoutedEventArgs e) => await ReloadAsync();
    private void CloseMemory(object sender, RoutedEventArgs e) => Close();
    private void WindowClosing(object? sender, CancelEventArgs e)
    { if (Dirty && !confirm(MemoryConfirmation.Discard)) e.Cancel = true; }
    private void WindowClosed(object? sender, EventArgs e)
    {
        closed = true; lifetime.Cancel(); rendering = true;
        TitleBox.Clear(); ContentBox.Clear(); SearchBox.Clear(); MemoryList.ItemsSource = null;
        loaded = null; results = null; view = new(); stale = missing = false;
        MetadataText.Text = PageText.Text = StatusText.Text = "";
        lifetime.Dispose();
    }
}
