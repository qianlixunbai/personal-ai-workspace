using System;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;

namespace PersonalAiWorkspace.Desktop;

internal sealed class NativeWebFetchConfirmation(Func<Window?> owner) : IWebFetchConfirmation
{
    public Task<bool> ConfirmAsync(WebFetchTarget target, CancellationToken cancellation)
    {
        cancellation.ThrowIfCancellationRequested();
        Window? host = owner();
        if (host is null || !host.IsVisible) return Task.FromResult(false);
        host.Dispatcher.VerifyAccess();
        var dialog = new WebFetchConfirmationWindow(host, target);
        using var registration = cancellation.Register(() =>
        {
            // Queue closure: cancellation can originate outside the WPF dispatcher.
            _ = dialog.Dispatcher.BeginInvoke(new Action(() => { if (dialog.IsVisible) dialog.Close(); }));
        });
        if (cancellation.IsCancellationRequested) return Task.FromResult(false);
        bool allowed = dialog.ShowDialog() == true;
        return Task.FromResult(allowed && !cancellation.IsCancellationRequested);
    }
}

internal sealed class WebFetchConfirmationWindow : Window
{
    internal WebFetchConfirmationWindow(Window owner, WebFetchTarget target)
    {
        Owner = owner; Title = "Public Web access / 公共网络访问";
        Width = 640; SizeToContent = SizeToContent.Height; MaxHeight = 650;
        ResizeMode = ResizeMode.NoResize; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        ShowInTaskbar = false;
        var panel = new StackPanel { Margin = new Thickness(20) };
        panel.Children.Add(new TextBlock { Text = "Canonical hostname / 目标主机", Margin = new Thickness(0, 0, 0, 6) });
        panel.Children.Add(ReadOnly(target.Hostname, 90));
        panel.Children.Add(new TextBlock { Text = "Full canonical URL / 完整规范 URL", Margin = new Thickness(0, 12, 0, 6) });
        panel.Children.Add(ReadOnly(target.Url, 240));
        panel.Children.Add(new TextBlock
        {
            Text = "This destination will be accessed over the public Internet. Up to two same-host redirects may be followed. No cross-host redirect will be followed.\n将通过公共网络访问此目标；最多跟随两次同主机重定向，不跟随跨主机重定向。",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 16, 0, 16)
        });
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var cancel = new Button { Content = "Cancel / 取消", IsCancel = true, IsDefault = true, MinWidth = 120, Margin = new Thickness(0, 0, 12, 0) };
        var allow = new Button { Content = "Allow once / 允许本次访问", MinWidth = 170 };
        cancel.Click += (_, _) => { DialogResult = false; };
        allow.Click += (_, _) => { allow.IsEnabled = false; DialogResult = true; };
        buttons.Children.Add(cancel); buttons.Children.Add(allow); panel.Children.Add(buttons);
        Content = panel; Loaded += (_, _) => cancel.Focus();
    }
    private static TextBox ReadOnly(string text, double maximumHeight) => new()
    {
        Text = text, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, MaxHeight = maximumHeight,
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled
    };
}
