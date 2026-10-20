using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using PersonalAiWorkspace.Desktop.Bridge;

namespace PersonalAiWorkspace.Desktop.Hosting;

// WebView-specific capabilities stay in Desktop. Core remains browser independent.
internal sealed class WorkspaceWebViewHost
{
    private readonly WebView2 view;
    private readonly IWorkspaceNativeActions native;
    private readonly Action<string> fallback;
    private readonly string assetFolder;
    private readonly Func<bool> confirmDiscard;
    internal bool HasDirtyEditor => bridge?.EditorDirty == true;
    private WorkspaceBridge? bridge;
    private WorkspaceContentPolicy? policy;
    private CoreWebView2Environment? environment;
    private ulong navigation;
    private bool closing;
    private bool failed;
    private readonly CancellationTokenSource lifetime = new();
    private readonly TaskCompletionSource ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly Stopwatch elapsed = new();
    internal double InitializationMilliseconds { get; private set; }
    internal double FirstReadyMilliseconds { get; private set; }
    internal string SessionId => bridge?.SessionId ?? "";
    internal string? UserDataFolder { get; private set; }
    internal bool CleanupPassed { get; private set; }
    internal int BlockedNavigations { get; private set; }
    internal int BlockedFrames { get; private set; }
    internal int BlockedPopups { get; private set; }
    internal int BlockedPermissions { get; private set; }
    internal int BlockedDownloads { get; private set; }
    internal string StartupStage { get; private set; } = "NOT_STARTED";
    internal bool IsReady { get; private set; }
    internal string? StartupFailureCode { get; private set; }
#if DEBUG
    private StreamWriter? diagnostics;
    private int diagnosticRecords;
#endif
    private void Stage(string stage)
    {
        if (closing || failed) return;
        StartupStage = stage;
        Diagnose("OK");
    }
    private void Diagnose(string code, int hresult = 0)
    {
#if DEBUG
        if (diagnosticRecords++ >= 64) return;
        // Only native fixed enums and numeric values; never exception text, URLs or page content.
        string record = System.Text.Json.JsonSerializer.Serialize(new {
            contract = "WORKSPACE_UI_V1", stage = StartupStage, code, hresult,
            elapsedMs = elapsed.ElapsedMilliseconds, utc = DateTime.UtcNow });
        Console.WriteLine(record);
        try { diagnostics?.WriteLine(record); } catch (IOException) { }
#endif
    }

#if MMF3_ACCEPTANCE && DEBUG
    private readonly Mmf3AcceptanceLaunch? acceptance;
#endif
    internal WorkspaceWebViewHost(WebView2 view, IWorkspaceNativeActions native, Action<string> fallback, string? assetFolder = null, Func<bool>? confirmDiscard = null
#if MMF3_ACCEPTANCE && DEBUG
        , Mmf3AcceptanceLaunch? acceptance = null
#endif
        )
    {
        this.view = view; this.native = native; this.fallback = fallback; this.assetFolder = assetFolder ?? Path.Combine(AppContext.BaseDirectory, "MainWorkspace"); this.confirmDiscard = confirmDiscard ?? (() => false);
#if MMF3_ACCEPTANCE && DEBUG
        this.acceptance = acceptance;
#endif
    }

