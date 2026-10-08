using System;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;

namespace PersonalAiWorkspace.Desktop;

internal sealed class NativeModelConfirmation(Func<Window?> owner) : IModelConfirmation
{
    public Task<bool> ConfirmAsync(ModelIntent intent, CancellationToken cancellation)
    {
        cancellation.ThrowIfCancellationRequested(); var host = owner();
        if (host is null || !host.IsVisible) return Task.FromResult(false);
        host.Dispatcher.VerifyAccess(); var dialog = new ModelConfirmationWindow(host, intent);
        using var registration = cancellation.Register(() => _ = dialog.Dispatcher.BeginInvoke(new Action(() => { if (dialog.IsVisible) dialog.Close(); })));
        if (cancellation.IsCancellationRequested) return Task.FromResult(false);
        return Task.FromResult(dialog.ShowDialog() == true && !cancellation.IsCancellationRequested);
    }
}
internal sealed class ModelConfirmationWindow : Window
{
    internal const string EvictionWarning = "加载新模型可能触发 Ollama 自动驱逐旧模型或其他客户端正在驻留的模型。即使 Workspace 未主动释放旧模型，也无法保证旧模型继续驻留。切换失败不会自动恢复旧模型的显存状态。";
    internal ModelConfirmationWindow(Window owner, ModelIntent intent)
    {
        Owner = owner; Title = "Model exact-intent confirmation / 模型操作确认";
        Width = 700; SizeToContent = SizeToContent.Height; MaxHeight = 800; ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner; ShowInTaskbar = false;
        var panel = new StackPanel { Margin = new Thickness(20) };
        panel.Children.Add(new TextBlock { Text = "本次操作：" + intent.Action, Margin = new Thickness(0, 0, 0, 8) });
        panel.Children.Add(Field("候选 / 目标：" + intent.CandidateModel + "\nManifest SHA-256：" + intent.CandidateDigest));
        panel.Children.Add(Field("旧 Active：" + (intent.ExpectedActiveModel ?? "明确无 Active") + "\n旧 digest：" + (intent.ExpectedActiveDigest ?? "无")
            + "\nExpected selection revision：" + intent.ExpectedSelectionRevision + (intent.RecoveryGeneration is null ? "" : "\nRecovery generation：" + intent.RecoveryGeneration)));
        panel.Children.Add(new TextBlock { Text = intent.Action == ModelAction.RECOVER
            ? "仅清除当前 guard；不会加载模型或重放原任务。原请求完成或原服务/runner 已结束是你接受的信任假设，Workspace 无法独立证明。\n本地执行尚未退出时必须保持 STOP。"
            : intent.Action == ModelAction.RELEASE ? "仅释放上述单个模型，不改变 durable selection；不以 /api/ps 证明其他客户端空闲。" : EvictionWarning,
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) });
        if (intent.Action == ModelAction.RELEASE_OLD_THEN_SWITCH)
            panel.Children.Add(new TextBlock { Text = "额外授权：仅一次释放上述旧 Active identity/digest，再加载候选。失败保留旧 durable selection，不保证旧 residency。", TextWrapping = TextWrapping.Wrap });
        CheckBox? external = null;
        if (intent.ExternalConfirmed) {
            external = new CheckBox { Content = new TextBlock { Text = intent.Action == ModelAction.RECOVER
                ? "已协调共享客户端，确认原请求完成或原服务/runner 已结束；接受 Workspace 无法独立证明该外部事实。"
                : "已协调共享 Ollama 客户端，确认可释放指定单模型；接受外部空闲是用户确认的信任假设。", TextWrapping = TextWrapping.Wrap }, Margin = new Thickness(0, 12, 0, 12) };
            panel.Children.Add(external);
        }
        panel.Children.Add(new TextBlock { Text = "60 秒内有效；取消、Escape 或关闭窗口均拒绝本次操作。", TextWrapping = TextWrapping.Wrap });
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        var cancel = new Button { Content = "Cancel / 取消", IsCancel = true, IsDefault = true, MinWidth = 120, Margin = new Thickness(0, 0, 12, 0) };
        var allow = new Button { Content = "仅允许本次精确操作", MinWidth = 170, IsEnabled = external is null };
        if (external is not null) { external.Checked += (_, _) => allow.IsEnabled = true; external.Unchecked += (_, _) => allow.IsEnabled = false; }
        cancel.Click += (_, _) => DialogResult = false;
        allow.Click += (_, _) => { if (external is null || external.IsChecked == true) { allow.IsEnabled = false; DialogResult = true; } };
        buttons.Children.Add(cancel); buttons.Children.Add(allow); panel.Children.Add(buttons);
        Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Loaded += (_, _) => cancel.Focus();
    }
    private static TextBox Field(string text) => new() { Text = text, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 8) };
}
