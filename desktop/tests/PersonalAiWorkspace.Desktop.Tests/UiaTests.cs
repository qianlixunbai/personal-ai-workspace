using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class UiaTests
{
    [Fact]
    public async Task ActualUiaReadsOnlySelectedFixtureTextAndRefusesFixturePassword()
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(async () =>
            {
                try
                {
                    using var source = new HwndSource(new HwndSourceParameters("Private UIA test fixture") { Width = 300, Height = 200 });
                    var input = new TextBox { Text = "unselected-prefix selected-fixture unselected-suffix" };
                    var password = new PasswordBox { Password = "protected-fixture" };
                    var button = new Button { Content = "Unsupported fixture" };
                    var panel = new StackPanel();
                    panel.Children.Add(input); panel.Children.Add(password); panel.Children.Add(button);
                    source.RootVisual = panel;
                    input.Select(18, 16);
                    async Task<SelectionResult> Probe(bool protectedInput, ControlType? controlType = null) => await Task.Run(() =>
                    {
                        // Probe only this hidden fixture's tree; never ambient foreground/selection.
                        var root = AutomationElement.FromHandle(source.Handle);
                        var focused = root.FindFirst(TreeScope.Descendants, new AndCondition(
                            new PropertyCondition(AutomationElement.ControlTypeProperty, controlType ?? ControlType.Edit),
                            new PropertyCondition(AutomationElement.IsPasswordProperty, protectedInput)))!;
                        return SelectionWorker.ReadElement(focused, source.Handle, IntPtr.Zero, () => true);
                    }).WaitAsync(TimeSpan.FromSeconds(3));
                    var selection = await Probe(false);
                    Assert.Equal(SelectionStatus.Success, selection.Status);
                    Assert.Equal("selected-fixture", selection.Text);
                    Assert.Equal(SelectionStatus.Protected, (await Probe(true)).Status);
                    // WPF Button reports IsPassword as unsupported: fail closed before querying text.
                    Assert.Equal(SelectionStatus.Untrusted, (await Probe(false, ControlType.Button)).Status);
                    input.Select(0, 0);
                    Assert.Equal(SelectionStatus.Empty, (await Probe(false)).Status);
                    done.SetResult();
                }
                catch (Exception error) { done.SetException(error); }
                finally { dispatcher.InvokeShutdown(); }
            });
            Dispatcher.Run();
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        await done.Task;
    }
}
