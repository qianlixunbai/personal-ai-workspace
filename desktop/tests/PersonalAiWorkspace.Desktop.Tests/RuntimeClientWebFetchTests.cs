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
}
