using System;
using System.ComponentModel;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;

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
    private BrowserPairingWindow? pairingWindow;
    private MemoryWindow? memoryWindow;
    private MemorySelectionWindow? selectionWindow;
    private IReadOnlyList<MemorySelection> selectedMemory = Array.Empty<MemorySelection>();
    internal bool MemoryNeedsReview { get; private set; }
    internal IReadOnlyList<MemoryReference> MemoryReferences => Array.AsReadOnly(selectedMemory.Select(x => x.Reference).ToArray());
    internal AssistantWindow(IAssistantController app, RuntimeClient? runtime = null)
    {
        this.app = app;
        this.runtime = runtime;
        InitializeComponent();
        Closing += HideOnClose;
    }
    internal bool AllowCopyFallback => CopyFallback.IsChecked == true;
    internal void SetBusy(bool busy)
    {
        InputText.IsReadOnly = busy;
        TranslateButton.IsEnabled = !busy;
        TargetLanguage.IsEnabled = !busy;
        ActionSelector.IsEnabled = !busy;
        ImportButton.IsEnabled = !busy;
        ForgetButton.IsEnabled = !busy;
        PairBrowserButton.IsEnabled = !busy && runtime is not null;
        MemoryButton.IsEnabled = !busy && runtime is not null;
        UseMemoryButton.IsEnabled = !busy && runtime is not null;
        ClearMemoryButton.IsEnabled = !busy && selectedMemory.Count > 0;
        TranslateButton.IsEnabled = !busy && !MemoryNeedsReview;
        CancelButton.IsEnabled = busy;
        CopyButton.IsEnabled = !busy && ResultText.Text.Length != 0;
    }
    internal void ClearText()
    {
        InputText.Clear(); ResultText.Clear(); CopyButton.IsEnabled = false;
        ClearMemorySelection();
    }
    private void HideOnClose(object? sender, CancelEventArgs e)
    {
        if (!CloseMemory()) { e.Cancel = true; return; }
        CloseBrowserPairing();
        CloseMemorySelector(); ClearMemorySelection();
        if (app.Exiting) return;
        e.Cancel = true;
        Hide();
        if (!app.Busy) ClearText();
    }
    private async void Translate(object sender, RoutedEventArgs e) => await app.SubmitAsync();
    internal AssistantAction SelectedAction => (AssistantAction)ActionSelector.SelectedIndex;
    internal void SelectTranslate() => ActionSelector.SelectedIndex = 0;
    private void ActionChanged(object sender, SelectionChangedEventArgs e)
    {
        // Initial XAML selection fires before later controls have been constructed.
        if (InputText is null) return;
        ClearText();
        MemoryAskPanel.Visibility = SelectedAction == AssistantAction.Ask ? Visibility.Visible : Visibility.Collapsed;
        bool translate = SelectedAction == AssistantAction.Translate;
        TargetLanguage.Visibility = LanguageLabel.Visibility = translate ? Visibility.Visible : Visibility.Collapsed;
        TranslateButton.Content = ((ComboBoxItem)ActionSelector.SelectedItem).Content;
        InputText.MaxLength = SelectedAction switch { AssistantAction.Summarize => 6000, AssistantAction.Ask => 3000, _ => 4000 };
        InputLabel.Text = SelectedAction == AssistantAction.Ask ? "Question · 单轮问题，无历史" : "Input · 可手动输入或粘贴";
        StatusText.Text = "Ready · " + TranslateButton.Content;
    }
    internal string SelectedLanguage => ((ComboBoxItem)TargetLanguage.SelectedItem).Content.ToString()!;
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
        catch (ExternalException) { StatusText.Text = "Clipboard unavailable：请稍后重试复制。"; }
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
    private void OpenMemory(object sender, RoutedEventArgs e)
    {
        if (app.Busy || app.Exiting || runtime is null || memoryWindow is not null) return;
        memoryWindow = new MemoryWindow(runtime) { Owner = this };
        try { memoryWindow.ShowDialog(); }
        finally { memoryWindow = null; }
    }
    internal bool CloseMemory() => memoryWindow?.TryClose() ?? true;
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
        MemoryCountText.Text = $"Memory: {selectedMemory.Count} selected" + (MemoryNeedsReview ? " · Review required" : "");
        MemorySelectionText.Text = string.Join("\n", selectedMemory.Select(x => $"{x.Title} · {x.Type} · Revision {x.Revision}"));
        UseMemoryButton.Content = selectedMemory.Count == 0 ? "Use Memory…" : "Review / Change Memory…";
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
