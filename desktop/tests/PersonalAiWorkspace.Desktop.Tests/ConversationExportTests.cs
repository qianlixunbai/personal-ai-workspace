using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Serialization;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class ConversationExportTests
{
    internal static readonly Guid Id = Guid.Parse("b9718e4d-3d2e-4f87-977a-c1cdd35b4040");
    internal const string Literal = "中文 😀\n```\n<script>alert('literal')</script>\n[链接](https://example.invalid/)";
    private static readonly DateTimeOffset At = DateTimeOffset.Parse("2026-11-09T00:00:00Z");
    internal static ConversationDetail Page(int page, int total = 11, bool pending = false, bool changed = false, bool large = false)
    {
        var conversation = new Conversation(Id, changed ? "已改名" : "合成会话 <script>标题</script>", ConversationStatus.ARCHIVED, At, At);
        var turns = Enumerable.Range(page * 10 + 1, Math.Min(10, Math.Max(0, total - page * 10))).Select(n =>
        {
            Guid turn = Guid.Parse($"00000000-0000-4000-8000-{n:D12}");
            var state = pending && n == total ? ConversationTurnStatus.PENDING : n == 2 ? ConversationTurnStatus.FAILED
                : n == 3 ? ConversationTurnStatus.CANCELLED : n == 4 ? ConversationTurnStatus.TIMED_OUT : ConversationTurnStatus.SUCCEEDED;
            var user = new ConversationMessage(Guid.Parse($"10000000-0000-4000-8000-{n:D12}"), turn, ConversationRole.USER, large ? new string('x', 8192) : "用户原文 " + n + "\n" + Literal, At);
            var answer = state == ConversationTurnStatus.SUCCEEDED ? new ConversationMessage(Guid.Parse($"20000000-0000-4000-8000-{n:D12}"), turn, ConversationRole.ASSISTANT, large ? new string('x', 8192) : "助手原文 " + n + "\n" + Literal, At) : null;
            return new ConversationTurn(turn, Id, n, state, At, At, user, answer, FailureCode: state == ConversationTurnStatus.FAILED ? ConversationFailureCode.EXECUTION_INTERRUPTED : null,
                Memories: [new(Guid.Parse("30000000-0000-4000-8000-000000000001"), long.MaxValue, 0)]);
        }).ToArray();
        return new(conversation, turns, total, page, 10);
    }
    private sealed class Handler : HttpMessageHandler
    {
        internal readonly List<int> Reads = [];
        internal int Total = 11;
        internal bool Pending, Changed, Large;
        internal TaskCompletionSource? Hold;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Assert.Equal(HttpMethod.Get, request.Method); Assert.Equal(new string('t', 43), request.Headers.Authorization!.Parameter);
            object body;
            if (request.RequestUri!.AbsolutePath == "/api/v1/conversations") body = new { items = new[] { Page(0).Conversation }, total = 1, page = 0, limit = 10 };
            else
            {
                int page = int.Parse(request.RequestUri.Query.Split("page=")[1].Split('&')[0]); Reads.Add(page);
                if (Hold is not null) await Hold.Task.WaitAsync(ct);
                body = Page(page, Total, Pending, Changed && Reads.Count > 2, Large);
            }
            return RuntimeClientTests.Response(HttpStatusCode.OK, JsonSerializer.Serialize(body, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, Converters = { new JsonStringEnumConverter() } }));
        }
    }
    private sealed class Output : IConversationExport
    {
        internal int Calls; internal bool Cancel; internal string? Text;
        public Task<bool> SaveAsync(Guid id, string text, CancellationToken token) { token.ThrowIfCancellationRequested(); Assert.Equal(Id, id); Calls++; Text = text; return Task.FromResult(!Cancel); }
    }
    private sealed class Fixture : IDisposable
    {
        internal readonly Handler Handler = new(); internal readonly Output Output = new();
        internal readonly RuntimeClient Runtime; internal readonly WorkspaceConversations Conversations;
        internal Fixture() { Runtime = new(Handler, () => new string('t', 43)); Conversations = new(Runtime, _ => Task.FromResult<IReadOnlyList<MemorySelection>?>(null), Output); Conversations.BeginSession("one"); }
        internal Task Authorize() => Conversations.ListAsync("one", ConversationStatus.ARCHIVED, 0, default);
        public void Dispose() { Conversations.EndSession("one"); Runtime.Dispose(); }
    }

    [Fact] public async Task CompleteArchivedTranscriptIncludesEveryPageRawUnicodeTerminalStatusAndMemoryIds()
    {
        using var f = new Fixture();
        Assert.Equal(DesktopError.ConversationNotFound, (await Assert.ThrowsAsync<DesktopException>(() => f.Conversations.ExportAsync("one", Id, default))).Error);
        Assert.Empty(f.Handler.Reads); await f.Authorize();
        Assert.Contains("SAVED", JsonSerializer.Serialize(await f.Conversations.ExportAsync("one", Id, default)));
        Assert.Equal(new[] { 0, 1, 0 }, f.Handler.Reads); Assert.Equal(1, f.Output.Calls);
        string text = f.Output.Text!;
        Assert.Contains("总轮次：11", text); Assert.Contains("## 第 11 轮 · 已完成", text);
        Assert.Contains("## 第 2 轮 · 失败", text); Assert.Contains("## 第 3 轮 · 已取消", text); Assert.Contains("## 第 4 轮 · 超时", text);
        Assert.Contains("````text\n用户原文 11\n" + Literal + "\n````", text);
        Assert.Contains("EXECUTION_INTERRUPTED", text); Assert.Contains("9223372036854775807", text);
        Assert.DoesNotContain("PENDING", text); Assert.DoesNotContain(new string('t', 43), text);
        f.Output.Cancel = true;
        Assert.Contains("CANCELLED", JsonSerializer.Serialize(await f.Conversations.ExportAsync("one", Id, default)));
    }

    [Theory]
    [InlineData("pending", DesktopError.ConversationExportNotReady)]
    [InlineData("changed", DesktopError.ConversationExportNotReady)]
    [InlineData("large", DesktopError.ConversationExportTooLarge)]
    public async Task UnsafeOrIncompleteSnapshotNeverOpensTheSaveDialog(string scenario, DesktopError error)
    {
        using var f = new Fixture(); await f.Authorize();
        f.Handler.Pending = scenario == "pending"; f.Handler.Changed = scenario == "changed";
        if (scenario == "large") { f.Handler.Large = true; f.Handler.Total = 300; }
        Assert.Equal(error, (await Assert.ThrowsAsync<DesktopException>(() => f.Conversations.ExportAsync("one", Id, default))).Error);
        Assert.Equal(0, f.Output.Calls);
    }

    [Fact] public async Task ReplacingSessionCancelsTheReadAndConcurrentExportsAreRejected()
    {
        using var f = new Fixture(); await f.Authorize(); f.Handler.Hold = new();
        var pending = f.Conversations.ExportAsync("one", Id, default);
        Assert.Equal(DesktopError.QueueFull, (await Assert.ThrowsAsync<DesktopException>(() => f.Conversations.ExportAsync("one", Id, default))).Error);
        f.Conversations.BeginSession("one");
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending); Assert.Equal(0, f.Output.Calls);
    }
}
