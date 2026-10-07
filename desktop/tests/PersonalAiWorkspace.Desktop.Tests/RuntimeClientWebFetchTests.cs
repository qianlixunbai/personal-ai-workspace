using System.Net;
using System.Net.Http;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.RuntimeClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class RuntimeClientWebFetchTests
{
    internal const string Url = "https://example.com/a%2fb?x=1&x=2&q=a+b";
    internal static object Result(string url = Url) => new { requestedUrl = url, finalUrl = url, hostname = "example.com",
        title = "Public title", acquiredAt = "2026-10-08T01:00:00Z", contentType = "text/plain", extractionVersion = "web-extract-1",
        text = "Public evidence", titleTruncated = false, textTruncated = false };
    internal static string Envelope(Guid id, string state = "QUEUED", object? result = null, object? error = null) =>
        JsonSerializer.Serialize(new { operationId = id.ToString("D"), state, result, error });
    internal static HttpResponseMessage Reply(Guid id, string body, HttpStatusCode status = HttpStatusCode.OK)
    {
        var response = Response(status, body);
        if (status == HttpStatusCode.Accepted) response.Headers.Location = new Uri($"/api/v1/web/fetches/{id:D}", UriKind.Relative);
        return response;
    }
    [Fact] public async Task TypedPostPollCancelAndControlledErrorsKeepPrivateDiagnosticsOut()
    {
        Guid id = Guid.NewGuid(); var calls = new List<HttpMethod>();
        using var runtime = new RuntimeClient(new Handler(async (request, ct) =>
        {
            Assert.Equal("http://127.0.0.1:8765", request.RequestUri!.GetLeftPart(UriPartial.Authority));
            Assert.Equal(new string('t', 43), request.Headers.Authorization!.Parameter); calls.Add(request.Method);
            if (request.Method == HttpMethod.Post)
            {
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                Assert.Equal(new[] { "operationId", "url" }, body.RootElement.EnumerateObject().Select(p => p.Name));
                Assert.Equal(id.ToString("D"), body.RootElement.GetProperty("operationId").GetString());
                Assert.Equal(Url, body.RootElement.GetProperty("url").GetString());
                return Reply(id, Envelope(id), HttpStatusCode.Accepted);
            }
            Assert.Equal($"/api/v1/web/fetches/{id:D}", request.RequestUri.AbsolutePath); Assert.Null(request.Content);
            return Reply(id, request.Method == HttpMethod.Delete ? Envelope(id, "CANCELLED") : Envelope(id, "SUCCEEDED", Result()));
        }), () => new string('t', 43));
        Assert.Equal(WebFetchState.QUEUED, (await runtime.SubmitWebFetchAsync(id, Url, default)).State);
        var success = await runtime.GetWebFetchAsync(id, default);
        Assert.Equal(Url, success.Result!.RequestedUrl); Assert.DoesNotContain("Public evidence", success.Result.ToString());
        Assert.DoesNotContain("example.com", success.ToString());
        Assert.Equal(WebFetchState.CANCELLED, (await runtime.CancelWebFetchAsync(id, default)).State);
        Assert.Equal(new[] { HttpMethod.Post, HttpMethod.Get, HttpMethod.Delete }, calls);
        foreach (var (code, status, expected) in new[] {
            ("WEB_DISABLED", 403, DesktopError.WebDisabled), ("WEB_TARGET_INVALID", 400, DesktopError.WebTargetInvalid),
            ("WEB_TARGET_NOT_PUBLIC", 403, DesktopError.WebTargetNotPublic), ("WEB_DNS_FAILED", 502, DesktopError.WebDnsFailed),
            ("WEB_TLS_FAILED", 502, DesktopError.WebTlsFailed), ("WEB_TIMEOUT", 504, DesktopError.WebTimeout),
            ("WEB_REDIRECT_DENIED", 403, DesktopError.WebRedirectDenied), ("WEB_RESPONSE_TOO_LARGE", 413, DesktopError.WebResponseTooLarge),
            ("WEB_CONTENT_TYPE_UNSUPPORTED", 415, DesktopError.WebContentTypeUnsupported), ("WEB_CONTENT_INVALID", 502, DesktopError.WebContentInvalid),
            ("WEB_FETCH_FAILED", 502, DesktopError.WebFetchFailed), ("WEB_FETCH_NOT_FOUND", 404, DesktopError.WebFetchNotFound),
            ("UNAUTHORIZED", 401, DesktopError.Unauthorized), ("POLICY_DENIED", 403, DesktopError.PolicyDenied),
            ("INVALID_REQUEST", 400, DesktopError.InvalidRequest), ("QUEUE_FULL", 429, DesktopError.QueueFull), ("INTERNAL_ERROR", 500, DesktopError.InternalError) })
        {
            using var failed = new RuntimeClient(new Handler((_, _) => Task.FromResult(Response((HttpStatusCode)status,
                JsonSerializer.Serialize(new { code, message = new string('t', 43) + " DNS 127.0.0.1 https://private.example.com", phase = "secret" })))), () => new string('t', 43));
            var error = await Assert.ThrowsAsync<DesktopException>(() => failed.GetWebFetchAsync(id, default));
            Assert.Equal(expected, error.Error); Assert.DoesNotContain("private.example.com", error.ToString());
            Assert.DoesNotContain(new string('t', 43), error.ToString()); Assert.DoesNotContain("127.0.0.1", error.ToString());
        }
    }
    [Fact] public async Task StrictWebShapesAndBudgetsRejectForgedOrAmbiguousResponses()
    {
        Guid id = Guid.NewGuid(); string good = Envelope(id, "SUCCEEDED", Result());
        string failed = Envelope(id, "FAILED", error: new { code = "WEB_FETCH_FAILED", message = "secret", phase = "WEB" });
        foreach (string bad in new[] { "{", good + "{}", good.Replace("\"state\":", "\"state\":\"RUNNING\",\"state\":"),
            good.Replace("\"state\":", "\"headers\":{},\"state\":"), good.Replace("SUCCEEDED", "RUNNING"),
            good.Replace("SUCCEEDED", "CANCELLED"), good.Replace("SUCCEEDED", "TIMED_OUT"), Envelope(Guid.NewGuid()),
            Envelope(id, "SUCCEEDED"), Envelope(id, "FAILED"), failed.Replace("WEB_FETCH_FAILED", "PROVIDER_UNAVAILABLE"),
            good.Replace("example.com/a%2fb", "other.com/a%2fb"), good.Replace("text/plain", "application/pdf"),
            good.Replace("web-extract-1", "unknown"), good.Replace("Public title", new string('a', 161)),
            good.Replace("Public evidence", "\\uD800"), good.Replace("2026-10-08T01:00:00Z", "10/08/2026"),
            good.Replace("Public evidence", new string('a', 4097)), good.Replace("Public evidence", new string('界', 3000)),
            good.Replace("Public evidence", new string('a', 65537)), good.Replace("\"textTruncated\":false", "\"textTruncated\":0") })
        {
            using var runtime = new RuntimeClient(new Handler((_, _) => Task.FromResult(Reply(id, bad))), () => new string('t', 43));
            Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => runtime.GetWebFetchAsync(id, default))).Error);
        }
        using var acceptedFailure = new RuntimeClient(new Handler((_, _) => Task.FromResult(Reply(id, failed))), () => new string('t', 43));
        Assert.Equal(DesktopError.WebFetchFailed, (await acceptedFailure.GetWebFetchAsync(id, default)).Error);
    }
}
