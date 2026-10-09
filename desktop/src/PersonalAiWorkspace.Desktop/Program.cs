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
        try { acceptance = Mmf3AcceptanceLaunch.Parse(args); }
        catch (Exception) { return 2; }
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
}
