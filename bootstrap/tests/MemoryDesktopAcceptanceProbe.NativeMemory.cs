using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

internal static partial class MemoryDesktopAcceptanceProbe
{
    // Prerequisite only: new synthetic Profile through the actual packaged native
    // contract. No Runtime, browser, HTTP request, account or existing Profile.
    private static int PrepareNativeMemory(string evidence, string id)
    {
        output = Path.GetFullPath(evidence);
        try
        {
            stage = "NATIVE_PROFILE_INPUT";
            Check(Directory.Exists(output) && !File.Exists(Path.Combine(output, "owned-profile.json")));
            Check(System.Text.RegularExpressions.Regex.IsMatch(id, "\\A[a-f0-9]{32}\\z"));
            using var sources = TrustedDevelopmentRuntimeSources.Open();
            string root = Mmf3ProfileState.Root(id);
            Check(!Path.Exists(root));
            File.WriteAllText(Path.Combine(output, "owned-profile.json"), JsonSerializer.Serialize(new
            { id, root, synthetic = true, newlyPrepared = false, preparationStarted = true, hostPid = Environment.ProcessId }));
            stage = "NATIVE_PROFILE_PREPARE";
            Mmf3ProfileState.Prepare(id);
            stage = "NATIVE_PROFILE_PROVISION";
            Mmf3ProfileNative.Provision(id, sources.JnaJar);
            using var pin = Mmf3ProfileState.Pin(id);
            stage = "NATIVE_PROFILE_PARSE";
            var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
            File.WriteAllText(Path.Combine(output, "owned-profile.json"), JsonSerializer.Serialize(new
            { id, root = launch.Root, synthetic = true, newlyPrepared = true, hostPid = Environment.ProcessId }));
            Console.WriteLine("NATIVE_MEMORY_PROFILE_PREPARED");
            return 0;
        }
        catch (Exception error)
        {
            File.WriteAllText(Path.Combine(output, "prepare-failure.json"), JsonSerializer.Serialize(new
            { stage, type = error.GetType().Name, hresult = error.HResult,
              message = error.Message.StartsWith("Development ", StringComparison.Ordinal) ? error.Message : null,
              frames = new StackTrace(error, true).GetFrames().Select(f => new { method = f.GetMethod()?.Name, line = f.GetFileLineNumber() }) }));
            Console.WriteLine("NATIVE_MEMORY_PROFILE_STOP " + stage);
            return 10;
        }
    }

