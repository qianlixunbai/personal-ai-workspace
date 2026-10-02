using System;
using System.ComponentModel;
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
        CancelButton.IsEnabled = busy;
        CopyButton.IsEnabled = !busy && ResultText.Text.Length != 0;
    }
    internal void ClearText()
    {
        InputText.Clear(); ResultText.Clear(); CopyButton.IsEnabled = false;
    }
    private void HideOnClose(object? sender, CancelEventArgs e)
    {
        CloseBrowserPairing();
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
}
