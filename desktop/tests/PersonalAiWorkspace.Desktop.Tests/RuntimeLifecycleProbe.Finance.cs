#if RUNTIME_LIFECYCLE_PROBE && MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.IO;
using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private sealed record FinanceStoreEvidence(Guid Memory, Guid Conversation, string Document, string BindingHash);

    // One realistic product flow, reused once for process reopen and once for a separate SQLite Profile.
    private static void VerifyFinanceGui(AssistantApp app, Mmf3AcceptanceLaunch launch, string mode)
    {
        bool passed = false; string phase = "STARTUP";
        app.Startup += async (_, _) =>
        {
            try
            {
                async Task Wait(Func<Task<bool>> predicate, int seconds = 70)
                {
                    var watch = Stopwatch.StartNew();
                    while (!await predicate()) { Assert.True(watch.Elapsed < TimeSpan.FromSeconds(seconds), phase); await Task.Delay(100); }
                }
                await Wait(() => Task.FromResult(app.Workspace?.Host.IsReady is true && app.DevelopmentRuntime!.Snapshot().CanQueryStorage));
                var core = app.Workspace!.Browser.CoreWebView2;
                using var client = RuntimeClient.CreateMmf3Acceptance(app.DevelopmentRuntime!.ReadCredential);
                using var http = new HttpClient(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
                async Task<HttpResponseMessage> Request(string path, bool post = false)
                {
                    using var request = new HttpRequestMessage(post ? HttpMethod.Post : HttpMethod.Get, "http://127.0.0.1:18765" + path);
                    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", app.DevelopmentRuntime.ReadCredential());
                    if (post) request.Content = new StringContent("{\"userId\":2}", Encoding.UTF8, "application/json");
                    return await http.SendAsync(request);
                }
                async Task Restart()
                {
                    using var old = Process.GetProcessById(app.DevelopmentRuntime.OwnedProcessId!.Value);
                    await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
                    await Wait(() => Task.FromResult(app.DevelopmentRuntime.Snapshot().CanQueryStorage && app.DevelopmentRuntime.OwnedProcessId != old.Id));
                    Assert.True(old.WaitForExit(10000));
                }
                async Task Display(string expected)
                {
                    await core.ExecuteScriptAsync("location.hash='#/finance'");
                    const string refresh = "[...document.querySelectorAll('button')].find(b=>b.textContent==='刷新 Finance'&&!b.disabled)";
                    await Wait(async () => await core.ExecuteScriptAsync("Boolean(" + refresh + ")") == "true");
                    await core.ExecuteScriptAsync(refresh + ".click()");
                    await Wait(async () => await core.ExecuteScriptAsync("document.querySelector('.finance-page')?.innerText.includes(" + JsonSerializer.Serialize(expected) + ")") == "true");
                    await core.ExecuteScriptAsync("document.querySelector('.finance-page').scrollIntoView({block:'center'})");
                }
                string binding = Path.Combine(launch.Root, "data", "finance-identity", "binding.json");
                string evidencePath = Path.Combine(launch.Root, "evidence", "finance-stores.json");
                if (mode == "gui-finance-isolation")
                {
                    Assert.Equal("SQLITE", (await client.GetStorageStatusAsync(CancellationToken.None)).Backend);
                    Assert.Equal("DISABLED", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State);
                    Assert.False(File.Exists(binding));
                    Assert.Empty((await client.ListMemoryAsync(new(), CancellationToken.None)).Items);
                    Assert.Empty((await client.ListConversationsAsync(ConversationStatus.ACTIVE, 0, 10, CancellationToken.None)).Items);
                    Assert.Empty((await client.ListKnowledgeAsync("ACTIVE", 0, CancellationToken.None)).Items);
                    await Display("Finance 已关闭");
                    Console.WriteLine("FINANCE_SQLITE_PROFILE_SEPARATE_FROM_POSTGRESQL_AND_DISABLED_PASS");
                }
                else
                {
                    using var target = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(Environment.CurrentDirectory, ".verification", mode.StartsWith("gui-finance-next10", StringComparison.Ordinal) ? "fnext10-target.json" : mode.StartsWith("gui-finance-next9", StringComparison.Ordinal) ? "fnext9-target.json" : mode.StartsWith("gui-finance-next8", StringComparison.Ordinal) ? "fnext8-target.json" : mode.StartsWith("gui-finance-next7", StringComparison.Ordinal) ? "fnext7-target.json" : mode.StartsWith("gui-finance-next6", StringComparison.Ordinal) ? "fnext6-target.json" : mode == "gui-finance-next5" ? "fnext5-target.json" : mode == "gui-finance-next3" ? "fnext3-target.json" : "fnext1-target.json")));
                    string fixtureRoot = target.RootElement.GetProperty("root").GetString()!;
                    Assert.StartsWith(@"D:\PersonalAiWorkspace-Fnext1\", fixtureRoot, StringComparison.Ordinal);
                    byte[] Fixture(string name) => ProtectedData.Unprotect(File.ReadAllBytes(Path.Combine(fixtureRoot, name)), null, DataProtectionScope.CurrentUser);
                    async Task Mutate(string action)
                    {
                        byte[] input = Fixture("fixture.dpapi");
                        try
                        {
                            using var sources = TrustedDevelopmentRuntimeSources.Open();
                            var start = new ProcessStartInfo(sources.Java) { UseShellExecute = false, CreateNoWindow = true,
                                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
                            foreach (string item in new[] { "-cp", Path.Combine(fixtureRoot, "tooling", "classes") + ";" + Path.Combine(fixtureRoot, "tooling", "lib", "*"), "FinanceNextPreparation", action }) start.ArgumentList.Add(item);
                            using var process = Process.Start(start)!;
                            await process.StandardInput.BaseStream.WriteAsync(input); process.StandardInput.Close();
                            string result = await process.StandardOutput.ReadToEndAsync(); string errors = await process.StandardError.ReadToEndAsync();
                            await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(30));
                            Assert.Equal("FNEXT_FIXTURE_PASS_" + action, result.Trim()); Assert.Equal(0, process.ExitCode); Assert.Empty(errors);
                        }
                        finally { CryptographicOperations.ZeroMemory(input); }
                    }
                    if (mode is "gui-finance" or "gui-finance-next3" or "gui-finance-next5" or "gui-finance-next6" or "gui-finance-next6-bob" or "gui-finance-next7" or "gui-finance-next7-bob" or "gui-finance-next8" or "gui-finance-next8-bob" or "gui-finance-next9" or "gui-finance-next9-bob" or "gui-finance-next10" or "gui-finance-next10-bob" && !File.Exists(binding))
                    {
                        Assert.Equal("DISABLED", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State);
                        phase = "NATIVE_WORKSPACE_CONFIGURATION";
                        byte[] config = Fixture("workspace.dpapi");
                        try {
                            var state = await NativeDatabaseSetup.ExecuteAsync(launch, "STATE", null, CancellationToken.None);
                            if (state == DatabaseSetupResult.NOT_CONFIGURED)
                                Assert.Equal(DatabaseSetupResult.SAVED, await NativeDatabaseSetup.ExecuteAsync(launch, "SAVE!", config, CancellationToken.None));
                            else Assert.Equal(DatabaseSetupResult.CONFIGURED, state);
                        }
                        finally { CryptographicOperations.ZeroMemory(config); }
                        await Restart();
                        Assert.Equal("POSTGRESQL", (await client.GetStorageStatusAsync(CancellationToken.None)).Backend);
                        await core.ExecuteScriptAsync("location.hash='#/settings'");
                        phase = "NATIVE_FINANCE_REVIEW";
                        await Wait(async () => await core.ExecuteScriptAsync("Boolean([...document.querySelectorAll('button')].find(b=>b.textContent==='原生 Finance 身份绑定…'&&!b.disabled))") == "true");
                        await core.ExecuteScriptAsync("[...document.querySelectorAll('button')].find(b=>b.textContent==='原生 Finance 身份绑定…').click()");
                        FinanceBindingWindow? dialog = null;
                        await Wait(() => Task.FromResult((dialog = app.Windows.OfType<FinanceBindingWindow>().SingleOrDefault()) is not null));
                        var panel = Assert.IsType<StackPanel>(Assert.IsType<ScrollViewer>(dialog!.Content).Content);
                        var fields = panel.Children.OfType<TextBox>().ToArray(); var password = Assert.Single(panel.Children.OfType<PasswordBox>());
                        var buttons = panel.Children.OfType<Button>().ToArray(); var candidates = Assert.Single(panel.Children.OfType<ComboBox>());
                        config = Fixture("finance.dpapi");
                        try
                        {
                            using var values = JsonDocument.Parse(config); var value = values.RootElement;
                            fields[0].Text = value.GetProperty("port").GetInt32().ToString(); fields[1].Text = value.GetProperty("database").GetString();
                            fields[2].Text = value.GetProperty("username").GetString(); password.Password = value.GetProperty("password").GetString()!;
                        }
                        finally { CryptographicOperations.ZeroMemory(config); }
                        buttons[0].RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                        await Wait(() => Task.FromResult(candidates.Items.Count == 2 || panel.Children.OfType<TextBlock>().Any(x => x.Text.StartsWith("读取被拒绝", StringComparison.Ordinal))));
                        if (candidates.Items.Count != 2) Console.WriteLine("FINANCE_NATIVE_REVIEW_FAILURE_" + panel.Children.OfType<TextBlock>().Last().Text);
                        Assert.True(candidates.Items.Count == 2); Assert.Equal(-1, candidates.SelectedIndex); Assert.False(buttons[1].IsEnabled);
                        Assert.False(File.Exists(binding)); Assert.Empty(password.Password);
                        Assert.Contains("服务端数据库：fnext1_finance_", string.Join("\n", panel.Children.OfType<TextBlock>().Select(x => x.Text)));
                        Assert.Contains("Synthetic Alice", candidates.Items[0].ToString()); Assert.Contains("Synthetic Bob", candidates.Items[1].ToString());
                        // The real native MessageBox is automated only for this explicitly synthetic candidate.
                        candidates.SelectedIndex = mode.EndsWith("-bob", StringComparison.Ordinal) ? 1 : 0;
                        phase = "NATIVE_FINANCE_CONFIRMATION";
                        await ConfirmNative(buttons[1], dialog);
                        await Wait(() => Task.FromResult(panel.Children.OfType<TextBlock>().Any(x => x.Text.StartsWith("已确认绑定并请求重启", StringComparison.Ordinal) || x.Text.StartsWith("绑定或重启未确认", StringComparison.Ordinal))));
                        Assert.True(File.Exists(binding));
                        Console.WriteLine("FINANCE_REAL_NATIVE_CONFIRMATION_PUBLISHED_BINDING_PASS");
                        dialog.Close();
                        await Wait(() => Task.FromResult(app.DevelopmentRuntime.Snapshot().CanQueryStorage));
                        Console.WriteLine("FINANCE_REAL_WPF_SERVER_REVIEW_EXPLICIT_SELECTION_MESSAGEBOX_CREATE_NEW_PASS");
                    }
                    if (mode.StartsWith("gui-finance-next10", StringComparison.Ordinal)) { phase = "DASHBOARD"; await VerifyFinanceDashboard(app, client, launch, mode.EndsWith("-bob", StringComparison.Ordinal), Mutate); passed = true; return; }
                    if (mode.StartsWith("gui-finance-next9", StringComparison.Ordinal)) { phase = "IMPORTS"; await VerifyFinanceImports(app, client, launch, mode.EndsWith("-bob", StringComparison.Ordinal), Mutate); passed = true; return; }
                    if (mode.StartsWith("gui-finance-next8", StringComparison.Ordinal)) { phase = "HISTORY"; await VerifyFinanceHistory(app, client, launch, mode.EndsWith("-bob", StringComparison.Ordinal), mode.EndsWith("-ui", StringComparison.Ordinal), Mutate); passed = true; return; }
                    if (mode is "gui-finance-next6-bob" or "gui-finance-next7-bob") {
                        if (mode == "gui-finance-next7-bob") await VerifyFinancePortfolio(app, client, launch, true);
                        phase = "POSITIONS_BOB"; await VerifyFinancePositionsBob(app, client); passed = true; return;
                    }
                    phase = "ACCOUNT_DISPLAY";
                    var overview = await client.GetFinanceOverviewAsync(CancellationToken.None);
                    Assert.Equal("READY", overview.State); Assert.Equal("9007199254740993.30", overview.CnyBalance!.Balance);
                    Assert.Equal("9007199254740993.10", overview.Account!.Balance); Assert.Equal("ACTIVE", overview.Account.Status);
                    await Display("9007199254740993.10 CNY");
                    Assert.Equal("true", await core.ExecuteScriptAsync("document.querySelector('.finance-page').innerText.includes('9007199254740993.30 CNY')"));
                    Console.WriteLine("FINANCE_REAL_REACT_TYPED_BRIDGE_RUNTIME_POSTGRESQL_EXACT_CNY_PASS");
                    if (mode is "gui-finance" or "gui-finance-next3")
                    {
                        byte[] original = await File.ReadAllBytesAsync(binding);
                        if (mode == "gui-finance")
                        {
                        phase = "IDENTITY_SECURITY";
                        using (var response = await Request("/api/v1/finance/accounts/3")) Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
                        foreach (string path in new[] { "/api/v1/finance/bind", "/api/v1/finance/confirmHistoricalBinding" })
                            using (var response = await Request(path, true)) Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
                        using (var response = await Request("/api/v1/finance/overview?userId=2")) Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
                        // Owned synthetic fixture mutations; restore exact original bytes and never touch another Profile.
                        string held = binding + ".synthetic-held"; File.Move(binding, held);
                        try { Assert.Equal("NOT_BOUND", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State); }
                        finally { File.Move(held, binding); }
                        try { await File.WriteAllTextAsync(binding, "{}"); Assert.Equal("BINDING_INVALID", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State); }
                        finally { await File.WriteAllBytesAsync(binding, original); }
                        await Mutate("identity-change");
                        try { Assert.Equal("BINDING_INVALID", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State); await Display("历史用户身份已改变"); }
                        finally { await Mutate("identity-restore"); }
                        await Mutate("swap");
                        try { Assert.Equal("BINDING_INVALID", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State); }
                        finally { await Mutate("unswap"); }
                        await Mutate("empty");
                        try { var empty = await client.GetFinanceOverviewAsync(CancellationToken.None); Assert.Equal("READY", empty.State); Assert.Null(empty.Account); await Display("没有 CNY 账户"); }
                        finally { await Mutate("accounts-restore"); }
                        await Mutate("offline");
                        try { Assert.Equal("UNAVAILABLE", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State); Assert.Equal("CONNECTED", (await client.GetStorageStatusAsync(CancellationToken.None)).Connection); }
                        finally { await Mutate("online"); }
                        await Display("9007199254740993.10 CNY");
                        await using (var rejected = new NativeFinanceSetup(CancellationToken.None))
                            await Assert.ThrowsAsync<IOException>(() => rejected.ReadAsync(launch, null));
                        Assert.Equal(original, await File.ReadAllBytesAsync(binding));
                        Console.WriteLine("FINANCE_UNBOUND_OTHER_USER_HTTP_BYPASS_INVALID_FILE_HISTORY_DATASET_EMPTY_OUTAGE_RECOVERY_NO_REBIND_PASS");
                        }
                        phase = "WORKSPACE_REGRESSION";
                        var memory = await client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, "F-next-1 synthetic", SyntheticMemory), CancellationToken.None);
                        var conversation = await client.CreateConversationAsync("F-next-1 synthetic", CancellationToken.None);
                        await client.RenameConversationAsync(conversation.Id, "F-next-1 persisted conversation", CancellationToken.None);
                        await client.ArchiveConversationAsync(conversation.Id, CancellationToken.None); await client.UnarchiveConversationAsync(conversation.Id, CancellationToken.None);
                        using var source = new MemoryStream(Encoding.UTF8.GetBytes("Amber lighthouse F-next-1 " + launch.RunId));
                        var job = await client.UploadKnowledgeAsync(source, "fnext1.txt", Guid.NewGuid().ToString("D"), null, null, CancellationToken.None);
                        await Wait(async () => (job = await client.GetKnowledgeImportAsync(job.RequestId, CancellationToken.None)).State == "READY");
                        Assert.Single((await client.SearchKnowledgeAsync("amber lighthouse", 10, CancellationToken.None)).Hits);
                        var evidence = new FinanceStoreEvidence(memory.Id, conversation.Id, job.DocumentId, Convert.ToHexString(SHA256.HashData(original)));
                        await File.WriteAllTextAsync(evidencePath, JsonSerializer.Serialize(evidence));
                        phase = "FINANCE_SWITCH_ISOLATION";
                        string financeConfig = Path.Combine(launch.Root, "data", "finance.dpapi"), disabled = financeConfig + ".synthetic-disabled";
                        File.Move(financeConfig, disabled);
                        try
                        {
                            await Restart(); Assert.Equal("DISABLED", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State);
                            Assert.Equal(SyntheticMemory, (await client.GetMemoryAsync(memory.Id, CancellationToken.None)).Content);
                            Assert.Single((await client.SearchKnowledgeAsync("amber lighthouse", 10, CancellationToken.None)).Hits);
                            Assert.Equal(conversation.Id, (await client.GetConversationAsync(conversation.Id, 0, 10, CancellationToken.None)).Conversation.Id);
                        }
                        finally { File.Move(disabled, financeConfig); }
                        await Restart();
                        Console.WriteLine("FINANCE_DISABLED_MEMORY_KNOWLEDGE_CONVERSATION_REGRESSION_AND_RESTART_PASS");
                    }
                    phase = "DURABLE_REOPEN";
                    if (mode is not ("gui-finance-next5" or "gui-finance-next6" or "gui-finance-next7")) {
                    var saved = JsonSerializer.Deserialize<FinanceStoreEvidence>(await File.ReadAllTextAsync(evidencePath))!;
                    Assert.Equal(saved.BindingHash, Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(binding))));
                    Assert.Equal(SyntheticMemory, (await client.GetMemoryAsync(saved.Memory, CancellationToken.None)).Content);
                    Assert.Equal("F-next-1 persisted conversation", (await client.GetConversationAsync(saved.Conversation, 0, 10, CancellationToken.None)).Conversation.Title);
                    Assert.Single((await client.SearchKnowledgeAsync("amber lighthouse", 10, CancellationToken.None)).Hits);
                    Assert.Equal("READY", (await client.GetKnowledgeAsync(saved.Document, CancellationToken.None)).Document.ProcessingState);
                    Assert.Equal("READY", (await client.GetFinanceOverviewAsync(CancellationToken.None)).State);
                    await Display("9007199254740993.10 CNY");
                    using (var image = new FileStream(Path.Combine(launch.Root, "evidence", mode + "-" + Guid.NewGuid().ToString("N") + ".png"), FileMode.CreateNew))
                        await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
                    Console.WriteLine("FINANCE_BOUND_PROFILE_AND_THREE_WORKSPACE_STORES_DURABLE_PASS");
                    }
                }
                if (mode == "gui-finance-next3") { phase = "TRANSACTIONS"; await VerifyFinanceTransactions(app, client); }
                if (mode == "gui-finance-next5") { phase = "ASSETS"; await VerifyFinanceAssets(app, client, launch); }
                if (mode == "gui-finance-next6") { phase = "POSITIONS"; await VerifyFinancePositions(app, client, launch); }
                if (mode == "gui-finance-next7") {
                    phase = "POSITIONS_REGRESSION"; await VerifyFinancePositions(app, client, launch, "fnext7-target.json", "fnext7-positions");
                    phase = "PORTFOLIO"; await VerifyFinancePortfolio(app, client, launch, false);
                }
                passed = true;
            }
            catch (Exception error) { Console.WriteLine("FINANCE_GUI_STOP_" + phase + "_" + (error is DesktopException desktop ? desktop.Error.ToString() : error.GetType().Name) + "_" + app.DevelopmentRuntime!.Snapshot().Failure); }
            finally { try { await app.ExitAsync(); } catch { passed = false; app.Shutdown(10); } }
        };
        app.Run(); Assert.True(passed);
    }
}
#endif
