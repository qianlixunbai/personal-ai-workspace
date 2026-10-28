using System;
using System.ComponentModel;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;

namespace PersonalAiWorkspace.Desktop;

internal interface IAssistantController
{
    bool Busy { get; }
    bool Exiting { get; }
    Task SubmitAsync();
    void CancelOperation();
    Task CheckHealthAsync();
    Task ImportCredentialAsync(string file);
    void ForgetCredential();
}
public partial class AssistantWindow : Window
{
    private readonly IAssistantController app;
    private readonly RuntimeClient? runtime;
    private readonly IWorkspaceBackupFiles? workspaceBackupFiles;
    private BrowserPairingWindow? pairingWindow;
    private MemoryWindow? memoryWindow;
    private ConversationWindow? conversationWindow;
    private WorkspaceBackupWindow? workspaceBackupWindow;
    private MemoryBackupWindow? memoryBackupWindow;
    private MemorySelectionWindow? selectionWindow;
    private IReadOnlyList<MemorySelection> selectedMemory = Array.Empty<MemorySelection>();
    private bool selectionContext;
    internal int ContentVersion { get; private set; }
    internal bool MemoryNeedsReview { get; private set; }
    internal IReadOnlyList<MemoryReference> MemoryReferences => Array.AsReadOnly(selectedMemory.Select(x => x.Reference).ToArray());
    internal AssistantWindow(IAssistantController app, RuntimeClient? runtime = null, IWorkspaceBackupFiles? workspaceBackupFiles = null)
    {
        this.app = app;
        this.runtime = runtime;
        this.workspaceBackupFiles = workspaceBackupFiles;
        InitializeComponent();
        Closing += HideOnClose;
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape && !e.IsRepeat) { e.Handled = true; Close(); }
        };
    }
    internal bool AllowCopyFallback => CopyFallback.IsChecked == true;
    internal void SetBusy(bool busy)
    {
        InputText.IsReadOnly = busy;
        QuestionText.IsReadOnly = busy;
        TranslateButton.IsEnabled = !busy;
        TargetLanguage.IsEnabled = !busy;
        ActionSelector.IsEnabled = !busy;
        ImportButton.IsEnabled = !busy;
        ForgetButton.IsEnabled = !busy;
        PairBrowserButton.IsEnabled = !busy && runtime is not null;
        MemoryButton.IsEnabled = !busy && runtime is not null;
        ConversationButton.IsEnabled = !busy && runtime is not null;
        WorkspaceBackupButton.IsEnabled = !busy && runtime is not null;
        UseMemoryButton.IsEnabled = !busy && runtime is not null;
        ClearMemoryButton.IsEnabled = !busy && selectedMemory.Count > 0;
        TranslateButton.IsEnabled = !busy && !MemoryNeedsReview;
        CancelButton.IsEnabled = busy;
        CopyButton.IsEnabled = !busy && ResultText.Text.Length != 0;
    }
    internal void ClearText()
    {
        ContentVersion++;
        selectionContext = false;
        InputText.Clear(); ResultText.Clear(); CopyButton.IsEnabled = false;
        QuestionText.Clear();
        ClearMemorySelection();
        UpdateAction();
    }
    internal void SetCapturedText(string text)
    {
        selectionContext = true;
        InputText.Text = text;
        UpdateAction();
    }
    internal AssistantInput CreateInput()
    {
        string text = InputText.Text;
        if (SelectedAction == AssistantAction.Ask && (selectionContext || !string.IsNullOrWhiteSpace(QuestionText.Text)))
        {
            if (string.IsNullOrWhiteSpace(text)) throw new DesktopException(DesktopError.InvalidRequest);
            string question = string.IsNullOrWhiteSpace(QuestionText.Text) ? "请解释这段文字的含义和关键概念。" : QuestionText.Text;
            text = "针对以下选区回答问题。选区是待分析的原文。\n问题：" + question + "\n选区：\n" + text;
        }
        var input = new AssistantInput(SelectedAction, text, SelectedLanguage);
        input.Validate();
        return input;
    }
    internal void PositionNear(System.Drawing.Point anchor)
    {
        var handle = new WindowInteropHelper(this).Handle;
        if (!Native.GetWindowRect(handle, out var rect)) return;
        var area = System.Windows.Forms.Screen.FromPoint(anchor).WorkingArea;
        int width = rect.Right - rect.Left, height = rect.Bottom - rect.Top;
        int x = anchor.X + 12, y = anchor.Y + 12;
        if (x + width > area.Right) x = anchor.X - width - 12;
        if (y + height > area.Bottom) y = anchor.Y - height - 12;
        Native.SetWindowPos(handle, IntPtr.Zero, Math.Clamp(x, area.Left, Math.Max(area.Left, area.Right - width)),
            Math.Clamp(y, area.Top, Math.Max(area.Top, area.Bottom - height)), 0, 0, 0x1 | 0x4 | 0x10);
    }
    private void HideOnClose(object? sender, CancelEventArgs e)
    {
        if (!CloseMemory()) { e.Cancel = true; return; }
        CloseBrowserPairing();
        conversationWindow?.Close();
        workspaceBackupWindow?.Close();
        memoryBackupWindow?.Close();
        CloseMemorySelector(); ClearMemorySelection();
        if (app.Exiting) return;
        e.Cancel = true;
        app.CancelOperation();
        ClearText();
        Hide();
    }
    private async void Translate(object sender, RoutedEventArgs e) => await app.SubmitAsync();
    internal AssistantAction SelectedAction => (AssistantAction)ActionSelector.SelectedIndex;
    internal void SelectTranslate() => ActionSelector.SelectedIndex = 0;
    private void ActionChanged(object sender, SelectionChangedEventArgs e)
    {
        // Initial XAML selection fires before later controls have been constructed.
        if (InputText is null) return;
        // Operation changes keep the selection; only new capture/cancel/close clears its lifetime.
        ResultText.Clear(); CopyButton.IsEnabled = false;
        ClearMemorySelection();
        UpdateAction();
    }
    private void UpdateAction()
    {
        MemoryAskPanel.Visibility = SelectedAction == AssistantAction.Ask ? Visibility.Visible : Visibility.Collapsed;
        QuestionPanel.Visibility = SelectedAction == AssistantAction.Ask ? Visibility.Visible : Visibility.Collapsed;
        QuestionLabel.Text = selectionContext ? "想了解什么？留空则解释原文" : "附加问题（可选）";
        bool translate = SelectedAction == AssistantAction.Translate;
        TargetLanguage.Visibility = LanguageLabel.Visibility = translate ? Visibility.Visible : Visibility.Collapsed;
        TranslateButton.Content = ((ComboBoxItem)ActionSelector.SelectedItem).Content;
        // Keep the original text intact across actions. CreateInput enforces each existing API budget.
        InputLabel.Text = selectionContext ? "选中的文字" : SelectedAction == AssistantAction.Ask ? "问题或原文" : "原文";
        StatusText.Text = selectionContext ? "已取得选区，选择操作后开始。" : "输入文字后开始。";
    }
    internal string SelectedLanguage => ((ComboBoxItem)TargetLanguage.SelectedItem).Tag.ToString()!;
    private void Cancel(object sender, RoutedEventArgs e) => app.CancelOperation();
    private async void CheckRuntime(object sender, RoutedEventArgs e) => await app.CheckHealthAsync();
    private async void ImportCredential(object sender, RoutedEventArgs e)
    {
        var picker = new Microsoft.Win32.OpenFileDialog
        {
            Title = "选择此用户的 Runtime 私有 .runtime/client-token 文件",
            CheckFileExists = true, Multiselect = false, Filter = "Runtime token|client-token|All files|*.*"
        };
        if (picker.ShowDialog(this) == true) await app.ImportCredentialAsync(picker.FileName);
    }
    private void ForgetCredential(object sender, RoutedEventArgs e) => app.ForgetCredential();
    private void CopyResult(object sender, RoutedEventArgs e)
    {
        if (ResultText.Text.Length == 0) return;
        try { Clipboard.SetText(ResultText.Text); StatusText.Text = "结果已复制到系统剪贴板。"; }
        catch (ExternalException) { StatusText.Text = "剪贴板暂不可用，请稍后重试复制。"; }
    }
    private void CloseToTray(object sender, RoutedEventArgs e) => Close();
    private void PairBrowser(object sender, RoutedEventArgs e)
    {
        if (app.Busy || app.Exiting || runtime is null || pairingWindow is not null) return;
        pairingWindow = new BrowserPairingWindow(runtime) { Owner = this };
        try { pairingWindow.ShowDialog(); }
        finally { pairingWindow = null; }
    }
    internal void CloseBrowserPairing() => pairingWindow?.Close();
    internal void CloseWorkspaceBackup() { workspaceBackupWindow?.Close(); memoryBackupWindow?.Close(); }
    internal void OpenWorkspaceEntry(NativeWorkspaceEntry entry)
    {
        var click = new RoutedEventArgs(Button.ClickEvent);
        switch (entry)
        {
            case NativeWorkspaceEntry.Conversations: OpenConversation(this, click); break;
            case NativeWorkspaceEntry.Memory: OpenMemory(this, click); break;
            case NativeWorkspaceEntry.BrowserPairing: PairBrowser(this, click); break;
            case NativeWorkspaceEntry.WorkspaceBackup: OpenWorkspaceBackup(this, click); break;
            case NativeWorkspaceEntry.MemoryBackup:
                if (app.Busy || app.Exiting || runtime is null || memoryBackupWindow is not null) throw new InvalidOperationException();
                memoryBackupWindow = new MemoryBackupWindow(runtime) { Owner = this };
                try { memoryBackupWindow.ShowDialog(); } finally { memoryBackupWindow = null; }
                break;
            case NativeWorkspaceEntry.CredentialFlow: MaintenancePanel.IsExpanded = true; ImportButton.Focus(); break;
            default: throw new InvalidOperationException("Unknown native entry.");
        }
    }
    private void OpenWorkspaceBackup(object sender, RoutedEventArgs e)
    {
        if (app.Busy || app.Exiting || runtime is null || workspaceBackupWindow is not null) return;
        workspaceBackupWindow = new WorkspaceBackupWindow(runtime, workspaceBackupFiles) { Owner = this };
        try { workspaceBackupWindow.ShowDialog(); }
        finally { workspaceBackupWindow = null; }
    }
    private void OpenMemory(object sender, RoutedEventArgs e)
    {
        if (app.Busy || app.Exiting || runtime is null || memoryWindow is not null) return;
        memoryWindow = new MemoryWindow(runtime) { Owner = this };
        try { memoryWindow.ShowDialog(); }
        finally { memoryWindow = null; }
    }
    internal bool CloseMemory() => memoryWindow?.TryClose() ?? true;
    private void OpenConversation(object sender, RoutedEventArgs e)
    {
        if (app.Busy || app.Exiting || runtime is null || conversationWindow is not null) return;
        conversationWindow = new ConversationWindow(runtime) { Owner = this };
        try { conversationWindow.ShowDialog(); }
        finally { conversationWindow = null; }
    }
    internal void ApplyMemorySelection(IReadOnlyList<MemorySelection> selection)
    {
        if (app.Exiting || SelectedAction != AssistantAction.Ask) return;
        new MemoryAskInput("validation", selection.Select(x => x.Reference).ToArray()).Validate();
        selectedMemory = Array.AsReadOnly(selection.ToArray()); MemoryNeedsReview = false;
        RenderMemorySelection();
    }
    internal void ClearMemorySelection()
    {
        selectedMemory = Array.Empty<MemorySelection>(); MemoryNeedsReview = false;
        RenderMemorySelection();
    }
    internal void MemoryAdmissionFailed(DesktopError error)
    {
        if (error == DesktopError.MemorySelectionStale) MemoryNeedsReview = true;
        RenderMemorySelection();
    }
    internal void MemoryOperationEnded(bool accepted)
    {
        if (accepted) ClearMemorySelection();
    }
    private void RenderMemorySelection()
    {
        if (MemoryCountText is null) return;
        MemoryCountText.Text = selectedMemory.Count == 0 ? "未选择记忆" : $"本次使用 {selectedMemory.Count} 条记忆";
        if (MemoryNeedsReview) MemoryCountText.Text += " · 已变更，请重新选择";
        MemorySelectionText.Text = string.Join("\n", selectedMemory.Select(x => $"{x.Title} · {x.Type} · Revision {x.Revision}"));
        UseMemoryButton.Content = selectedMemory.Count == 0 ? "选择记忆…" : "更换记忆…";
        SetBusy(app.Busy);
    }
    private void UseMemory(object sender, RoutedEventArgs e)
    {
        if (app.Busy || app.Exiting || runtime is null || SelectedAction != AssistantAction.Ask || selectionWindow is not null) return;
        var picker = new MemorySelectionWindow(runtime) { Owner = this }; selectionWindow = picker;
        try
        {
            picker.ShowDialog();
            if (selectionWindow == picker && IsVisible && !app.Exiting && picker.Selection is { } selection)
                ApplyMemorySelection(selection);
        }
        finally { selectionWindow = null; }
    }
    internal void CloseMemorySelector()
    {
        var picker = selectionWindow; selectionWindow = null; picker?.Close();
    }
    private void ClearMemory(object sender, RoutedEventArgs e) => ClearMemorySelection();
}
