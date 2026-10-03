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
    public async Task CrudUsesNativeBearerExactMethodsPathsBodiesAndServerState()
    {
        string[] paths = ["/api/v1/memory/items", $"/api/v1/memory/items/{Id:D}", $"/api/v1/memory/items/{Id:D}",
            $"/api/v1/memory/items/{Id:D}/archive", $"/api/v1/memory/items/{Id:D}/restore", $"/api/v1/memory/items/{Id:D}"];
        HttpMethod[] methods = [HttpMethod.Post, HttpMethod.Get, HttpMethod.Put, HttpMethod.Post, HttpMethod.Post, HttpMethod.Delete];
        int calls = 0;
        using var client = new RuntimeClient(new Handler(async (request, ct) =>
        {
            int index = calls++;
            Assert.Equal(methods[index], request.Method); Assert.Equal(paths[index], request.RequestUri!.AbsolutePath);
            Assert.Equal("127.0.0.1", request.RequestUri.Host); Assert.Equal(8765, request.RequestUri.Port);
            Assert.Equal("Bearer", request.Headers.Authorization?.Scheme);
            Assert.True(Token == request.Headers.Authorization?.Parameter, "Bearer differs.");
            Assert.False(request.Headers.Contains("Origin"));
            if (index == 1) Assert.Null(request.Content);
            else
            {
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                var root = body.RootElement;
                Assert.Equal(index == 0 ? 3 : index == 2 ? 4 : 1, root.EnumerateObject().Count());
                if (index > 0) Assert.Equal(index == 2 ? 1 : index - 1, root.GetProperty("expectedRevision").GetInt64());
                if (index is 0 or 2)
                {
                    Assert.Equal("PROJECT_NOTE", root.GetProperty("type").GetString());
                    Assert.True(PrivateTitle == root.GetProperty("title").GetString(), "Title differs.");
                    Assert.True(PrivateContent == root.GetProperty("content").GetString(), "Content differs.");
                }
            }
            if (index == 5) return new HttpResponseMessage(HttpStatusCode.NoContent);
            var response = Response(Item(revision: index == 0 ? 1 : index, status: index == 3 ? "ARCHIVED" : "ACTIVE"),
                index == 0 ? HttpStatusCode.Created : HttpStatusCode.OK);
            if (index == 0) response.Headers.Location = new Uri(paths[1], UriKind.Relative);
            return response;
        }), () => Token);
        var created = await client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, PrivateTitle, PrivateContent), default);
        Safe(created.ToString());
        Assert.Equal(1, (await client.GetMemoryAsync(Id, default)).Revision);
        Assert.Equal(2, (await client.UpdateMemoryAsync(Id, new(1, MemoryType.PROJECT_NOTE, PrivateTitle, PrivateContent), default)).Revision);
        Assert.Equal(MemoryStatus.ARCHIVED, (await client.ArchiveMemoryAsync(Id, 2, default)).Status);
        Assert.Equal(4, (await client.RestoreMemoryAsync(Id, 3, default)).Revision);
        await client.DeleteMemoryAsync(Id, 4, default); Assert.Equal(6, calls);
    }

    [Fact]
    public async Task SearchEncodesUnicodeAndReservedCharactersAndPagesRemainBounded()
    {
        string query = "synthetic-" + Guid.NewGuid() + " 中文 &type=PREFERENCE?#+%";
        using var client = new RuntimeClient(new Handler((request, _) =>
        {
            var uri = request.RequestUri!;
            Assert.True(uri.Query.Contains("query=" + Uri.EscapeDataString(query), StringComparison.Ordinal), "Query encoding differs.");
            Assert.Empty(uri.Fragment);
            Assert.Contains("status=ACTIVE", uri.Query); Assert.Contains("page=2&limit=20", uri.Query);
            Assert.Contains("&type=PROJECT_NOTE", uri.Query);
            return Task.FromResult(Response(Page([], 40, 2)));
        }), () => Token);
        var page = await client.ListMemoryAsync(new(query, Type: MemoryType.PROJECT_NOTE, Page: 2), default);
        Assert.True(page.HasPrevious); Assert.False(page.HasNext); Assert.Empty(page.Items);
        Assert.DoesNotContain(query, page.ToString()); Assert.DoesNotContain(query, new MemoryQuery(query).ToString());
    }

    [Theory]
    [InlineData(404, "MEMORY_NOT_FOUND", DesktopError.MemoryNotFound)]
    [InlineData(409, "MEMORY_REVISION_CONFLICT", DesktopError.MemoryRevisionConflict)]
    [InlineData(409, "MEMORY_LIMIT_EXCEEDED", DesktopError.MemoryLimitExceeded)]
    [InlineData(400, "MEMORY_INVALID", DesktopError.MemoryInvalid)]
    [InlineData(503, "MEMORY_STORAGE_UNAVAILABLE", DesktopError.MemoryStorageUnavailable)]
    [InlineData(503, "MEMORY_SCHEMA_UNSUPPORTED", DesktopError.MemorySchemaUnsupported)]
    [InlineData(413, "INVALID_REQUEST", DesktopError.InvalidRequest)]
    [InlineData(401, "UNAUTHORIZED", DesktopError.Unauthorized)]
    public async Task MemoryErrorsAreStrictAndPrivate(int status, string code, DesktopError expected)
    {
        using var client = Client(Error(code), (HttpStatusCode)status);
        var error = await Assert.ThrowsAsync<DesktopException>(() => client.GetMemoryAsync(Id, default));
        Assert.Equal(expected, error.Error); Safe(error.ToString()); Assert.Null(error.InnerException);
    }

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
    public async Task UnicodeValidationAndInvalidInputsNeverReachHttpAndDiagnosticsAreMetadataOnly()
    {
        new MemoryCreateInput(MemoryType.PREFERENCE, string.Concat(Enumerable.Repeat("😀", 160)), new string('中', 2000)).Validate();
        new MemoryQuery(string.Concat(Enumerable.Repeat("😀", 160))).Validate();
        using var client = new RuntimeClient(new Handler((_, _) => throw new InvalidOperationException("Unexpected HTTP")), () => Token);
        Func<Task>[] invalid = [() => client.CreateMemoryAsync(new((MemoryType)9, PrivateTitle, PrivateContent), default),
            () => client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, new string('x', 161), PrivateContent), default),
            () => client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, " \n\t", PrivateContent), default),
            () => client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, "\ud800", PrivateContent), default),
            () => client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, PrivateTitle, new string('x', 2001)), default),
            () => client.UpdateMemoryAsync(Id, new(0, MemoryType.PROJECT_NOTE, PrivateTitle, PrivateContent), default),
            () => client.GetMemoryAsync(Guid.Empty, default), () => client.DeleteMemoryAsync(Id, 0, default),
            () => client.ListMemoryAsync(new(new string('x', 161)), default), () => client.ListMemoryAsync(new("\ud800"), default),
            () => client.ListMemoryAsync(new("\0"), default), () => client.ListMemoryAsync(new(Page: -1), default),
            () => client.ListMemoryAsync(new(Limit: 101), default), () => client.ListMemoryAsync(new(Status: (MemoryStatus)9), default)];
        foreach (var action in invalid) Assert.Equal(DesktopError.MemoryInvalid, (await Assert.ThrowsAsync<DesktopException>(action)).Error);
        Safe(new MemoryCreateInput(MemoryType.PROJECT_NOTE, PrivateTitle, PrivateContent).ToString());
        Safe(new MemoryUpdateInput(1, MemoryType.PROJECT_NOTE, PrivateTitle, PrivateContent).ToString());
        foreach (var error in Enum.GetValues<DesktopError>()) Safe(ErrorText.For(error));
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
