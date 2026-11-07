#if MMF3_PROFILE_ACCEPTANCE && DEBUG && RUNTIME_LIFECYCLE_PROBE
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private sealed record MemoryWorkflowEvidence(Guid Memory, long Revision, Guid Conversation, string Candidate,
        string Quote, string[] Answers);
    private static void VerifyMemoryGui(AssistantApp app, Mmf3AcceptanceLaunch launch, bool reopen)
    {
        const string source = "Project Amber uses teal for status indicators. This is the current project decision.";
        bool passed = false;
        string stage = "STARTUP";
        Process? owned = null;
        string evidencePath = Path.Combine(launch.Root, "evidence", "memory-full-workflow.json");
        app.Startup += async (_, _) =>
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(5));
            var ct = timeout.Token;
            async Task Wait(Func<Task<bool>> condition)
            {
                var clock = Stopwatch.StartNew();
                while (!await condition()) { Assert.True(clock.Elapsed < TimeSpan.FromSeconds(90), stage); await Task.Delay(100, ct); }
            }
            try
            {
                await Wait(() => {
                    var startup = app.DevelopmentRuntime!.Snapshot();
                    Assert.NotEqual("UNAVAILABLE", startup.Runtime);
                    return Task.FromResult(app.Workspace?.Host.IsReady is true && startup.CanQueryStorage);
                });
                owned = Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value);
                using var client = RuntimeClient.CreateMmf3Acceptance(app.DevelopmentRuntime.ReadCredential);
                var shell = app.Workspace!;
                var core = shell.Browser.CoreWebView2;
                Task<string> Script(string js) => core.ExecuteScriptAsync(js);
                Task<bool> Is(string js) => IsTrue(js);
                async Task<bool> IsTrue(string js) => await Script(js) == "true";
                string Button(string label) => "[...document.querySelectorAll('button')].find(b=>b.getClientRects().length>0&&!b.disabled&&b.textContent===" + JsonSerializer.Serialize(label) + ")";
                async Task Click(string label) { await Wait(() => Is("Boolean(" + Button(label) + ")")); await Script(Button(label) + ".click()"); }
                async Task Input(string id, string value)
                {
                    await Wait(() => Is("Boolean(document.getElementById(" + JsonSerializer.Serialize(id) + "))"));
                    await Script("(()=>{const e=document.getElementById(" + JsonSerializer.Serialize(id) + ");Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value').set.call(e," + JsonSerializer.Serialize(value) + ");e.dispatchEvent(new Event('input',{bubbles:true}));})()");
                }
                async Task<string> Value(string expression) => JsonSerializer.Deserialize<string>(await Script(expression))!;
                MemoryWorkflowEvidence evidence;
                if (reopen)
                {
                    stage = "REOPEN_PERSISTED_STATE";
                    evidence = JsonSerializer.Deserialize<MemoryWorkflowEvidence>(File.ReadAllText(evidencePath))!;
                    Assert.Equal(evidence.Revision, (await client.GetMemoryAsync(evidence.Memory, ct)).Revision);
                    var detail = await client.GetConversationAsync(evidence.Conversation, 0, 10, ct);
                    Assert.Equal(4, detail.TotalTurns);
                    Assert.Equal(evidence.Answers, detail.Turns.Where(t => t.Sequence >= 2).Select(t => t.AssistantMessage!.Content).ToArray());
                    Assert.Empty(detail.Turns.Single(t => t.Sequence == 4).Memories!);
                    Assert.All(detail.Turns.Where(t => t.Sequence is 2 or 3), t => Assert.Equal(1, Assert.Single(t.Memories!).Revision));
                    await Script("location.hash='#/memory'");
                    await Wait(() => Is("document.querySelectorAll('.memory-select').length===1"));
                    await Script("document.querySelector('.memory-select').click()");
                    await Wait(() => Is("document.querySelector('#memory-content')?.value===" + JsonSerializer.Serialize(evidence.Candidate)));
                    await Click("归档");
                    await Wait(async () => (await client.GetMemoryAsync(evidence.Memory, ct)).Status == MemoryStatus.ARCHIVED);
                    Assert.Equal(0, (await client.RecallMemoryAsync("Amber teal", null, ct)).Total);
                    await Click("恢复");
                    await Wait(async () => (await client.GetMemoryAsync(evidence.Memory, ct)).Status == MemoryStatus.ACTIVE);
                    Assert.Equal(1, (await client.RecallMemoryAsync("Amber teal", null, ct)).Total);
                    await Click("删除"); await Click("永久删除");
                    await Wait(async () => (await client.ListMemoryAsync(new(), ct)).Total == 0);
                    Assert.Equal(4, (await client.GetConversationAsync(evidence.Conversation, 0, 10, ct)).TotalTurns);
                    Console.WriteLine("MEMORY_FULL_APP_RESTART_PERSISTENCE_ARCHIVE_RESTORE_DELETE_PASS");
                }
                else
                {
                    Assert.False(File.Exists(evidencePath));
                    Assert.Equal(0, (await client.ListMemoryAsync(new(), ct)).Total);
                    stage = "OWNED_MODEL_ADMISSION";
                    var catalog = await client.GetModelCatalogAsync(ct);
                    var model = Assert.Single(catalog.Models, x => x.Model == "qwen3.5:4b" && x.Runner == "llamacpp");
                    Assert.Equal("96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7", model.Digest);
                    var state = await client.GetModelStatusAsync(ct);
                    Assert.False(state.Uncertain);
                    if (state.ActiveDigest != model.Digest || state.ActiveRunner != model.Runner)
                        state = await client.MutateModelAsync(new(ModelAction.SWITCH, model.Handle, model.Model, model.Runner, model.Digest,
                            state.SelectionRevision, state.ActiveModel, state.ActiveRunner, state.ActiveDigest, null, false), ct);
                    if (!state.Ready)
                    {
                        catalog = await client.GetModelCatalogAsync(ct);
                        model = Assert.Single(catalog.Models, x => x.Digest == model.Digest && x.Runner == "llamacpp");
                        state = await client.MutateModelAsync(new(ModelAction.VALIDATE, model.Handle, model.Model, model.Runner, model.Digest,
                            state.SelectionRevision, state.ActiveModel, state.ActiveRunner, state.ActiveDigest, null, false), ct);
                    }
                    Assert.True(state.Ready);
                    Console.WriteLine("MEMORY_FULL_APP_OWNED_MODEL_READY_PASS");
                    stage = "SOURCE_AND_STRUCTURED_CANDIDATE";
                    await Script("location.hash='#/conversations'"); await Click("新建对话");
                    await Input("conversation-input", source); await Click("发送");
                    await Wait(() => Is("document.body.innerText.includes('第 1 轮 · 已完成')"));
                    var conversation = Assert.Single((await client.ListConversationsAsync(ConversationStatus.ACTIVE, 0, 10, ct)).Items);
                    await Click("保存为记忆草稿"); await Click("生成记忆候选");
                    await Wait(() => Is("Boolean(document.querySelector('#candidate-content'))"));
                    string content = await Value("document.querySelector('#candidate-content').value");
                    string quote = await Value("document.querySelector('.memory-candidate blockquote').textContent");
                    Assert.Equal("PROJECT_NOTE", await Value("document.querySelector('#candidate-type').value"));
                    Assert.Contains("Amber", content); Assert.Contains("teal", content); Assert.Contains(quote, source);
                    Assert.Equal(0, (await client.ListMemoryAsync(new(), ct)).Total);
                    await Click("采用候选到编辑器"); Assert.Equal(0, (await client.ListMemoryAsync(new(), ct)).Total);
                    await Click("保存"); await Wait(async () => (await client.ListMemoryAsync(new(), ct)).Total == 1);
                    var saved = Assert.Single((await client.ListMemoryAsync(new(), ct)).Items);
                    Assert.Equal(content, (await client.GetMemoryAsync(saved.Id, ct)).Content);
                    Assert.Equal(1, saved.Revision);
                    Console.WriteLine("MEMORY_FULL_APP_REAL_CANDIDATE_REVIEW_EXPLICIT_SAVE_PASS");
                    stage = "RECALL_AND_RETAINED_TURNS";
                    await Script("location.hash='#/conversations'");
                    await Wait(() => Is("Boolean(" + Button("选择记忆…") + ")"));
                    var opening = Script(Button("选择记忆…") + ".click()");
                    await Wait(() => Task.FromResult(app.Windows.OfType<MemorySelectionWindow>().Any()));
                    var picker = app.Windows.OfType<MemorySelectionWindow>().Single();
                    await Wait(() => Task.FromResult(picker.RecallButton.IsEnabled));
                    picker.SearchBox.Text = "Amber teal";
                    picker.RecallButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
                    await Wait(() => Task.FromResult(picker.RecallButton.IsEnabled && picker.PageText.Text.StartsWith("相关 ")));
                    Assert.Single(picker.MemoryList.Items); Assert.Empty(picker.SelectedList.Items); Assert.False(picker.UseButton.IsEnabled);
                    picker.MemoryList.SelectedIndex = 0;
                    await Wait(() => Task.FromResult(picker.AddButton.IsEnabled)); Assert.Equal(content, picker.PreviewContent.Text);
                    picker.AddButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent)); picker.UseButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent));
                    await opening.WaitAsync(ct);
                    await Wait(() => Is("document.body.innerText.includes('本次使用 1 条')"));
                    await Script("document.querySelector('.conversation-memory-retention input').click()");
                    var answers = new List<string>();
                    for (int turn = 2; turn <= 3; turn++)
                    {
                        await Input("conversation-input", "What colour does Project Amber use for status indicators? Answer briefly."); await Click("发送");
                        await Wait(() => Is("document.body.innerText.includes('第 " + turn + " 轮 · 已完成')"));
                        var latest = (await client.GetConversationAsync(conversation.Id, 0, 10, ct)).Turns.Single(t => t.Sequence == turn);
                        Assert.Equal(new ConversationSelection(saved.Id, 1, 0), Assert.Single(latest.Memories!));
                        Assert.Contains("teal", latest.AssistantMessage!.Content, StringComparison.OrdinalIgnoreCase);
                        answers.Add(latest.AssistantMessage.Content);
                    }
                    stage = "STALE_AND_CLEAR";
                    var updated = await client.UpdateMemoryAsync(saved.Id, new(saved.Revision, saved.Type, saved.Title + " reviewed", content), ct);
                    await Input("conversation-input", "This stale attempt must not create a turn."); await Click("发送");
                    await Wait(() => Is("document.body.innerText.includes('记忆已变更，请重新选择或清除后再发送。')"));
                    Assert.Equal(3, (await client.GetConversationAsync(conversation.Id, 0, 10, ct)).TotalTurns);
                    await Click("清除记忆");
                    await Input("conversation-input", "Explicit memory was cleared. Can prior conversation history still contain the colour? Answer briefly."); await Click("发送");
                    await Wait(() => Is("document.body.innerText.includes('第 4 轮 · 已完成')"));
                    var cleared = (await client.GetConversationAsync(conversation.Id, 0, 10, ct)).Turns.Single(t => t.Sequence == 4);
                    Assert.Empty(cleared.Memories!); Assert.StartsWith("Yes", cleared.AssistantMessage!.Content, StringComparison.OrdinalIgnoreCase);
                    answers.Add(cleared.AssistantMessage.Content);
                    evidence = new(saved.Id, updated.Revision, conversation.Id, content, quote, answers.ToArray());
                    File.WriteAllText(evidencePath, JsonSerializer.Serialize(evidence));
                    Console.WriteLine("MEMORY_FULL_APP_REAL_RECALL_RETAIN_STALE_CLEAR_PASS");
                }
                using (var screenshot = new FileStream(Path.Combine(launch.Root, "evidence", reopen ? "memory-reopened.png" : "memory-conversation.png"), FileMode.CreateNew))
                    await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, screenshot);
                passed = true;
            }
            catch (Exception error)
            {
                File.WriteAllText(Path.Combine(launch.Root, "evidence", "memory-full-failure.json"), JsonSerializer.Serialize(new {
                    stage, type = error.GetType().Name, desktopError = (error as DesktopException)?.Error.ToString(), runtime = app.DevelopmentRuntime?.Snapshot(),
                    ownedProcessId = app.DevelopmentRuntime?.OwnedProcessId,
                    frames = new StackTrace(error, true).GetFrames().Select(f => new { method = f.GetMethod()?.Name, line = f.GetFileLineNumber() }) }));
                Console.WriteLine("MEMORY_FULL_APP_STOP " + stage + " " + error.GetType().Name);
            }
            finally
            {
                try { await app.ExitAsync(); if (owned is not null) Assert.True(owned.WaitForExit(10000)); }
                catch { passed = false; app.Shutdown(10); }
                finally { owned?.Dispose(); }
            }
        };
        app.Run(); Assert.True(passed);
    }
}
#endif
