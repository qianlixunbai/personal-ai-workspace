#if MMF3_PROFILE_ACCEPTANCE && DEBUG && RUNTIME_LIFECYCLE_PROBE
using System.Diagnostics;
using System.IO;
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
    private sealed record WebSourceEvidence(string Url, KnowledgeDocument Document, KnowledgeRevision Revision,
        string TextSha256, KnowledgeSearchHit Reference, string ReferenceText);
    private sealed record WebRestartEvidence(int AppPid, int JavaPid, DateTime JavaStartedUtc, WebSourceEvidence[] Sources);
    private sealed record WebAnswerEvidence(string Question, string Query, KnowledgeAnswerTask Task, string[] CitationTexts);
    private sealed record WebAnswersEvidence(int AppPid, int JavaPid, WebAnswerEvidence[] Answers);
    private static readonly string[] WebKnowledgeSources = ["https://example.com/", "https://www.rfc-editor.org/rfc/rfc2606.html"];
    private static readonly JsonSerializerOptions WebKnowledgeJson = new() { WriteIndented = true };

    // Explicit manual modes only. The actual AssistantApp owns Runtime, WebView, bridge and credentials.
    // Fixture evidence is an expected-value oracle only; history is discovered through the product API/UI.
    private static void VerifyWebKnowledgeGui(AssistantApp app, Mmf3AcceptanceLaunch launch, string mode)
    {
        bool capture = mode == "gui-web-knowledge", citationsOnly = mode == "gui-web-knowledge-citations", passed = false;
        string stage = "STARTUP";
        Process? owned = null;
        var evidenceRoot = Path.Combine(launch.Root, "evidence");
        string sourcePath = Path.Combine(evidenceRoot, "web-knowledge-sources.json");
        string answerPath = Path.Combine(evidenceRoot, "web-knowledge-answers.json");
        app.Startup += async (_, _) =>
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(5));
            var ct = timeout.Token;
            async Task Wait(Func<Task<bool>> condition, int seconds = 60)
            {
                var clock = Stopwatch.StartNew();
                while (!await condition()) { Assert.True(clock.Elapsed < TimeSpan.FromSeconds(seconds), stage); await Task.Delay(100, ct); }
            }
            try
            {
                await Wait(() => {
                    var state = app.DevelopmentRuntime!.Snapshot(); Assert.NotEqual("UNAVAILABLE", state.Runtime);
                    return Task.FromResult(app.Workspace?.Host.IsReady is true && state.CanQueryStorage);
                });
                owned = Process.GetProcessById(app.DevelopmentRuntime!.OwnedProcessId!.Value);
                File.WriteAllText(Path.Combine(evidenceRoot, "web-knowledge-process-" + mode + ".json"), JsonSerializer.Serialize(new {
                    appPid = Environment.ProcessId, javaPid = owned.Id, javaStartedUtc = owned.StartTime.ToUniversalTime(), mode }, WebKnowledgeJson));
                using var client = RuntimeClient.CreateMmf3Acceptance(app.DevelopmentRuntime.ReadCredential);
                var shell = app.Workspace!; var core = shell.Browser.CoreWebView2;
                int fetchSubmissions = 0, saves = 0, answerSubmissions = 0, historyReads = 0;
                var observedAnswerIds = new HashSet<Guid>();
                core.WebMessageReceived += (_, message) => {
                    using var item = JsonDocument.Parse(message.WebMessageAsJson);
                    if (!item.RootElement.TryGetProperty("method", out var method)) return;
                    string? name = method.GetString();
                    if (name == "web.fetchSubmit") fetchSubmissions++;
                    if (name == "web.saveKnowledge") saves++;
                    if (name == "knowledge.answerSubmit") answerSubmissions++;
                    if (name == "knowledge.answerHistoryGet") historyReads++;
                    if (name == "knowledge.answerGet") observedAnswerIds.Add(Guid.Parse(item.RootElement.GetProperty("payload").GetProperty("taskId").GetString()!));
                };
                Task<string> Script(string js) => core.ExecuteScriptAsync(js);
                async Task<bool> Is(string js) => await Script(js) == "true";
                string Json(string value) => JsonSerializer.Serialize(value);
                string Button(string label) => "[...document.querySelectorAll('button')].find(b=>b.getClientRects().length>0&&!b.disabled&&b.textContent===" + Json(label) + ")";
                async Task Click(string label) { await Wait(() => Is("Boolean(" + Button(label) + ")")); await Script(Button(label) + ".click()"); }
                async Task Input(string id, string value)
                {
                    await Wait(() => Is("Boolean(document.getElementById(" + Json(id) + "))"));
                    await Script("(()=>{const e=document.getElementById(" + Json(id) + ");Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value').set.call(e," + Json(value) + ");e.dispatchEvent(new Event('input',{bubbles:true}));})()");
                }
                async Task<string> ReadText(string id, string revision)
                {
                    var result = new StringBuilder(); int offset = 0;
                    for (int page = 0; page < 8; page++) {
                        var preview = await client.PreviewKnowledgeAsync(id, revision, offset, ct); result.Append(preview.Text);
                        if (preview.NextOffset is null) return result.ToString(); offset = preview.NextOffset.Value;
                    }
                    throw new InvalidOperationException("Unexpected unbounded source");
                }
                static string Hash(string text) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
                async Task Snapshot(string name)
                { using var image = new FileStream(Path.Combine(evidenceRoot, name), FileMode.CreateNew); await core.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image); }
                await Script("location.hash='#/knowledge'");
                await Wait(() => Is("Boolean(document.querySelector('.knowledge-web-capture'))"));
                WebRestartEvidence evidence;
                if (capture)
                {
                    Assert.False(File.Exists(sourcePath)); Assert.Equal(0, (await client.ListKnowledgeAsync("ACTIVE", 0, ct)).Total);
                    await Script("document.querySelector('.knowledge-web-capture').closest('details').querySelector('summary').click()");
                    var sources = new List<WebSourceEvidence>();
                    for (int i = 0; i < WebKnowledgeSources.Length; i++)
                    {
                        stage = "PUBLIC_FETCH_" + i;
                        string url = WebKnowledgeSources[i]; await Input("knowledge-web-url", url);
                        await Wait(() => Is("Boolean(" + Button("获取并预览网页") + ")"));
                        var click = Script(Button("获取并预览网页") + ".click()");
                        await Wait(() => Task.FromResult(app.Windows.OfType<WebFetchConfirmationWindow>().Any(w => w.Owner == shell && w.IsVisible)));
                        var dialog = app.Windows.OfType<WebFetchConfirmationWindow>().Single(w => w.Owner == shell && w.IsVisible);
                        var panel = Assert.IsType<StackPanel>(dialog.Content);
                        Assert.Equal([new Uri(url).Host, url], panel.Children.OfType<TextBox>().Select(t => t.Text).ToArray());
                        var allow = panel.Children.OfType<StackPanel>().Single().Children.OfType<Button>().Single(b => b.Content.ToString() == "Allow once / 允许本次访问");
                        allow.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Button.ClickEvent)); await click.WaitAsync(ct);
                        await Wait(() => Is("Boolean(document.querySelector('.knowledge-web-capture pre'))||Boolean(document.querySelector('.knowledge-web-capture [role=alert]'))"), 35);
                        Assert.True(await Is("Boolean(document.querySelector('.knowledge-web-capture pre'))"));
                        Assert.Equal(i, (await client.ListKnowledgeAsync("ACTIVE", 0, ct)).Total);
                        stage = "SAVE_" + i; await Click("保存网页摘录…"); await Click("确认保存摘录");
                        await Wait(() => Is("Boolean(" + Button("打开已保存资料") + ")"));
                        var list = await client.ListKnowledgeAsync("ACTIVE", 0, ct); Assert.Equal(i + 1, list.Total);
                        var doc = Assert.Single(list.Items, d => sources.All(s => s.Document.DocumentId != d.DocumentId));
                        var detail = await client.GetKnowledgeAsync(doc.DocumentId, ct); var revision = Assert.Single(detail.Revisions);
                        string text = await ReadText(doc.DocumentId, revision.SourceRevision);
                        Assert.Contains("请求网址：" + url, text); Assert.Contains(i == 0 ? "正文已截断：否" : "正文已截断：是，内容不完整", text);
                        await Wait(async () => (await client.KnowledgeSearchStatusAsync(ct)).State == "READY");
                        var hit = (await client.SearchKnowledgeAsync(i == 0 ? "permission" : "invalid", 10, ct)).Hits.First(h => h.DocumentId == doc.DocumentId);
                        sources.Add(new(url, detail.Document, revision, Hash(text), hit, text[hit.StartOffset..hit.EndOffset]));
                    }
                    evidence = new(Environment.ProcessId, owned.Id, owned.StartTime.ToUniversalTime(), sources.ToArray());
                    File.WriteAllText(sourcePath, JsonSerializer.Serialize(evidence, WebKnowledgeJson));
                    Assert.Equal(2, fetchSubmissions); Assert.Equal(2, saves); Assert.Equal(0, answerSubmissions);
                    Console.WriteLine("WEB_KNOWLEDGE_FULL_APP_CAPTURE_TWO_PUBLIC_SOURCES_PASS");
                }
                else
                {
                    stage = "RESTART_SOURCE_RECOVERY";
                    evidence = JsonSerializer.Deserialize<WebRestartEvidence>(File.ReadAllText(sourcePath))!;
                    Assert.NotEqual(evidence.AppPid, Environment.ProcessId);
                    Assert.False(evidence.JavaPid == owned.Id && evidence.JavaStartedUtc == owned.StartTime.ToUniversalTime());
                    Assert.Equal(2, (await client.ListKnowledgeAsync("ACTIVE", 0, ct)).Total);
                    foreach (var source in evidence.Sources)
                    {
                        var detail = await client.GetKnowledgeAsync(source.Document.DocumentId, ct);
                        Assert.Equal(source.Document, detail.Document); Assert.Equal(source.Revision, Assert.Single(detail.Revisions));
                        string text = await ReadText(source.Document.DocumentId, source.Revision.SourceRevision);
                        Assert.Equal(source.TextSha256, Hash(text)); Assert.Equal(source.ReferenceText, text[source.Reference.StartOffset..source.Reference.EndOffset]);
                        var direct = await client.PreviewKnowledgeAsync(source.Reference.DocumentId, source.Reference.SourceRevision, source.Reference.StartOffset, ct);
                        Assert.StartsWith(source.ReferenceText, direct.Text, StringComparison.Ordinal);
                    }
                    await Wait(async () => (await client.KnowledgeSearchStatusAsync(ct)).State == "READY");
                    await Wait(() => Is("document.querySelectorAll('.knowledge-list .memory-select').length===2"));
                    await Input("knowledge-search-query", "permission"); await Click("检索");
                    await Wait(() => Is("document.querySelectorAll('.knowledge-search li').length>0")); await Click("打开此位置");
                    await Wait(() => Is("Boolean(document.querySelector('[aria-label=纯文本预览] mark'))"));
                    await Script("document.querySelector('[aria-label=纯文本预览]').scrollIntoView({block:'center'})");
                    await Snapshot(citationsOnly ? "web-knowledge-third-start-source.png" : "web-knowledge-restarted-source.png");
                    Assert.Equal(0, fetchSubmissions); Assert.Equal(0, saves);
                    Console.WriteLine("WEB_KNOWLEDGE_FULL_PROCESS_RESTART_SOURCE_HASH_REVISION_RANGE_PASS");
                    if (citationsOnly)
                    {
                        stage = "PERSISTED_ANSWER_HISTORY_WITHOUT_MODEL";
                        var previous = JsonSerializer.Deserialize<WebAnswersEvidence>(File.ReadAllText(answerPath))!;
                        Assert.NotEqual(previous.AppPid, Environment.ProcessId); Assert.NotEqual(previous.JavaPid, owned.Id);
                        Assert.False((await client.GetModelStatusAsync(ct)).Ready);
                        var history = await client.ListKnowledgeAnswersAsync(0, ct);
                        Assert.Equal(previous.Answers.Length, history.Total);
                        Assert.Equal(previous.Answers.Select(a => a.Task.TaskId.ToString()).Order(), history.Items.Select(a => a.TaskId).Order());
                        await Script("document.querySelector('.knowledge-answer').closest('details').querySelector('summary').click()");
                        await Click("查看问答历史");
                        await Wait(() => Is("document.querySelectorAll('[aria-label=问答历史] li').length===2"));
                        foreach (var answer in previous.Answers)
                        {
                            var restored = await client.GetKnowledgeAnswerHistoryAsync(answer.Task.TaskId, ct);
                            Assert.Equal(answer.Question, restored.Question); Assert.Equal(answer.Query, restored.Query);
                            Assert.Equal(JsonSerializer.Serialize(answer.Task), JsonSerializer.Serialize(restored.Task));
                            Assert.Equal(JsonSerializer.Serialize(answer.Task), JsonSerializer.Serialize(await client.GetKnowledgeAnswerAsync(answer.Task.TaskId, ct)));
                            await Click(history.Items.Single(h => h.TaskId == answer.Task.TaskId.ToString()).Question);
                            await Wait(() => Is("document.getElementById('knowledge-answer-question').value===" + Json(answer.Question) + "&&document.querySelector('.knowledge-answer .knowledge-snippet')?.textContent===" + Json(answer.Task.Result!.Answer)));
                            Assert.True(await Is("document.getElementById('knowledge-answer-query').value===" + Json(answer.Query)));
                            for (int i = 0; i < answer.Task.Result!.Citations.Count; i++)
                            {
                                var c = answer.Task.Result.Citations[i]; string text = await ReadText(c.DocumentId, c.SourceRevision);
                                Assert.Equal(answer.CitationTexts[i], text[c.StartOffset..c.EndOffset]);
                                var preview = await client.PreviewKnowledgeAsync(c.DocumentId, c.SourceRevision, c.StartOffset, ct);
                                Assert.StartsWith(answer.CitationTexts[i], preview.Text, StringComparison.Ordinal);
                            }
                            await Click("打开引用来源");
                            await Wait(() => Is("document.querySelector('#knowledge-source-range')?.textContent.includes('引用范围')===true"));
                            await Script("document.querySelector('.knowledge-answer .knowledge-snippet').scrollIntoView({block:'start'})");
                            await Snapshot("web-knowledge-history-" + answer.Query + ".png");
                        }
                        Assert.Equal(2, historyReads); Assert.Equal(0, answerSubmissions);
                        Console.WriteLine("WEB_KNOWLEDGE_FULL_PROCESS_HISTORY_UI_EXACT_ANSWERS_CITATIONS_MODEL_OFF_PASS");
                        stage = "DELETE_HISTORY_PRESERVE_SOURCES";
                        string deletedQuestion = history.Items.Single(h => h.TaskId == previous.Answers[1].Task.TaskId.ToString()).Question;
                        await Script("[...document.querySelectorAll('[aria-label=问答历史] li')].find(li=>li.querySelector('button')?.textContent===" + Json(deletedQuestion) + ").querySelectorAll('button')[1].click()");
                        await Click("确认删除问答");
                        await Wait(() => Is("document.querySelectorAll('[aria-label=问答历史] li').length===1&&document.getElementById('knowledge-answer-question').value===''"));
                        Assert.Equal(1, (await client.ListKnowledgeAnswersAsync(0, ct)).Total);
                        Assert.Equal(2, (await client.ListKnowledgeAsync("ACTIVE", 0, ct)).Total);
                        var gone = await Assert.ThrowsAsync<DesktopException>(() => client.GetKnowledgeAnswerHistoryAsync(previous.Answers[1].Task.TaskId, ct));
                        Assert.Equal(DesktopError.TaskNotFound, gone.Error);
                        Console.WriteLine("WEB_KNOWLEDGE_HISTORY_DELETE_UI_SOURCE_RETENTION_PASS");
                    }
                    else
                    {
                        stage = "ISOLATED_MODEL_ADMISSION";
                        var catalog = await client.GetModelCatalogAsync(ct);
                        var model = Assert.Single(catalog.Models, m => m.Model == "qwen3.5:4b" && m.Runner == "llamacpp");
                        Assert.Equal("96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7", model.Digest);
                        var state = await client.GetModelStatusAsync(ct); Assert.False(state.Uncertain);
                        if (state.ActiveDigest != model.Digest || state.ActiveRunner != model.Runner)
                            state = await client.MutateModelAsync(new(ModelAction.SWITCH, model.Handle, model.Model, model.Runner, model.Digest,
                                state.SelectionRevision, state.ActiveModel, state.ActiveRunner, state.ActiveDigest, null, false), ct);
                        if (!state.Ready) {
                            catalog = await client.GetModelCatalogAsync(ct); model = Assert.Single(catalog.Models, m => m.Digest == model.Digest && m.Runner == "llamacpp");
                            state = await client.MutateModelAsync(new(ModelAction.VALIDATE, model.Handle, model.Model, model.Runner, model.Digest,
                                state.SelectionRevision, state.ActiveModel, state.ActiveRunner, state.ActiveDigest, null, false), ct);
                        }
                        Assert.True(state.Ready); Console.WriteLine("WEB_KNOWLEDGE_OWNED_REAL_MODEL_READY_PASS");
                        await Script("document.querySelector('.knowledge-answer').closest('details').querySelector('summary').click()");
                        var answers = new List<WebAnswerEvidence>();
                        string[] questions = ["According to the Example Domain page, what may this domain be used for? Answer briefly in English.",
                            "According to RFC 2606, which top-level domain is intended for names that are certain to be invalid? Answer briefly in English."];
                        string[] queries = ["permission", "invalid"];
                        for (int i = 0; i < questions.Length; i++)
                        {
                            stage = "REAL_MODEL_ANSWER_" + i; observedAnswerIds.Clear();
                            await Input("knowledge-answer-question", questions[i]); await Input("knowledge-answer-query", queries[i]); await Click("基于知识回答");
                            await Wait(() => Is("document.querySelector('.knowledge-answer [role=status]')?.textContent==='回答完成'||Boolean(document.querySelector('.knowledge-answer [role=alert]'))"), 90);
                            Assert.True(await Is("document.querySelector('.knowledge-answer [role=status]').textContent==='回答完成'"));
                            var taskId = Assert.Single(observedAnswerIds); var task = await client.GetKnowledgeAnswerAsync(taskId, ct);
                            Assert.Equal(TaskState.SUCCEEDED, task.Status); Assert.NotNull(task.Result);
                            var citations = task.Result.Citations; Assert.NotEmpty(citations);
                            var texts = new List<string>();
                            foreach (var c in citations) {
                                Assert.Equal(evidence.Sources[i].Document.DocumentId, c.DocumentId); Assert.Equal("1", c.SourceRevision);
                                string full = await ReadText(c.DocumentId, c.SourceRevision); texts.Add(full[c.StartOffset..c.EndOffset]);
                            }
                            var answer = new WebAnswerEvidence(questions[i], queries[i], task, texts.ToArray()); answers.Add(answer);
                            // Keep actual output before semantic assertions, including any failed result.
                            File.WriteAllText(Path.Combine(evidenceRoot, "web-knowledge-answer-" + i + ".json"), JsonSerializer.Serialize(answer, WebKnowledgeJson));
                            if (i == 0) Assert.Contains("documentation", task.Result.Answer, StringComparison.OrdinalIgnoreCase);
                            else Assert.Contains(".invalid", task.Result.Answer, StringComparison.OrdinalIgnoreCase);
                            await Click("打开引用来源"); await Wait(() => Is("document.querySelector('#knowledge-source-range')?.textContent.includes('引用范围')===true"));
                            await Script("document.querySelector('.knowledge-answer .knowledge-snippet').scrollIntoView({block:'start'})");
                            await Snapshot("web-knowledge-real-answer-" + i + ".png");
                        }
                        File.WriteAllText(answerPath, JsonSerializer.Serialize(new WebAnswersEvidence(Environment.ProcessId, owned.Id, answers.ToArray()), WebKnowledgeJson));
                        Assert.Equal(2, answerSubmissions); Assert.Equal(0, fetchSubmissions); Assert.Equal(0, saves);
                        Console.WriteLine("WEB_KNOWLEDGE_REAL_MODEL_TWO_PUBLIC_ANSWERS_AND_EXACT_CITATIONS_PASS");
                    }
                }
                File.WriteAllText(Path.Combine(evidenceRoot, "web-knowledge-result-" + mode + ".json"), JsonSerializer.Serialize(new {
                    passed = true, mode, fetchSubmissions, saves, answerSubmissions, historyReads, appPid = Environment.ProcessId, javaPid = owned.Id }, WebKnowledgeJson));
                passed = true;
            }
            catch (Exception error)
            {
                File.WriteAllText(Path.Combine(evidenceRoot, "web-knowledge-failure-" + mode + ".json"), JsonSerializer.Serialize(new {
                    stage, type = error.GetType().Name, desktopError = (error as DesktopException)?.Error.ToString(), runtime = app.DevelopmentRuntime?.Snapshot(),
                    frames = new StackTrace(error, true).GetFrames().Select(f => new { method = f.GetMethod()?.Name, line = f.GetFileLineNumber() }) }, WebKnowledgeJson));
                if (app.Workspace?.Host.IsReady is true) {
                    File.WriteAllText(Path.Combine(evidenceRoot, "web-knowledge-failure-dom-" + mode + ".json"), await app.Workspace.Browser.CoreWebView2.ExecuteScriptAsync("document.body.innerText"));
                }
                Console.WriteLine("WEB_KNOWLEDGE_FULL_APP_STOP " + stage + " " + error.GetType().Name);
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