    private static int RunNativeMemory(string evidence, string phase, string classpathFile)
    {
        output = Path.GetFullPath(evidence);
        Process? child = null;
        MemoryWindow? window = null;
        int result = 10;
        try
        {
            Check(phase is "save" or "reopen");
            using var proof = JsonDocument.Parse(File.ReadAllText(Path.Combine(output, "prepare", "owned-profile.json")));
            Check(proof.RootElement.GetProperty("synthetic").GetBoolean() && proof.RootElement.GetProperty("newlyPrepared").GetBoolean());
            string id = proof.RootElement.GetProperty("id").GetString()!, root = Mmf3ProfileState.Root(id);
            Check(root == proof.RootElement.GetProperty("root").GetString());
            using var pin = Mmf3ProfileState.Pin(id);
            var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
            using var sources = TrustedDevelopmentRuntimeSources.Open();
            using var job = RuntimeProcessJob.Create();
            // Keep full-context Knowledge history/journal paths below its current
            // Win32 limit. This is a new synthetic workspace, never Profile/data.
            string fixtureRoot = Path.Combine(root, "evidence", "m");
            string ready = Path.Combine(fixtureRoot, phase + "-ready.json"), done = Path.Combine(fixtureRoot, phase + "-done");
            Check(!File.Exists(ready) && !File.Exists(done));
            string preference = Path.Combine(Directory.GetParent(Directory.GetParent(root)!.FullName)!.FullName, "last-workspace");
            string? PreferenceHash() => File.Exists(preference) ? Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(preference))) : null;
            string? beforePreference = PreferenceHash();
            stage = "NATIVE_JVM_" + phase;
            var start = new ProcessStartInfo(sources.Java) { UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = root };
            foreach (string option in new[] { "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Dworkspace.native.development-profile=" + id,
                "-Dworkspace.native.bootstrap=" + Mmf3ProfileNative.Helper,
                "-Djna.boot.library.path=" + Path.Combine(root, "jna-5.17.0-win32-x86-64"), "-Djna.boot.library.name=jnidispatch",
                "-Djna.nosys=true", "-Djna.noclasspath=true", "-Djna.nounpack=true",
                "-Djava.io.tmpdir=" + Path.Combine(root, "evidence"), "-Djna.tmpdir=" + Path.Combine(root, "evidence"),
                "-Dmemory.native.desktop.fixture=" + phase, "-cp", File.ReadAllText(classpathFile).Trim(), "MemoryDesktopIntegrationProbe" })
                start.ArgumentList.Add(option);
            child = Process.Start(start) ?? throw new IOException();
            job.Assign(child);
            File.WriteAllText(Path.Combine(output, phase + "-owned-java.json"), JsonSerializer.Serialize(new
            { pid = child.Id, startedUtc = child.StartTime.ToUniversalTime(), hostPid = Environment.ProcessId, profileId = id }));
            using var stdout = new FileStream(Path.Combine(output, phase + "-java.log"), FileMode.CreateNew);
            using var stderr = new FileStream(Path.Combine(output, phase + "-java-errors.log"), FileMode.CreateNew);
            var log = child.StandardOutput.BaseStream.CopyToAsync(stdout);
            var errors = child.StandardError.BaseStream.CopyToAsync(stderr);
            var clock = Stopwatch.StartNew();
            while (!File.Exists(ready)) { Check(!child.HasExited && clock.Elapsed.TotalSeconds < 55); Thread.Sleep(100); }
            using var state = JsonDocument.Parse(File.ReadAllText(ready));
            int port = state.RootElement.GetProperty("port").GetInt32();
            Check(port is > 1024 and <= 65535 && port is not (8765 or 18765 or 18895));
            Check(state.RootElement.GetProperty("pid").GetInt32() == child.Id && !child.HasExited);
            Check(state.RootElement.GetProperty("phase").GetString() == phase && state.RootElement.GetProperty("syntheticNativeMemory").GetBoolean());
            string token = state.RootElement.GetProperty("tokenFile").GetString()!;
            Check(Path.GetFullPath(token) == Path.Combine(fixtureRoot, "credentials", "client-token"));
            Check(state.RootElement.GetProperty("database").GetString() == Path.Combine(fixtureRoot, "data", "memory.db"));
            using var transport = new NativeMemoryTransport(port, phase == "save");
            using var client = new RuntimeClient(transport, () => File.ReadAllText(token).Trim());
            var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
            app.Startup += async (_, _) =>
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(70)); var ct = timeout.Token;
                try
                {
                    stage = "NATIVE_WINDOW_" + phase;
                    window = new MemoryWindow(client) { Title = "原生记忆验收 · 仅合成数据" };
                    app.MainWindow = window; window.Show();
                    await Wait(() => Task.FromResult(window.StatusText.Text == "Memory 列表已刷新。"), ct);
                    if (phase == "save")
                    {
                        Check(window.MemoryList.Items.Count == 0);
                        window.TitleBox.Text = "待丢弃的合成草稿"; window.ContentBox.Text = "此内容不得保存。";
                        stage = "NATIVE_CANCEL_DISCARD";
                        await NativeDecision(window, false, () => window.NewButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent)));
                        Check(window.Dirty && window.ContentBox.Text == "此内容不得保存。" && transport.Writes == 0);
                        await NativeDecision(window, true, () => window.NewButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent)));
                        Check(!window.Dirty && window.ContentBox.Text == "" && transport.Writes == 0);
                        stage = "NATIVE_EXPLICIT_SAVE";
                        window.TitleBox.Text = "合成原生持久化 😀";
                        window.ContentBox.Text = "Synthetic only: 原生窗口明确保存。\n第二个独立进程必须读到这段原文 😀。";
                        Check(window.SaveButton.IsEnabled);
                        window.SaveButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
                        await Wait(() => Task.FromResult(!window.Dirty && window.StatusText.Text.StartsWith("Memory 已保存。", StringComparison.Ordinal)), ct);
                        var saved = (await client.ListMemoryAsync(new(), ct)).Items.Single();
                        var item = await client.GetMemoryAsync(saved.Id, ct);
                        Check(item.Revision == 1 && item.Content == window.ContentBox.Text && transport.Writes == 1);
                        File.WriteAllText(Path.Combine(output, "saved-record.json"), JsonSerializer.Serialize(new
                        { item, hostPid = Environment.ProcessId, javaPid = child.Id, profileId = id }));
                        CaptureNative(window, "saved-native.png");
                        stage = "NATIVE_CANCEL_CLOSE";
                        window.ContentBox.Text += "\nUNSAVED: this must not survive.";
                        await NativeDecision(window, false, window.Close);
                        Check(!window.IsClosed && window.Dirty);
                        stage = "NATIVE_NORMAL_CLOSE";
                        await NativeDecision(window, true, window.Close);
                    }
                    else
                    {
                        stage = "NATIVE_INDEPENDENT_REOPEN";
                        using var expected = JsonDocument.Parse(File.ReadAllText(Path.Combine(output, "saved-record.json")));
                        Check(expected.RootElement.GetProperty("hostPid").GetInt32() != Environment.ProcessId);
                        Check(expected.RootElement.GetProperty("javaPid").GetInt32() != child.Id);
                        var saved = expected.RootElement.GetProperty("item").Deserialize<MemoryItem>()!;
                        Check(window.MemoryList.Items.Count == 1);
                        window.MemoryList.SelectedItem = window.MemoryList.Items[0];
                        await Wait(() => Task.FromResult(window.StatusText.Text == "Memory 已加载。"), ct);
                        Check(window.TitleBox.Text == saved.Title && window.ContentBox.Text == saved.Content && !window.Dirty);
                        Check(await client.GetMemoryAsync(saved.Id, ct) == saved && transport.Writes == 0);
                        CaptureNative(window, "reopened-native.png");
                        window.Close();
                    }
                    Check(window.IsClosed && window.ContentBox.Text == "");
                    using (var file = new FileStream(done, FileMode.CreateNew)) using (var writer = new StreamWriter(file)) writer.Write("PASS");
                    result = 0;
                }
                catch (Exception error) { NativeFailure(phase, error); }
                finally
                {
                    if (window is { IsClosed: false })
                    {
                        if (window.Dirty) await NativeDecision(window, true, window.Close);
                        else window.Close();
                    }
                    app.Shutdown(result);
                }
            };
            app.Run();
            stage = "NATIVE_JVM_NORMAL_EXIT_" + phase;
            Check(child.WaitForExit(18000)); Task.WhenAll(log, errors).GetAwaiter().GetResult();
            Check(result == 0 && child.ExitCode == 0);
            Check(!System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(p => p.Port == port));
            Check(PreferenceHash() == beforePreference && !Directory.EnumerateFileSystemEntries(Path.Combine(root, "data")).Any());
            File.WriteAllText(Path.Combine(output, phase + "-result.json"), JsonSerializer.Serialize(new
            { passed = true, phase, hostPid = Environment.ProcessId, javaPid = child.Id, javaExitCode = child.ExitCode, port,
              windowClosedNormally = true, javaExitedNormally = true, portClosed = true, writes = transport.Writes,
              actualNativeWindow = true, actualHttp = true, actualSqlite = true, actualAssistantApp = false,
              webViewStarted = false, modelCalls = 0, personalSelectionUnchanged = true, ordinaryProfileDataEmpty = true }));
            Console.WriteLine("NATIVE_MEMORY_PERSISTENCE_PASS " + phase);
            return 0;
        }
        catch (Exception error) { NativeFailure(phase, error); return 10; }
        finally
        {
            if (child is not null)
            {
                bool forced = !child.HasExited;
                if (forced) { child.Kill(entireProcessTree: true); child.WaitForExit(5000); }
                File.WriteAllText(Path.Combine(output, phase + "-cleanup.json"), JsonSerializer.Serialize(new { pid = child.Id, exited = child.HasExited, forced }));
                child.Dispose();
            }
        }
    }

    private static void NativeFailure(string phase, Exception error)
    {
        string file = Path.Combine(output, phase + "-failure.json");
        if (File.Exists(file)) file = Path.Combine(output, phase + "-cleanup-failure.json");
        File.WriteAllText(file, JsonSerializer.Serialize(new
        { stage, type = error.GetType().Name, hresult = error.HResult, desktopError = (error as DesktopException)?.Error.ToString(),
          frames = new StackTrace(error, true).GetFrames().Select(f => new { method = f.GetMethod()?.Name, line = f.GetFileLineNumber() }) }));
        Console.WriteLine("NATIVE_MEMORY_PERSISTENCE_STOP " + phase + " " + stage);
    }
    private static async Task NativeDecision(MemoryWindow window, bool yes, Action action)
    {
        uint thread = ExportThreadId(); IntPtr owner = new WindowInteropHelper(window).Handle;
        var response = Task.Run(async () =>
        {
            var clock = Stopwatch.StartNew(); bool answered = false;
            while (!answered && clock.Elapsed.TotalSeconds < 8)
            {
                ExportThreadWindows(thread, (hwnd, _) =>
                {
                    if (ExportGetWindow(hwnd, 4) != owner) return true;
                    var title = new StringBuilder(80); ExportWindowText(hwnd, title, title.Capacity);
                    if (title.ToString() != "Memory") return true;
                    ExportSendMessage(hwnd, 0x0111, yes ? 6 : 7, 0); answered = true; return false;
                }, IntPtr.Zero);
                if (!answered) await Task.Delay(50);
            }
            Check(answered);
        });
        action(); await response;
    }
    private static void CaptureNative(MemoryWindow window, string name)
    {
        window.UpdateLayout();
        var bitmap = new RenderTargetBitmap((int)window.ActualWidth, (int)window.ActualHeight, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(window); var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = new FileStream(Path.Combine(output, name), FileMode.CreateNew); encoder.Save(file);
    }
    private sealed class NativeMemoryTransport(int port, bool allowCreate) : DelegatingHandler(new HttpClientHandler
        { UseProxy = false, AllowAutoRedirect = false, UseCookies = false })
    {
        internal int Writes;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Check(request.RequestUri!.Scheme == "http" && request.RequestUri.Host == "127.0.0.1" && request.RequestUri.Port == 8765);
            string path = request.RequestUri.AbsolutePath;
            Check(path == "/api/v1/memory/items" || System.Text.RegularExpressions.Regex.IsMatch(path, "\\A/api/v1/memory/items/[a-f0-9-]{36}\\z"));
            if (request.Method != HttpMethod.Get) { Writes++; Check(allowCreate && Writes == 1 && request.Method == HttpMethod.Post && path == "/api/v1/memory/items"); }
            request.RequestUri = new UriBuilder(request.RequestUri) { Port = port }.Uri;
            return base.SendAsync(request, ct);
        }
    }
}
