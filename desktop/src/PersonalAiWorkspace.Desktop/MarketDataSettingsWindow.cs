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
    private readonly CheckBox enabled = new() { Content = "允许主动刷新时连接 Twelve Data（默认关闭）" };
    private readonly CheckBox licensed = new() { Content = "我已确认此账户允许本人显示行情及按以下时长缓存" };
    private readonly TextBox retention = new() { Text = "24", MaxLength = 3 };
    private readonly TextBlock status = new() { Text = "正在读取本机状态…", TextWrapping = TextWrapping.Wrap };
    private readonly Button save = new() { Content = "安全保存", IsEnabled = false };
    internal MarketDataSettingsWindow(RuntimeClient runtime, CancellationToken cancellation)
    {
        Title = "行情服务 · 仅开发版"; Width = 580; SizeToContent = SizeToContent.Height;
        WindowStartupLocation = WindowStartupLocation.CenterOwner; ResizeMode = ResizeMode.NoResize; ShowInTaskbar = false;
        var panel = new StackPanel { Margin = new Thickness(24) }; Content = panel;
        panel.Children.Add(new TextBlock { Text = "Twelve Data · US STOCK / ETF · USD/CNY", FontSize = 18 });
        panel.Children.Add(new TextBlock { Text = "Basic 免费档当前仅允许内部非展示用途。请先核对账号方案、交易所展示与缓存许可；本窗口不订阅付费服务。启用后仅主动刷新联网，不会发送数量、余额或交易。",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        panel.Children.Add(new Label { Content = "API Key（留空保留已有 Key；仅原生窗口输入）", Target = key }); panel.Children.Add(key);
        panel.Children.Add(enabled); panel.Children.Add(licensed);
        panel.Children.Add(new Label { Content = "账号许可允许的缓存保留小时数（1–168）", Target = retention }); panel.Children.Add(retention);
        panel.Children.Add(new TextBlock { Text = "Key 和缓存以 DPAPI CurrentUser 保存，不进入网页或备份。禁用后可读取保留期内缓存；缓存有效不代表实时。每分钟最多 8 次、滚动 24 小时最多 800 次，失败也计入额度。", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        panel.Children.Add(status); panel.Children.Add(save);
        var lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        Loaded += async (_, _) => {
            try {
                var s = await runtime.GetFinanceMarketDataAsync(lifetime.Token);
                if (!IsVisible) return;
                enabled.IsChecked = s.Enabled; retention.Text = s.RetentionHours.ToString(System.Globalization.CultureInfo.InvariantCulture);
                status.Text = s.KeyConfigured ? "已有安全保存的 Key。启用或更改配置须重新确认许可。" : "尚未配置 Key；外部请求关闭。"; save.IsEnabled = true;
            } catch (Exception) { if (IsVisible) status.Text = "读取失败；请确认 Runtime 可用且 Finance 已完成身份绑定。"; }
        };
        save.Click += async (_, _) => {
            if (!int.TryParse(retention.Text, out int hours) || hours is < 1 or > 168 || enabled.IsChecked == true && licensed.IsChecked != true)
            { status.Text = "请检查保留时长，并在启用前确认展示和缓存许可。"; return; }
            save.IsEnabled = false;
            try {
                var result = await runtime.ConfigureFinanceMarketDataAsync(enabled.IsChecked == true, licensed.IsChecked == true, key.Password, hours, lifetime.Token);
                if (IsVisible) { key.Clear(); status.Text = result.Enabled ? "已安全保存并启用。返回页面主动刷新才会联网。" : "已安全保存，外部请求关闭。"; }
            } catch (Exception) { if (IsVisible) status.Text = "保存未确认；请关闭并重新打开以检查状态，不会自动重试。"; }
            finally { if (IsVisible) save.IsEnabled = true; }
        };
        Closed += (_, _) => { lifetime.Cancel(); lifetime.Dispose(); key.Clear(); };
    }
}
#endif