    internal async Task InitializeAsync()
    {
        if (elapsed.IsRunning || closing) return;
        elapsed.Start();
        try
        {
            await InitializeCoreAsync().WaitAsync(TimeSpan.FromSeconds(30), lifetime.Token);
        }
        catch (OperationCanceledException) when (closing) { }
        catch (Exception error)
        {
            if (closing) return;
            string code = error is TimeoutException ? "STARTUP_TIMEOUT" : "INITIALIZATION_FAILED";
            StartupFailureCode = code;
            Diagnose(code, error.HResult);
            Fail($"工作区启动失败（{code} / {StartupStage}）。请关闭后重新打开；若从打包工具中启动，请在普通 Windows 桌面中双击开发客户端。原生 Assistant 可继续使用。");
            // EnsureCoreWebView2Async has no cancellation API. Dispose its control so a late
            // completion cannot navigate or revive a failed window; observe the task above.
            view.Dispose();
        }

        async Task InitializeCoreAsync()
        {
            Stage("ASSETS");
            var assets = new WorkspaceAssets(assetFolder);
            policy = new WorkspaceContentPolicy(assets.Files);
#if MMF3_ACCEPTANCE && DEBUG
            UserDataFolder = acceptance is not null ? WorkspaceProfile.Mmf3PrivateFolder(acceptance) : WorkspaceProfile.CreatePrivateFolder();
#else
            UserDataFolder = WorkspaceProfile.CreatePrivateFolder();
#endif
#if MMF3_ACCEPTANCE && DEBUG
            if (acceptance is not null)
            {
                try
                {
                    diagnostics = new StreamWriter(new FileStream(Path.Combine(acceptance.Root, "logs",
                        $"workspace-ui-{Guid.NewGuid():N}.jsonl"), FileMode.CreateNew, FileAccess.Write, FileShare.Read)) { AutoFlush = true };
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
#endif
            Stage("PROFILE_VALIDATED");
            // Environment overrides are not frontend configuration; reject rather than inherit a debug port or foreign profile.
            foreach (string name in new[] { "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", "WEBVIEW2_USER_DATA_FOLDER", "WEBVIEW2_BROWSER_EXECUTABLE_FOLDER", "WEBVIEW2_PIPE_FOR_SCRIPT_DEBUGGER", "WEBVIEW2_WAIT_FOR_SCRIPT_DEBUGGER" })
                if (!string.IsNullOrEmpty(Environment.GetEnvironmentVariable(name))) throw new InvalidOperationException("Unexpected WebView environment override.");
            Stage("ENVIRONMENT_START");
            environment = await CoreWebView2Environment.CreateAsync(null, UserDataFolder,
                new CoreWebView2EnvironmentOptions { AreBrowserExtensionsEnabled = false, IsCustomCrashReportingEnabled = true });
            Stage("ENVIRONMENT_CREATED");
#if MMF3_ACCEPTANCE && DEBUG
            if (acceptance is not null)
            {
                acceptance.Validate();
                if (!string.Equals(Path.GetFullPath(environment.UserDataFolder), acceptance.Profile, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("MMF3 profile mismatch.");
            }
#endif
            if (closing || failed) return;
            var options = environment.CreateCoreWebView2ControllerOptions();
            options.ProfileName = "MainWorkspace";
            options.IsInPrivateModeEnabled = true;
            Stage("CONTROLLER_START");
            view.CoreWebView2InitializationCompleted += (_, e) =>
            {
                if (closing || failed) return;
                if (e.IsSuccess) Stage("CORE_INITIALIZED");
                else Diagnose("CORE_INITIALIZATION_FAILED", e.InitializationException.HResult);
            };
            await view.EnsureCoreWebView2Async(environment, options);
            Stage("CONTROLLER_CREATED");
            if (closing || failed) return;
            var core = view.CoreWebView2;
#if MMF3_ACCEPTANCE && DEBUG
            if (acceptance is not null && !string.Equals(Path.GetFullPath(core.Environment.UserDataFolder), acceptance.Profile, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("MMF3 controller profile mismatch.");
#endif
            if (!core.Profile.IsInPrivateModeEnabled) throw new InvalidOperationException("Private profile required.");
            core.Profile.IsPasswordAutosaveEnabled = false;
            core.Profile.IsGeneralAutofillEnabled = false;
            Stage("PROFILE_CLEAR_START");
            await core.Profile.ClearBrowsingDataAsync(CoreWebView2BrowsingDataKinds.AllProfile);
            if (closing || failed) return;
            Stage("PROFILE_CLEAR_COMPLETED");
            var settings = core.Settings;
            settings.AreHostObjectsAllowed = false;
            settings.AreDevToolsEnabled = policy.Development;
            settings.AreDefaultContextMenusEnabled = false;
            settings.AreDefaultScriptDialogsEnabled = false;
            settings.IsStatusBarEnabled = false;
            settings.IsBuiltInErrorPageEnabled = false;
            settings.IsPasswordAutosaveEnabled = false;
            settings.IsGeneralAutofillEnabled = false;
            settings.IsWebMessageEnabled = true;
            settings.AreBrowserAcceleratorKeysEnabled = false;
            settings.IsZoomControlEnabled = true;
            if (!policy.Development) core.SetVirtualHostNameToFolderMapping(new Uri(policy.Origin).Host,
                assets.Folder, CoreWebView2HostResourceAccessKind.Deny);
#if DEBUG
            // Observe startup failures locally without enabling DevTools UI or a debugging port.
            core.GetDevToolsProtocolEventReceiver("Runtime.exceptionThrown").DevToolsProtocolEventReceived += (_, _) => Diagnose("SCRIPT_UNCAUGHT");
            core.GetDevToolsProtocolEventReceiver("Log.entryAdded").DevToolsProtocolEventReceived += (_, e) =>
            {
                using var entry = System.Text.Json.JsonDocument.Parse(e.ParameterObjectAsJson);
                if (entry.RootElement.GetProperty("entry").GetProperty("source").GetString() == "security") Diagnose("BROWSER_SECURITY_ERROR");
            };
            await core.CallDevToolsProtocolMethodAsync("Runtime.enable", "{}");
            await core.CallDevToolsProtocolMethodAsync("Log.enable", "{}");
            if (closing || failed) return;
            core.WebResourceResponseReceived += (_, e) =>
            {
                if (!policy.TrustedOrigin(e.Request.Uri)) return;
                string path = new Uri(e.Request.Uri).AbsolutePath;
                if (e.Response.StatusCode >= 400) Diagnose("ASSET_RESPONSE_FAILED");
                else if (path == "/index.html") Diagnose("HTML_LOADED");
                else if (path.EndsWith(".js", StringComparison.Ordinal)) Diagnose("SCRIPT_LOADED");
                else if (path.EndsWith(".css", StringComparison.Ordinal)) Diagnose("STYLESHEET_LOADED");
            };
#endif
            bridge = new WorkspaceBridge(policy, native, json =>
            {
                if (!closing && !failed && policy.Document(core.Source)) core.PostWebMessageAsJson(json);
            });
            core.NavigationStarting += (_, e) =>
            {
                if (!policy.Document(e.Uri)) { e.Cancel = true; BlockedNavigations++; return; }
                // Confirm before rotating the document/session. Cancellation preserves both.
                if (HasDirtyEditor && !confirmDiscard()) { e.Cancel = true; return; }
                navigation = e.NavigationId;
                IsReady = false;
                Stage("NAVIGATION_START");
                bridge.BeginDocument(e.Uri);
            };
            core.NavigationCompleted += (_, e) =>
            {
                if (closing || failed || e.NavigationId != navigation) return;
                if (!e.IsSuccess) { ready.TrySetException(new InvalidOperationException()); Diagnose("NAVIGATION_FAILED"); return; }
                bridge.Ready(core.Source);
                Stage("NAVIGATION_COMPLETED");
            };
            core.FrameNavigationStarting += (_, e) => { e.Cancel = !WorkspaceContentPolicy.AllowFrame; BlockedFrames++; };
            core.FrameCreated += (_, e) => e.Frame.NavigationStarting += (_, args) => { args.Cancel = true; BlockedFrames++; };
            core.NewWindowRequested += (_, e) => { e.Handled = !WorkspaceContentPolicy.AllowPopup; BlockedPopups++; };
            core.DownloadStarting += (_, e) => { e.Cancel = !WorkspaceContentPolicy.AllowDownload; e.Handled = true; BlockedDownloads++; };
            core.PermissionRequested += (_, e) =>
            { e.State = CoreWebView2PermissionState.Deny; e.SavesInProfile = false; e.Handled = true; BlockedPermissions++; };
            core.ServerCertificateErrorDetected += (_, e) => e.Action = CoreWebView2ServerCertificateErrorAction.Cancel;
            core.ProcessFailed += (_, _) => Fail("工作区浏览器进程已停止。原生功能仍可使用，请关闭后重新打开工作区。");
            core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All, CoreWebView2WebResourceRequestSourceKinds.All);
            core.WebResourceRequested += (_, e) =>
            {
                if (!policy.Resource(e.Request.Uri, e.Request.Method, e.ResourceContext.ToString()))
                {
                    Diagnose("RESOURCE_BLOCKED");
                    e.Response = environment.CreateWebResourceResponse(Stream.Null, 403, "Blocked", "Content-Type: text/plain\r\nCache-Control: no-store");
                }
            };
            core.WebMessageReceived += async (_, e) =>
            {
                if (closing || failed) return;
                try
                {
                    await bridge.ReceiveAsync(e.Source, core.Source, e.WebMessageAsJson);
                    if (!closing && !failed && bridge.HasBootstrapped && !IsReady)
                    {
                        IsReady = true;
                        Stage("REACT_BRIDGE_READY");
                        if (FirstReadyMilliseconds == 0) FirstReadyMilliseconds = elapsed.Elapsed.TotalMilliseconds;
                        ready.TrySetResult();
                    }
                }
                catch (Exception) { Fail("工作区通信不可用，请使用原生 Assistant。"); }
            };
            InitializationMilliseconds = elapsed.Elapsed.TotalMilliseconds;
            Stage("NAVIGATE");
            core.Navigate(policy.Entry);
            await ready.Task;
            if (!closing && !failed) view.Focus();
        }
    }

    internal void Fail(string message)
    {
        if (failed || closing) return;
        failed = true; bridge?.Invalidate();
        IsReady = false;
        // Never include browser exceptions, URLs, WebMessages or page text in diagnostics.
        fallback(message);
    }

    internal async Task CloseAsync()
    {
        if (closing) return;
        closing = true; lifetime.Cancel(); ready.TrySetCanceled(); bridge?.Dispose();
        try
        {
            if (view.CoreWebView2 is not null)
            {
                await view.CoreWebView2.Profile.ClearBrowsingDataAsync(CoreWebView2BrowsingDataKinds.AllProfile).WaitAsync(TimeSpan.FromSeconds(3));
                CleanupPassed = true;
            }
        }
        catch (Exception) { CleanupPassed = false; }
        finally { view.Dispose(); CloseDiagnostics(); }
    }
    internal void DisposeImmediately()
    {
        if (!closing) { closing = true; lifetime.Cancel(); ready.TrySetCanceled(); bridge?.Dispose(); }
        view.Dispose(); CloseDiagnostics();
    }
    private void CloseDiagnostics()
    {
#if DEBUG
        try { diagnostics?.Dispose(); } catch (IOException) { }
        diagnostics = null;
#endif
    }
}
