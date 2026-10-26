#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed class MarketDataSettingsWindow : Window
{
    private readonly PasswordBox key = new() { MaxLength = 256 };
    private readonly TextBlock enabled = new() { Text = "真实请求：已锁定 · 展示与缓存许可未核验", TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock licensed = new() { Text = "用户勾选、保存 Key 或自报套餐均不构成授权。本版本不提供解锁许可的入口。", TextWrapping = TextWrapping.Wrap };
    private readonly TextBox retention = new() { Text = "24", MaxLength = 3, IsReadOnly = true };
    private readonly TextBlock status = new() { Text = "正在读取本机状态…", TextWrapping = TextWrapping.Wrap };
    private readonly Button save = new() { Content = "安全保存", IsEnabled = false };
    internal MarketDataSettingsWindow(RuntimeClient runtime, CancellationToken cancellation)
    {
        Title = "行情服务 · 仅开发版"; Width = 580; SizeToContent = SizeToContent.Height;
        WindowStartupLocation = WindowStartupLocation.CenterOwner; ResizeMode = ResizeMode.NoResize; ShowInTaskbar = false;
        var panel = new StackPanel { Margin = new Thickness(24) }; Content = panel;
        panel.Children.Add(new TextBlock { Text = "Twelve Data · US STOCK / ETF · USD/CNY", FontSize = 18 });
        panel.Children.Add(new TextBlock { Text = "Basic 当前标为内部非展示用途；Grow 列有内部展示能力，但不据套餐名称推定具体账号和缓存权利。下一步：取得账号适用的展示、缓存及期限证据，独立核验后再安排真实 Provider 验证。当前实际验证 NOT RUN。",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        panel.Children.Add(new Label { Content = "API Key（留空保留已有 Key；仅原生窗口输入）", Target = key }); panel.Children.Add(key);
        panel.Children.Add(enabled); panel.Children.Add(licensed);
        panel.Children.Add(new Label { Content = "历史缓存保留设置（小时；不是许可证）", Target = retention }); panel.Children.Add(retention);
        panel.Children.Add(new TextBlock { Text = "Key 仍可用 DPAPI CurrentUser 安全保存，留空保留已有 Key。没有真实网络验证；旧版启用/许可勾选不会继续生效，已有 Twelve Data 缓存也不参与展示或估值。", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        panel.Children.Add(status); panel.Children.Add(save);
        var lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        Loaded += async (_, _) => {
            try {
                var s = await runtime.GetFinanceMarketDataAsync(lifetime.Token);
                if (!IsVisible) return;
                retention.Text = s.RetentionHours.ToString(System.Globalization.CultureInfo.InvariantCulture);
                status.Text = s.KeyConfigured ? "Key 已安全保存，但未验证；真实请求与 Provider 缓存展示仍锁定。" : "尚未配置 Key；外部请求关闭。"; save.IsEnabled = true;
            } catch (Exception) { if (IsVisible) status.Text = "读取失败；请确认 Runtime 可用且 Finance 已完成身份绑定。"; }
        };
        save.Click += async (_, _) => {
            if (!int.TryParse(retention.Text, out int hours) || hours is < 1 or > 168)
            { status.Text = "历史设置无效，请保留现有配置。"; return; }
            save.IsEnabled = false;
            try {
                var result = await runtime.ConfigureFinanceMarketDataAsync(false, false, key.Password, hours, lifetime.Token);
                if (IsVisible) { key.Clear(); status.Text = "Key 已安全保存（未验证）。许可未核验，外部请求与 Provider 缓存展示保持锁定。"; }
            } catch (Exception) { if (IsVisible) status.Text = "保存未确认；请关闭并重新打开以检查状态，不会自动重试。"; }
            finally { if (IsVisible) save.IsEnabled = true; }
        };
        Closed += (_, _) => { lifetime.Cancel(); lifetime.Dispose(); key.Clear(); };
    }
}
#endif
