using System;
using System.Drawing;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using Forms = System.Windows.Forms;

namespace PersonalAiWorkspace.Desktop;

internal sealed class AssistantApp : Application, IAssistantController, IWorkspaceNativeActions
{
    private readonly SingleInstance single;
    private readonly CredentialStore credentials;
    private readonly CancellationTokenSource lifetime = new();
    private readonly RuntimeClient runtime;
    private readonly WorkspaceOperations workspaceOperations;
    private readonly WorkspaceConversations workspaceConversations;
    private readonly WorkspaceMemory workspaceMemory;
    private readonly WorkspaceKnowledge workspaceKnowledge;
    private MemorySelectionWindow? workspaceSelector;
    private AssistantWindow window = null!;
    private MainWorkspaceWindow? workspace;
    private Forms.NotifyIcon? tray;
    private Forms.ContextMenuStrip? trayMenu;
    private HwndSource? messages;
    private HotkeyRegistration? hotkey;
    private AssistantOperation? operation;
    private Task? activeOperation;
    private Task? activeCapture;
    private bool capturing;
    private bool exitRequested;
    private bool cleanedUp;
    public bool Busy => capturing || operation is not null;
    public bool Exiting { get; private set; }

    internal Forms.ContextMenuStrip? TrayMenu => trayMenu;
    internal MainWorkspaceWindow? Workspace => workspace;
    internal bool TrayVisible => tray?.Visible == true;
    internal AssistantApp(SingleInstance single, CredentialStore? testCredentials = null)
    {
        this.single = single;
        credentials = testCredentials ?? new CredentialStore();
        runtime = new RuntimeClient(() => credentials.Load());
        workspaceOperations = new WorkspaceOperations(runtime, SelectWorkspaceMemoryAsync, text => Clipboard.SetText(text));
        workspaceConversations = new WorkspaceConversations(runtime, SelectWorkspaceMemoryAsync);
        workspaceMemory = new WorkspaceMemory(runtime);
        workspaceKnowledge = new WorkspaceKnowledge(runtime,new NativeKnowledgeSourceFiles(()=>workspace));
        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        DispatcherUnhandledException += (_, error) =>
        {
            error.Handled = true;
            MessageBox.Show("Assistant 遇到应用错误，将退出并释放资源。请重新启动；如重复出现，请报告发生步骤。", "Personal AI Assistant");
            Exiting = true;
            Shutdown(1);
        };
    }
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        window = new AssistantWindow(this, runtime);
        messages = new HwndSource(new HwndSourceParameters("Personal AI Assistant messages")
        { ParentWindow = new IntPtr(-3), WindowStyle = 0, Width = 0, Height = 0 });
        messages.AddHook(WindowMessage);
        try { hotkey = new HotkeyRegistration(messages.Handle); }
        catch (InvalidOperationException failure) { window.HotkeyText.Text = failure.Message; }
        trayMenu = new Forms.ContextMenuStrip();
        trayMenu.Items.Add("Open Personal AI Workspace", null, (_, _) => Dispatcher.Invoke(ShowWorkspace));
        trayMenu.Items.Add("Quick Assistant / Quick Translate", null, (_, _) => Dispatcher.Invoke(ShowAssistant));
        trayMenu.Items.Add("检查 Runtime", null, async (_, _) => await CheckHealthAsync());
        trayMenu.Items.Add("退出", null, async (_, _) => await ExitAsync());
        tray = new Forms.NotifyIcon { Icon = SystemIcons.Application, Text = "Personal AI Workspace · Local Only", ContextMenuStrip = trayMenu, Visible = true };
        tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowWorkspace);
        single.Listen(() => Dispatcher.BeginInvoke(ShowWorkspace));
        RefreshCredentialStatus();
        ShowWorkspace();
        await CheckHealthAsync();
    }
    private IntPtr WindowMessage(IntPtr hwnd, int message, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (message == 0x0312 && wParam.ToInt32() == HotkeyRegistration.Id)
        {
            handled = true;
            if (!Busy && !exitRequested)
            {
                // Capture foreground before showing/activating our own window.
                IntPtr foreground = Native.GetForegroundWindow();
                activeCapture = CaptureAsync(foreground);
            }
        }
        return IntPtr.Zero;
    }
    private void ShowAssistant()
    {
        if (exitRequested) return;
        window.Show();
        if (window.WindowState == WindowState.Minimized) window.WindowState = WindowState.Normal;
        window.Activate();
    }
    internal void ShowWorkspace()
    {
        if (exitRequested) return;
        if (workspace is null)
        {
            workspace = new MainWorkspaceWindow(this);
            workspace.Closed += (_, _) => workspace = null;
        }
        MainWindow = workspace;
        workspace.Show();
        if (workspace.WindowState == WindowState.Minimized) workspace.WindowState = WindowState.Normal;
        workspace.Activate();
    }
    Task<ShellStatus> IWorkspaceNativeActions.StatusAsync(CancellationToken cancellation) =>
        new WorkspaceStatusProbe(runtime, credentials.Load).ReadAsync(cancellation);
    WorkspaceOperations IWorkspaceNativeActions.Operations => workspaceOperations;
    WorkspaceConversations IWorkspaceNativeActions.Conversations => workspaceConversations;
    WorkspaceMemory IWorkspaceNativeActions.Memory => workspaceMemory;
    WorkspaceKnowledge IWorkspaceNativeActions.Knowledge => workspaceKnowledge;
    private Task<System.Collections.Generic.IReadOnlyList<MemorySelection>?> SelectWorkspaceMemoryAsync(CancellationToken cancellation)
    {
        cancellation.ThrowIfCancellationRequested();
        if (exitRequested || workspace is null || workspaceSelector is not null) throw new InvalidOperationException();
        var picker = new MemorySelectionWindow(runtime) { Owner = workspace };
        workspaceSelector = picker;
        using var registration = cancellation.Register(() => Dispatcher.BeginInvoke(() => { if (!picker.IsClosed) picker.Close(); }));
        try { picker.ShowDialog(); cancellation.ThrowIfCancellationRequested(); return Task.FromResult(picker.Selection); }
        finally { workspaceSelector = null; workspace?.ReturnFocus(); }
    }
    Task IWorkspaceNativeActions.OpenAsync(NativeWorkspaceEntry entry, CancellationToken cancellation)
    {
        cancellation.ThrowIfCancellationRequested();
        if (exitRequested || Busy) throw new InvalidOperationException("Native surface unavailable.");
        if(entry==NativeWorkspaceEntry.KnowledgeBackup){
            var maintenance=new KnowledgeBackupWindow(runtime){Owner=workspace??(Window)window};
            using var registration=cancellation.Register(()=>Dispatcher.BeginInvoke(()=>maintenance.Close()));
            try{maintenance.ShowDialog();cancellation.ThrowIfCancellationRequested();}finally{workspace?.ReturnFocus();}
            return Task.CompletedTask;
        }
        bool quickWasVisible = window.IsVisible;
        ShowAssistant();
        if (entry == NativeWorkspaceEntry.LegacyAssistant) return Task.CompletedTask;
        try { window.OpenWorkspaceEntry(entry); }
        finally
        {
            if (entry != NativeWorkspaceEntry.CredentialFlow)
            {
                if (!quickWasVisible) window.Hide();
                workspace?.ReturnFocus();
            }
        }
        return Task.CompletedTask;
    }
    private async Task CaptureAsync(IntPtr foreground)
    {
        window.SelectTranslate();
        capturing = true;
        window.SetBusy(true);
        window.ResultText.Clear();
        window.CopyButton.IsEnabled = false;
        window.StatusText.Text = "正在获取主动选区…";
        try
        {
            if (foreground == IntPtr.Zero || foreground == new WindowInteropHelper(window).Handle)
            { ShowAssistant(); window.StatusText.Text = "请在其他应用选中文字，或在此手动输入。"; return; }
            var selection = await SelectionWorker.CaptureAsync(foreground, lifetime.Token);
            string notice = "";
            if (selection.Status == SelectionStatus.Unsupported && selection.CopyAllowed && window.AllowCopyFallback)
            {
                var copied = await ControlledCopy.CaptureAsync(selection,
                    new NativeCopyPort(foreground, new IntPtr(selection.FocusWindow), messages!.Handle), lifetime.Token);
                selection = copied.Selection;
                notice = copied.Notice;
            }
            if (exitRequested) return;
            ShowAssistant();
            if (selection.Status == SelectionStatus.Success)
            {
                window.InputText.Text = selection.Text!;
                capturing = false;
                await SubmitAsync();
            }
            else
            {
                // Clear any previous input so capture failure never translates stale text.
                window.InputText.Clear();
                window.StatusText.Text = notice.Length > 0 ? notice : SelectionText.For(selection.Status);
            }
        }
        catch (OperationCanceledException) { if (!exitRequested) window.StatusText.Text = "Selection cancelled。"; }
        catch (Exception) { if (!exitRequested) { ShowAssistant(); window.StatusText.Text = SelectionText.For(SelectionStatus.Failed); } }
        finally { capturing = false; if (!exitRequested) window.SetBusy(Busy); }
    }
    public async Task SubmitAsync()
    {
        if (Busy || exitRequested) return;
        if (window.MemoryNeedsReview) { window.StatusText.Text = ErrorText.For(DesktopError.MemorySelectionStale); return; }
        var input = new AssistantInput(window.SelectedAction, window.InputText.Text, window.SelectedLanguage);
        try { input.Validate(); }
        catch (DesktopException error) { window.StatusText.Text = error.Message; return; }
        var current = new AssistantOperation(runtime);
        operation = current;
        window.ResultText.Clear();
        window.CopyButton.IsEnabled = false;
        window.SetBusy(true);
        activeOperation = RunOperationAsync(current, input);
        await activeOperation;
    }
    private async Task RunOperationAsync(AssistantOperation current, AssistantInput input)
    {
        try
        {
            var result = await current.RunAsync(input, task =>
            {
                window.StatusText.Text = task.Status switch
                {
                    TaskState.QUEUED => "Queued：等待本机 Runtime 执行…",
                    TaskState.RUNNING => "Running：正在本机执行 " + input.Action + "…",
                    TaskState.SUCCEEDED => "Succeeded：" + input.Action + " 完成。",
                    _ => ErrorText.For(task.Error ?? DesktopError.InternalError)
                };
            }, lifetime.Token, window.MemoryReferences);
            if (result.Status == TaskState.SUCCEEDED) window.ResultText.Text = result.Result!;
        }
        catch (DesktopException error)
        {
            window.StatusText.Text = current.AdmissionOutcomeUnknown ? ErrorText.For(DesktopError.OutcomeUnknown) : error.Message;
            if (!current.Accepted) window.MemoryAdmissionFailed(error.Error);
        }
        catch (OperationCanceledException) { window.StatusText.Text = "应用关闭中；取消已尽力发送，未确认的 Runtime 状态不能视为已取消。"; }
        catch (Exception) { window.StatusText.Text = ErrorText.For(DesktopError.InternalError); }
        finally
        {
            operation = null;
            window.MemoryOperationEnded(current.Accepted);
            if (!exitRequested) window.SetBusy(Busy);
            if (!window.IsVisible) window.ClearText();
        }
    }
    public void CancelOperation()
    {
        if (capturing) { window.StatusText.Text = "选区与备用复制均有有限等待；等待捕获结束或从托盘退出。"; return; }
        if (operation is null) return;
        operation.RequestCancel();
        window.StatusText.Text = "Cancelling：正在向 Runtime 请求取消…";
        window.CancelButton.IsEnabled = false;
    }
    public async Task CheckHealthAsync()
    {
        if (exitRequested) return;
        try { await runtime.CheckHealthAsync(lifetime.Token); window.HealthText.Text = "Runtime UP · http://127.0.0.1:8765"; }
        catch (DesktopException error) { window.HealthText.Text = error.Message; }
        catch (OperationCanceledException) { }
    }
    private bool RefreshCredentialStatus()
    {
        try
        {
            bool ready = credentials.Load() is not null;
            window.CredentialText.Text = ready ? "凭据已保存在 Windows Credential Manager · 同一本机信任域" : ErrorText.For(DesktopError.CredentialMissing);
            return ready;
        }
        catch (DesktopException error) { window.CredentialText.Text = error.Message; return false; }
    }
    public async Task ImportCredentialAsync(string file)
    {
        if (Busy || exitRequested) return;
        try
        {
            credentials.Import(file);
            RefreshCredentialStatus();
            // Authenticated probe confirms imported credential without creating a task.
            await ProbeCredentialAsync();
        }
        catch (DesktopException error) { window.CredentialText.Text = error.Message; }
        catch (OperationCanceledException) { }
    }
    private async Task ProbeCredentialAsync()
    {
        // Provider readiness is authenticated and distinguishes bad auth from provider state.
        await runtime.CheckCredentialAsync(lifetime.Token);
        window.CredentialText.Text = "凭据有效 · Windows Credential Manager · single trust domain";
    }
    public void ForgetCredential()
    {
        if (Busy) return;
        try { credentials.Forget(); RefreshCredentialStatus(); }
        catch (DesktopException error) { window.CredentialText.Text = error.Message; }
    }
    internal async Task ExitAsync()
    {
        if (exitRequested) return;
        if (workspace is not null && !workspace.TryDiscardEditor()) return;
        if (!window.CloseMemory()) return;
        exitRequested = true;
        workspaceSelector?.Close();
        if (workspace is not null) await workspace.ShutdownAsync();
        await workspaceOperations.ShutdownAsync();
        window.CloseMemorySelector(); window.ClearMemorySelection();
        window.CloseBrowserPairing();
        window.CloseWorkspaceBackup();
        hotkey?.Dispose();
        operation?.RequestCancel();
        if (activeOperation is not null) await Task.WhenAny(activeOperation, Task.Delay(5000));
        lifetime.Cancel();
        if (activeCapture is not null) await Task.WhenAny(activeCapture, Task.Delay(3000));
        if (activeOperation is not null) await Task.WhenAny(activeOperation, Task.Delay(2500));
        Exiting = true;
        Shutdown();
    }
    protected override void OnExit(ExitEventArgs e)
    {
        Cleanup();
        base.OnExit(e);
    }
    internal void Cleanup()
    {
        if (cleanedUp) return;
        cleanedUp = true;
        window?.CloseBrowserPairing();
        window?.CloseWorkspaceBackup();
        // Normal exit awaits profile cleanup in ExitAsync. Emergency exit still disposes the controller.
        workspace?.DisposeImmediately();
        workspaceSelector?.Close();
        workspaceOperations.Dispose();
        window?.CloseMemorySelector();
        single.StopListening();
        lifetime.Cancel();
        hotkey?.Dispose();
        messages?.RemoveHook(WindowMessage);
        messages?.Dispose();
        if (tray is not null) { tray.Visible = false; tray.Dispose(); }
        trayMenu?.Dispose();
        runtime.Dispose();
        lifetime.Dispose();
        window?.ClearText();
    }
}
