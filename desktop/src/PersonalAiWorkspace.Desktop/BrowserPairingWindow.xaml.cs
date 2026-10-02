using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

public partial class BrowserPairingWindow : Window
{
    private readonly RuntimeClient runtime;
    private readonly CancellationTokenSource lifetime = new();
    private readonly DispatcherTimer expiry = new() { Interval = TimeSpan.FromSeconds(1) };
    private DateTimeOffset? expiresAt;
    private bool busy, closed;
    internal BrowserPairingWindow(RuntimeClient runtime)
    {
        this.runtime = runtime;
        InitializeComponent();
        expiry.Tick += ExpiryTick;
        Closed += WindowClosed;
        // Opening the window or changing input never creates a pairing or lists clients.
    }

    private void SetBusy(bool value)
    {
        busy = value;
        OriginText.IsReadOnly = value;
        CreatePairingButton.IsEnabled = RefreshClientsButton.IsEnabled = !value;
        RevokeClientButton.IsEnabled = !value && ClientsList.SelectedItem is BrowserClientMetadata;
        CopyPairingIdButton.IsEnabled = CopySecretButton.IsEnabled = !value && PairingSecretText.Text.Length != 0;
    }

    internal void ClearPairing()
    {
        expiry.Stop(); expiresAt = null;
        PairingIdText.Clear(); PairingSecretText.Clear(); ExpiresAtText.Clear();
        CopyPairingIdButton.IsEnabled = CopySecretButton.IsEnabled = false;
        PairingDetails.Visibility = Visibility.Collapsed;
    }

    private void ExpiryTick(object? sender, EventArgs e)
    {
        if (expiresAt.HasValue && DateTimeOffset.UtcNow >= expiresAt.Value)
        { ClearPairing(); PairingStatusText.Text = "配对已过期，显示已清除；如需配对，请再次显式创建。"; }
    }

    internal async Task CreatePairingAsync()
    {
        if (busy || closed) return;
        ClearPairing(); // Also clear on invalid input or failed replacement; no pairing history.
        string origin = OriginText.Text;
        if (!BrowserOrigin.Valid(origin))
        { PairingStatusText.Text = ErrorText.For(DesktopError.InvalidExtensionOrigin); return; }
        SetBusy(true);
        PairingStatusText.Text = "正在为已批准的 Origin 创建一次性配对…";
        try
        {
            var pairing = await runtime.CreateBrowserPairingAsync(origin, lifetime.Token);
            if (closed) return; // A cancelled request may still return; never restore closed UI state.
            if (pairing.ExpiresAt <= DateTimeOffset.UtcNow)
            { PairingStatusText.Text = "配对已过期，请检查系统时间并再次显式创建。"; return; }
            PairingIdText.Text = pairing.PairingId.ToString("D");
            PairingSecretText.Text = pairing.PairingSecret;
            ExpiresAtText.Text = pairing.ExpiresAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss zzz");
            expiresAt = pairing.ExpiresAt;
            PairingDetails.Visibility = Visibility.Visible;
            expiry.Start();
            PairingStatusText.Text = "配对已创建。请将 ID 与 Secret 手动复制到此扩展；本窗口不执行 exchange。";
        }
        catch (DesktopException error) { if (!closed) PairingStatusText.Text = error.Message; }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!closed) PairingStatusText.Text = ErrorText.For(DesktopError.PairingCreationFailed); }
        finally { if (!closed) SetBusy(false); }
    }

    internal async Task RefreshClientsAsync()
    {
        if (busy || closed) return;
        SetBusy(true);
        ClientsList.ItemsSource = null;
        try
        {
            var clients = await runtime.ListBrowserClientsAsync(lifetime.Token);
            if (closed) return;
            ClientsList.ItemsSource = clients;
            PairingStatusText.Text = clients.Count == 0 ? "尚无已配对 Browser；未 exchange 的 session 不在此列表。" : "已刷新安全客户端 metadata。";
        }
        catch (DesktopException error) { if (!closed) PairingStatusText.Text = error.Message; }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!closed) PairingStatusText.Text = ErrorText.For(DesktopError.BrowserManagementFailed); }
        finally { if (!closed) SetBusy(false); }
    }

    internal async Task RevokeSelectedAsync()
    {
        if (busy || closed || ClientsList.SelectedItem is not BrowserClientMetadata selected) return;
        SetBusy(true);
        try
        {
            await runtime.RevokeBrowserClientAsync(selected.ClientId, lifetime.Token);
            if (closed) return;
            var remaining = new List<BrowserClientMetadata>();
            foreach (BrowserClientMetadata item in ClientsList.Items)
                if (item.ClientId != selected.ClientId) remaining.Add(item);
            ClientsList.ItemsSource = remaining.AsReadOnly();
            PairingStatusText.Text = "已撤销所选 Browser；后续 credential 请求将被拒绝，已接受的任务不会自动取消。";
        }
        catch (DesktopException error) { if (!closed) PairingStatusText.Text = error.Message; }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!closed) PairingStatusText.Text = ErrorText.For(DesktopError.BrowserManagementFailed); }
        finally { if (!closed) SetBusy(false); }
    }

    private void Copy(string text)
    {
        ExpiryTick(null, EventArgs.Empty);
        if (busy || closed || PairingSecretText.Text.Length == 0) return;
        try { Clipboard.SetText(text); PairingStatusText.Text = "已显式复制到系统剪贴板。"; }
        catch (ExternalException) { PairingStatusText.Text = "Clipboard unavailable：请稍后重试复制。"; }
    }
    private void CopyPairingId(object sender, RoutedEventArgs e) => Copy(PairingIdText.Text);
    private void CopySecret(object sender, RoutedEventArgs e) => Copy(PairingSecretText.Text);
    private async void CreatePairing(object sender, RoutedEventArgs e) => await CreatePairingAsync();
    private async void RefreshClients(object sender, RoutedEventArgs e) => await RefreshClientsAsync();
    private async void RevokeClient(object sender, RoutedEventArgs e) => await RevokeSelectedAsync();
    private void ClientSelected(object sender, SelectionChangedEventArgs e)
    { if (RevokeClientButton is not null) RevokeClientButton.IsEnabled = !busy && !closed && ClientsList.SelectedItem is BrowserClientMetadata; }
    private void ClosePairing(object sender, RoutedEventArgs e) => Close();
    private void WindowClosed(object? sender, EventArgs e)
    {
        closed = true;
        lifetime.Cancel();
        ClearPairing(); OriginText.Clear(); ClientsList.ItemsSource = null;
        expiry.Tick -= ExpiryTick;
        lifetime.Dispose();
    }
}
