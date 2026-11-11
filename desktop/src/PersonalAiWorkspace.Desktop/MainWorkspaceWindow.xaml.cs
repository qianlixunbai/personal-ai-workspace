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
    private bool openingNativeMemory;
    private bool requestingExit;
    private readonly Func<Task>? requestExit;
    private readonly Func<bool> confirmDiscard;
    internal bool TryDiscardEditor() => !Host.HasDirtyEditor || confirmDiscard();
    internal WorkspaceWebViewHost Host { get; }
    internal bool FallbackVisible => Fallback.Visibility == Visibility.Visible;
    internal MainWorkspaceWindow(IWorkspaceNativeActions native, string? assetFolder = null, Func<bool>? confirmation = null, Func<Task>? requestExit = null
#if MMF3_ACCEPTANCE && DEBUG
        , Mmf3AcceptanceLaunch? acceptance = null
#endif
        )
    {
        this.native = native;
        this.requestExit = requestExit;
        confirmDiscard = confirmation ?? (() => MessageBox.Show(this,
            "丢弃 Memory 未保存的修改并继续？", "Memory", MessageBoxButton.YesNo,
            MessageBoxImage.Warning, MessageBoxResult.No) == MessageBoxResult.Yes);
        InitializeComponent();
        ExitWorkspaceButton.IsEnabled = requestExit is not null;
#if MMF3_ACCEPTANCE && DEBUG
        if (acceptance is not null) Title += " · " + Mmf3AcceptanceLaunch.Label;
#if MMF3_PROFILE_ACCEPTANCE
        if (acceptance?.OpenOnce == true) Title += " · 仅本次打开";
#endif
#endif
        Host = new WorkspaceWebViewHost(Browser, native, message =>
        {
            BrowserZoomControls.IsEnabled = false;
            Browser.Visibility = Visibility.Collapsed;
            FallbackTitle.Text = "工作区暂时不可用";
            FallbackMessage.Text = message;
            Fallback.Visibility = Visibility.Visible;
            NativeMemoryFallbackButton.Visibility = Visibility.Visible;
            NativeAssistantButton.Visibility = Visibility.Visible;
            NativeAssistantButton.Focus();
        }, assetFolder, confirmDiscard
#if MMF3_ACCEPTANCE && DEBUG
            , acceptance
#endif
            );
        Loaded += async (_, _) =>
        {
            await Host.InitializeAsync();
            if (Host.IsReady && !closed && !shuttingDown)
            {
                Fallback.Visibility = Visibility.Collapsed;
                BrowserZoomControls.IsEnabled = true;
            }
        };
        Closing += CloseSafely;
    }
    private async void OpenAssistant(object sender, RoutedEventArgs e)
    {
        try { await native.OpenAsync(NativeWorkspaceEntry.LegacyAssistant, CancellationToken.None); }
        catch (Exception) { FallbackMessage.Text = "原生 Assistant 暂时不可用，请从托盘重试。"; }
    }
    private async void OpenNativeMemory(object sender, RoutedEventArgs e)
    {
        if (closed || shuttingDown || openingNativeMemory || NativeMemoryFallbackButton.Visibility != Visibility.Visible) return;
        openingNativeMemory = true;
        NativeMemoryFallbackButton.IsEnabled = false;
        NativeMemoryStatus.Text = "";
        NativeMemoryStatus.Visibility = Visibility.Collapsed;
        try { await native.OpenAsync(NativeWorkspaceEntry.Memory, CancellationToken.None); }
        catch (Exception)
        {
            // Preserve the WebView failure above; never expose native exception details.
            NativeMemoryStatus.Text = "原生记忆暂时不可用，请稍后重试或从托盘打开。";
            NativeMemoryStatus.Visibility = Visibility.Visible;
        }
        finally
        {
            openingNativeMemory = false;
            NativeMemoryFallbackButton.IsEnabled = true;
        }
    }
    private void FocusWorkspace(object sender, RoutedEventArgs e) => ReturnFocus();
    private async void ExitWorkspace(object sender, RoutedEventArgs e)
    {
        if (closed || shuttingDown || requestingExit || requestExit is null) return;
        requestingExit = true;
        ExitWorkspaceButton.IsEnabled = false;
        ExitWorkspaceButton.Content = "正在退出…";
        try
        {
            // The application owns draft confirmation, task draining and Runtime
            // shutdown. Use precisely the same entry point as the tray menu.
            await requestExit();
        }
        finally
        {
            requestingExit = false;
            if (!closed && !shuttingDown)
            {
                // Cancelling an existing unsaved-editor confirmation keeps the UI usable.
                ExitWorkspaceButton.Content = "退出工作区";
                ExitWorkspaceButton.IsEnabled = true;
            }
        }
    }
    private bool CanZoom => !closed && !shuttingDown && Host.IsReady;
    private void ZoomIn(object sender, RoutedEventArgs e) => ChangeZoom(.25);
    private void ZoomOut(object sender, RoutedEventArgs e) => ChangeZoom(-.25);
    private void ResetZoom(object sender, RoutedEventArgs e) => SetZoom(1);
    private void ChangeZoom(double delta)
    {
        // A queued routed event can still arrive after the controls were disabled.
        if (!CanZoom) return;
        SetZoom(Math.Clamp(Browser.ZoomFactor + delta, .75, 2));
    }
    private void SetZoom(double factor)
    {
        if (!CanZoom) return;
        Browser.ZoomFactor = factor;
        ZoomResetButton.Content = $"{factor:P0}";
    }
    internal void ReturnFocus() { if (!closed && Browser.Visibility == Visibility.Visible) { Activate(); Browser.Focus(); } }
    private async void CloseSafely(object? sender, CancelEventArgs e)
    {
        if (closed) return;
        e.Cancel = true;
        if (shuttingDown || requestingExit) return;
        if (!TryDiscardEditor()) return;
        shuttingDown = true;
        BrowserZoomControls.IsEnabled = false;
        ExitWorkspaceButton.IsEnabled = false;
        await Host.CloseAsync();
        closed = true;
        Close();
    }
    internal async Task ShutdownAsync()
    {
        if (closed) return;
        shuttingDown = true;
        BrowserZoomControls.IsEnabled = false;
        ExitWorkspaceButton.IsEnabled = false;
        await Host.CloseAsync();
        closed = true; Close();
    }
    internal void DisposeImmediately()
    { if (!closed) { BrowserZoomControls.IsEnabled = false; ExitWorkspaceButton.IsEnabled = false; Host.DisposeImmediately(); closed = true; Close(); } }
}
