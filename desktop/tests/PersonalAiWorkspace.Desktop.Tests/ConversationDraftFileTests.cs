using System.Net;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class ConversationDraftFileTests
{
    private static readonly Guid Id = Guid.Parse("e60123cd-1111-4111-8111-111111111111");
    private const string Text = " 保留空白\r\n中文😀<script>文本</script>\n资料 ID：可编辑线索\n ";
    private static readonly DateTimeOffset At = DateTimeOffset.Parse("2026-11-10T00:00:00Z");
    private sealed class Handler : HttpMessageHandler
    {
        internal int Gets;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            Assert.Equal(HttpMethod.Get, request.Method); Gets++;
            var conversation = new Conversation(Id, "合成草稿目标", ConversationStatus.ACTIVE, At, At);
            object value = request.RequestUri!.AbsolutePath.EndsWith("/conversations", StringComparison.Ordinal)
                ? new ConversationPage([conversation], 1, 0, 10) : new ConversationDetail(conversation, [], 0, 0, 10);
            return Task.FromResult(RuntimeClientTests.Response(HttpStatusCode.OK, JsonSerializer.Serialize(value,
                new JsonSerializerOptions(JsonSerializerDefaults.Web) { Converters = { new JsonStringEnumConverter() } })));
        }
    }
    private sealed class Files(string directory) : IConversationDraftFiles
    {
        internal readonly string Path = System.IO.Path.Combine(directory, "saved.workspace-draft.md");
        internal bool Cancel, WrongTarget; internal int Writes, Opens;
        internal TaskCompletionSource? Hold;
        public async Task<bool> SaveAsync(ConversationDraftFile draft, CancellationToken token)
        {
            if (Cancel) return false;
            await NativeKnowledgeAnswerExport.SaveNewAsync(Path, ConversationDraftFormat.Format(draft), token); Writes++; return true;
        }
        public async Task<ConversationDraftFile?> OpenAsync(CancellationToken token)
        {
            Opens++; if (Cancel) return null;
            if (Hold is not null) await Hold.Task; // Simulate a picker returning after its old session ended.
            using var file = NativeKnowledgeSourceFiles.OpenConversationDraft(Path);
            var draft = await ConversationDraftFormat.ReadAsync(file, token);
            return WrongTarget ? draft with { ConversationId = Guid.Parse("e60123cd-2222-4222-8222-222222222222") } : draft;
        }
    }
    private sealed class Actions(WorkspaceConversations conversations) : IWorkspaceNativeActions
    {
        public WorkspaceConversations Conversations => conversations;
        public Task<ShellStatus> StatusAsync(CancellationToken token) => Task.FromResult(new ShellStatus(1, "1.0.0.0", ShellRuntimeState.Available, ShellCredentialState.Ready, "Available", []));
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken token) => throw new InvalidOperationException();
    }
    [Fact] public async Task ManualFileRoundTripSurvivesANewSessionWithoutWritesToRuntimeAndRejectsWrongOrLateSources()
    {
        string directory = Environment.GetEnvironmentVariable("WORKSPACE_DRAFT_TEST_DIR") ?? throw new InvalidOperationException("Synthetic evidence directory required");
        Directory.CreateDirectory(directory);
        using var runtime = new RuntimeClient(new Handler(), () => new string('t', 43));
        var files = new Files(directory); var conversations = new WorkspaceConversations(runtime, _ => throw new InvalidOperationException(), drafts: files);
        const string document = WorkspaceContentPolicy.ProductionOrigin + "/index.html";
        var replies = new List<string>();
        using var bridge = new WorkspaceBridge(new WorkspaceContentPolicy(["index.html"]), new Actions(conversations), replies.Add);
        bridge.BeginDocument(document); bridge.Ready(document);
        async Task<JsonElement> Call(string method, object payload)
        {
            await bridge.ReceiveAsync(document, document, JsonSerializer.Serialize(new { version = 1, sessionId = bridge.SessionId,
                requestId = Guid.NewGuid().ToString("D"), method, payload }));
            return JsonDocument.Parse(replies.Last()).RootElement.Clone();
        }
        var denied = await Call("conversations.saveDraft", new { conversationId = Id, message = Text });
        Assert.Equal("ConversationNotFound", denied.GetProperty("error").GetProperty("code").GetString()); Assert.Equal(0, files.Writes);
        await Call("conversations.list", new { status = "ACTIVE", page = 0 });
        var saved = await Call("conversations.saveDraft", new { conversationId = Id, message = Text });
        Assert.Equal("SAVED", saved.GetProperty("result").GetProperty("outcome").GetString()); Assert.Equal(1, files.Writes);
        byte[] original = await File.ReadAllBytesAsync(files.Path);
        // Restart the document and native conversation authority; the only recovery state is the explicit file.
        bridge.BeginDocument(document); bridge.Ready(document); await Call("conversations.list", new { status = "ACTIVE", page = 0 });
        var opened = await Call("conversations.openDraft", new { conversationId = Id });
        Assert.Equal(Text, opened.GetProperty("result").GetProperty("draft").GetProperty("message").GetString());
        Assert.Equal(Id, opened.GetProperty("result").GetProperty("draft").GetProperty("conversationId").GetGuid());
        var duplicate = await Call("conversations.saveDraft", new { conversationId = Id, message = "后来草稿" });
        Assert.Equal("ConversationDraftSaveUnknown", duplicate.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal(original, await File.ReadAllBytesAsync(files.Path)); Assert.Equal(1, files.Writes);
        files.Cancel = true;
        Assert.Equal("CANCELLED", (await Call("conversations.openDraft", new { conversationId = Id })).GetProperty("result").GetProperty("outcome").GetString());
        Assert.Equal("CANCELLED", (await Call("conversations.saveDraft", new { conversationId = Id, message = Text })).GetProperty("result").GetProperty("outcome").GetString());
        files.Cancel = false; files.WrongTarget = true;
        Assert.Equal("ConversationDraftMismatch", (await Call("conversations.openDraft", new { conversationId = Id })).GetProperty("error").GetProperty("code").GetString());
        files.WrongTarget = false; files.Hold = new();
        var late = Call("conversations.openDraft", new { conversationId = Id });
        bridge.BeginDocument(document); bridge.Ready(document); int count = replies.Count; files.Hold.SetResult(); await late;
        Assert.Equal(count, replies.Count); Assert.Equal(original, await File.ReadAllBytesAsync(files.Path));
        foreach (string bad in new[] { "# unsupported", Encoding.UTF8.GetString(original).Replace("\"totalTurns\":0", "\"totalTurns\":0,\"memoryRefs\":[]"), new string('x', 32769) })
        {
            using var input = new MemoryStream(Encoding.UTF8.GetBytes(bad));
            Assert.Equal("ConversationDraftInvalid", (await Assert.ThrowsAsync<WorkspaceOperationException>(() => ConversationDraftFormat.ReadAsync(input, default))).Code);
        }
        Assert.False(ConversationDraftFormat.ValidMessage("x\uD800")); Assert.False(ConversationDraftFormat.ValidMessage(new string('x', 3001)));
    }
}
