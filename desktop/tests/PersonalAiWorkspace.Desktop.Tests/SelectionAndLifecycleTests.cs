using System.Windows.Interop;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class SelectionAndLifecycleTests
{
    private sealed class CopyPort : ICopyPort
    {
        public bool ContextStable { get; set; } = true;
        public bool ModifiersReleased => true;
        public ClipboardSnapshot? Saved { get; set; } = new(7, "old-private-clipboard", true);
        public uint Sequence { get; set; } = 7;
        public uint AfterCopy { get; set; } = 8;
        public int Copies { get; private set; }
        public int Restores { get; private set; }
        public ClipboardRestore RestoreResult { get; set; } = ClipboardRestore.Restored;
        public bool OtherOwner { get; set; }
        public string? NewText { get; set; } = "new-private-selection";
        public bool CancelDuringRead { get; set; }
        public Task<ClipboardSnapshot?> SnapshotAsync(CancellationToken cancellationToken) => Task.FromResult(Saved);
        public bool SendCopy(uint expectedSequence) { Copies++; Sequence = AfterCopy; return true; }
        public bool ExpectedCopy(uint before, uint observed) => SelectionRules.FreshClipboard(before, observed, Sequence, !OtherOwner, ContextStable);
        public Task<string?> ReadFreshAsync(uint before, uint observed, CancellationToken cancellationToken)
        {
            if (CancelDuringRead) throw new OperationCanceledException();
            return Task.FromResult(ExpectedCopy(before, observed) ? NewText : null);
        }
        public Task<ClipboardRestore> RestoreAsync(ClipboardSnapshot snapshot, uint copiedSequence)
        { Restores++; return Task.FromResult(RestoreResult); }
    }
    private static SelectionResult CopyProof => new(SelectionStatus.Unsupported, FocusWindow: 1, CopyAllowed: true);

    [Fact]
    public async Task SelectionSuccessEmptyProtectedChangedAndTimeoutNeverSendCopy()
    {
        var success = SelectionRules.Classify(false, true, true, "selected-private-text");
        Assert.Equal(SelectionStatus.Success, success.Status);
        Assert.Equal("selected-private-text", success.Text);
        Assert.Equal(SelectionStatus.Unsupported, SelectionRules.Classify(false, true, false, null).Status);
        Assert.Equal(SelectionStatus.Empty, SelectionRules.Classify(false, true, true, "").Status);
        Assert.Equal(SelectionStatus.Protected, SelectionRules.Classify(true, true, true, "secret").Status);
        var port = new CopyPort();
        foreach (var proof in new[] { success, new(SelectionStatus.Empty), new(SelectionStatus.Protected),
            new(SelectionStatus.Timeout), new(SelectionStatus.ForegroundChanged), new(SelectionStatus.Untrusted), new(SelectionStatus.Unsupported) })
            await ControlledCopy.CaptureAsync(proof, port, CancellationToken.None);
        Assert.Equal(0, port.Copies);
        Assert.DoesNotContain("selected-private-text", success.ToString());
    }

    [Fact]
    public async Task FreshControlledCopyRestoresClipboardAndRefusesOldText()
    {
        var fresh = new CopyPort();
        var result = await ControlledCopy.CaptureAsync(CopyProof, fresh, CancellationToken.None);
        Assert.Equal(SelectionStatus.Success, result.Selection.Status);
        Assert.Equal("new-private-selection", result.Selection.Text);
        Assert.Equal(1, fresh.Copies);
        Assert.Equal(1, fresh.Restores);
        var stale = new CopyPort { AfterCopy = 7 };
        var rejected = await ControlledCopy.CaptureAsync(CopyProof, stale, CancellationToken.None);
        Assert.Equal(SelectionStatus.Empty, rejected.Selection.Status);
        Assert.Null(rejected.Selection.Text);
        Assert.Equal(0, stale.Restores);
        Assert.DoesNotContain("old-private-clipboard", fresh.Saved!.ToString());
    }

    [Fact]
    public async Task ClipboardOwnerChangesAndRichFormatsFailClosedWithoutOverwriting()
    {
        var other = new CopyPort { OtherOwner = true };
        var result = await ControlledCopy.CaptureAsync(CopyProof, other, CancellationToken.None);
        Assert.Null(result.Selection.Text);
        Assert.Equal(0, other.Restores);
        foreach (var outcome in new[] { ClipboardRestore.ExternalChange, ClipboardRestore.Failed })
        {
            var changed = new CopyPort { RestoreResult = outcome };
            var failed = await ControlledCopy.CaptureAsync(CopyProof, changed, CancellationToken.None);
            Assert.Null(failed.Selection.Text);
            Assert.NotEmpty(failed.Notice);
        }
        var rich = new CopyPort { Saved = new(7, null, false) };
        await ControlledCopy.CaptureAsync(CopyProof, rich, CancellationToken.None);
        Assert.Equal(0, rich.Copies);
        var oversized = new CopyPort { NewText = new string('x', 4001) };
        Assert.Equal(SelectionStatus.TooLarge, (await ControlledCopy.CaptureAsync(CopyProof, oversized, CancellationToken.None)).Selection.Status);
        Assert.Equal(1, oversized.Restores);
    }

    [Fact]
    public async Task ClipboardReadCancellationStillAttemptsRestore()
    {
        var port = new CopyPort { CancelDuringRead = true };
        await Assert.ThrowsAsync<OperationCanceledException>(() => ControlledCopy.CaptureAsync(CopyProof, port, CancellationToken.None));
        Assert.Equal(1, port.Restores);
    }

    private sealed class HotkeyStub(bool succeeds) : IHotkeyApi
    {
        public int Releases { get; private set; }
        public bool Register(IntPtr window, int id) => succeeds;
        public void Unregister(IntPtr window, int id) => Releases++;
    }
    [Fact]
    public void HotkeyConflictIsExplicitAndDisposeReleasesExactlyOnce()
    {
        var failed = new HotkeyStub(false);
        Assert.Contains("注册失败", Assert.Throws<InvalidOperationException>(() => new HotkeyRegistration(IntPtr.Zero, failed)).Message);
        Assert.Equal(0, failed.Releases);
        var registered = new HotkeyStub(true);
        var hotkey = new HotkeyRegistration(new IntPtr(1), registered);
        hotkey.Dispose(); hotkey.Dispose();
        Assert.Equal(1, registered.Releases);
    }

    [Fact]
    public async Task NativeHotkeyConflictThenShutdownMakesRegistrationAvailableAgain()
    {
        await Sta(() =>
        {
            using var messages = new HwndSource(new HwndSourceParameters("Desktop hotkey test") { ParentWindow = new IntPtr(-3) });
            // Use a test-only key so this test never consumes the user's production hotkey.
            const uint modifiers = 0x4000 | 1 | 2 | 4;
            Assert.True(Native.RegisterHotKey(messages.Handle, 0x51A1, modifiers, 0x78));
            try { Assert.False(Native.RegisterHotKey(messages.Handle, 0x51A2, modifiers, 0x78)); }
            finally { Native.UnregisterHotKey(messages.Handle, 0x51A1); }
            Assert.True(Native.RegisterHotKey(messages.Handle, 0x51A2, modifiers, 0x78));
            Assert.True(Native.UnregisterHotKey(messages.Handle, 0x51A2));
        });
    }

    [Fact]
    public async Task SingleInstanceSignalsPrimaryAndReleasesMutexOnShutdown()
    {
        string suffix = ".test." + Guid.NewGuid().ToString("N");
        await Sta(() =>
        {
            using var primary = new SingleInstance(suffix);
            Assert.True(primary.IsPrimary);
            using var activated = new ManualResetEventSlim();
            primary.Listen(() => activated.Set());
            Task.Run(() =>
            {
                using var secondary = new SingleInstance(suffix);
                Assert.False(secondary.IsPrimary);
                Assert.True(secondary.SignalPrimary());
            }).GetAwaiter().GetResult();
            Assert.True(activated.Wait(TimeSpan.FromSeconds(2)));
        });
        await Sta(() => { using var next = new SingleInstance(suffix); Assert.True(next.IsPrimary); });
    }

    private sealed class Controller : IAssistantController
    {
        public bool Busy => false;
        public bool Exiting => false;
        public int Cancels { get; private set; }
        public Task SubmitAsync() => Task.CompletedTask;
        public void CancelOperation() => Cancels++;
        public Task CheckHealthAsync() => Task.CompletedTask;
        public Task ImportCredentialAsync(string path) => Task.CompletedTask;
        public void ForgetCredential() { }
    }
    [Fact]
    public async Task WpfResultCardLoadsAndRemainsUsableForManualInput()
    {
        await Sta(() =>
        {
            var controller = new Controller();
            var window = new AssistantWindow(controller);
            Assert.True(window.IsInitialized);
            Assert.Equal(AssistantAction.Translate, window.SelectedAction);
            window.InputText.Text = "prior-input"; window.ResultText.Text = "prior-result";
            window.ActionSelector.SelectedIndex = 1;
            Assert.Equal(AssistantAction.Summarize, window.SelectedAction);
            Assert.Equal("Summarize", window.TranslateButton.Content);
            Assert.Empty(window.InputText.Text); Assert.Empty(window.ResultText.Text);
            Assert.Equal(6000, window.InputText.MaxLength);
            window.InputText.Text = "summary input"; window.ResultText.Text = "summary result";
            window.ActionSelector.SelectedIndex = 2;
            Assert.Equal(AssistantAction.Ask, window.SelectedAction);
            Assert.Empty(window.InputText.Text); Assert.Empty(window.ResultText.Text);
            Assert.Equal(3000, window.InputText.MaxLength);
            // This same reset is called by the actual hotkey before capture begins.
            window.SelectTranslate();
            Assert.Equal(AssistantAction.Translate, window.SelectedAction);
            Assert.Equal(4000, window.InputText.MaxLength);
            Assert.True(window.ResultText.IsReadOnly);
            window.InputText.Text = "manual-private-input";
            window.SetBusy(true);
            Assert.False(window.TranslateButton.IsEnabled);
            Assert.False(window.ActionSelector.IsEnabled);
            Assert.True(window.CancelButton.IsEnabled);
            window.CancelButton.RaiseEvent(new System.Windows.RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
            Assert.Equal(1, controller.Cancels);
            window.SetBusy(false);
            Assert.True(window.TranslateButton.IsEnabled);
            window.ClearText();
            Assert.Empty(window.InputText.Text);
        });
    }

    internal static Task Sta(Action action)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try { action(); done.SetResult(); }
            catch (Exception error) { done.SetException(error); }
            finally { System.Windows.Threading.Dispatcher.CurrentDispatcher.InvokeShutdown(); }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return done.Task;
    }
}
