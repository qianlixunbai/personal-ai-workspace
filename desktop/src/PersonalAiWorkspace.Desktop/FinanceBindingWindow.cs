#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Controls;

namespace PersonalAiWorkspace.Desktop;

internal sealed class FinanceBindingWindow : Window
{
    private readonly TextBox port = new() { Text = "5432", MaxLength = 5 };
    private readonly TextBox database = new() { MaxLength = 63 };
    private readonly TextBox username = new() { MaxLength = 63 };
    private readonly PasswordBox password = new() { MaxLength = 1024 };
    private readonly CheckBox saved = new() { Content = "使用当前工作区已保存的 Finance 连接", IsChecked = false };
    private readonly ComboBox candidates = new() { MinWidth = 400 };
    private readonly TextBlock proof = new() { TextWrapping = TextWrapping.Wrap, MaxWidth = 580 };
    private readonly TextBlock status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button read = new() { Content = "读取数据库身份与候选用户" };
    private readonly Button confirm = new() { Content = "确认绑定所选 Finance 用户", IsEnabled = false };
    private NativeFinanceSetup? session;
    private readonly CancellationTokenSource lifetime;
    private bool busy, bound;

    internal FinanceBindingWindow(Mmf3AcceptanceLaunch profile, DevelopmentRuntimeOwner runtime, CancellationToken cancellation)
    {
        lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        Title = "Finance 身份绑定 · 受信原生确认"; Width = 660; SizeToContent = SizeToContent.Height;
        MaxHeight = 900; WindowStartupLocation = WindowStartupLocation.CenterOwner; ShowInTaskbar = false;
        var panel = new StackPanel { Margin = new Thickness(24) };
        panel.Children.Add(new TextBlock { Text = "Finance · 只读账户", FontSize = 22 });
        panel.Children.Add(new TextBlock { Text = "连接独立 Finance PostgreSQL，先读取真实数据库身份，再明确选择并确认业务用户。只创建首次绑定，已有绑定不可覆盖。不会运行迁移。",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        panel.Children.Add(saved);
        Field("端口（127.0.0.1）", port); Field("Finance 数据库", database); Field("只读应用角色", username); Field("密码", password);
        panel.Children.Add(read); panel.Children.Add(proof); panel.Children.Add(candidates); panel.Children.Add(confirm); panel.Children.Add(status);
        Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        void Field(string label, Control control) { panel.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 8, 0, 4) }); panel.Children.Add(control); }
        void Busy(bool value) { busy = value; read.IsEnabled = !value && !bound; confirm.IsEnabled = !value && !bound && session?.Review is not null && candidates.SelectedIndex >= 0; }
        candidates.SelectionChanged += (_, _) => { if (!busy) Busy(false); };
        read.Click += async (_, _) =>
        {
            if (busy || bound) return; Busy(true); proof.Text = ""; candidates.Items.Clear(); status.Text = "正在通过受信原生子进程读取…";
            byte[]? payload = null;
            try
            {
                if (session is not null) { await session.DisposeAsync(); session = null; }
                if (saved.IsChecked != true)
                {
                    if (!int.TryParse(port.Text, out int number)) throw new InvalidOperationException();
                    payload = JsonSerializer.SerializeToUtf8Bytes(new { format = 1, backend = "postgresql", host = "127.0.0.1", port = number,
                        database = database.Text, username = username.Text, password = password.Password });
                }
                password.Clear(); session = new NativeFinanceSetup(lifetime.Token); await session.ReadAsync(profile, payload);
                var review = session.Review!;
                proof.Text = $"服务端数据库：{review.Database}\n服务端地址：{review.Server}\n数据集 SHA-256：{review.DatasetFingerprint}";
                foreach (var candidate in review.Candidates) candidates.Items.Add($"{candidate.Username} · {candidate.Email} · 创建 {candidate.CreatedAt}\n用户 {candidate.Identity.UserId} · SHA-256 {candidate.Identity.UserFingerprint}");
                status.Text = review.Candidates.Length == 0 ? "没有 ACTIVE Finance 用户；未绑定。" : "请核对数据库和候选用户，明确选择后确认。此核对将在五分钟后过期。";
            }
            catch (Exception rejected) {
                string reason = rejected.Message switch {
                    "REJECTED_CONFIGURATION" => "连接配置校验失败",
                    "REJECTED_CONTEXT" => "只读模块初始化失败",
                    "REJECTED_REVIEW" => "数据库身份、结构、角色或已有绑定校验失败",
                    _ => "受信通道未完成或核对已过期"
                };
                status.Text = "读取被拒绝：" + reason + "。保留现有文件，核对连接后重试。";
                if (session is not null) { await session.DisposeAsync(); session = null; }
            }
            finally { if (payload is not null) CryptographicOperations.ZeroMemory(payload); if (IsVisible) Busy(false); }
        };
        confirm.Click += async (_, _) =>
        {
            if (busy || session?.Review is null || candidates.SelectedIndex < 0) return;
            var candidate = session.Review.Candidates[candidates.SelectedIndex];
            if (MessageBox.Show(this, $"数据库：{session.Review.Database}\n数据集：{session.Review.DatasetFingerprint}\nFinance 用户：{candidate.Username} · {candidate.Email}\n历史指纹：{candidate.Identity.UserFingerprint}\n\n确认这是要绑定的 Finance 用户？绑定仅创建一次，不可静默更换。",
                Title, MessageBoxButton.OKCancel, MessageBoxImage.Question, MessageBoxResult.Cancel) != MessageBoxResult.OK) return;
            Busy(true);
            try
            {
                await session.ConfirmAsync(candidates.SelectedIndex); bound = true;
                await session.DisposeAsync(); session = null;
                status.Text = "已确认绑定；正在重启当前桌面拥有的 Runtime…";
                await runtime.RestartAsync(cancellation);
                status.Text = "已确认绑定并请求重启。关闭此窗口后，点击“只读刷新账户”。";
            }
            catch (Exception) { status.Text = "绑定或重启未确认。请保留现有文件，先关闭窗口并只读刷新；不会自动重绑。"; }
            finally { if (session is not null) { await session.DisposeAsync(); session = null; } if (IsVisible) Busy(false); }
        };
        Closed += async (_, _) => { lifetime.Cancel(); if (session is not null) { await session.DisposeAsync(); session = null; } lifetime.Dispose(); };
    }
}
#endif
