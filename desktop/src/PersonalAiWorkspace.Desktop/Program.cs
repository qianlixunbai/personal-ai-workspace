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
#if MMF3_ACCEPTANCE && DEBUG
        Mmf3AcceptanceLaunch acceptance;
        try
        {
#if MMF3_PROFILE_ACCEPTANCE
            if (args.Length == 0)
            {
                var selected = ChooseDevelopmentProfile();
                if (selected is null) return 0;
                args = [Mmf3AcceptanceLaunch.Argument, selected];
            }
#endif
            acceptance = Mmf3AcceptanceLaunch.Parse(args);
        }
        catch (Exception)
        {
            MessageBox.Show("受信 Windows 启动上下文校验未通过。工作区未启动，数据库凭据未读取；请保留现有 Profile 权限并检查受控准入证据。", "Personal AI Workspace");
            return 2;
        }
#else
        if (args.Length != 0) return 2;
#endif
        try
        {
#if MMF3_ACCEPTANCE && DEBUG
            using var single = new SingleInstance(acceptance.InstanceSuffix);
#else
            using var single = new SingleInstance();
#endif
            if (!single.IsPrimary)
            {
                if (!single.SignalPrimary()) MessageBox.Show("已有实例正在启动，请稍后从托盘打开。", "Personal AI Workspace");
                return 0;
            }
#if MMF3_ACCEPTANCE && DEBUG
            var app = new AssistantApp(single, acceptance: acceptance);
#else
            var app = new AssistantApp(single);
#endif
            try { return app.Run(); }
            finally { app.Cleanup(); }
        }
        catch (Exception)
        {
            MessageBox.Show("Assistant 启动失败。请检查 Windows 用户会话与桌面权限后重试。", "Personal AI Workspace");
            return 1;
        }
    }
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    private static string? ChooseDevelopmentProfile()
    {
        var choice = MessageBox.Show("仅开发版：使用经过本机审查的构建和工具链，正式发布准入尚未完成。\n\n是：创建独立开发工作区（仅为新目录设置私有权限；不修复已有 ACL）。\n否：选择已有开发工作区。\n取消：退出。",
            "Personal AI Workspace · 开发工作区", MessageBoxButton.YesNoCancel, MessageBoxImage.Question, MessageBoxResult.Cancel);
        if (choice == MessageBoxResult.Cancel) return null;
        if (choice == MessageBoxResult.No)
        {
            var picker = new Microsoft.Win32.OpenFolderDialog { Title = "选择已有受信开发 Profile 文件夹" };
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
