#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    internal enum SourceBackupStage
    {
        Preparing, AwaitingAuthorization, SendingAuthorization, ProducingBackup,
        VerifyingBackup, RegisteringBackup, Recorded
    }

    internal enum SourceBackupFailureReason { CancelledBeforeSend, DeadlineExpired, ExecutionFailed }

    private sealed class SourceAuthorizationCancelledException : OperationCanceledException { }

    // In-memory presentation only. Never persisted or accepted as source/stop authority.
    internal sealed class SourceBackupFailure(string backupId, SourceBackupStage stage, SourceBackupFailureReason reason)
        : IOException($"Synthetic backup incomplete: {backupId}; stage={stage}; reason={reason}.")
    {
        internal string BackupId { get; } = backupId;
        internal SourceBackupStage Stage { get; } = stage;
        internal SourceBackupFailureReason Reason { get; } = reason;
    }
}

internal sealed partial class ProfileRecoveryWindow
{
    internal const string SourceBackupTitle = "Personal AI Workspace · 合成备份进度";

    internal static Task<ProfileRecoveryCoordinator.SourceRegistration> RunSyntheticSourceBackupAsync(string backupId,
        Func<Action<ProfileRecoveryCoordinator.SourceBackupStage>, Task<ProfileRecoveryCoordinator.SourceRegistration>> execute)
    {
        var completion = new TaskCompletionSource<ProfileRecoveryCoordinator.SourceRegistration>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            ProfileRecoveryCoordinator.SourceRegistration? result = null;
            ProfileRecoveryCoordinator.SourceBackupFailure? failure = null;
            var stage = ProfileRecoveryCoordinator.SourceBackupStage.Preparing;
            try
            {
                var window = new ProfileRecoveryWindow(SourceBackupTitle, DescribeSourceBackupProgress(backupId, stage));
                window.inspecting = true;
                window.close.Name = "CloseSourceBackup";
                window.close.IsEnabled = false;
                bool started = false;
                window.Loaded += async (_, _) =>
                {
                    if (started) return;
                    started = true; // A repeated Loaded event must never rerun this intent.
                    try
                    {
                        result = await execute(next =>
                        {
                            stage = next;
                            window.Dispatcher.Invoke(() => window.status.Text = DescribeSourceBackupProgress(backupId, next));
                        });
                        window.status.Text = "合成备份已登记（SYNTHETIC_BACKUP_RECORDED）。\n备份：" + backupId +
                            "\n\n已核验密文和来源登记，短期角色已回收。本窗口尚未恢复或激活工作区。" +
                            "\n下一步：由隔离合成流程核对本次登记，再恢复到新目标；激活仍需另行显式确认。" +
                            "\n\n个人备份和恢复仍为 BLOCKED。关闭此窗口可返回所属流程。";
                    }
                    catch (Exception error)
                    {
                        failure = error as ProfileRecoveryCoordinator.SourceBackupFailure ?? new(backupId, stage,
                            ProfileRecoveryCoordinator.SourceBackupFailureReason.ExecutionFailed);
                        window.status.Text = DescribeSourceBackupFailure(failure);
                    }
                    finally
                    {
                        // The executor has completed its finally/owned-resource cleanup before
                        // the user can acknowledge. Closing never requests cancellation/kill.
                        window.inspecting = false;
                        window.close.IsEnabled = true;
                        window.close.Focus();
                    }
                };
                window.ShowDialog();
                if (failure is not null) completion.TrySetException(failure);
                else if (result is not null) completion.TrySetResult(result);
                else completion.TrySetException(new ProfileRecoveryCoordinator.SourceBackupFailure(backupId, stage,
                    ProfileRecoveryCoordinator.SourceBackupFailureReason.ExecutionFailed));
            }
            catch (Exception)
            {
                completion.TrySetException(new ProfileRecoveryCoordinator.SourceBackupFailure(backupId, stage,
                    ProfileRecoveryCoordinator.SourceBackupFailureReason.ExecutionFailed));
            }
            finally { Dispatcher.CurrentDispatcher.InvokeShutdown(); }
        }) { IsBackground = true };
        thread.SetApartmentState(ApartmentState.STA); thread.Start();
        return completion.Task;
    }

    internal static string DescribeSourceBackupProgress(string backupId, ProfileRecoveryCoordinator.SourceBackupStage stage) =>
        "仅隔离合成数据。备份：" + backupId + "\n\n" + SourceBackupStageText(stage) +
        "\n\n可在授权窗口取消。本次执行有时限，发送授权后须等待结果和资源收尾。" +
        "\n此进度不代表成功、停写证明或个人来源授权。";

    internal static string DescribeSourceBackupFailure(ProfileRecoveryCoordinator.SourceBackupFailure failure)
    {
        string reason = failure.Reason switch
        {
            ProfileRecoveryCoordinator.SourceBackupFailureReason.CancelledBeforeSend => "已取消授权",
            ProfileRecoveryCoordinator.SourceBackupFailureReason.DeadlineExpired => "执行时限已到",
            _ => "执行未完成"
        };
        string outcome = failure.Stage switch
        {
            ProfileRecoveryCoordinator.SourceBackupStage.Preparing or ProfileRecoveryCoordinator.SourceBackupStage.AwaitingAuthorization =>
                "授权尚未发送（AUTHORIZATION_NOT_SENT）。\n本次管理员凭据和备份口令未发送给执行进程；没有可用备份确认。" +
                "\n下一步：保留已分配的意图与本次记录，核对未完成原因。",
            ProfileRecoveryCoordinator.SourceBackupStage.RegisteringBackup =>
                "登记结果待核对（REGISTRATION_UNCONFIRMED）。\n密文已核验，但来源登记尚未完成独立重读确认。" +
                "\n下一步：保留密文与本次记录，只读核对同一备份编号的登记；不能按成功恢复。",
            ProfileRecoveryCoordinator.SourceBackupStage.Recorded =>
                "登记已重读，资源收尾未确认（RECORDED_CLEANUP_UNCONFIRMED）。" +
                "\n下一步：保留本次登记与密文，核对所属执行进程的收尾；不会自动继续恢复。",
            _ => "执行结果未确认（OUTCOME_UNCONFIRMED）。\n授权可能已送达；短期角色、密文和回收结果不能按成功或回滚处理。" +
                "\n下一步：保留本次记录，核对角色回收、密文和来源登记。"
        };
        return reason + "。备份：" + failure.BackupId + "\n最后观察阶段：" + SourceBackupStageText(failure.Stage) +
            "\n\n" + outcome + "\n\n本次意图不可重放；关闭不会重试、删除记录或授予恢复权限。个人恢复仍为 BLOCKED。";
    }

    private static string SourceBackupStageText(ProfileRecoveryCoordinator.SourceBackupStage stage) => stage switch
    {
        ProfileRecoveryCoordinator.SourceBackupStage.Preparing => "核对合成来源与排他访问",
        ProfileRecoveryCoordinator.SourceBackupStage.AwaitingAuthorization => "等待原生窗口中的本次授权",
        ProfileRecoveryCoordinator.SourceBackupStage.SendingAuthorization => "开始发送本次私有授权",
        ProfileRecoveryCoordinator.SourceBackupStage.ProducingBackup => "执行冻结、加密与短期角色回收",
        ProfileRecoveryCoordinator.SourceBackupStage.VerifyingBackup => "核验子进程结果、配置和实际密文",
        ProfileRecoveryCoordinator.SourceBackupStage.RegisteringBackup => "发布并重读本机合成来源登记",
        ProfileRecoveryCoordinator.SourceBackupStage.Recorded => "登记重读通过，正在释放本次资源",
        _ => "阶段未知"
    };
}
#endif
