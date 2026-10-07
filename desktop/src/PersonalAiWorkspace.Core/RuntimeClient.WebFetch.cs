using System.Net;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient
{
    public async Task<WebFetchOperation> SubmitWebFetchAsync(Guid operationId, string url, CancellationToken token)
    {
        WebId(operationId);
        var target = WebFetchTarget.Parse(url);
        if (target.Url != url) throw new DesktopException(DesktopError.WebTargetInvalid);
        try
        {
            using var body = await SendAsync(HttpMethod.Post, "/api/v1/web/fetches",
                JsonSerializer.SerializeToUtf8Bytes(new { operationId = operationId.ToString("D"), url }), true, HttpStatusCode.Accepted, token,
                (response, document) =>
                {
                    var view = ParseWebFetch(document.RootElement, operationId);
                    if (response.Headers.Location?.OriginalString != $"/api/v1/web/fetches/{operationId:D}") throw Invalid();
                    if (view.Result is { } result && result.RequestedUrl != url) throw Invalid();
                }, endpointErrorMap: WebHttpError, maximumResponse: 65536);
            return ParseWebFetch(body.RootElement, operationId);
        }
        catch (DesktopException e) when (e.Error is DesktopError.RuntimeUnavailable or DesktopError.ClientTimeout or DesktopError.InvalidResponse)
        { throw new DesktopException(DesktopError.OutcomeUnknown); }
        catch (InvalidOperationException) { throw new DesktopException(DesktopError.OutcomeUnknown); }
    }
    public Task<WebFetchOperation> GetWebFetchAsync(Guid id, CancellationToken token) => WebFetchRequest(HttpMethod.Get, id, token);
    public Task<WebFetchOperation> CancelWebFetchAsync(Guid id, CancellationToken token) => WebFetchRequest(HttpMethod.Delete, id, token);
    private async Task<WebFetchOperation> WebFetchRequest(HttpMethod method, Guid id, CancellationToken token)
    {
        WebId(id);
        try
        {
            using var body = await SendAsync(method, $"/api/v1/web/fetches/{id:D}", null, true, HttpStatusCode.OK, token,
                endpointErrorMap: WebHttpError, maximumResponse: 65536);
            return ParseWebFetch(body.RootElement, id);
        }
        catch (InvalidOperationException) { throw Invalid(); }
    }
    private static void WebId(Guid id) { if (id == Guid.Empty) throw new DesktopException(DesktopError.InvalidRequest); }
    private static DesktopError WebCode(string code) => code switch
    {
        "WEB_DISABLED" => DesktopError.WebDisabled, "WEB_TARGET_INVALID" => DesktopError.WebTargetInvalid,
        "WEB_TARGET_NOT_PUBLIC" => DesktopError.WebTargetNotPublic, "WEB_DNS_FAILED" => DesktopError.WebDnsFailed,
        "WEB_TLS_FAILED" => DesktopError.WebTlsFailed, "WEB_TIMEOUT" => DesktopError.WebTimeout,
        "WEB_REDIRECT_DENIED" => DesktopError.WebRedirectDenied, "WEB_RESPONSE_TOO_LARGE" => DesktopError.WebResponseTooLarge,
        "WEB_CONTENT_TYPE_UNSUPPORTED" => DesktopError.WebContentTypeUnsupported, "WEB_CONTENT_INVALID" => DesktopError.WebContentInvalid,
        "WEB_FETCH_FAILED" => DesktopError.WebFetchFailed, "WEB_FETCH_NOT_FOUND" => DesktopError.WebFetchNotFound,
        "UNAUTHORIZED" or "POLICY_DENIED" or "INVALID_REQUEST" or "QUEUE_FULL" or "INTERNAL_ERROR" => MapError(code),
        _ => throw Invalid()
    };
    private static DesktopError WebHttpError(HttpStatusCode status, JsonElement root)
    {
        MemoryFields(root, "code", "message", "phase"); _ = String(root, "message"); _ = String(root, "phase");
        string code = String(root, "code");
        int expected = code switch
        {
            "WEB_TARGET_INVALID" or "INVALID_REQUEST" => 400,
            "WEB_DISABLED" or "WEB_TARGET_NOT_PUBLIC" or "WEB_REDIRECT_DENIED" or "POLICY_DENIED" => 403,
            "UNAUTHORIZED" => 401, "WEB_FETCH_NOT_FOUND" => 404, "WEB_TIMEOUT" => 504,
            "WEB_RESPONSE_TOO_LARGE" => 413, "WEB_CONTENT_TYPE_UNSUPPORTED" => 415,
            "WEB_DNS_FAILED" or "WEB_TLS_FAILED" or "WEB_CONTENT_INVALID" or "WEB_FETCH_FAILED" => 502,
            "QUEUE_FULL" => 429, "INTERNAL_ERROR" => 500, _ => throw Invalid()
        };
        if ((int)status != expected) throw Invalid();
        return WebCode(code);
    }
    private static WebFetchOperation ParseWebFetch(JsonElement root, Guid expected)
    {
        MemoryFields(root, "operationId", "state", "result", "error");
        if (String(root, "operationId") != expected.ToString("D")) throw Invalid();
        string state = String(root, "state");
        if (!Enum.TryParse<WebFetchState>(state, out var parsed) || !Enum.IsDefined(parsed) || parsed.ToString() != state) throw Invalid();
        var result = Property(root, "result"); var error = Property(root, "error");
        if (parsed == WebFetchState.SUCCEEDED)
        {
            if (error.ValueKind != JsonValueKind.Null) throw Invalid();
            return new(expected, parsed, ParseWebResult(result), null);
        }
        if (result.ValueKind != JsonValueKind.Null) throw Invalid();
        if (parsed != WebFetchState.FAILED)
        {
            if (error.ValueKind != JsonValueKind.Null) throw Invalid();
            return new(expected, parsed, null, null);
        }
        MemoryFields(error, "code", "message", "phase"); _ = String(error, "message"); _ = String(error, "phase");
        return new(expected, parsed, null, WebCode(String(error, "code")));
    }
    private static WebFetchResult ParseWebResult(JsonElement r)
    {
        MemoryFields(r, "requestedUrl", "finalUrl", "hostname", "title", "acquiredAt", "contentType", "extractionVersion", "text", "titleTruncated", "textTruncated");
        string requested = String(r, "requestedUrl"), final = String(r, "finalUrl"), host = String(r, "hostname");
        try
        {
            var a = WebFetchTarget.Parse(requested); var b = WebFetchTarget.Parse(final);
            if (a.Url != requested || b.Url != final || a.Hostname != host || b.Hostname != host) throw Invalid();
        }
        catch (DesktopException) { throw Invalid(); }
        string title = String(r, "title"), text = String(r, "text");
        ValidateUnicode(title); ValidateUnicode(text);
        if (title.EnumerateRunes().Count() > 160 || Encoding.UTF8.GetByteCount(title) > 640
            || text.Length > 4096 || Encoding.UTF8.GetByteCount(text) > 8192) throw Invalid();
        string acquired = String(r, "acquiredAt"), type = String(r, "contentType"), version = String(r, "extractionVersion");
        if (acquired.Length > 40 || !Regex.IsMatch(acquired, @"\A[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z\z")
            || !DateTimeOffset.TryParse(acquired, CultureInfo.InvariantCulture, DateTimeStyles.None, out _)
            || type is not ("text/html" or "text/plain" or "application/xhtml+xml") || version != "web-extract-1") throw Invalid();
        var titleFlag = Property(r, "titleTruncated"); var textFlag = Property(r, "textTruncated");
        if (titleFlag.ValueKind is not (JsonValueKind.True or JsonValueKind.False) || textFlag.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw Invalid();
        return new(requested, final, host, title, acquired, type, version, text, titleFlag.GetBoolean(), textFlag.GetBoolean());
    }
}
