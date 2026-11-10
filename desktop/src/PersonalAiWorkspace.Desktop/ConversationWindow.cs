using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal enum ConversationDraftAction { Switch, Create }

// Minimal native surface. Runtime owns all history admission and prompt assembly.
public sealed class ConversationWindow : Window
{
    private readonly RuntimeClient runtime;
    private readonly Func<ConversationDraftAction, bool> confirmDraft;
    private readonly CancellationTokenSource lifetime = new();
    internal readonly ComboBox Conversations = new() { DisplayMemberPath = "Title", MinWidth = 220 };
    internal readonly TextBox Input = new() { AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MaxLength = 3000, Height = 110, IsUndoEnabled = false };
    internal readonly TextBox History = new() { IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, IsUndoEnabled = false };
    internal readonly TextBlock State = new() { TextWrapping = TextWrapping.Wrap };
    internal readonly TextBlock MemoryState = new() { Text = "Memory: 0 selected", TextWrapping = TextWrapping.Wrap };
    internal readonly Button NewButton = new() { Content = "New Conversation" }, RefreshButton = new() { Content = "Refresh / Reopen" },
        SendButton = new() { Content = "Send" }, CancelButton = new() { Content = "Cancel", IsEnabled = false },
        MemoryButton = new() { Content = "Use Memory…" }, ClearMemoryButton = new() { Content = "Clear Memory" },
        ArchiveButton = new() { Content = "Archive" }, PreviousButton = new() { Content = "Previous" }, NextButton = new() { Content = "Next" };
    internal ConversationDetail? Detail { get; private set; }
    internal Guid? ActiveTask { get; private set; }
    internal bool Busy { get; private set; }
    internal bool NeedsReview { get; private set; }
    private IReadOnlyList<MemoryReference> memories = Array.Empty<MemoryReference>();
    private MemorySelectionWindow? picker;
    private bool closed, rendering;
    private int historyPage;
    internal ConversationWindow(RuntimeClient runtime, Func<ConversationDraftAction, bool>? confirmation = null)
    {
        this.runtime = runtime; Title = "Conversation · Local Only"; Width = 720; Height = 740; MinWidth = 550; MinHeight = 600;
        confirmDraft = confirmation ?? (action => MessageBox.Show(this,
            action == ConversationDraftAction.Create ? "新建对话并丢弃当前未发送消息？\n取消或打开失败会保留草稿。" : "切换会话并丢弃当前未发送消息？\n取消或读取失败会保留草稿。",
            "Conversation", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No) == MessageBoxResult.Yes);
        var grid = new Grid { Margin = new Thickness(16) };
        foreach (var height in new[] { GridLength.Auto, GridLength.Auto, new GridLength(1,GridUnitType.Star), GridLength.Auto, GridLength.Auto, GridLength.Auto })
            grid.RowDefinitions.Add(new RowDefinition { Height = height });
        var top = Row(Conversations, NewButton, RefreshButton, ArchiveButton); grid.Children.Add(top);
        var pages = Row(PreviousButton, NextButton); Grid.SetRow(pages,1); grid.Children.Add(pages);
        Grid.SetRow(History,2); grid.Children.Add(History);
        var selection = Row(MemoryButton, ClearMemoryButton, MemoryState); Grid.SetRow(selection,3); grid.Children.Add(selection);
        Grid.SetRow(Input,4); grid.Children.Add(Input);
        var bottom = Row(SendButton, CancelButton, State); Grid.SetRow(bottom,5); grid.Children.Add(bottom); Content = grid;
        Loaded += async (_,_) => await RunAsync(RefreshAsync);
        NewButton.Click += async (_,_) => await NewAsync();
        RefreshButton.Click += async (_,_) => await RunAsync(RefreshAsync);
        Conversations.SelectionChanged += async (_,_) => {
            if (!rendering && !closed && Conversations.SelectedItem is Conversation item) await SelectAsync(item);
        };
        PreviousButton.Click += async (_,_) => await RunAsync(() => LoadDetailAsync(historyPage - 1));
        NextButton.Click += async (_,_) => await RunAsync(() => LoadDetailAsync(historyPage + 1));
        SendButton.Click += async (_,_) => await SendAsync();
        CancelButton.Click += async (_,_) => await CancelAsync();
        ArchiveButton.Click += async (_,_) => await RunAsync(async () => {
            if (Detail is null) return; await runtime.ArchiveConversationAsync(Detail.Conversation.Id,lifetime.Token); await LoadDetailAsync();
        });
        ClearMemoryButton.Click += (_,_) => ClearSelection();
        MemoryButton.Click += (_,_) => {
            if (Busy || closed) return;
            picker = new MemorySelectionWindow(runtime) { Owner = this };
            try { picker.ShowDialog(); if (!closed && picker.Selection is { } selected) ApplySelection(selected); }
            finally { picker = null; }
        };
        Closed += (_,_) => {
            closed=true; lifetime.Cancel(); picker?.Close(); memories=Array.Empty<MemoryReference>();
            Input.Clear(); History.Clear(); State.Text=MemoryState.Text=""; Conversations.ItemsSource=null; Detail=null; ActiveTask=null;
        };
        Controls();
    }
    private static WrapPanel Row(params UIElement[] children) {
        var row = new WrapPanel { Margin = new Thickness(0,6,0,6) };
        foreach (var child in children) { if (child is FrameworkElement element) element.Margin=new Thickness(0,0,8,0); row.Children.Add(child); }
        return row;
    }
    private void Controls() {
        SendButton.IsEnabled=!Busy && !NeedsReview && Detail?.Conversation.Status==ConversationStatus.ACTIVE && ActiveTask is null;
        CancelButton.IsEnabled=ActiveTask is not null;
        Conversations.IsEnabled=NewButton.IsEnabled=RefreshButton.IsEnabled=MemoryButton.IsEnabled=ClearMemoryButton.IsEnabled=!Busy;
        ArchiveButton.IsEnabled=!Busy && Detail?.Conversation.Status==ConversationStatus.ACTIVE;
        Input.IsReadOnly=Busy;
        PreviousButton.IsEnabled=!Busy && historyPage>0;
        NextButton.IsEnabled=!Busy && Detail is not null && (historyPage+1)*10<Detail.TotalTurns;
    }
    private async Task RunAsync(Func<Task> work) {
        if (Busy || closed) return; Busy=true; Controls();
        try { await work(); }
        catch (OperationCanceledException) { }
        catch (DesktopException ex) { if (!closed) State.Text=ErrorText.For(ex.Error); }
        finally { Busy=false; if (!closed) Controls(); }
    }
    private Task RefreshAsync() => RefreshAsync(null);
    private async Task RefreshAsync(Conversation? preferred) {
        var current=preferred ?? Detail?.Conversation ?? Conversations.SelectedItem as Conversation;
        var items=new List<Conversation>();
        for (int page=0;page<100;page++) {
            var result=await runtime.ListConversationsAsync(ConversationStatus.ACTIVE,page,10,lifetime.Token); items.AddRange(result.Items);
            if (items.Count>=result.Total) break;
        }
        if (closed) return;
        // Retain the current identity even if it disappeared from the ACTIVE list.
        // Read it explicitly instead of silently moving its draft into another conversation.
        var item=items.FirstOrDefault(x=>x.Id==current?.Id) ?? current ?? items.FirstOrDefault();
        var next=item is null ? null : await ReadLatestAsync(item.Id);
        if (closed) return;
        if (item is not null && items.All(x=>x.Id!=item.Id)) items.Insert(0,next!.Conversation);
        rendering=true; Conversations.ItemsSource=items; rendering=false;
        CommitConversation(next);
    }
    internal async Task NewAsync() {
        if (Busy || closed || Input.Text.Length>0 && !confirmDraft(ConversationDraftAction.Create)) return;
        await RunAsync(async () => {
            var item=await runtime.CreateConversationAsync(null,lifetime.Token);
            if (closed) return;
            try { await RefreshAsync(item); }
            catch (DesktopException ex) {
                if (!closed) State.Text=ErrorText.For(ex.Error)+" 新会话已创建，但未能打开。当前草稿已保留；可刷新列表后打开，无需再次新建。";
            }
        });
    }
    internal async Task SelectAsync(Conversation item) {
        RestoreSelected();
        if (Busy || closed || item.Id==Detail?.Conversation.Id
            || Input.Text.Length>0 && !confirmDraft(ConversationDraftAction.Switch)) return;
        await RunAsync(async () => {
            var next=await ReadLatestAsync(item.Id);
            if (!closed) CommitConversation(next);
        });
    }
    private void RestoreSelected() {
        rendering=true;
        Conversations.SelectedItem=Conversations.Items.Cast<Conversation>().FirstOrDefault(x=>x.Id==Detail?.Conversation.Id);
        rendering=false;
    }
    private async Task<ConversationDetail> ReadLatestAsync(Guid id) {
        var first=await runtime.GetConversationAsync(id,0,10,lifetime.Token);
        if (closed || first.TotalTurns<=10) return first;
        return await runtime.GetConversationAsync(id,(first.TotalTurns-1)/10,10,lifetime.Token);
    }
    private void CommitConversation(ConversationDetail? next) {
        if (next?.Conversation.Id!=Detail?.Conversation.Id) Input.Clear();
        ClearSelection(); ActiveTask=null;
        if (next is null) { Detail=null; historyPage=0; History.Clear(); State.Text="Create a Conversation to begin."; }
        else ShowDetail(next);
        RestoreSelected();
    }
    private Task LoadDetailAsync() => LoadDetailAsync(historyPage);
    private async Task LoadDetailAsync(int page) {
        if (Conversations.SelectedItem is not Conversation item) { Detail=null; History.Clear(); State.Text="Create a Conversation to begin."; return; }
        var detail=await runtime.GetConversationAsync(item.Id,page,10,lifetime.Token);
        if (!closed) ShowDetail(detail);
    }
    private void ShowDetail(ConversationDetail detail) {
        Detail=detail; historyPage=detail.Page;
        History.Text=string.Join("\n\n",detail.Turns.Select(x=> $"Turn {x.Sequence} · USER\n{x.UserMessage.Content}\n\n"
            +(x.AssistantMessage is { } assistant ? $"ASSISTANT\n{assistant.Content}" : $"[{x.Status}{(x.FailureCode is { } failure ? " · "+failure : "")}]")));
        State.Text=$"{detail.Conversation.Status} · Page {historyPage+1} · {detail.TotalTurns} Turns";
        var pending=detail.Turns.FirstOrDefault(x=>x.Status==ConversationTurnStatus.PENDING);
        if (pending is not null || historyPage==Math.Max(0,(detail.TotalTurns-1)/10)) ActiveTask=pending?.TaskId;
    }
    internal void ApplySelection(IReadOnlyList<MemorySelection> selected) {
        if (closed) return;
        new MemoryAskInput("validation",selected.Select(x=>x.Reference).ToArray()).Validate();
        memories=Array.AsReadOnly(selected.Select(x=>x.Reference).ToArray()); NeedsReview=false;
        MemoryState.Text=$"Memory: {memories.Count} selected\n"+string.Join("\n",selected.Select(x=>$"{x.Title} · Revision {x.Revision}")); Controls();
    }
    private void ClearSelection() { memories=Array.Empty<MemoryReference>(); NeedsReview=false; MemoryState.Text="Memory: 0 selected"; Controls(); }
    internal async Task SendAsync() {
        if (Busy || NeedsReview || Detail?.Conversation.Status!=ConversationStatus.ACTIVE || ActiveTask is not null || closed) return;
        Busy=true; Controls(); bool accepted=false;
        try {
            var result=await runtime.SubmitConversationTurnAsync(Detail.Conversation.Id,Input.Text,memories,lifetime.Token);
            accepted=true; ActiveTask=result.TaskId; Input.Clear(); ClearSelection(); Controls();
            State.Text=result.Status.ToString();
            while (!closed) {
                var task=await runtime.GetConversationTaskAsync(result.TaskId,lifetime.Token);
                if (closed) return; State.Text=task.Status.ToString();
                if (task.Terminal) { ActiveTask=null; break; }
                await Task.Delay(200,lifetime.Token);
            }
            if (!closed) { historyPage=Math.Max(0,Detail.TotalTurns/10); await LoadDetailAsync(); }
        }
        catch (DesktopException ex) {
            if (!closed) {
                State.Text=ErrorText.For(ex.Error);
                if (ex.Error==DesktopError.MemorySelectionStale) { NeedsReview=true; MemoryState.Text="Selected Memory changed. Review or clear Memory."; }
                if (accepted || ex.Error is DesktopError.RuntimeUnavailable or DesktopError.ClientTimeout or DesktopError.InvalidResponse) ClearSelection();
                // Submission rejection may already have a durable FAILED USER turn.
                // Refresh it without resending; preserve the controlled error and draft.
                if (!accepted && ex.Error is DesktopError.QueueFull or DesktopError.ConversationStorageUnavailable or DesktopError.InternalError) {
                    try { await LoadDetailAsync(); if (!closed) State.Text=ErrorText.For(ex.Error); }
                    catch (DesktopException) { }
                }
            }
        }
        catch (OperationCanceledException) { }
        finally { Busy=false; if (!closed) Controls(); }
    }
    internal async Task CancelAsync() {
        if (ActiveTask is not { } id || closed) return;
        try {
            var task=await runtime.CancelConversationTaskAsync(id,lifetime.Token);
            if (closed) return;
            if (task.Terminal) ActiveTask=null;
            State.Text=task.Status.ToString();
            if (!Busy) await RunAsync(LoadDetailAsync);
        }
        catch (DesktopException ex) { if (!closed) State.Text=ErrorText.For(ex.Error); }
        catch (OperationCanceledException) { }
        Controls();
    }
}
