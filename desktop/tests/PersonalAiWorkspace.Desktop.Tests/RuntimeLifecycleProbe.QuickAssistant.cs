#if MMF3_PROFILE_ACCEPTANCE && DEBUG && RUNTIME_LIFECYCLE_PROBE
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private const string X1Selection = "The local assistant helps people understand selected text.";
    private static class X1Input
    {
        [DllImport("user32.dll")] internal static extern bool SetForegroundWindow(IntPtr window);
        [DllImport("user32.dll")] internal static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
    }
    private static int RunX1SelectionFixture()
    {
        var panel = new StackPanel();
        var text = new TextBox { Text = X1Selection, Height = 100 };
        AutomationProperties.SetAutomationId(text, "X1SelectedText");
        var password = new PasswordBox { Password = "test-fixture-only", Height = 40 };
        AutomationProperties.SetAutomationId(password, "X1ProtectedInput");
        panel.Children.Add(text); panel.Children.Add(password);
        var fixture = new Window { Title = "X1 owned selection fixture", Width = 500, Height = 240, Content = panel };
        fixture.Loaded += (_, _) => { text.SelectAll(); text.Focus(); };
        return new Application().Run(fixture);
    }
    private static async Task X1Wait(Func<bool> ready, string stage, int seconds = 20)
    {
        var elapsed = Stopwatch.StartNew();
        while (!ready())
        {
            if (elapsed.Elapsed > TimeSpan.FromSeconds(seconds)) throw new TimeoutException(stage);
            await Task.Delay(50);
        }
        Console.WriteLine("X1_PASS " + stage);
    }
    private static void X1Keys(byte key, bool hotkey = true)
    {
        // Only injected by this opt-in acceptance driver after verifying its owned foreground.
        byte[] keys = hotkey ? [0x11, 0x12, 0x10, key] : [key];
        foreach (byte item in keys) X1Input.keybd_event(item, 0, 0, UIntPtr.Zero);
        foreach (byte item in keys.Reverse()) X1Input.keybd_event(item, 0, 2, UIntPtr.Zero);
    }
    private static void VerifyX1Gui(AssistantApp app, bool nativeOnly)
    {
        bool passed = false;
        string stage = "startup";
        app.Startup += (_, _) => app.Dispatcher.BeginInvoke(new Action(async () =>
        {
            Process? fixture = null;
            try
            {
                await X1Wait(() => app.DevelopmentRuntime!.Snapshot().Runtime != "STARTING", "runtime-startup", 45);
                Assert.True(app.DevelopmentRuntime!.Snapshot().CanQueryStorage, "Runtime owner did not pass admission.");
                var quick = app.Windows.OfType<AssistantWindow>().Single();
                Assert.True(string.IsNullOrWhiteSpace(quick.HotkeyText.Text), "Hotkey registration conflict.");
                var start = new ProcessStartInfo(Environment.ProcessPath!)
                    { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden };
                start.ArgumentList.Add("--x1-selection-fixture");
                fixture = Process.Start(start)!;
                await X1Wait(() => { fixture.Refresh(); return fixture.MainWindowHandle != IntPtr.Zero; }, "external-fixture");
                var source = fixture.MainWindowHandle;
                var root = AutomationElement.FromHandle(source);
                var text = root.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.AutomationIdProperty, "X1SelectedText"));
                async Task Capture(byte key, AutomationElement control)
                {
                    Assert.True(X1Input.SetForegroundWindow(source), "Cannot activate owned fixture.");
                    control.SetFocus();
                    await X1Wait(() => Native.GetForegroundWindow() == source, "owned-source-focus");
                    X1Keys(key);
                    await X1Wait(() => quick.IsVisible && !app.Busy, "hotkey-capture", 90);
                }
                stage = "quick-actions";
                uint clipboard = Native.GetClipboardSequenceNumber();
                await Capture(0x51, text);
                Assert.Equal(X1Selection, quick.InputText.Text);
                Assert.Empty(quick.ResultText.Text); // Q never submits automatically.
                quick.ActionSelector.SelectedIndex = 1;
                Assert.Equal(X1Selection, quick.InputText.Text);
                Console.WriteLine("X1_PASS Q-capture-and-action-switch-preserve-selection");
                quick.ActionSelector.SelectedIndex = 2;
                Assert.EndsWith(X1Selection, quick.CreateInput().Text);
                quick.QuestionText.Text = "old-question";
                stage = "escape";
                Assert.Equal(new WindowInteropHelper(quick).Handle, Native.GetForegroundWindow());
                X1Keys(0x1B, false);
                await X1Wait(() => !quick.IsVisible, "physical-escape-hide");
                Assert.Empty(quick.InputText.Text); Assert.Empty(quick.QuestionText.Text);
                stage = "protected-new-capture";
                var password = root.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.AutomationIdProperty, "X1ProtectedInput"));
                await Capture(0x51, password);
                Assert.Empty(quick.InputText.Text); Assert.Empty(quick.ResultText.Text);
                Assert.Contains("Protected", quick.StatusText.Text);
                Console.WriteLine("X1_PASS new-protected-capture-no-stale-input");
                quick.Close();
                await Capture(0x51, text);
                Assert.Equal(X1Selection, quick.InputText.Text); Assert.Empty(quick.QuestionText.Text);
                Assert.Equal(clipboard, Native.GetClipboardSequenceNumber());
                Console.WriteLine("X1_PASS recapture-without-old-question-or-clipboard-change");
                if (nativeOnly) { passed = true; return; }
                quick.ActionSelector.SelectedIndex = 1;
                stage = "summary";
                await app.SubmitAsync();
                Console.WriteLine("X1_OPERATION_STATUS " + quick.StatusText.Text);
                Assert.False(string.IsNullOrWhiteSpace(quick.ResultText.Text), quick.StatusText.Text);
                stage = "selection-question";
                quick.ActionSelector.SelectedIndex = 2;
                quick.QuestionText.Text = "用一句中文说明这段文字的主题。";
                await app.SubmitAsync();
                Assert.False(string.IsNullOrWhiteSpace(quick.ResultText.Text), quick.StatusText.Text);
                Assert.Equal(clipboard, Native.GetClipboardSequenceNumber()); // No automatic copy/paste.
                Assert.Equal(X1Selection, ((ValuePattern)text.GetCurrentPattern(ValuePattern.Pattern)).Current.Value);
                Console.WriteLine("X1_PASS real-local-summary-question-display-only");

                quick.Close();
                stage = "quick-translate";
                await Capture(0x54, text);
                Assert.False(string.IsNullOrWhiteSpace(quick.ResultText.Text), quick.StatusText.Text);
                Console.WriteLine("X1_PASS existing-T-auto-translate");

                stage = "close-pending";
                var pending = app.SubmitAsync();
                Assert.True(app.Busy);
                quick.Close();
                Assert.Empty(quick.InputText.Text); Assert.Empty(quick.ResultText.Text);
                await pending;
                quick.Show(); quick.Activate();
                Assert.Empty(quick.InputText.Text); Assert.Empty(quick.ResultText.Text); Assert.Empty(quick.QuestionText.Text);
                Console.WriteLine("X1_PASS close-cancel-reopen-reject-late-result");
                quick.Close();
                passed = true;
            }
            catch (Exception error) { Console.WriteLine("X1_STOP stage=" + stage + " type=" + error.GetType().Name); }
            finally
            {
                if (fixture is not null)
                {
                    fixture.CloseMainWindow();
                    if (!fixture.WaitForExit(2000)) fixture.Kill(entireProcessTree: true);
                    fixture.Dispose();
                }
                await app.ExitAsync();
            }
        }), DispatcherPriority.ApplicationIdle);
        app.Run();
        Assert.True(passed, "X1 integrated flow stopped; see X1_STOP.");
    }
}
#endif
