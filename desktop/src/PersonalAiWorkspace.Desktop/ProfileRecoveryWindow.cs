#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Windows;
using System.Windows.Controls;

namespace PersonalAiWorkspace.Desktop;

// Native presentation only. A result never grants maintenance or SQL-source authority.
internal sealed class ProfileRecoveryWindow : Window
{
    internal const string Argument = "--recover-development-workspace-v1";
    internal const string Scope = "范围：当前 PostgreSQL 工作区的 Memory、Conversations 和 Knowledge。\n" +
        "Finance 不恢复；模型需重新准入；运行中任务不重放。恢复只创建全新工作区，保留原数据。\n" +
        "同机同用户恢复需要完整的本机来源登记，以及至少一个仍可通过安全校验的工作区。";
    internal const string RunningGuidance = "请先在原工作流处理运行中的操作，再从托盘退出。\n" +
        "使用 PersonalAiWorkspace.Desktop.exe --choose-development-workspace 打开选择器，再选择“备份与恢复维护”。\n" +
        "维护不会替您取消任务或停止已有服务。";
    private readonly StackPanel body = new() { Margin = new Thickness(24) };
    private readonly TextBlock status;
    private readonly Button close;
    private bool inspecting;
    internal string? ActivationRequestId { get; private set; }

    private ProfileRecoveryWindow(string title, string text)
    {
        Title = title; Width = 660; SizeToContent = SizeToContent.Height; ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Content = body;
        body.Children.Add(new TextBlock { Text = Scope, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 16) });
        status = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 16) };
        body.Children.Add(status);
        close = AddButton("关闭", () => Close()); close.IsCancel = true;
        Closing += (_, args) => { if (inspecting) args.Cancel = true; };
    }

    private Button AddButton(string label, Action action)
    {
        var button = new Button { Content = label, Padding = new Thickness(12, 7, 12, 7), Margin = new Thickness(0, 4, 0, 0) };
        button.Click += (_, _) => action(); body.Children.Add(button); return button;
    }

    internal static string? ChooseAction()
    {
        var window = new ProfileRecoveryWindow("Personal AI Workspace · 开发工作区",
            "仅开发版：使用经过本机审查的构建和工具链，正式发布准入尚未完成。");
        string? action = null;
        foreach (var item in new[] { ("创建独立开发工作区", "create"), ("选择已有开发工作区", "open"), ("备份与恢复维护", "recovery") })
            window.AddButton(item.Item1, () => { action = item.Item2; window.DialogResult = true; });
        window.ShowDialog(); return action;
    }

    internal static void ShowInspection(ProfileRecoveryCoordinator coordinator)
    {
        var window = new ProfileRecoveryWindow("Personal AI Workspace · 备份与恢复维护", "正在只读核对维护前置条件…");
        window.AddButton("创建备份（条件未满足）", () => { }).IsEnabled = false;
        window.AddButton("从备份恢复（来源授权未开放）", () => { }).IsEnabled = false;
        window.inspecting = true; window.close.IsEnabled = false;
        window.Loaded += async (_, _) =>
        {
            try { window.status.Text = Describe(await coordinator.InspectSourceReadinessAsync()); }
            catch (Exception) { window.status.Text = "BLOCKED（INSPECTION_UNAVAILABLE）\n无法核对独占访问、完整退出记录或受信来源。\n" + RunningGuidance; }
            finally { window.inspecting = false; window.close.IsEnabled = true; }
        };
        // The entry thread retains its mutex and coordinator until this dialog closes.
        window.ShowDialog();
    }

    internal static string Describe(ProfileRecoveryCoordinator.SourceReadiness result) =>
        "维护状态：BLOCKED\n\n" +
        DescribeRuntimeHistory(result.RuntimeHistory) +
        "\n完整停写：未证明旧版本、其他登录会话、手动 Java 和外部数据库客户端均停止。" +
        (result.Native.ModelState == "NO_GUARD_FILE_OBSERVED" ? "\n模型：未发现 guard 文件；这不能证明未知模型执行已结束。" : "\n模型：存在未解决状态，请在原工作流处理。") +
        (result.Native.SourceState == "BOUND_READ_ONLY" ? "\n来源配置：只读绑定核对通过；这不是备份或 SQL 来源授权。" : "\n来源配置：尚未通过只读绑定核对。") +
        "\n来源授权：个人备份生产与本机完成登记尚未开放。口令、文件选择或停写勾选不能替代来源授权。\n\n未备份、恢复或激活；保留现有数据和登记。";

    internal static string DescribeRuntimeHistory(ProfileRecoveryCoordinator.RuntimeHistoryInspection history)
    {
        if (history.RegisteredAttempts is not int attempts)
            return "直属 Runtime：登记缺失、不可读取或校验失败，历史仍为未知。请保留现有记录。";
        string text = "直属 Runtime：已核对 " + attempts + " 次启动登记；其中 " + history.ObservedExitedChildren +
            " 次在 owner 收尾前观察到退出码 0，" + history.OwnerCleanupObserved + " 次在 owner 收尾后观察到退出。";
        foreach (string issue in history.Issues)
            text += "\n• " + (issue switch
            {
                "START_OUTCOME_UNCONFIRMED" => "有启动意图未登记子进程身份；不能判定启动是否发生。",
                "CHILD_EXIT_UNOBSERVED" => "有已登记子进程缺少退出观察；本次检查不会按历史 PID 接管进程。",
                "OWNER_STOP_REQUESTED" => "退出前已请求停止，不能据此证明模型或写入已完成。",
                "CHILD_EXIT_FAILED" => "有子进程以非零退出码结束，需保留失败证据。",
                "OWNER_CLEANUP_EXIT_UNOBSERVED" => "已记录 owner 收尾意图，但退出结果未知。",
                "OWNER_CLEANUP_NOT_SAFE_STOP" => "owner 收尾后退出只证明该子进程结束；模型执行及安全停写仍未证明。",
                _ => "存在无法解释的历史状态，请保留现有记录。"
            });
        return text;
    }

    // Only the owning coordinator creates this window after re-reading its exact READY target.
    internal static ProfileRecoveryWindow SyntheticReady(string targetId) => new(
        "Personal AI Workspace · 隔离合成恢复验收", "仅隔离合成数据，未获得个人数据恢复授权。\n目标：" + targetId +
        "\n状态：READY。离线数据与配置核对完成，目标尚未激活。\n激活将启动一个受控 Runtime；不会保存个人上次工作区记录。");

    internal string? ConfirmSyntheticActivation()
    {
        var activate = AddButton("激活此合成恢复工作区", () =>
        {
            if (ActivationRequestId is not null) return;
            ActivationRequestId = Guid.NewGuid().ToString("N"); DialogResult = true;
        });
        activate.Name = "ActivateSyntheticRecovery";
        // No default/Enter activation. Closing or cancelling leaves READY intact.
        ShowDialog(); return ActivationRequestId;
    }
}
#endif
