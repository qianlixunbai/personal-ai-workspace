#if MMF3_PROFILE_ACCEPTANCE && DEBUG && RUNTIME_LIFECYCLE_PROBE
using System.Diagnostics;
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
        private readonly EventHandler sourceInitialized;
        private readonly int sessionId;
        private readonly string threadDesktop;
        private readonly string inputDesktop;
        private int records;
        private int heartbeats;
        private bool? controllerCompleted;
        private int? controllerHresult;
        private CoreWebView2? initializedCore;
        private long? browserProcessId;
        private string? browserVersion;
        private string? processFailureKind;
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
                processFailureKind = result.ProcessFailedKind.ToString();
                Write("BROWSER_PROCESS_FAILED");
            };
            completed = (_, result) =>
            {
                controllerCompleted = result.IsSuccess;
                controllerHresult = result.InitializationException?.HResult;
                if (result.IsSuccess && window.Browser.CoreWebView2 is { } core)
                {
                    initializedCore = core;
                    browserProcessId = core.BrowserProcessId;
                    browserVersion = core.Environment.BrowserVersionString;
                    core.ProcessFailed += processFailed;
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

        private void Write(string observation)
        {
            if (disposed || records++ >= 64) return;
            var hwnd = new WindowInteropHelper(window).Handle;
            uint hwndThread = hwnd == IntPtr.Zero ? 0 : Native.GetWindowThreadProcessId(hwnd, out _);
            Console.WriteLine(JsonSerializer.Serialize(new
            {
                contract = "MEMORY_DRAFT_STARTUP_V1", observation, elapsedMs = clock.ElapsedMilliseconds,
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
                processFailureKind, threadDesktop, inputDesktop
            }));
        }

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
