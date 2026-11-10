using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

internal static partial class MemoryDesktopAcceptanceProbe
{
    // Actual bundled AssistantApp/settings/Bridge/native window/Runtime, explicitly open-once.
    // The external guardian owns a fresh PostgreSQL cluster and passes only synthetic credentials.
    private static int RunDatabaseCutover(string evidence, string phase)
    {
        output = Path.GetFullPath(evidence);
        if (phase is not ("switch" or "reopen")) return 2;
        int result = 10;
        byte[] input = new byte[16385];
        try
        {
            stage = "CUTOVER_INPUT";
            int size = 0, read;
            using (var stdin = Console.OpenStandardInput())
                while (size < input.Length && (read = stdin.Read(input, size, input.Length - size)) != 0) size += read;
            Check(size is > 0 and < 16385);
            using var config = JsonDocument.Parse(input.AsMemory(0, size));
            using var proof = JsonDocument.Parse(File.ReadAllText(Path.Combine(output, "prepare", "owned-profile.json")));
            Check(proof.RootElement.GetProperty("synthetic").GetBoolean() && proof.RootElement.GetProperty("newlyPrepared").GetBoolean());
            string id = proof.RootElement.GetProperty("id").GetString()!;
            using var pin = Mmf3ProfileState.Pin(id);
            var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.OpenOnceArgument, id]);
            Check(launch.OpenOnce && launch.Root == proof.RootElement.GetProperty("root").GetString());
            using var expected = JsonDocument.Parse(File.ReadAllText(Path.Combine(launch.Root, "evidence", "cutover-data.json")));
            Guid memoryId = expected.RootElement.GetProperty("memoryId").GetGuid();
            string documentId = expected.RootElement.GetProperty("documentId").GetString()!;
            string dataConfig = Path.Combine(launch.Root, "data", "database.dpapi");
            using var single = new SingleInstance(launch.InstanceSuffix); Check(single.IsPrimary);
            var app = new AssistantApp(single, acceptance: launch);
            app.Startup += async (_, _) =>
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(190)); var ct = timeout.Token;
                try
                {
                    async Task Until(Func<Task<bool>> condition, int seconds = 45)
                    {
                        var clock = Stopwatch.StartNew();
                        while (!await condition()) { Check(clock.Elapsed.TotalSeconds < seconds); await Task.Delay(100, ct); }
                    }
                    stage = "CUTOVER_APP_READY_" + phase;
                    await Until(() => Task.FromResult(app.Workspace?.Host.IsReady is true && app.DevelopmentRuntime!.Snapshot().CanQueryStorage), 85);
                    Check(await app.DevelopmentStartupCompletion.WaitAsync(ct) == "READY_OPEN_ONCE");
                    var core = app.Workspace!.Browser.CoreWebView2;
                    using var client = RuntimeClient.CreateMmf3Acceptance(app.DevelopmentRuntime!.ReadCredential);
                    async Task VerifyData()
                    {
                        var item = await client.GetMemoryAsync(memoryId, ct);
                        Check(item.Content == "Synthetic cutover 中文 😀" && item.Revision == 1);
                        Check((await client.PreviewKnowledgeAsync(documentId, "1", 0, ct)).Text == "# Cutover\nSynthetic preserved source 中文 😀\n");
                    }
                    await VerifyData();
                    if (phase == "switch")
                    {
                        Check(!File.Exists(dataConfig));
                        Check((await client.GetStorageStatusAsync(ct)).Backend == "SQLITE");
                        stage = "CUTOVER_REAL_SETTINGS_ENTRY";
                        await core.ExecuteScriptAsync("location.hash='#/settings'");
                        const string inspect = "[...document.querySelectorAll('button')].find(b=>b.textContent==='重新检查存储'&&!b.disabled)";
                        await Until(async () => await core.ExecuteScriptAsync("Boolean(" + inspect + ")") == "true");
                        await core.ExecuteScriptAsync(inspect + ".click()");
                        await Until(async () => await core.ExecuteScriptAsync("[...document.querySelectorAll('dt')].find(x=>x.textContent==='当前持久化引擎')?.nextElementSibling?.textContent==='SQLite'") == "true");
                        const string open = "[...document.querySelectorAll('button')].find(b=>b.textContent==='打开原生数据库设置'&&!b.disabled)";
                        await Until(async () => await core.ExecuteScriptAsync("Boolean(" + open + ")") == "true");
                        await core.ExecuteScriptAsync(open + ".click()");
                        DatabaseSettingsWindow? dialog = null;
                        await Until(() => Task.FromResult((dialog = app.Windows.OfType<DatabaseSettingsWindow>().SingleOrDefault()) is not null));
                        var panel = (StackPanel)dialog!.Content;
                        var fields = panel.Children.OfType<TextBox>().ToArray(); Check(fields.Length == 3);
                        var password = panel.Children.OfType<PasswordBox>().Single();
                        var buttons = panel.Children.OfType<WrapPanel>().Single().Children.OfType<Button>().ToArray();
                        await Until(() => Task.FromResult(buttons[0].IsEnabled));
                        fields[0].Text = config.RootElement.GetProperty("port").GetInt32().ToString(System.Globalization.CultureInfo.InvariantCulture);
                        fields[1].Text = "cutover_fixture"; fields[2].Text = "cutover_app";
                        password.Password = config.RootElement.GetProperty("appPassword").GetString()!;
                        stage = "CUTOVER_NATIVE_CHECK";
                        buttons[0].RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
                        await Until(() => Task.FromResult(panel.Children.OfType<TextBlock>().Any(t => t.Text.StartsWith("连接成功；", StringComparison.Ordinal))));
                        Check(!File.Exists(dataConfig));
                        Console.WriteLine("CUTOVER_REAL_WEB_ENTRY_NATIVE_READONLY_CHECK_PASS");
                        stage = "CUTOVER_CANCEL_SAVE";
                        await DatabaseDecision(dialog, false, () => buttons[1].RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent)));
                        Check(!File.Exists(dataConfig));
                        stage = "CUTOVER_CONFIRM_SAVE";
                        await DatabaseDecision(dialog, true, () => buttons[1].RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent)));
                        await Until(() => Task.FromResult(panel.Children.OfType<TextBlock>().Any(t => t.Text.StartsWith("DPAPI CurrentUser 已保存", StringComparison.Ordinal))));
                        Check(password.Password == "" && File.Exists(dataConfig));
                        Check((await client.GetStorageStatusAsync(ct)).Backend == "SQLITE");
                        Console.WriteLine("CUTOVER_CANCEL_THEN_CONFIRMED_DPAPI_SAVE_NO_EARLY_SWITCH_PASS");
                        stage = "CUTOVER_SOURCE_BASELINE";
                        var sourceBefore = new[] { "memory.db", "knowledge/knowledge.db" }.ToDictionary(name => name,
                            name => CutoverSourceHash(Path.Combine(launch.Root, "data", name)));
                        using var oldProcess = Process.GetProcessById(app.DevelopmentRuntime.OwnedProcessId!.Value);
                        string? oldToken = app.DevelopmentRuntime.ReadCredential(); Check(oldToken is not null);
                        using var oldClient = RuntimeClient.CreateMmf3Acceptance(() => oldToken);
                        stage = "CUTOVER_CONFIRM_RESTART";
                        await DatabaseDecision(dialog, true, () => buttons[2].RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent)));
                        await Until(() => Task.FromResult(app.DevelopmentRuntime.OwnedProcessId is int next && next != oldProcess.Id
                            && app.DevelopmentRuntime.Snapshot().CanQueryStorage && buttons[2].IsEnabled));
                        Check(oldProcess.WaitForExit(10000));
                        bool rejected = false;
                        try { await oldClient.CheckCredentialAsync(ct); }
                        catch (DesktopException failure) { rejected = failure.Error == DesktopError.Unauthorized; }
                        Check(rejected); dialog.Close();
                        foreach (var file in sourceBefore) Check(file.Value.SequenceEqual(CutoverSourceHash(Path.Combine(launch.Root, "data", file.Key))));
                        Console.WriteLine("CUTOVER_REAL_NATIVE_RESTART_OLD_TOKEN_REJECTED_SOURCE_BYTES_RETAINED_PASS");
                    }
                    stage = "CUTOVER_POSTGRESQL_DATA_" + phase;
                    var storage = await client.GetStorageStatusAsync(ct);
                    Check(storage.Backend == "POSTGRESQL" && storage.Connection == "CONNECTED" && storage.ConfigurationTrust == "TRUSTED_LOCAL"
                        && storage.WorkspaceSchema == "V1_V2_READY" && storage.KnowledgeSchema == "READY" && !storage.FinanceEnabled);
                    await VerifyData();
                    await core.ExecuteScriptAsync("location.hash='#/settings'");
                    const string refresh = "[...document.querySelectorAll('button')].find(b=>b.textContent==='重新检查存储'&&!b.disabled)";
                    await Until(async () => await core.ExecuteScriptAsync("Boolean(" + refresh + ")") == "true");
                    await core.ExecuteScriptAsync(refresh + ".click()");
                    await Until(async () => await core.ExecuteScriptAsync("[...document.querySelectorAll('dt')].find(x=>x.textContent==='当前持久化引擎')?.nextElementSibling?.textContent==='PostgreSQL'") == "true");
                    using (var screenshot = new FileStream(Path.Combine(output, phase + "-postgresql.png"), FileMode.CreateNew))
                        await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, screenshot);
                    File.WriteAllText(Path.Combine(output, phase + "-result.json"), JsonSerializer.Serialize(new
                    { phase, backend = storage.Backend, profile = id, hostPid = Environment.ProcessId, runtimePid = app.DevelopmentRuntime.OwnedProcessId,
                      configurationSha256 = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(dataConfig))), openOnce = launch.OpenOnce,
                      realSettingsEntry = phase == "switch", importedMemoryRead = true, importedKnowledgeRead = true, modelCalls = 0 }));
                    result = 0; Console.WriteLine("DATABASE_CUTOVER_" + phase.ToUpperInvariant() + "_PASS");
                }
                catch (Exception failure) { Console.WriteLine("DATABASE_CUTOVER_STOP " + stage + " " + failure.GetType().Name + " " + failure.HResult.ToString("X8") + "\n" + failure.StackTrace); }
                finally
                {
                    try { await app.ExitAsync(); }
                    catch { result = 10; app.Shutdown(10); }
                }
            };
            try { app.Run(); } finally { app.Cleanup(); }
        }
        catch (Exception failure) { Console.WriteLine("DATABASE_CUTOVER_STOP " + stage + " " + failure.GetType().Name); }
        finally { CryptographicOperations.ZeroMemory(input); }
        return result;
    }

    private static byte[] CutoverSourceHash(string path)
    {
        // The synthetic source Runtime is still open. Share its existing read/write handles;
        // this observation is not a maintenance freeze or a backup consistency guarantee.
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        return SHA256.HashData(stream);
    }

    private static async Task DatabaseDecision(DatabaseSettingsWindow window, bool confirm, Action action)
    {
        uint thread = ExportThreadId(); IntPtr owner = new WindowInteropHelper(window).Handle;
        string expectedTitle = window.Title;
        var response = Task.Run(async () =>
        {
            var clock = Stopwatch.StartNew(); bool answered = false;
            while (!answered && clock.Elapsed.TotalSeconds < 8)
            {
                ExportThreadWindows(thread, (hwnd, _) =>
                {
                    if (ExportGetWindow(hwnd, 4) != owner) return true;
                    var title = new StringBuilder(80); ExportWindowText(hwnd, title, title.Capacity);
                    if (title.ToString() != expectedTitle) return true;
                    ExportSendMessage(hwnd, 0x0111, confirm ? 1 : 2, 0); answered = true; return false;
                }, IntPtr.Zero);
                if (!answered) await Task.Delay(50);
            }
            Check(answered);
        });
        action(); await response;
    }
}
