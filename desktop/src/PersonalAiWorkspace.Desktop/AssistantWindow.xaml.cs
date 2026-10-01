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
    Task TranslateAsync();
    void CancelOperation();
    Task CheckHealthAsync();
    Task ImportCredentialAsync(string file);
    void ForgetCredential();
}
public partial class AssistantWindow : Window
{
    private readonly IAssistantController app;
    internal AssistantWindow(IAssistantController app)
    {
        this.app = app;
        InitializeComponent();
        Closing += HideOnClose;
    }
    internal bool AllowCopyFallback => CopyFallback.IsChecked == true;
    internal void SetBusy(bool busy)
    {
        InputText.IsReadOnly = busy;
        TranslateButton.IsEnabled = !busy;
        TargetLanguage.IsEnabled = !busy;
        ImportButton.IsEnabled = !busy;
        ForgetButton.IsEnabled = !busy;
        CancelButton.IsEnabled = busy;
        CopyButton.IsEnabled = !busy && ResultText.Text.Length != 0;
    }
    internal void ClearText()
    {
        InputText.Clear(); ResultText.Clear(); CopyButton.IsEnabled = false;
    }
    private void HideOnClose(object? sender, CancelEventArgs e)
    {
        if (app.Exiting) return;
        e.Cancel = true;
        Hide();
        if (!app.Busy) ClearText();
    }
    private async void Translate(object sender, RoutedEventArgs e) => await app.TranslateAsync();
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
}
