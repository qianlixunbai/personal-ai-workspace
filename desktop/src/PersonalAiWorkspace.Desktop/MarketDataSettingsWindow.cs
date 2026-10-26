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
    private readonly TextBlock status = new() { Text = "正在读取本机状态…", TextWrapping = TextWrapping.Wrap };
    private readonly Button save = new() { Content = "安全保存", IsEnabled = false };
    internal MarketDataSettingsWindow(RuntimeClient runtime, CancellationToken cancellation)
    {
        Title = "行情服务 · 仅开发版"; Width = 580; SizeToContent = SizeToContent.Height;
        WindowStartupLocation = WindowStartupLocation.CenterOwner; ResizeMode = ResizeMode.NoResize; ShowInTaskbar = false;
        var panel = new StackPanel { Margin = new Thickness(24) }; Content = panel;
        panel.Children.Add(new TextBlock { Text = "Massive Basic · US STOCK / ETF · USD/CNY", FontSize = 18 });
        panel.Children.Add(new TextBlock { Text = "Stocks Basic / Currencies Basic 官方当前均为 $0/月。仅用于本人、非商业屏幕显示日终参考价；实际账号权限由服务商响应校验。请在 Massive 官网配置对应免费权限，本客户端不购买或升级套餐。",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        panel.Children.Add(new Label { Content = "API Key（留空保留已有 Key；仅原生窗口输入）", Target = key }); panel.Children.Add(key);
        panel.Children.Add(new TextBlock { Text = "Key 用 DPAPI CurrentUser 独立保存，不复用 Twelve Data Key。仅持久保存凭据及限流时间；不保存行情，不提供离线回看或衍生估值。保存不发起请求；回到 Finance 主动刷新即可查看与验证。没有许可勾选或套餐自报入口。", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        panel.Children.Add(status); panel.Children.Add(save);
        var lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        Loaded += async (_, _) => {
            try {
                var s = await runtime.GetFinanceMarketDataAsync(lifetime.Token);
                if (!IsVisible) return;
                status.Text = s.KeyConfigured ? "Massive Key 已保存。关闭此窗口后主动刷新；本次尚未验证服务商响应。" : "尚未配置 Massive Key；保存后可手动刷新日终价。"; save.IsEnabled = true;
            } catch (Exception) { if (IsVisible) status.Text = "读取失败；请确认 Runtime 可用且 Finance 已完成身份绑定。"; }
        };
        save.Click += async (_, _) => {
            save.IsEnabled = false;
            try {
                await runtime.ConfigureFinanceMarketDataAsync(key.Password, lifetime.Token);
                if (IsVisible) { key.Clear(); status.Text = "Massive Key 已安全保存。请关闭窗口，在 Finance 主动刷新 AAPL、SPY 或 USD/CNY；没有自动联网。"; }
            } catch (Exception) { if (IsVisible) status.Text = "保存未确认；请关闭并重新打开以检查状态，不会自动重试。"; }
            finally { if (IsVisible) save.IsEnabled = true; }
        };
        Closed += (_, _) => { lifetime.Cancel(); lifetime.Dispose(); key.Clear(); };
    }
}
#endif
