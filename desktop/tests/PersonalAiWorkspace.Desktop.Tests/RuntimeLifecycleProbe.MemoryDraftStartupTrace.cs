#if MMF3_PROFILE_ACCEPTANCE && DEBUG && RUNTIME_LIFECYCLE_PROBE
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows.Interop;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    // Observe only this fixture's UI thread/window. Never activate another desktop or
    // change browser flags, window order, security descriptors, or the startup deadline.
    private sealed class MemoryDraftStartupTrace : IDisposable
    {
        private readonly MainWorkspaceWindow window;
        private readonly DispatcherTimer timer;
        private readonly Stopwatch clock = Stopwatch.StartNew();
        private readonly EventHandler<CoreWebView2InitializationCompletedEventArgs> completed;
        private readonly EventHandler<CoreWebView2ProcessFailedEventArgs> processFailed;
        private readonly EventHandler<CoreWebView2BrowserProcessExitedEventArgs> browserExited;
        private readonly EventHandler sourceInitialized;
        private readonly int sessionId;
        private readonly string threadDesktop;
        private readonly string inputDesktop;
        private int records;
        private int heartbeats;
        private bool? controllerCompleted;
        private int? controllerHresult;
        private CoreWebView2? initializedCore;
        private CoreWebView2Environment? initializedEnvironment;
        private long? browserProcessId;
        private string? browserVersion;
        private string? processFailureKind;
        private string? failureReportFolder;
        private bool disposed;

        internal MemoryDraftStartupTrace(MainWorkspaceWindow window)
        {
            this.window = window;
            using var process = Process.GetCurrentProcess();
            sessionId = process.SessionId;
            threadDesktop = DesktopName(GetThreadDesktop(GetCurrentThreadId()));
            inputDesktop = InputDesktopName();
            processFailed = (_, result) =>
            {
                try
                {
                    processFailureKind = result.ProcessFailedKind.ToString();
                    Write("BROWSER_PROCESS_FAILED", new {
                        reason = result.Reason.ToString(), exitCode = result.ExitCode,
                        exitCodeHex = $"0x{unchecked((uint)result.ExitCode):X8}",
                        processDescription = DiagnosticText(result.ProcessDescription),
                        failureSourceModule = DiagnosticText(Path.GetFileName(result.FailureSourceModulePath))
                    });
                }
                catch (Exception error) when (error is COMException or InvalidOperationException)
                { Write("PROCESS_FAILURE_DETAILS_UNAVAILABLE", new { hresult = error.HResult }); }
            };
            browserExited = (_, result) => Write("ENVIRONMENT_BROWSER_EXITED", new {
                browserProcessId = result.BrowserProcessId,
                exitKind = result.BrowserProcessExitKind.ToString()
            });
            completed = (_, result) =>
            {
                controllerCompleted = result.IsSuccess;
                controllerHresult = result.InitializationException?.HResult;
                if (result.IsSuccess && window.Browser.CoreWebView2 is { } core)
                {
                    initializedCore = core;
                    browserProcessId = core.BrowserProcessId;
                    initializedEnvironment = core.Environment;
                    browserVersion = initializedEnvironment.BrowserVersionString;
                    initializedEnvironment.BrowserProcessExited += browserExited;
                    core.ProcessFailed += processFailed;
                    try
                    {
                        var relative = Path.GetRelativePath(window.Host.UserDataFolder!, initializedEnvironment.FailureReportFolderPath);
                        failureReportFolder = Path.IsPathRooted(relative) || relative == ".."
                            || relative.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal)
                            ? "OUTSIDE_OWNED_PROFILE" : relative;
                    }
                    catch (Exception error) when (error is COMException or InvalidOperationException)
                    { failureReportFolder = "UNAVAILABLE"; }
                }
                Write("CONTROLLER_CALLBACK");
            };
            sourceInitialized = (_, _) => Write("HWND_CREATED");
            window.Browser.CoreWebView2InitializationCompleted += completed;
            window.SourceInitialized += sourceInitialized;
            timer = new DispatcherTimer(DispatcherPriority.Background, window.Dispatcher)
                { Interval = TimeSpan.FromSeconds(1) };
            timer.Tick += (_, _) => { heartbeats++; Write("DISPATCHER_HEARTBEAT"); };
            Write("HOST_CREATED");
            timer.Start();
        }

        private void Write(string observation, object? eventDetails = null)
        {
            if (disposed || records++ >= 64) return;
            var hwnd = new WindowInteropHelper(window).Handle;
            uint hwndThread = hwnd == IntPtr.Zero ? 0 : Native.GetWindowThreadProcessId(hwnd, out _);
            Console.WriteLine(JsonSerializer.Serialize(new
            {
                contract = "MEMORY_DRAFT_STARTUP_V1", observation, elapsedMs = clock.ElapsedMilliseconds,
                utc = DateTime.UtcNow, sequence = records,
                appPid = Environment.ProcessId, sessionId,
                userInteractive = Environment.UserInteractive,
                apartment = Thread.CurrentThread.GetApartmentState().ToString(),
                synchronizationContext = SynchronizationContext.Current?.GetType().Name,
                uiThread = GetCurrentThreadId(), hwndThread, hwndCreated = hwnd != IntPtr.Zero,
                dispatcherAccess = window.Dispatcher.CheckAccess(), heartbeats,
                windowLoaded = window.IsLoaded, windowVisible = window.IsVisible,
                browserLoaded = window.Browser.IsLoaded, browserVisible = window.Browser.IsVisible,
                browserWidth = window.Browser.ActualWidth, browserHeight = window.Browser.ActualHeight,
                stage = window.Host.StartupStage, failure = window.Host.StartupFailureCode,
                controllerCompleted, controllerHresult, browserProcessId, browserVersion,
                processFailureKind, eventDetails, failureReportFolder,
                sdkVersion = typeof(CoreWebView2Environment).Assembly.GetName().Version?.ToString(),
                threadDesktop, inputDesktop
            }));
        }

        private static string DiagnosticText(string? value)
        {
            // Runtime-assigned description/module basename only: no URLs, arguments or page data.
            var text = new StringBuilder(128);
            foreach (char character in value ?? "")
            {
                if (!char.IsControl(character)) text.Append(character);
                if (text.Length == 128) break;
            }
            return text.ToString();
        }

        internal void HostShutdownStarting() => Write("HOST_SHUTDOWN_START");

        private static string DesktopName(IntPtr desktop)
        {
            if (desktop == IntPtr.Zero) return "UNAVAILABLE";
            var name = new StringBuilder(256);
            return GetUserObjectInformationW(desktop, 2, name, 512, out _)
                ? name.ToString() : "READ_FAILED_" + Marshal.GetLastWin32Error();
        }

        private static string InputDesktopName()
        {
            var desktop = OpenInputDesktop(0, false, 1); // DESKTOP_READOBJECTS only.
            if (desktop == IntPtr.Zero) return "OPEN_FAILED_" + Marshal.GetLastWin32Error();
            try { return DesktopName(desktop); }
            finally { CloseDesktop(desktop); }
        }

        public void Dispose()
        {
            timer.Stop();
            Write("HOST_TEARDOWN");
            disposed = true;
            window.Browser.CoreWebView2InitializationCompleted -= completed;
            window.SourceInitialized -= sourceInitialized;
            // Startup failure may already have disposed the control before fixture teardown.
            try { if (initializedCore is not null) initializedCore.ProcessFailed -= processFailed; }
            catch (InvalidOperationException) { }
            catch (COMException) { }
            try { if (initializedEnvironment is not null) initializedEnvironment.BrowserProcessExited -= browserExited; }
            catch (InvalidOperationException) { }
            catch (COMException) { }
        }

        [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
        [DllImport("user32.dll")] private static extern IntPtr GetThreadDesktop(uint thread);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
        [DllImport("user32.dll")] private static extern bool CloseDesktop(IntPtr desktop);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
        private static extern bool GetUserObjectInformationW(IntPtr handle, int index,
            StringBuilder information, uint length, out uint needed);
    }
}
#endif
