using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class MemoryClientTests
{
    internal static readonly Guid Id = Guid.NewGuid();
    internal static readonly string PrivateTitle = "synthetic-title-" + Guid.NewGuid();
    internal static readonly string PrivateContent = "synthetic-content-" + Guid.NewGuid();
    internal static readonly string Token = Convert.ToBase64String(Guid.NewGuid().ToByteArray().Concat(Guid.NewGuid().ToByteArray()).ToArray()).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    internal static string Item(Guid? id = null, long revision = 1, string status = "ACTIVE", string? content = null) => JsonSerializer.Serialize(new
    {
        id = id ?? Id, type = "PROJECT_NOTE", title = PrivateTitle, content = content ?? PrivateContent,
        status, revision, source = "MANUAL", createdAt = "2026-10-03T10:00:00Z", updatedAt = "2026-10-03T10:00:01Z"
    });
    internal static string Page(string[]? items = null, int? total = null, int page = 0, int limit = 20) =>
        $"{{\"items\":[{string.Join(',', items ?? [Item()])}],\"total\":{total ?? items?.Length ?? 1},\"page\":{page},\"limit\":{limit}}}";
    internal sealed class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) : HttpMessageHandler
    { protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) => send(request, token); }
    internal static HttpResponseMessage Response(string json, HttpStatusCode status = HttpStatusCode.OK) => new(status)
    { Content = new StringContent(json, Encoding.UTF8, "application/json") };
    internal static RuntimeClient Client(string json, HttpStatusCode status = HttpStatusCode.OK) =>
        new(new Handler((_, _) => Task.FromResult(Response(json, status))), () => Token);
    internal static string Error(string code) => JsonSerializer.Serialize(new { code, message = PrivateTitle + PrivateContent + Token, phase = "MEMORY" });
    internal static void Safe(string text)
    { Assert.True(new[] { PrivateTitle, PrivateContent, Token }.All(x => !text.Contains(x, StringComparison.Ordinal)), "Private text leaked."); }




    [Fact]
    public async Task EndpointAllowlistDoesNotWeakenAiOrPairingAndRejectsMismatchedMemoryCodes()
    {
        foreach (var (status, code) in new[] { (400, "MEMORY_INVALID"), (404, "MEMORY_NOT_FOUND"),
            (409, "MEMORY_REVISION_CONFLICT"), (503, "MEMORY_STORAGE_UNAVAILABLE") })
        {
            using var client = Client(Error(code), (HttpStatusCode)status);
            Func<Task>[] actions = [() => client.SubmitTranslateAsync(new("synthetic", "en"), default),
                () => client.SubmitSummarizeAsync(new("synthetic"), default), () => client.SubmitAskAsync(new("synthetic"), default),
                () => client.CreateBrowserPairingAsync("chrome-extension://abcdefghijklmnopabcdefghijklmnop", default)];
            foreach (var action in actions)
                Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(action)).Error);
        }
        foreach (var (status, code) in new[] { (400, "MEMORY_NOT_FOUND"), (404, "TASK_NOT_FOUND"),
            (409, "INVALID_REQUEST"), (503, "PROVIDER_UNAVAILABLE"), (200, "MEMORY_INVALID"), (302, "MEMORY_INVALID") })
        {
            using var client = Client(Error(code), (HttpStatusCode)status);
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => client.GetMemoryAsync(Id, default))).Error);
        }
    }

    [Fact]
    public async Task MalformedItemAndPageResponsesFailClosedWithoutExposingText()
    {
        string valid = Item();
        string[] invalidItems = ["null", "[]", "{}", "<raw>", "{bad", valid.Replace("\"id\":", "\"credential\":\"private\",\"id\":"),
            valid.Replace("\"id\":", "\"id\":null,\"id\":"), valid.Replace(Id.ToString(), Guid.Empty.ToString()),
            valid.Replace("PROJECT_NOTE", "UNKNOWN"), valid.Replace("ACTIVE", "UNKNOWN"), valid.Replace("MANUAL", "AUTO"),
            valid.Replace("\"revision\":1", "\"revision\":0"), valid.Replace("\"revision\":1", "\"revision\":\"1\""),
            valid.Replace("2026-10-03T10:00:01Z", "2026-10-02T10:00:00Z"), valid.Replace("2026-10-03T10:00:00Z", "today"),
            Item(content: new string('x', 2001)), Item(content: " "), Item(content: "\0")];
        foreach (string json in invalidItems)
        {
            using var client = Client(json);
            var error = await Assert.ThrowsAsync<DesktopException>(() => client.GetMemoryAsync(Id, default));
            Assert.Equal(DesktopError.InvalidResponse, error.Error); Safe(error.ToString());
        }
        string[] invalidPages = [Page(total: -1), Page(total: 1001), Page(page: 1), Page(limit: 100), Page([], 1),
            Page([valid, valid]), Page([Item(status: "ARCHIVED")]), Page().Replace("\"total\":1", "\"total\":1,\"total\":1"),
            Page().Replace("\"items\":", "\"token\":\"private\",\"items\":"), Page().Replace("\"total\":1", "\"total\":\"1\"")];
        foreach (string json in invalidPages)
        {
            using var client = Client(json);
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => client.ListMemoryAsync(new(), default))).Error);
        }
    }


    [Fact]
    public async Task SizeLimitTransportFailureCancellationAndMutationResponseValidationAreControlled()
    {
        using var oversized = Client(new string('x', 1024 * 1024 + 1));
        Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => oversized.GetMemoryAsync(Id, default))).Error);
        using var offline = new RuntimeClient(new Handler((_, _) => throw new HttpRequestException(Token + PrivateContent)), () => Token);
        var error = await Assert.ThrowsAsync<DesktopException>(() => offline.GetMemoryAsync(Id, default));
        Assert.Equal(DesktopError.RuntimeUnavailable, error.Error); Safe(error.ToString());
        using var cancelled = new CancellationTokenSource(); cancelled.Cancel();
        using var client = new RuntimeClient(new Handler((_, ct) => Task.FromCanceled<HttpResponseMessage>(ct)), () => Token);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.ListMemoryAsync(new(), cancelled.Token));
        using var privateCancellation = new RuntimeClient(new Handler((_, _) => throw new OperationCanceledException(PrivateContent + Token)), () => Token);
        var cancelledError = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => privateCancellation.GetMemoryAsync(Id, cancelled.Token));
        Safe(cancelledError.ToString()); Assert.Null(cancelledError.InnerException);
        using var stale = Client(Item());
        Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => stale.UpdateMemoryAsync(Id,
            new(1, MemoryType.PROJECT_NOTE, PrivateTitle, PrivateContent), default))).Error);
        using var wrongId = Client(Item(Guid.NewGuid()));
        Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => wrongId.GetMemoryAsync(Id, default))).Error);
        using var deleteBody = Client(PrivateContent, HttpStatusCode.NoContent);
        Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => deleteBody.DeleteMemoryAsync(Id, 1, default))).Error);
    }
}
