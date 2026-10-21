#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private sealed record ConversationEvidence(Guid Success, Guid Failed, Guid Cancelled, string Answer);

    // Continue the already configured synthetic Profile. Never prepare a database or copy credentials.
    private static void VerifyPostgresqlConversationGui(AssistantApp app, Mmf3AcceptanceLaunch launch, bool reopen, bool recover)
    {
        bool passed = false;
        string phase = "STARTUP";
        app.Startup += async (_, _) =>
        {
            try
            {
                async Task Wait(Func<Task<bool>> predicate, int seconds = 180)
                {
                    var timer = Stopwatch.StartNew();
                    while (!await predicate()) { Assert.True(timer.Elapsed < TimeSpan.FromSeconds(seconds), phase); await Task.Delay(150); }
                }
                await Wait(() => Task.FromResult(app.Workspace?.Host.IsReady is true && app.DevelopmentRuntime!.Snapshot().CanQueryStorage));
                using var client = RuntimeClient.CreateMmf3Acceptance(app.DevelopmentRuntime!.ReadCredential);
                var storage = await client.GetStorageStatusAsync(CancellationToken.None);
                Assert.Equal("POSTGRESQL", storage.Backend); Assert.Equal("V1_V2_READY", storage.WorkspaceSchema);
                Assert.Equal("READY", storage.KnowledgeSchema); Assert.False(storage.FinanceEnabled);
                var prior = JsonSerializer.Deserialize<PostgresqlEvidence>(await File.ReadAllTextAsync(Path.Combine(launch.Root, "evidence", "postgresql-stores.json")))!;
                var memory = await client.GetMemoryAsync(prior.Memory, CancellationToken.None);
                Assert.Equal(SyntheticMemory, memory.Content); Assert.Equal(prior.Revision, memory.Revision);
                var preview = await client.PreviewKnowledgeAsync(prior.Document, "2", 0, CancellationToken.None);
                Assert.Contains("Amber lighthouse", preview.Text);
                string path = Path.Combine(launch.Root, "evidence", "postgresql-conversation.json");
                ConversationEvidence evidence;
                async Task<ConversationTurn> Terminal(Guid conversation)
                {
                    ConversationTurn? turn = null;
                    await Wait(async () => (turn = (await client.GetConversationAsync(conversation, 0, 10, CancellationToken.None)).Turns.Single()).Status != ConversationTurnStatus.PENDING);
                    return turn!;
                }
                if (reopen) evidence = JsonSerializer.Deserialize<ConversationEvidence>(await File.ReadAllTextAsync(path))!;
                else
                {
                    Assert.False(File.Exists(path));
                    if (recover)
                    {
                        // Explicit operator-only continuation after this synthetic run's aborted cold load.
                        // No inferred /api/ps idleness, release, model switch or direct guard-file mutation.
                        Assert.Equal("55be9b5e4c414064bdfa817f5e4d8b8a", launch.RunId);
                        phase = "EXPLICIT_RECOVERY";
                        var catalog = await client.GetModelCatalogAsync(CancellationToken.None);
                        var candidate = Assert.Single(catalog.Models);
                        Assert.Equal("qwen3.5:4b", candidate.Model); Assert.Equal("llamacpp", candidate.Runner);
                        var state = await client.GetModelStatusAsync(CancellationToken.None, recovery: true);
                        Assert.True(state.Uncertain);
                        state = await client.MutateModelAsync(new(ModelAction.RECOVER, candidate.Handle, candidate.Model, candidate.Runner, candidate.Digest,
                            state.SelectionRevision, state.ActiveModel, state.ActiveRunner, state.ActiveDigest, state.RecoveryGeneration, true), CancellationToken.None);
                        Assert.False(state.Uncertain); Assert.True(state.ValidationRequired);
                        state = await client.MutateModelAsync(new(ModelAction.VALIDATE, candidate.Handle, candidate.Model, candidate.Runner, candidate.Digest,
                            state.SelectionRevision, state.ActiveModel, state.ActiveRunner, state.ActiveDigest, null, false), CancellationToken.None);
                        Assert.True(state.Ready); Assert.Equal(catalog.SelectionRevision, state.SelectionRevision);
                        Console.WriteLine("REAL_EXPLICIT_PROFILE_RECOVERY_AND_TEXT_VALIDATION_NO_SELECTION_CHANGE_PASS");
                    }
                    phase = "REAL_INFERENCE";
                    var success = await client.CreateConversationAsync("Synthetic PostgreSQL inference", CancellationToken.None);
                    var accepted = await client.SubmitConversationTurnAsync(success.Id, "Reply with the word amber.", [new(prior.Memory, prior.Revision)], CancellationToken.None);
                    Assert.Equal(1, accepted.MemoryCount);
                    var turn = await Terminal(success.Id);
                    Assert.Equal(accepted.TurnId, turn.Id); Assert.Equal(accepted.TaskId, turn.TaskId);
                    Assert.Equal("Reply with the word amber.", turn.UserMessage.Content);
                    Assert.Equal(ConversationTurnStatus.SUCCEEDED, turn.Status);
                    Assert.NotNull(turn.AssistantMessage); Assert.Contains("amber", turn.AssistantMessage.Content, StringComparison.OrdinalIgnoreCase);
                    var task = await client.GetConversationTaskAsync(accepted.TaskId, CancellationToken.None);
                    Assert.Equal(TaskState.SUCCEEDED, task.Status); Assert.Equal(turn.AssistantMessage.Content, task.Result);
                    Console.WriteLine("REAL_POSTGRESQL_USER_ASSISTANT_TASK_SUCCEEDED_AND_EXPLICIT_MEMORY_PASS");

                    phase = "ADMISSION_ERRORS";
                    await client.ArchiveConversationAsync(success.Id, CancellationToken.None);
                    Assert.Equal(DesktopError.ConversationConflict, (await Assert.ThrowsAsync<DesktopException>(() => client.SubmitConversationTurnAsync(success.Id, "Rejected archived turn", [], CancellationToken.None))).Error);
                    await client.UnarchiveConversationAsync(success.Id, CancellationToken.None);
                    Assert.Equal(1, (await client.GetConversationAsync(success.Id, 0, 10, CancellationToken.None)).TotalTurns);
                    Console.WriteLine("REAL_POSTGRESQL_ARCHIVED_ADMISSION_REJECTED_WITHOUT_USER_MUTATION_PASS");

                    phase = "REAL_FAILURE";
                    var failure = await client.CreateConversationAsync("Synthetic PostgreSQL output boundary", CancellationToken.None);
                    var failedTask = await client.SubmitConversationTurnAsync(failure.Id, "Print the integers from 1 to 10000, each on a separate line. Start immediately at 1 and continue without skipping any number or adding explanations. Do not abbreviate.", [], CancellationToken.None);
                    var failed = await Terminal(failure.Id);
                    Assert.Equal(ConversationTurnStatus.FAILED, failed.Status); Assert.Null(failed.AssistantMessage);
                    Assert.NotNull(failed.FailureCode); Assert.Equal(TaskState.FAILED, (await client.GetConversationTaskAsync(failedTask.TaskId, CancellationToken.None)).Status);
                    Console.WriteLine("REAL_POSTGRESQL_FAILED_TASK_NO_ASSISTANT_AND_FAILURE_CODE_PASS");

                    phase = "REAL_CANCEL";
                    var cancelled = await client.CreateConversationAsync("Synthetic PostgreSQL cancellation", CancellationToken.None);
                    var cancellation = await client.SubmitConversationTurnAsync(cancelled.Id, "Print integers from 1 to 10000, one per line, without abbreviation.", [], CancellationToken.None);
                    Assert.Equal(TaskState.CANCELLED, (await client.CancelConversationTaskAsync(cancellation.TaskId, CancellationToken.None)).Status);
                    var cancelledTurn = await Terminal(cancelled.Id);
                    Assert.Equal(ConversationTurnStatus.CANCELLED, cancelledTurn.Status); Assert.Null(cancelledTurn.AssistantMessage); Assert.Null(cancelledTurn.FailureCode);
                    Console.WriteLine("REAL_POSTGRESQL_CANCELLED_TASK_AND_NO_ASSISTANT_PASS");
                    evidence = new(success.Id, failure.Id, cancelled.Id, turn.AssistantMessage.Content);
                    using var output = new FileStream(path, FileMode.CreateNew);
                    await JsonSerializer.SerializeAsync(output, evidence);
                }
                async Task VerifyDurable()
                {
                    var success = await Terminal(evidence.Success);
                    Assert.Equal(ConversationTurnStatus.SUCCEEDED, success.Status); Assert.Equal(evidence.Answer, success.AssistantMessage!.Content);
                    var selection = Assert.Single(success.Memories!); Assert.Equal(prior.Memory, selection.MemoryId); Assert.Equal(prior.Revision, selection.Revision);
                    var failure = await Terminal(evidence.Failed); Assert.Equal(ConversationTurnStatus.FAILED, failure.Status); Assert.Null(failure.AssistantMessage); Assert.NotNull(failure.FailureCode);
                    var cancelled = await Terminal(evidence.Cancelled); Assert.Equal(ConversationTurnStatus.CANCELLED, cancelled.Status); Assert.Null(cancelled.AssistantMessage);
                    Assert.Equal(SyntheticMemory, (await client.GetMemoryAsync(prior.Memory, CancellationToken.None)).Content);
                    Assert.Equal(preview.Text, (await client.PreviewKnowledgeAsync(prior.Document, "2", 0, CancellationToken.None)).Text);
                    Assert.Empty((await client.GetConversationAsync(prior.Conversation, 0, 10, CancellationToken.None)).Turns);
                }
                await VerifyDurable();
                if (!reopen)
                {
                    phase = "RESTART";
                    string? oldToken = app.DevelopmentRuntime.ReadCredential(); Assert.NotNull(oldToken);
                    using var stale = RuntimeClient.CreateMmf3Acceptance(() => oldToken);
                    using var process = Process.GetProcessById(app.DevelopmentRuntime.OwnedProcessId!.Value);
                    await app.DevelopmentRuntime.RestartAsync(CancellationToken.None);
                    Assert.True(process.WaitForExit(10000));
                    await Wait(() => Task.FromResult(app.DevelopmentRuntime.Snapshot().CanQueryStorage));
                    Assert.Equal(DesktopError.Unauthorized, (await Assert.ThrowsAsync<DesktopException>(() => stale.CheckCredentialAsync(CancellationToken.None))).Error);
                    await VerifyDurable();
                    Console.WriteLine("REAL_POSTGRESQL_CONVERSATION_THREE_TERMINALS_RESTART_AND_OLD_BEARER_401_PASS");
                }
                var core = app.Workspace!.Browser.CoreWebView2;
                await core.ExecuteScriptAsync("location.hash='#/settings'");
                const string recheck = "[...document.querySelectorAll('button')].find(b=>b.textContent==='重新检查存储'&&!b.disabled)";
                await Wait(async () => await core.ExecuteScriptAsync("Boolean(" + recheck + ")") == "true");
                await core.ExecuteScriptAsync(recheck + ".click()");
                await Wait(async () => await core.ExecuteScriptAsync("[...document.querySelectorAll('dt')].find(x=>x.textContent==='当前持久化引擎')?.nextElementSibling?.textContent==='PostgreSQL'") == "true");
                using (var image = new FileStream(Path.Combine(launch.Root, "evidence", "postgresql-conversation-" + (reopen ? "reopen" : "integrated") + ".png"), FileMode.CreateNew))
                    await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
                Console.WriteLine("REAL_POSTGRESQL_CONVERSATION_" + (reopen ? "REOPEN" : "INTEGRATED") + "_PASS");
                passed = true;
            }
            catch (Exception error) { Console.WriteLine("POSTGRESQL_CONVERSATION_STOP_" + phase + "_" + (error is DesktopException desktop ? desktop.Error.ToString() : error.GetType().Name)); }
            finally
            {
                using var owned = app.DevelopmentRuntime?.OwnedProcessId is int pid ? Process.GetProcessById(pid) : null;
                await app.ExitAsync();
                if (owned is not null) Assert.True(owned.WaitForExit(10000));
                Console.WriteLine("REAL_POSTGRESQL_CONVERSATION_NORMAL_EXIT_RUNTIME_CLEANUP_PASS");
            }
        };
        app.Run(); Assert.True(passed);
    }
}
#endif
