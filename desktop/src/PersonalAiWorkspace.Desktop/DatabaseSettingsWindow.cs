#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Globalization;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;

namespace PersonalAiWorkspace.Desktop;

internal sealed class DatabaseSettingsWindow : Window
{
    private readonly Mmf3AcceptanceLaunch profile;
    private readonly DevelopmentRuntimeOwner runtime;
    private readonly CancellationToken applicationLifetime;
    private readonly CancellationTokenSource lifetime;
    private readonly TextBox port = new() { Text = "5432", MaxLength = 5 };
    private readonly TextBox database = new() { MaxLength = 63 };
    private readonly TextBox username = new() { MaxLength = 63 };
    private readonly PasswordBox password = new() { MaxLength = 1024 };
    private readonly TextBlock status = new() { Text = "正在检查本机配置…", TextWrapping = TextWrapping.Wrap };
    private readonly Button check = new() { Content = "检查连接与结构", IsEnabled = false };
    private readonly Button save = new() { Content = "安全保存", IsEnabled = false };
    private readonly Button restart = new() { Content = "重启 Runtime", IsEnabled = false };
    private bool busy, configured;

    internal DatabaseSettingsWindow(Mmf3AcceptanceLaunch profile, DevelopmentRuntimeOwner runtime, CancellationToken applicationLifetime)
    {
        this.profile = profile; this.runtime = runtime; this.applicationLifetime = applicationLifetime;
        lifetime = CancellationTokenSource.CreateLinkedTokenSource(applicationLifetime);
        Title = "本机数据库设置 · 仅开发版"; Width = 540; SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize; WindowStartupLocation = WindowStartupLocation.CenterOwner; ShowInTaskbar = false;
        var panel = new StackPanel { Margin = new Thickness(24) };
        panel.Children.Add(new TextBlock { Text = "PostgreSQL · 127.0.0.1 · Finance 关闭", FontSize = 18 });
        panel.Children.Add(new TextBlock { Text = "请仅使用独立合成数据。检查只读；不会建库、创建角色、初始化结构或迁移 SQLite。密码仅在本原生窗口输入。",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        Field(panel, "端口", port); Field(panel, "数据库", database); Field(panel, "应用角色（非管理员）", username); Field(panel, "密码", password);
        status.Margin = new Thickness(0, 12, 0, 12); panel.Children.Add(status);
        var buttons = new WrapPanel();
        foreach (var button in new[] { check, save, restart }) { button.Margin = new Thickness(0, 0, 8, 8); button.Padding = new Thickness(10, 6, 10, 6); buttons.Children.Add(button); }
        panel.Children.Add(buttons); Content = panel;
        check.Click += async (_, _) => await ExecuteAsync("CHECK");
        save.Click += async (_, _) =>
        {
            if (MessageBox.Show(this, "仅在检查通过后为当前开发工作区创建 DPAPI CurrentUser 配置。现有凭据不覆盖。下次重启将使用上述 PostgreSQL；SQLite 原数据不迁移。确认保存？",
                Title, MessageBoxButton.OKCancel, MessageBoxImage.Question, MessageBoxResult.Cancel) == MessageBoxResult.OK)
                await ExecuteAsync("SAVE!");
        };
        restart.Click += async (_, _) =>
        {
            if (busy || MessageBox.Show(this, "将停止本次桌面拥有的 Runtime；进行中的任务可能中断。重新启动后读取已保存配置。不会迁移 SQLite 或执行 Flyway。确认重启？",
                Title, MessageBoxButton.OKCancel, MessageBoxImage.Question, MessageBoxResult.Cancel) != MessageBoxResult.OK) return;
            SetBusy(true);
            try { await runtime.RestartAsync(applicationLifetime); status.Text = "已请求重新启动；关闭此窗口后在设置页重新检查存储。"; }
            catch (Exception) { status.Text = "Runtime 重启未完成；请在设置页检查固定启动状态。"; }
            finally { if (IsVisible) SetBusy(false); }
        };
        Loaded += async (_, _) => await ExecuteAsync("STATE");
        Closed += (_, _) => { lifetime.Cancel(); lifetime.Dispose(); password.Clear(); };
    }
    private static void Field(Panel panel, string label, Control input)
    {
        panel.Children.Add(new Label { Content = label, Target = input });
        input.Margin = new Thickness(0, 0, 0, 8); panel.Children.Add(input);
    }
    private void SetBusy(bool value)
    {
        busy = value; check.IsEnabled = !value && !configured; save.IsEnabled = !value && !configured;
        restart.IsEnabled = !value;
        port.IsEnabled = database.IsEnabled = username.IsEnabled = password.IsEnabled = !value && !configured;
    }
    private async Task ExecuteAsync(string action)
    {
        if (busy) return;
        byte[]? payload = null;
        SetBusy(true);
        try
        {
            if (action != "STATE")
            {
                if (!int.TryParse(port.Text, NumberStyles.None, CultureInfo.InvariantCulture, out int number) || number is < 1 or > 65535)
                { status.Text = "端口须为 1–65535。"; return; }
                payload = JsonSerializer.SerializeToUtf8Bytes(new { format = 1, backend = "postgresql", host = "127.0.0.1", port = number,
                    database = database.Text, username = username.Text, password = password.Password });
            }
            status.Text = "正在通过受信本机入口检查…";
            var result = await NativeDatabaseSetup.ExecuteAsync(profile, action, payload, lifetime.Token);
            if (!IsVisible) return;
            configured |= result is DatabaseSetupResult.SAVED or DatabaseSetupResult.CONFIGURED or DatabaseSetupResult.ALREADY_CONFIGURED;
            if (configured) password.Clear();
            status.Text = result switch
            {
                DatabaseSetupResult.NOT_CONFIGURED => "尚未保存数据库配置；当前默认 SQLite。",
                DatabaseSetupResult.CONFIGURED or DatabaseSetupResult.ALREADY_CONFIGURED => "本机 DPAPI 配置已存在且可解密；不覆盖。使用设置页检查当前连接，或明确重启后重新检查。",
                DatabaseSetupResult.READY => "连接成功；应用角色与 Workspace V1/V2、Knowledge 结构检查通过。尚未保存。",
                DatabaseSetupResult.SAVED => "DPAPI CurrentUser 已保存并重新解密校验；重启 Runtime 后生效。",
                DatabaseSetupResult.CONNECTION_FAILED => "连接失败；请检查本机 PostgreSQL 服务和端口。",
                DatabaseSetupResult.AUTHENTICATION_FAILED => "数据库认证失败；请检查角色与密码。",
                DatabaseSetupResult.SCHEMA_INITIALIZATION_REQUIRED => "数据库结构尚未准备；需要独立授权的初始化，本窗口不会执行。",
                DatabaseSetupResult.SCHEMA_INCOMPATIBLE => "数据库结构或检查权限不符合要求；配置未保存。",
                DatabaseSetupResult.ROLE_REJECTED => "应用角色权限不符合要求；请使用受限角色。",
                DatabaseSetupResult.CREDENTIAL_UNAVAILABLE => "凭据不可用；请检查安全输入与 Windows 当前用户。",
                DatabaseSetupResult.CONFIGURATION_UNTRUSTED => "配置格式或私有文件安全校验未通过；请保留现有文件。",
                _ => "受信准入或配置检查未完成；未降低权限要求，请保留现有 Profile。"
            };
        }
        catch (OperationCanceledException) { }
        catch (Exception) { if (IsVisible) status.Text = "配置操作未完成；请重新检查，不要覆盖现有凭据。"; }
        finally { if (payload is not null) CryptographicOperations.ZeroMemory(payload); if (IsVisible) SetBusy(false); }
    }
}
#endif
