using System;
using System.ComponentModel;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal enum MemoryConfirmation { Discard, Reload, Delete, AdoptReview, ContinueReview }

public partial class MemoryWindow : Window
{
    private const string UnknownOutcomeMessage = "操作结果待确认。不会自动重试。请刷新列表核对，再重新加载；新建结果不明时请先检查是否已存在。";
    private readonly RuntimeClient runtime;
    private readonly Func<MemoryConfirmation, bool> confirm;
    private readonly CancellationTokenSource lifetime = new();
    private MemoryItem? loaded;
    private MemoryItem? reviewed;
    private string? duplicateBody;
    private MemoryPage? results;
    private MemoryQuery view = new();
    private bool busy, writing, closed, rendering, initialized, stale, missing, outcomeUnknown;
    internal bool Dirty => initialized && (TitleBox.Text != (loaded?.Title ?? "")
        || ContentBox.Text != (loaded?.Content ?? "") || SelectedType != (loaded?.Type ?? MemoryType.PROJECT_NOTE));
    internal bool Stale => stale;
    internal bool IsClosed => closed;
    private MemoryType SelectedType => EditorType.SelectedIndex == 0 ? MemoryType.PREFERENCE : MemoryType.PROJECT_NOTE;
    private bool CanAdoptReview => !outcomeUnknown && !missing && reviewed?.Status == MemoryStatus.ACTIVE
        && (loaded is null || loaded.Id == reviewed.Id)
        && new MemoryCreateInput(SelectedType, TitleBox.Text, ContentBox.Text).IsValid();

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
        MemoryConfirmation.AdoptReview => "保留右侧草稿，改为修订左侧已核对的这条记忆？\n此操作不会保存；之后点击 Save 才会更新它，不会另建一条。",
        MemoryConfirmation.ContinueReview => "保留右侧草稿，以左侧已核对的最新版本继续编辑？\n请先将需要保留的内容合并到草稿。之后点击 Save 将用草稿更新这条记忆，此时不会写入。",
        _ => "丢弃未保存的修改？"
    }, "Memory", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No) == MessageBoxResult.Yes;

    private void UpdateControls()
    {
        if (!initialized || closed) return;
        bool available = !busy;
        BackupButton.IsEnabled = available;
        SearchButton.IsEnabled = RefreshButton.IsEnabled = NewButton.IsEnabled = available;
        StatusFilter.IsEnabled = TypeFilter.IsEnabled = MemoryList.IsEnabled = available;
        SearchBox.IsReadOnly = TitleBox.IsReadOnly = ContentBox.IsReadOnly = busy;
        EditorType.IsEnabled = available;
        bool validDraft = new MemoryCreateInput(SelectedType, TitleBox.Text, ContentBox.Text).IsValid();
        ReviewTarget.IsEnabled = available;
        ReviewButton.IsEnabled = available && ReviewTarget.SelectedItem is MemoryItem;
        AdoptReviewButton.IsEnabled = available && CanAdoptReview;
        AdoptReviewButton.Content = loaded is null ? "保留草稿，修订这条记忆" : "保留草稿，以此版本继续编辑";
        ReviewCurrentButton.IsEnabled = available && loaded is not null;
        CheckDuplicatesButton.IsEnabled = available && new MemoryCreateInput(MemoryType.PROJECT_NOTE, "检查", ContentBox.Text).IsValid();
        DuplicateMatches.IsEnabled = available;
        ReviewDuplicateButton.IsEnabled = available && DuplicateMatches.SelectedItem is MemoryItem;
        SaveButton.IsEnabled = available && !stale && !missing && !outcomeUnknown && Dirty && validDraft;
        DraftValidationText.Text = $"标题 {TitleBox.Text.EnumerateRunes().Count()} / 160 字符 · 正文 {ContentBox.Text.Length} / 2000 UTF-16 单位（{Encoding.UTF8.GetByteCount(ContentBox.Text)} / 8192 UTF-8 字节）";
        if (Dirty && !validDraft) DraftValidationText.Text += "\n暂不能保存：标题和正文均须填写有效文字，且不能超过上限。请修改草稿；内容不会自动截断。";
        LifecycleButton.IsEnabled = DeleteButton.IsEnabled = available && loaded is not null && !stale && !missing && !outcomeUnknown;
        ReloadButton.IsEnabled = available && loaded is not null && !missing;
        PreviousButton.IsEnabled = available && results?.HasPrevious == true;
        NextButton.IsEnabled = available && results?.HasNext == true;
        LifecycleButton.Content = loaded?.Status == MemoryStatus.ARCHIVED ? "Restore" : "Archive";
        MetadataText.Text = loaded is null ? "New · 尚未保存" :
            $"{loaded.Id:D}\n{loaded.Status} · Revision {loaded.Revision}\nCreated {loaded.CreatedAt:yyyy-MM-dd HH:mm:ss zzz}\nUpdated {loaded.UpdatedAt:yyyy-MM-dd HH:mm:ss zzz}";
        if (Dirty) MetadataText.Text += " · 未保存";
        if (stale) MetadataText.Text += " · 需重新加载";
        if (missing) MetadataText.Text += " · 已不存在";
        if (outcomeUnknown) MetadataText.Text += " · 操作结果待确认";
    }
    private void LoadEditor(MemoryItem? item)
    {
        ClearReview();
        ClearDuplicates();
        rendering = true;
        loaded = item; stale = missing = outcomeUnknown = false;
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
    private async Task RunAsync(Func<Task> operation, bool affectsLoaded = true, bool mutation = false)
    {
        if (busy || closed) return;
        busy = true; writing = mutation; UpdateControls(); StatusText.Text = "正在处理 Memory…";
        try { await operation(); }
        catch (DesktopException error)
        {
            if (closed) return;
            if (mutation && error.Error is DesktopError.OutcomeUnknown or DesktopError.ClientTimeout
                or DesktopError.RuntimeUnavailable or DesktopError.InvalidResponse) outcomeUnknown = true;
            if (affectsLoaded && error.Error == DesktopError.MemoryRevisionConflict) stale = true;
            if (affectsLoaded && error.Error == DesktopError.MemoryNotFound) missing = true;
            StatusText.Text = ErrorText.For(error.Error) + (outcomeUnknown ? "\n" + UnknownOutcomeMessage : "");
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!closed) StatusText.Text = ErrorText.For(DesktopError.InternalError); }
        finally { writing = false; if (!closed) { busy = false; UpdateControls(); } }
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
        ReviewTarget.ItemsSource = page.Items;
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
        ReviewTarget.ItemsSource = null;
        await FetchListAsync();
        if (!closed) StatusText.Text = outcomeUnknown ? UnknownOutcomeMessage : stale ? ErrorText.For(DesktopError.MemoryRevisionConflict)
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
        if (busy || closed || stale || missing || outcomeUnknown || !Dirty) return Task.CompletedTask;
        return RunAsync(async () =>
        {
            var saved = loaded is null
                ? await runtime.CreateMemoryAsync(new(SelectedType, TitleBox.Text, ContentBox.Text), lifetime.Token)
                : await runtime.UpdateMemoryAsync(loaded.Id, new(loaded.Revision, SelectedType, TitleBox.Text, ContentBox.Text), lifetime.Token);
            if (closed) return;
            LoadEditor(saved);
            StatusText.Text = "Memory 已保存。";
            await RefreshAfterMutationAsync();
        }, mutation: true);
    }
    internal Task ChangeStatusAsync()
    {
        if (busy || closed || loaded is null || stale || missing || outcomeUnknown) return Task.CompletedTask;
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
        }, mutation: true);
    }
    internal Task DeleteAsync()
    {
        if (busy || closed || loaded is null || stale || missing || outcomeUnknown || !confirm(MemoryConfirmation.Delete)) return Task.CompletedTask;
        return RunAsync(async () =>
        {
            await runtime.DeleteMemoryAsync(loaded.Id, loaded.Revision, lifetime.Token);
            if (closed) return;
            LoadEditor(null); StatusText.Text = "Memory 已删除。";
            await RefreshAfterMutationAsync();
        }, mutation: true);
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
    private void ClearReview()
    {
        reviewed = null;
        ReviewTitle.Clear(); ReviewContent.Clear(); ReviewMetadata.Text = "";
    }
    private void ClearDuplicates()
    {
        duplicateBody = null;
        DuplicateMatches.ItemsSource = null;
        DuplicateSummary.Text = "尚未检查当前正文。修改正文后请重新检查。";
    }
    internal Task CheckDuplicatesAsync()
    {
        string body = ContentBox.Text;
        if (!new MemoryCreateInput(MemoryType.PROJECT_NOTE, "检查", body).IsValid()) return Task.CompletedTask;
        return RunAsync(async () =>
        {
            ClearDuplicates(); DuplicatesTab.IsSelected = true;
            DuplicateSummary.Text = "正在读取相同正文的候选记录；尚不能判断是否重复。";
            try
            {
                string query = string.Concat(body.EnumerateRunes().Take(160).Select(x => x.ToString()));
                var active = await runtime.ListMemoryAsync(new(Query: query, Limit: 20), lifetime.Token);
                if (closed) return;
                var archived = await runtime.ListMemoryAsync(new(Query: query, Status: MemoryStatus.ARCHIVED, Limit: 20), lifetime.Token);
                if (closed) return;
                // Both bounded list responses already include full bodies. No automatic detail-read loop.
                var matches = active.Items.Concat(archived.Items)
                    .Where(x => x.Id != loaded?.Id && x.Content == body).DistinctBy(x => x.Id).ToArray();
                duplicateBody = body;
                DuplicateMatches.ItemsSource = matches;
                DuplicateSummary.Text = $"使用中：已核对 {active.Items.Count} / {active.Total} 条搜索结果；已归档：{archived.Items.Count} / {archived.Total} 条。\n"
                    + $"本次找到 {matches.Length} 条相同正文（不含当前编辑记录）。结果对应检查时的内容。";
                if (active.HasNext || archived.HasNext)
                    DuplicateSummary.Text += "\n检查不完整：还有未核对的记录，不能据此排除重复。可用上方搜索继续查找。";
                StatusText.Text = "草稿查重已结束。选择结果后可保留草稿并对照；不会自动合并或保存。";
            }
            catch
            {
                if (!closed) { ClearDuplicates(); DuplicateSummary.Text = "未能完成检查，不能判断是否重复。草稿已保留，可稍后重新检查。"; }
                throw;
            }
        }, affectsLoaded: false);
    }
    private void ShowReview(MemoryItem item)
    {
        reviewed = item;
        ReviewTitle.Text = item.Title; ReviewContent.Text = item.Content;
        ReviewMetadata.Text = $"{item.Id:D}\n{item.Type} · {item.Status} · Revision {item.Revision}\nUpdated {item.UpdatedAt:yyyy-MM-dd HH:mm:ss zzz}";
        if (item.Status == MemoryStatus.ARCHIVED) ReviewMetadata.Text += "\n已归档：仅供对照，不能采用为修订目标。";
        ReviewPicker.IsExpanded = false;
        ReviewTab.IsSelected = true;
    }
    internal Task ReviewItemAsync(MemoryItem item) => RunAsync(async () =>
    {
        ClearReview();
        var latest = await runtime.GetMemoryAsync(item.Id, lifetime.Token);
        if (closed) return;
        ShowReview(latest);
        StatusText.Text = "已读取当前保存内容，请与右侧草稿对照。没有写入或替换草稿。";
    }, affectsLoaded: item.Id == loaded?.Id);
    internal Task AdoptReviewAsync()
    {
        if (busy || closed || !CanAdoptReview
            || !confirm(loaded is null ? MemoryConfirmation.AdoptReview : MemoryConfirmation.ContinueReview)) return Task.CompletedTask;
        var expected = reviewed!;
        return RunAsync(async () =>
        {
            ClearReview();
            var latest = await runtime.GetMemoryAsync(expected.Id, lifetime.Token);
            if (closed) return;
            ShowReview(latest);
            if (latest != expected)
            {
                StatusText.Text = "这条记忆已变更，请重新对照左侧最新内容再采用。右侧草稿已保留。";
                return;
            }
            // Change only the update target. The exact local draft remains in the editor.
            loaded = latest; stale = missing = false;
            SelectLoaded();
            StatusText.Text = "草稿已用于修订这条记忆，尚未保存。点击 Save 才会更新，不会另建一条。";
        }, affectsLoaded: false);
    }
    private void ReviewTargetChanged(object sender, SelectionChangedEventArgs e)
    { if (initialized) { ClearReview(); UpdateControls(); } }
    private async void ReviewSelected(object sender, RoutedEventArgs e)
    { if (ReviewTarget.SelectedItem is MemoryItem item) await ReviewItemAsync(item); }
    private async void AdoptReview(object sender, RoutedEventArgs e) => await AdoptReviewAsync();
    private async void ReviewCurrent(object sender, RoutedEventArgs e)
    { if (loaded is not null) await ReviewItemAsync(loaded); }
    private async void CheckDuplicates(object sender, RoutedEventArgs e) => await CheckDuplicatesAsync();
    private void DuplicateSelected(object sender, SelectionChangedEventArgs e) { if (initialized) UpdateControls(); }
    private async void ReviewDuplicate(object sender, RoutedEventArgs e)
    { if (DuplicateMatches.SelectedItem is MemoryItem item) await ReviewItemAsync(item); }
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
    private void EditorTextChanged(object sender, TextChangedEventArgs e)
    {
        if (!rendering)
        {
            if (duplicateBody is not null && duplicateBody != ContentBox.Text) ClearDuplicates();
            UpdateControls();
        }
    }
    private void EditorTypeChanged(object sender, SelectionChangedEventArgs e) { if (!rendering) UpdateControls(); }
    private void New(object sender, RoutedEventArgs e) => NewItem();
    private async void Save(object sender, RoutedEventArgs e) => await SaveAsync();
    private async void Lifecycle(object sender, RoutedEventArgs e) => await ChangeStatusAsync();
    private async void Delete(object sender, RoutedEventArgs e) => await DeleteAsync();
    private async void Reload(object sender, RoutedEventArgs e) => await ReloadAsync();
    private void CloseMemory(object sender, RoutedEventArgs e) => Close();
    private void OpenBackup(object sender, RoutedEventArgs e)
    { if (!busy && !closed) new MemoryBackupWindow(runtime) { Owner = this }.ShowDialog(); }
    private void WindowClosing(object? sender, CancelEventArgs e)
    {
        // Closing cancels the lifetime token. A dispatched write needs its bounded result first,
        // including lifecycle/delete writes that have no dirty draft to trigger confirmation.
        if (writing)
        {
            e.Cancel = true;
            StatusText.Text = "Memory 写入操作尚未结束，请等待结果后再关闭。";
            return;
        }
        if (Dirty && !confirm(MemoryConfirmation.Discard)) e.Cancel = true;
    }
    private void WindowClosed(object? sender, EventArgs e)
    {
        closed = true; lifetime.Cancel(); rendering = true;
        TitleBox.Clear(); ContentBox.Clear(); SearchBox.Clear(); MemoryList.ItemsSource = null;
        ReviewTarget.ItemsSource = null; ClearReview();
        ClearDuplicates(); DuplicateSummary.Text = "";
        loaded = null; results = null; view = new(); stale = missing = outcomeUnknown = false;
        DraftValidationText.Text = MetadataText.Text = PageText.Text = StatusText.Text = "";
        lifetime.Dispose();
    }
}
