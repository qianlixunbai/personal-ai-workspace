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
        if (args.Length != 0) return 2;
        try
        {
            using var single = new SingleInstance();
            if (!single.IsPrimary)
            {
                if (!single.SignalPrimary()) MessageBox.Show("已有实例正在启动，请稍后从托盘打开。", "Personal AI Workspace");
                return 0;
            }
            var app = new AssistantApp(single);
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
