using System;
using System.Diagnostics;
using System.IO;
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

    internal WorkspaceWebViewHost(WebView2 view, IWorkspaceNativeActions native, Action<string> fallback, string? assetFolder = null, Func<bool>? confirmDiscard = null)
    { this.view = view; this.native = native; this.fallback = fallback; this.assetFolder = assetFolder ?? Path.Combine(AppContext.BaseDirectory, "MainWorkspace"); this.confirmDiscard = confirmDiscard ?? (() => false); }

    internal async Task InitializeAsync()
    {
        elapsed.Start();
        try
        {
            var assets = new WorkspaceAssets(assetFolder);
            policy = new WorkspaceContentPolicy(assets.Files);
            UserDataFolder = WorkspaceProfile.CreatePrivateFolder();
            // Environment overrides are not frontend configuration; reject rather than inherit a debug port or foreign profile.
            foreach (string name in new[] { "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", "WEBVIEW2_USER_DATA_FOLDER", "WEBVIEW2_BROWSER_EXECUTABLE_FOLDER", "WEBVIEW2_PIPE_FOR_SCRIPT_DEBUGGER", "WEBVIEW2_WAIT_FOR_SCRIPT_DEBUGGER" })
                if (!string.IsNullOrEmpty(Environment.GetEnvironmentVariable(name))) throw new InvalidOperationException("Unexpected WebView environment override.");
            environment = await CoreWebView2Environment.CreateAsync(null, UserDataFolder,
                new CoreWebView2EnvironmentOptions { AreBrowserExtensionsEnabled = false, IsCustomCrashReportingEnabled = true });
            if (closing) return;
            var options = environment.CreateCoreWebView2ControllerOptions();
            options.ProfileName = "MainWorkspace";
            options.IsInPrivateModeEnabled = true;
            await view.EnsureCoreWebView2Async(environment, options);
            if (closing) return;
            var core = view.CoreWebView2;
            if (!core.Profile.IsInPrivateModeEnabled) throw new InvalidOperationException("Private profile required.");
            core.Profile.IsPasswordAutosaveEnabled = false;
            core.Profile.IsGeneralAutofillEnabled = false;
            await core.Profile.ClearBrowsingDataAsync(CoreWebView2BrowsingDataKinds.AllProfile);
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
                bridge.BeginDocument(e.Uri);
            };
            core.NavigationCompleted += (_, e) =>
            {
                if (closing || e.NavigationId != navigation) return;
                if (!e.IsSuccess) { Fail("页面未能加载。请使用原生 Assistant，或关闭后重新打开工作区。"); return; }
                bridge.Ready(core.Source);
                FirstReadyMilliseconds = elapsed.Elapsed.TotalMilliseconds;
                view.Focus();
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
                    e.Response = environment.CreateWebResourceResponse(Stream.Null, 403, "Blocked", "Content-Type: text/plain\r\nCache-Control: no-store");
            };
            core.WebMessageReceived += async (_, e) =>
            {
                if (closing || failed) return;
                try { await bridge.ReceiveAsync(e.Source, core.Source, e.WebMessageAsJson); }
                catch (Exception) { Fail("工作区通信不可用，请使用原生 Assistant。"); }
            };
            InitializationMilliseconds = elapsed.Elapsed.TotalMilliseconds;
            core.Navigate(policy.Entry);
        }
        catch (Exception) { if (!closing) Fail("无法初始化 Main Workspace。请确认已安装 Microsoft Edge WebView2 Runtime；原生 Assistant 可继续使用。"); }
    }

    internal void Fail(string message)
    {
        if (failed || closing) return;
        failed = true; bridge?.Invalidate();
        // Never include browser exceptions, URLs, WebMessages or page text in diagnostics.
        fallback(message);
    }

    internal async Task CloseAsync()
    {
        if (closing) return;
        closing = true; bridge?.Dispose();
        try
        {
            if (view.CoreWebView2 is not null)
            {
                await view.CoreWebView2.Profile.ClearBrowsingDataAsync(CoreWebView2BrowsingDataKinds.AllProfile).WaitAsync(TimeSpan.FromSeconds(3));
                CleanupPassed = true;
            }
        }
        catch (Exception) { CleanupPassed = false; }
        finally { view.Dispose(); }
    }
    internal void DisposeImmediately()
    {
        if (!closing) { closing = true; bridge?.Dispose(); }
        view.Dispose();
    }
}
