using System;
using System.ComponentModel;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;

namespace PersonalAiWorkspace.Desktop;

public partial class MainWorkspaceWindow : Window
{
    private readonly IWorkspaceNativeActions native;
    private bool shuttingDown;
    private bool closed;
    internal WorkspaceWebViewHost Host { get; }
    internal bool FallbackVisible => Fallback.Visibility == Visibility.Visible;
    internal MainWorkspaceWindow(IWorkspaceNativeActions native, string? assetFolder = null)
    {
        this.native = native;
        InitializeComponent();
        Host = new WorkspaceWebViewHost(Browser, native, message =>
        {
            Browser.Visibility = Visibility.Collapsed;
            FallbackTitle.Text = "工作区暂时不可用";
            FallbackMessage.Text = message;
            Fallback.Visibility = Visibility.Visible;
            NativeAssistantButton.Focus();
        }, assetFolder);
        Loaded += async (_, _) =>
        {
            await Host.InitializeAsync();
            if (Host.SessionId.Length > 0 && !closed) Fallback.Visibility = Visibility.Collapsed;
        };
        Browser.NavigationCompleted += (_, e) =>
        { if (e.IsSuccess && Host.SessionId.Length > 0 && !closed) Fallback.Visibility = Visibility.Collapsed; };
        Closing += CloseSafely;
    }
    private async void OpenAssistant(object sender, RoutedEventArgs e)
    {
        try { await native.OpenAsync(NativeWorkspaceEntry.LegacyAssistant, CancellationToken.None); }
        catch (Exception) { FallbackMessage.Text = "原生 Assistant 暂时不可用，请从托盘重试。"; }
    }
    private void FocusWorkspace(object sender, RoutedEventArgs e) => ReturnFocus();
    private void ZoomIn(object sender, RoutedEventArgs e) => SetZoom(Math.Min(2, Browser.ZoomFactor + .25));
    private void ZoomOut(object sender, RoutedEventArgs e) => SetZoom(Math.Max(.75, Browser.ZoomFactor - .25));
    private void ResetZoom(object sender, RoutedEventArgs e) => SetZoom(1);
    private void SetZoom(double factor) { Browser.ZoomFactor = factor; ZoomResetButton.Content = $"{factor:P0}"; }
    internal void ReturnFocus() { if (!closed && Browser.Visibility == Visibility.Visible) { Activate(); Browser.Focus(); } }
    private async void CloseSafely(object? sender, CancelEventArgs e)
    {
        if (closed) return;
        e.Cancel = true;
        if (shuttingDown) return;
        shuttingDown = true;
        await Host.CloseAsync();
        closed = true;
        Close();
    }
    internal async Task ShutdownAsync()
    {
        if (closed) return;
        shuttingDown = true;
        await Host.CloseAsync();
        closed = true; Close();
    }
    internal void DisposeImmediately()
    { if (!closed) { Host.DisposeImmediately(); closed = true; Close(); } }
}
