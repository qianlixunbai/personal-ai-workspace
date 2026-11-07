using System;
using System.Globalization;
using System.Windows;

namespace PersonalAiWorkspace.Desktop;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--selection-worker" && long.TryParse(args[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out long window))
            return SelectionWorker.Run(new IntPtr(window));
        if (args.Length > 0 && args[0] is "--clipboard-snapshot" or "--clipboard-read" or "--clipboard-restore")
            return NativeCopyPort.RunWorker(args[0], args);
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        // A maintenance request is terminal, including malformed/unknown versions and failures.
        // Never fall through to the chooser, AssistantApp, Runtime startup or preference saving.
        if (args.Length > 0 && args[0].StartsWith("--inspect-profile-recovery", StringComparison.Ordinal))
            return InspectRecoveryMaintenance(args, message => MessageBox.Show(message,
                "Personal AI Workspace · 恢复维护检查", MessageBoxButton.OK, MessageBoxImage.Information));
#endif
#if MMF3_ACCEPTANCE && DEBUG
        Mmf3AcceptanceLaunch acceptance;
        try
        {
#if MMF3_PROFILE_ACCEPTANCE
            var selected = ResolveDevelopmentProfile(args);
            if (selected is null) return 0;
            acceptance = selected;
#else
            acceptance = Mmf3AcceptanceLaunch.Parse(args);
#endif
        }
#if MMF3_PROFILE_ACCEPTANCE
        catch (Exception error)
#else
        catch (Exception)
#endif
        {
#if MMF3_PROFILE_ACCEPTANCE
            MessageBox.Show(ProfileStartupFailureMessage(error), "Personal AI Workspace");
#else
            MessageBox.Show("受信 Windows 启动上下文校验未通过。工作区未启动，数据库凭据未读取；请保留现有 Profile 权限并检查受控准入证据。", "Personal AI Workspace");
#endif
            return 2;
        }
#else
        if (args.Length != 0) return 2;
#endif
        int result;
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        bool switchDevelopmentProfile;
#endif
        try
        {
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
            using var profilePaths = Mmf3ProfileState.Pin(acceptance.RunId);
            acceptance.Validate();
#endif
#if MMF3_ACCEPTANCE && DEBUG
            using var single = new SingleInstance(acceptance.InstanceSuffix);
#else
            using var single = new SingleInstance();
#endif
            if (!single.IsPrimary)
            {
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
                MessageBox.Show("此开发工作区已有客户端运行。本次启动没有切换或更新 Runtime。请先从已有客户端的托盘菜单选择“退出”，再重新启动此包。", "Personal AI Workspace · 启动冲突", MessageBoxButton.OK, MessageBoxImage.Warning);
                return 1;
#else
                if (!single.SignalPrimary()) MessageBox.Show("已有实例正在启动，请稍后从托盘打开。", "Personal AI Workspace");
                return 0;
#endif
            }
#if MMF3_ACCEPTANCE && DEBUG
            var app = new AssistantApp(single, acceptance: acceptance);
#else
            var app = new AssistantApp(single);
#endif
            try { result = app.Run(); }
            finally { app.Cleanup(); }
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
            switchDevelopmentProfile = app.SwitchDevelopmentProfileRequested;
#endif
        }
        catch (Exception)
        {
            MessageBox.Show("Assistant 启动失败。请检查 Windows 用户会话与桌面权限后重试。", "Personal AI Workspace");
            return 1;
        }
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        // The previous app, owned Runtime and instance mutex are released before selecting again.
        if (result == 0 && switchDevelopmentProfile)
        {
            try
            {
                var start = new System.Diagnostics.ProcessStartInfo(Environment.ProcessPath!)
                { UseShellExecute = true, WorkingDirectory = AppContext.BaseDirectory };
                start.ArgumentList.Add(DevelopmentWorkspaceSelection.ChooseArgument);
                System.Diagnostics.Process.Start(start);
            }
            catch (Exception)
            {
                MessageBox.Show("工作区已正常退出，但无法重新打开选择器。请使用 PersonalAiWorkspace.Desktop.exe --choose-development-workspace 重新选择。", "Personal AI Workspace");
                return 1;
            }
        }
