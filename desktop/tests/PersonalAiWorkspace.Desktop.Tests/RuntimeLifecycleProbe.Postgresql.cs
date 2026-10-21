#if RUNTIME_LIFECYCLE_PROBE && MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private sealed record PostgresqlEvidence(Guid Memory, long Revision, Guid Conversation, Guid Turn, string Document);
    private const string SyntheticMemory = "Synthetic PostgreSQL persistence 中文 😀";
    private const string SyntheticSource = "# Synthetic PostgreSQL\n\nAmber lighthouse verification.\n";

    private static void VerifyPostgresqlGui(AssistantApp app, Mmf3AcceptanceLaunch launch, string suffix, bool reopen, bool storageOnly)
    {
        bool passed = false;
        string phase = "STARTUP";
        app.Startup += async (_, _) =>
        {
            try
            {
                async Task Wait(Func<Task<bool>> predicate, int seconds = 85)
                {
                    var clock = Stopwatch.StartNew();
                    while (!await predicate()) { Assert.True(clock.Elapsed < TimeSpan.FromSeconds(seconds), phase); await Task.Delay(150); }
                }
                await Wait(() => Task.FromResult(app.Workspace?.Host.IsReady is true && app.DevelopmentRuntime!.Snapshot().CanQueryStorage));
                var core = app.Workspace!.Browser.CoreWebView2;
                using var client = RuntimeClient.CreateMmf3Acceptance(app.DevelopmentRuntime!.ReadCredential);
                const string recheck = "[...document.querySelectorAll('button')].find(b=>b.textContent==='重新检查存储'&&!b.disabled)";
                const string engine = "[...document.querySelectorAll('dt')].find(x=>x.textContent==='当前持久化引擎')?.nextElementSibling?.textContent";
                async Task Display(string expected)
                {
                    await core.ExecuteScriptAsync("location.hash='#/settings'");
                    await Wait(async () => await core.ExecuteScriptAsync("Boolean(" + recheck + ")") == "true");
                    await core.ExecuteScriptAsync(recheck + ".click()");
                    await Wait(async () => await core.ExecuteScriptAsync(engine + "==='" + expected + "'") == "true");
                }
                string evidencePath = Path.Combine(launch.Root, "evidence", "postgresql-stores.json");
                string syntheticSource = SyntheticSource.Replace("verification.", "verification " + launch.RunId + ".", StringComparison.Ordinal);
                if (!reopen)
                {
                    phase = "NATIVE_CONFIGURATION";
                    Assert.False(File.Exists(Path.Combine(launch.Root, "data", "database.dpapi")));
                    Assert.Equal("SQLITE", (await client.GetStorageStatusAsync(CancellationToken.None)).Backend);
                    await Display("SQLite");
                    await core.ExecuteScriptAsync("[...document.querySelectorAll('button')].find(b=>b.textContent==='打开原生数据库设置'&&!b.disabled).click()");
                    DatabaseSettingsWindow? dialog = null;
                    await Wait(() => Task.FromResult((dialog = app.Windows.OfType<DatabaseSettingsWindow>().SingleOrDefault()) is not null));
                    var panel = Assert.IsType<StackPanel>(dialog!.Content);
                    var fields = panel.Children.OfType<TextBox>().ToArray(); Assert.Equal(3, fields.Length);
                    var password = Assert.Single(panel.Children.OfType<PasswordBox>());
                    var buttons = panel.Children.OfType<WrapPanel>().Single().Children.OfType<Button>().ToArray();
                    await Wait(() => Task.FromResult(buttons[0].IsEnabled));
                    // Read only this run's protected fixture; never a command argument or a WebMessage.
                    string session = Path.GetFullPath(Path.Combine(Environment.CurrentDirectory, ".runtime", "workspace", "pgdesktop-" + suffix));
                    using (var file = new FileStream(Path.Combine(session, "application.dpapi"), FileMode.Open, FileAccess.Read, FileShare.Read))
                    {
                        Assert.True(file.Length is > 0 and < 16384);
                        var acl = file.GetAccessControl();
                        Assert.True(acl.AreAccessRulesProtected);
                        Assert.Equal(WindowsIdentity.GetCurrent().User!.Value, acl.GetOwner(typeof(SecurityIdentifier))!.Value);
                        Assert.All(acl.GetAccessRules(true, true, typeof(SecurityIdentifier)).Cast<System.Security.AccessControl.FileSystemAccessRule>(), rule =>
                            Assert.Equal(WindowsIdentity.GetCurrent().User!.Value, rule.IdentityReference.Value));
                        byte[] encrypted = new byte[file.Length]; file.ReadExactly(encrypted);
                        byte[] plaintext = ProtectedData.Unprotect(encrypted, null, DataProtectionScope.CurrentUser);
                        try
                        {
                            using var config = JsonDocument.Parse(plaintext);
                            Assert.Equal("paw_pgdesktop_" + suffix, config.RootElement.GetProperty("database").GetString());
                            Assert.Equal("paw_pgd_app_" + suffix, config.RootElement.GetProperty("username").GetString());
                            fields[1].Text = config.RootElement.GetProperty("database").GetString();
                            fields[2].Text = config.RootElement.GetProperty("username").GetString();
                            password.Password = config.RootElement.GetProperty("password").GetString()!;
                        }
                        finally { CryptographicOperations.ZeroMemory(plaintext); CryptographicOperations.ZeroMemory(encrypted); }
                    }
                    buttons[0].RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                    await Wait(() => Task.FromResult(panel.Children.OfType<TextBlock>().Any(t => t.Text.StartsWith("连接成功；", StringComparison.Ordinal))));
                    Console.WriteLine("REAL_WPF_POSTGRESQL_CHECK_PASS");
                    await ConfirmNative(buttons[1], dialog);
                    await Wait(() => Task.FromResult(panel.Children.OfType<TextBlock>().Any(t => t.Text.StartsWith("DPAPI CurrentUser 已保存", StringComparison.Ordinal))));
                    Assert.Empty(password.Password);
                    byte[] saved = await File.ReadAllBytesAsync(Path.Combine(launch.Root, "data", "database.dpapi"));
                    Assert.Equal(DatabaseSetupResult.ALREADY_CONFIGURED, await NativeDatabaseSetup.ExecuteAsync(launch, "SAVE!", null, CancellationToken.None));
                    Assert.Equal(saved, await File.ReadAllBytesAsync(Path.Combine(launch.Root, "data", "database.dpapi")));
                    Console.WriteLine("REAL_WPF_DPAPI_SAVE_RELOAD_AND_CREATE_NEW_NO_OVERWRITE_PASS");
                    // Capture a fixed bearer immediately before the explicit native restart.
                    string? oldToken = app.DevelopmentRuntime.ReadCredential();
                    Assert.NotNull(oldToken);
                    using var oldClient = RuntimeClient.CreateMmf3Acceptance(() => oldToken);
                    using var oldProcess = Process.GetProcessById(app.DevelopmentRuntime.OwnedProcessId!.Value);
                    phase = "NATIVE_RESTART";
                    await ConfirmNative(buttons[2], dialog);
                    await Wait(() => Task.FromResult(app.DevelopmentRuntime.OwnedProcessId is int newPid
                        && newPid != oldProcess.Id && app.DevelopmentRuntime.Snapshot().CanQueryStorage && buttons[2].IsEnabled));
                    Assert.True(oldProcess.WaitForExit(10000));
                    Assert.Equal(DesktopError.Unauthorized, (await Assert.ThrowsAsync<DesktopException>(() => oldClient.CheckCredentialAsync(CancellationToken.None))).Error);
                    dialog.Close();
                    Console.WriteLine("REAL_NATIVE_EXPLICIT_POSTGRESQL_RESTART_AND_OLD_BEARER_401_PASS");
                }
                phase = "POSTGRESQL_REACT_STATUS";
                var storage = await client.GetStorageStatusAsync(CancellationToken.None);
                Assert.Equal("POSTGRESQL", storage.Backend); Assert.Equal("TRUSTED_LOCAL", storage.ConfigurationTrust);
                Assert.Equal("CONNECTED", storage.Connection); Assert.Equal("V1_V2_READY", storage.WorkspaceSchema);
                Assert.Equal("READY", storage.KnowledgeSchema); Assert.False(storage.FinanceEnabled); Assert.Equal("DISABLED", storage.FinanceSchema);
                await Display("PostgreSQL");
                Console.WriteLine("REAL_REACT_POSTGRESQL_NATIVE_BEARER_AND_FINANCE_DISABLED_PASS");
                var sqliteBefore = new Dictionary<string, byte[]>();
                foreach (string path in new[] { Path.Combine(launch.Root, "data", "memory.db"), Path.Combine(launch.Root, "data", "knowledge", "knowledge.db") })
                    if (File.Exists(path)) sqliteBefore.Add(path, SHA256.HashData(await File.ReadAllBytesAsync(path)));

                PostgresqlEvidence evidence;
                if (reopen) evidence = JsonSerializer.Deserialize<PostgresqlEvidence>(await File.ReadAllTextAsync(evidencePath))!;
                else
                {
                    phase = "MEMORY";
                    var memory = await client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, "Synthetic PostgreSQL", "initial"), CancellationToken.None);
                    long firstRevision = memory.Revision;
                    memory = await client.UpdateMemoryAsync(memory.Id, new(memory.Revision, memory.Type, memory.Title, SyntheticMemory), CancellationToken.None);
                    Assert.True(memory.Revision > firstRevision);
                    Assert.Equal(DesktopError.MemoryRevisionConflict, (await Assert.ThrowsAsync<DesktopException>(() => client.DeleteMemoryAsync(memory.Id, firstRevision, CancellationToken.None))).Error);
                    Assert.Equal(SyntheticMemory, (await client.GetMemoryAsync(memory.Id, CancellationToken.None)).Content);
                    Console.WriteLine("REAL_POSTGRESQL_MEMORY_CREATE_READ_UPDATE_REVISION_AND_STALE_DELETE_PROTECTION_PASS");

                    phase = "CONVERSATION";
                    var conversation = await client.CreateConversationAsync("Synthetic PostgreSQL", CancellationToken.None);
                    Guid turnId = Guid.Empty;
                    if (storageOnly)
                    {
                        await client.RenameConversationAsync(conversation.Id, "Synthetic PostgreSQL durable metadata", CancellationToken.None);
                        Assert.Equal(ConversationStatus.ARCHIVED, (await client.ArchiveConversationAsync(conversation.Id, CancellationToken.None)).Status);
                        Assert.Equal(ConversationStatus.ACTIVE, (await client.UnarchiveConversationAsync(conversation.Id, CancellationToken.None)).Status);
                        Console.WriteLine("REAL_POSTGRESQL_CONVERSATION_METADATA_LIFECYCLE_PASS_MESSAGES_AND_TERMINAL_NOT_EXECUTED");
                    }
                    else
                    {
                        var admission = await client.SubmitConversationTurnAsync(conversation.Id, "Reply with the word amber.", [], CancellationToken.None);
                        ConversationTurn? turn = null;
                        await Wait(async () => (turn = (await client.GetConversationAsync(conversation.Id, 0, 10, CancellationToken.None)).Turns.Single()).Status != ConversationTurnStatus.PENDING, 180);
                        Assert.Equal(admission.TurnId, turn!.Id); Assert.Equal("Reply with the word amber.", turn.UserMessage.Content);
                        Assert.True(turn.Status is ConversationTurnStatus.SUCCEEDED or ConversationTurnStatus.FAILED or ConversationTurnStatus.CANCELLED or ConversationTurnStatus.TIMED_OUT);
                        turnId = turn.Id;
                        Console.WriteLine("REAL_POSTGRESQL_CONVERSATION_MESSAGE_AND_TERMINAL_" + turn.Status + "_PASS");
                    }

                    phase = "KNOWLEDGE";
                    async Task<KnowledgeDetail> Import(string text, string filename, KnowledgeDetail? previous = null)
                    {
                        using var source = new MemoryStream(Encoding.UTF8.GetBytes(text));
                        var job = await client.UploadKnowledgeAsync(source, filename, Guid.NewGuid().ToString("D"), previous?.Document.DocumentId, previous?.Document.MetadataVersion, CancellationToken.None);
                        await Wait(async () => (job = await client.GetKnowledgeImportAsync(job.RequestId, CancellationToken.None)).State is not ("PENDING" or "PARSING"));
                        Assert.Equal("READY", job.State);
                        return await client.GetKnowledgeAsync(job.DocumentId, CancellationToken.None);
                    }
                    var document = await Import("# First revision\n\nAmber lighthouse.\n", "Synthetic.md");
                    document = await Import(syntheticSource, "Synthetic.md", document);
                    Assert.Equal(2, document.Revisions.Length); Assert.Equal("2", document.Document.CurrentReadyRevision);
                    var archived = await client.KnowledgeLifecycleAsync(document.Document.DocumentId, document.Document.MetadataVersion, "archive", CancellationToken.None);
                    await client.KnowledgeLifecycleAsync(archived.DocumentId, archived.MetadataVersion, "restore", CancellationToken.None);
                    var temporary = await Import("Synthetic temporary text deletion.\n", "Temporary.txt");
                    await client.DeleteKnowledgeAsync(temporary.Document.DocumentId, temporary.Document.MetadataVersion, CancellationToken.None);
                    Assert.Equal(DesktopError.KnowledgeNotFound, (await Assert.ThrowsAsync<DesktopException>(() => client.GetKnowledgeAsync(temporary.Document.DocumentId, CancellationToken.None))).Error);
                    await client.RebuildKnowledgeSearchAsync(CancellationToken.None);
                    evidence = new(memory.Id, memory.Revision, conversation.Id, turnId, document.Document.DocumentId);
                    using (var output = new FileStream(evidencePath, FileMode.CreateNew)) await JsonSerializer.SerializeAsync(output, evidence);
                    Console.WriteLine("REAL_POSTGRESQL_TXT_MARKDOWN_REVISIONS_ARCHIVE_RESTORE_AND_DELETE_PASS");
                }
                async Task VerifyDurable()
                {
                    Assert.Equal(SyntheticMemory, (await client.GetMemoryAsync(evidence.Memory, CancellationToken.None)).Content);
                    Assert.Equal(evidence.Revision, (await client.GetMemoryAsync(evidence.Memory, CancellationToken.None)).Revision);
                    var conversation = await client.GetConversationAsync(evidence.Conversation, 0, 10, CancellationToken.None);
                    if (storageOnly)
                    {
                        Assert.Equal(Guid.Empty, evidence.Turn); Assert.Empty(conversation.Turns);
                        Assert.Equal("Synthetic PostgreSQL durable metadata", conversation.Conversation.Title);
                        Assert.Equal(ConversationStatus.ACTIVE, conversation.Conversation.Status);
                    }
                    else
                    {
                        var turn = Assert.Single(conversation.Turns);
                        Assert.Equal(evidence.Turn, turn.Id); Assert.NotEqual(ConversationTurnStatus.PENDING, turn.Status);
                    }
                    var preview = await client.PreviewKnowledgeAsync(evidence.Document, "2", 0, CancellationToken.None);
                    Assert.Equal(syntheticSource, preview.Text);
                    var hit = Assert.Single((await client.SearchKnowledgeAsync("amber lighthouse " + launch.RunId, 10, CancellationToken.None)).Hits);
                    Assert.Equal(evidence.Document, hit.DocumentId); Assert.Equal("2", hit.SourceRevision);
                    Assert.Contains("Amber lighthouse", preview.Text[hit.StartOffset..hit.EndOffset]);
                    Assert.True(hit.StartLine > 0 && hit.EndLine >= hit.StartLine);
                    Assert.True(Directory.GetFiles(Path.Combine(launch.Root, "data", "knowledge", "sources"), "*", SearchOption.AllDirectories).Length >= 2);
                    // The original empty SQLite files are retained, and PostgreSQL never writes them.
                    foreach (var file in sqliteBefore) Assert.Equal(file.Value, SHA256.HashData(await File.ReadAllBytesAsync(file.Key)));
                }
                phase = "DURABLE_AND_CITATIONS";
                await VerifyDurable();
                if (!reopen)
                {
                    phase = "STORE_RESTART";
                    using var prior = Process.GetProcessById(app.DevelopmentRuntime.OwnedProcessId!.Value);
                    await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
                    Assert.True(prior.WaitForExit(10000));
                    await Wait(() => Task.FromResult(app.DevelopmentRuntime.Snapshot().CanQueryStorage));
                    await VerifyDurable(); await Display("PostgreSQL");
                }
                Console.WriteLine(storageOnly
                    ? "REAL_POSTGRESQL_MEMORY_KNOWLEDGE_CONVERSATION_METADATA_AND_CITATION_LOCATORS_" + (reopen ? "REOPEN" : "RESTART") + "_PASS_MESSAGES_PENDING"
                    : reopen ? "REAL_SAME_PROFILE_REOPEN_THREE_STORES_AND_CITATION_LOCATORS_PASS" : "REAL_POSTGRESQL_THREE_STORES_RESTART_PERSISTENCE_AND_CITATION_LOCATORS_PASS");
                using (var image = new FileStream(Path.Combine(launch.Root, "evidence", "postgresql-" + (reopen ? "reopen" : "integrated") + ".png"), FileMode.CreateNew))
                    await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
                passed = true;
            }
            catch (Exception error) { Console.WriteLine("POSTGRESQL_GUI_STOP_" + phase + "_" + (error is DesktopException desktop ? desktop.Error.ToString() : error.GetType().Name)); }
            finally
            {
                try
                {
                    using var owned = app.DevelopmentRuntime?.OwnedProcessId is int pid ? Process.GetProcessById(pid) : null;
                    await app.ExitAsync();
                    if (owned is not null) Assert.True(owned.WaitForExit(10000));
                    Console.WriteLine("REAL_POSTGRESQL_GUI_NORMAL_EXIT_RUNTIME_CLEANUP_PASS");
                }
                catch { passed = false; app.Shutdown(10); }
            }
        };
        app.Run(); Assert.True(passed);
    }

    private static async Task ConfirmNative(Button button, Window owner)
    {
        uint thread = GetCurrentThreadId();
        IntPtr handle = new WindowInteropHelper(owner).Handle;
        var confirmation = Task.Run(async () =>
        {
            var clock = Stopwatch.StartNew(); bool confirmed = false;
            while (!confirmed && clock.Elapsed < TimeSpan.FromSeconds(15))
            {
                EnumThreadWindows(thread, (window, _) =>
                {
                    var name = new StringBuilder(32); GetClassName(window, name, name.Capacity);
                    if (GetWindow(window, 4) != handle || name.ToString() != "#32770") return true;
                    SendMessage(window, 0x0111, 1, 0); confirmed = true; return false;
                }, IntPtr.Zero);
                if (!confirmed) await Task.Delay(100);
            }
            Assert.True(confirmed);
        });
        button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        await confirmation;
    }
    private delegate bool WindowCallback(IntPtr window, IntPtr data);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern bool EnumThreadWindows(uint thread, WindowCallback callback, IntPtr data);
    [DllImport("user32.dll")] private static extern IntPtr GetWindow(IntPtr window, uint command);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr window, StringBuilder name, int count);
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);
}
#endif
