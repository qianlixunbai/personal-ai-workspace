#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryWindow
{
    internal static string? ShowSqliteArchive(ProfileRecoveryCoordinator coordinator)
    {
        const string filter = "工作区加密备份 (*.workspace-profile.pgp)|*.workspace-profile.pgp";
        var window = new ProfileRecoveryWindow("Personal AI Workspace · Profile 备份与恢复",
            "请先正常退出来源工作区。恢复时可用一个仍能通过安全检查的已关闭工作区进入维护。");
        var password = new PasswordBox { MaxLength = 1024, Margin = new Thickness(0, 4, 0, 10) };
        var verification = new PasswordBox { MaxLength = 1024, Margin = new Thickness(0, 4, 0, 10) };
        window.body.Children.Add(new Label { Content = "备份口令（至少 16 个字符）", Target = password });
        window.body.Children.Add(password);
        window.body.Children.Add(new Label { Content = "创建备份时再输入一次口令", Target = verification });
        window.body.Children.Add(verification);
        string? profileId = null;
        string? open = null;
        var backup = window.AddButton("创建 SQLite 备份…", () => { });
        var restore = window.AddButton("选择备份并恢复…", () => { });
        var launch = window.AddButton("仅本次打开已恢复工作区", () => { open = profileId; window.DialogResult = true; });
        launch.IsEnabled = false;
        var inspect = window.AddButton("查看 PostgreSQL 维护条件…", () => ShowInspection(coordinator));
        backup.Click += async (_, _) => await RunAsync(false);
        restore.Click += async (_, _) => await RunAsync(true);
        window.ShowDialog();
        return open;

        async Task RunAsync(bool recovering)
        {
            if (window.inspecting) return;
            var secret = ReadSecret(password);
            var repeated = ReadSecret(verification);
            password.Clear();
            verification.Clear();
            Button[] actions = [backup, restore, launch, inspect, window.close];
            try
            {
                if (secret.Length < 16 || (!recovering && !secret.AsSpan().SequenceEqual(repeated)))
                {
                    window.status.Text = recovering ? "请输入至少 16 个字符的备份口令。" : "请输入至少 16 个字符，并核对两次输入一致。";
                    return;
                }
                FileDialog picker = recovering
                    ? new OpenFileDialog { Title = "选择工作区备份", Filter = filter, CheckFileExists = true }
                    : new SaveFileDialog { Title = "选择新备份文件", Filter = filter, AddExtension = true,
                        DefaultExt = ".workspace-profile.pgp", FileName = "workspace.workspace-profile.pgp", OverwritePrompt = true };
                if (picker.ShowDialog(window) != true) return;
                window.inspecting = true;
                foreach (var action in actions) action.IsEnabled = false;
                window.status.Text = recovering ? "正在验证并暂存备份…" : "正在核对数据并加密备份…";
                var result = await coordinator.RunSqliteArchiveAsync(picker.FileName, secret, recovering, summary =>
                    MessageBox.Show(window, Summary(summary) + "\n\n将恢复到全新的开发工作区。是否继续？",
                        "确认恢复到独立 Profile", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No) == MessageBoxResult.Yes);
                if (recovering)
                {
                    if (result.ProfileId is not null) profileId = result.ProfileId;
                    window.status.Text = result.ProfileId is null ? "已验证备份，恢复已取消。" :
                        "独立工作区已创建。可现在打开，也可稍后从选择器打开。\nProfile ID " + result.ProfileId + "\n" + Summary(result.Summary);
                }
                else window.status.Text = "加密备份已保存。请另外保管口令。\n" + Summary(result.Summary);
            }
            catch (IOException failure) when (failure.Message == "POSTGRESQL_SOURCE")
            {
                window.status.Text = "此工作区使用 PostgreSQL。当前备份入口仅支持 SQLite。";
            }
            catch (IOException failure) when (failure.Message == "PROFILE_CLOSE_UNCONFIRMED")
            {
                window.status.Text = "无法核对来源工作区的正常退出记录，备份已停止。";
            }
            catch (Exception)
            {
                window.status.Text = recovering
                    ? "恢复未完成。请检查备份版本、文件完整性、口令和可用空间。"
                    : "备份未完成。请核对工作区已退出、没有未完成任务、目标文件不存在且可写。";
            }
            finally
            {
                Array.Clear(secret);
                Array.Clear(repeated);
                window.inspecting = false;
                foreach (var action in actions) action.IsEnabled = true;
                launch.IsEnabled = profileId is not null;
            }
        }
    }

    private static string Summary(ProfileRecoveryCoordinator.ArchiveSummary summary) =>
        $"记忆 {summary.Memory} · 对话 {summary.Conversations} · 消息 {summary.Messages}\n" +
        $"文档 {summary.Documents} · 原文版本 {summary.Revisions} · 知识问答历史 {summary.Answers}";
}
#endif