#endif
        return result;
    }
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    internal static int InspectRecoveryMaintenance(string[] args, Action<string> report)
    {
        string id;
        try { id = ProfileRecoveryCoordinator.ParseInspectionRequest(args); }
        catch (System.IO.IOException)
        {
            report("请求无效（INVALID_REQUEST）。只接受 " + ProfileRecoveryCoordinator.InspectionArgument +
                " 和一个 32 位小写十六进制 Profile ID。\n\n未读取 Profile，未启动工作区。");
            return 2;
        }
        try
        {
            var authority = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
            // Stay on the STA entry thread: the existing mutex is thread-owned. Keep its scope
            // and all Profile/file pins until the result dialog closes; inspection grants nothing.
            using var coordinator = ProfileRecoveryCoordinator.OpenForPlanning(authority);
            var inspection = coordinator.InspectSourceReadinessAsync().GetAwaiter().GetResult();
            string history = inspection.ObservedExitedChildren is int count
                ? "已核对 " + count + " 次登记的直属 Runtime 退出观察。" : "直属 Runtime 退出记录不完整或不可验证。";
            string source = inspection.Native.SourceState == "BOUND_READ_ONLY"
                ? "受保护 Workspace 配置与应用数据库连接只读核对通过。" : "Workspace 来源尚未通过核对（" + inspection.Native.SourceState + "）。";
            report("维护状态：BLOCKED（" + inspection.Native.WriterCoverage + "）。\n\n" + history + "\n" + source + "\n" +
                "模型状态：" + inspection.Native.ModelState + "。\n旧版本、未登记进程及外部数据库客户端的停止状态尚未证明。\n\n" +
                "本次仅检查，未启动工作区，也未备份、恢复或激活。完整 PostgreSQL Profile 恢复仍未开放。");
            return 3;
        }
        catch (Exception)
        {
            // Do not expose paths, helper output, records or exception messages. A lock conflict,
            // absent/partial history, unknown guard or failed admission is not a stopped Profile.
            report("维护状态：BLOCKED（INSPECTION_UNAVAILABLE）。\n\n" +
                "无法完整核对受信 Profile、独占访问或退出记录。运行中的工作请在原工作流处理；其余情况请保留现有登记和文件。\n\n" +
                "未启动工作区，未执行备份、恢复或激活；不会通过清除记录、修复权限或停止进程来继续。");
            return 4;
        }
    }

    private static Mmf3AcceptanceLaunch? ResolveDevelopmentProfile(string[] args)
    {
        bool choose = args is [DevelopmentWorkspaceSelection.ChooseArgument];
        if (args.Length != 0 && !choose) return Mmf3AcceptanceLaunch.Parse(args);
        while (true)
        {
            try
            {
                string? id = choose ? null : DevelopmentWorkspaceSelection.Read();
                id ??= ChooseDevelopmentProfile();
                if (id is null) return null;
                return Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
            }
            catch (Exception error)
            {
                if (MessageBox.Show("无法打开上次记录或所选的开发工作区。它可能已删除、移动、损坏或未通过安全校验。\n\n" +
                    ProfileStartupFailureMessage(error) + "\n\n不会自动创建工作区、修复权限或切换数据库。是否重新选择工作区？",
                    "Personal AI Workspace · 工作区不可用", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No) != MessageBoxResult.Yes)
                    return null;
                choose = true;
            }
        }
    }

    internal static string ProfileStartupFailureMessage(Exception error)
    {
        // Fixed messages only. Never display exception text, paths, account names or helper output.
        string reason = error is System.IO.IOException ? error.Message : "";
        return reason switch
        {
            "Recovery candidate or registration unavailable." =>
                "此工作区属于尚未开放激活的恢复候选，或本机恢复登记无法核对。\n\n" +
                "工作区未启动；请保留恢复登记和候选文件。不要删除标记或复制原生文件来强行打开。",
            "Development private ACL rejected." =>
                "开发 Profile 私有目录权限不符合安全要求（PRIVATE_ACL）。\n\n" +
                "位置：Windows 每用户 LocalAppData 下 PersonalAIWorkspace.Development/Profiles。" +
                "私有目录须由当前用户拥有，保护继承边界；允许当前用户、SYSTEM 和 Administrators。其他主体的数据读取、写入或替换权限会被拒绝。\n\n" +
                "工作区未启动，数据库凭据未读取。请保留现场；不要提权或手工修复 ACL。",
            "MMF3 foreign ancestor replacement authority." or "MMF3 ancestor owner rejected." =>
                "开发 Profile 祖先目录权限不符合安全要求（ANCESTOR_ACL）。\n\n" +
                "工作区未启动，数据库凭据未读取。祖先目录的所有者或其他身份的写入、删除、权限修改能力不符合受信合同。" +
                "请保留现有目录和权限，等待同账户目录兼容方案；不要提权或手工修复 ACL。",
            _ => "受信 Windows 启动上下文校验未通过。工作区未启动，数据库凭据未读取；请保留现有 Profile 权限并检查受控准入证据。"
        };
    }

    private static string? ChooseDevelopmentProfile()
    {
        var choice = MessageBox.Show("仅开发版：使用经过本机审查的构建和工具链，正式发布准入尚未完成。\n\n是：创建独立开发工作区（仅为新目录设置私有权限；不修复已有 ACL）。\n否：选择已有开发工作区。\n取消：退出。",
            "Personal AI Workspace · 开发工作区", MessageBoxButton.YesNoCancel, MessageBoxImage.Question, MessageBoxResult.Cancel);
        if (choice == MessageBoxResult.Cancel) return null;
        if (choice == MessageBoxResult.No)
        {
            var picker = new Microsoft.Win32.OpenFolderDialog
            {
                Title = "选择已有受信开发 Profile 文件夹",
                InitialDirectory = PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.Parent()
            };
            if (picker.ShowDialog() != true) return null;
            string runId = System.IO.Path.GetFileName(picker.FolderName);
            if (!string.Equals(System.IO.Path.GetFullPath(picker.FolderName), Mmf3ProfileState.Root(runId), StringComparison.OrdinalIgnoreCase))
                throw new System.IO.IOException();
            return runId;
        }
        using var sources = TrustedDevelopmentRuntimeSources.Open();
        string fresh = Guid.NewGuid().ToString("N");
        Mmf3ProfileState.Prepare(fresh); // Original CREATE_NEW contract; no history or receipt edits.
        Mmf3ProfileNative.Provision(fresh, sources.JnaJar);
        return fresh;
    }
#endif
}
