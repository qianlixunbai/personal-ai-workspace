using System.Globalization;
using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient
{
    public async Task<MemoryPage> ListMemoryAsync(MemoryQuery query, CancellationToken cancellationToken)
    {
        query.Validate();
        string path = "/api/v1/memory/items?status=" + Uri.EscapeDataString(query.Status.ToString())
            + "&page=" + query.Page.ToString(CultureInfo.InvariantCulture) + "&limit=" + query.Limit.ToString(CultureInfo.InvariantCulture)
            + "&query=" + Uri.EscapeDataString(query.Query)
            + (query.Type.HasValue ? "&type=" + Uri.EscapeDataString(query.Type.Value.ToString()) : "");
        using var body = await SendMemoryAsync(HttpMethod.Get, path, null, HttpStatusCode.OK, cancellationToken);
        var root = body.RootElement;
        MemoryFields(root, "items", "total", "page", "limit");
        int total = MemoryInteger(root, "total"), page = MemoryInteger(root, "page"), limit = MemoryInteger(root, "limit");
        var array = Property(root, "items");
        if (total is < 0 or > 1000 || page != query.Page || limit != query.Limit || array.ValueKind != JsonValueKind.Array) throw Invalid();
        int expectedCount = (int)Math.Min(limit, Math.Max(0L, total - (long)page * limit));
        if (array.GetArrayLength() != expectedCount) throw Invalid();
        var items = array.EnumerateArray().Select(x => ParseMemory(x, null)).ToArray();
        if (items.Select(x => x.Id).Distinct().Count() != items.Length
            || items.Any(x => x.Status != query.Status || query.Type.HasValue && x.Type != query.Type)) throw Invalid();
        return new MemoryPage(Array.AsReadOnly(items), total, page, limit);
    }

    public async Task<MemoryItem> GetMemoryAsync(Guid id, CancellationToken cancellationToken)
    {
        MemoryValidation.Id(id);
        using var body = await SendMemoryAsync(HttpMethod.Get, MemoryPath(id), null, HttpStatusCode.OK, cancellationToken);
        return ParseMemory(body.RootElement, id);
    }
    public async Task<MemoryItem> CreateMemoryAsync(MemoryCreateInput input, CancellationToken cancellationToken)
    {
        input.Validate();
        using var body = await SendMemoryAsync(HttpMethod.Post, "/api/v1/memory/items",
            new { type = input.Type.ToString(), title = input.Title, content = input.Content }, HttpStatusCode.Created, cancellationToken,
            (response, document) =>
            {
                var item = ParseMemory(document.RootElement, null);
                if (response.Headers.Location?.OriginalString != MemoryPath(item.Id) || item.Status != MemoryStatus.ACTIVE) throw Invalid();
            });
        return ParseMemory(body.RootElement, null);
    }
    public async Task<MemoryItem> UpdateMemoryAsync(Guid id, MemoryUpdateInput input, CancellationToken cancellationToken)
    {
        MemoryValidation.Id(id); input.Validate();
        using var body = await SendMemoryAsync(HttpMethod.Put, MemoryPath(id),
            new { expectedRevision = input.ExpectedRevision, type = input.Type.ToString(), title = input.Title, content = input.Content },
            HttpStatusCode.OK, cancellationToken);
        return ChangedMemory(body.RootElement, id, input.ExpectedRevision, null);
    }
    public Task<MemoryItem> ArchiveMemoryAsync(Guid id, long expectedRevision, CancellationToken cancellationToken) =>
        MemoryLifecycleAsync(id, expectedRevision, MemoryStatus.ARCHIVED, cancellationToken);
    public Task<MemoryItem> RestoreMemoryAsync(Guid id, long expectedRevision, CancellationToken cancellationToken) =>
        MemoryLifecycleAsync(id, expectedRevision, MemoryStatus.ACTIVE, cancellationToken);
    private async Task<MemoryItem> MemoryLifecycleAsync(Guid id, long revision, MemoryStatus status, CancellationToken cancellationToken)
    {
        MemoryValidation.Id(id); MemoryValidation.Revision(revision);
        using var body = await SendMemoryAsync(HttpMethod.Post, MemoryPath(id) + (status == MemoryStatus.ARCHIVED ? "/archive" : "/restore"),
            new { expectedRevision = revision }, HttpStatusCode.OK, cancellationToken);
        return ChangedMemory(body.RootElement, id, revision, status);
    }
    public async Task DeleteMemoryAsync(Guid id, long expectedRevision, CancellationToken cancellationToken)
    {
        MemoryValidation.Id(id); MemoryValidation.Revision(expectedRevision);
        using var body = await SendMemoryAsync(HttpMethod.Delete, MemoryPath(id), new { expectedRevision }, HttpStatusCode.NoContent, cancellationToken);
    }
    private async Task<JsonDocument> SendMemoryAsync(HttpMethod method, string path, object? payload, HttpStatusCode expected,
        CancellationToken token, Action<HttpResponseMessage, JsonDocument>? validate = null)
    {
        try
        {
            return await SendAsync(method, path, payload is null ? null : JsonSerializer.SerializeToUtf8Bytes(payload), true, expected, token,
                validate: validate, endpointErrorMap: MemoryError);
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        { throw new OperationCanceledException(token); }
    }
    private static string MemoryPath(Guid id) => $"/api/v1/memory/items/{id:D}";
    private static DesktopError MemoryError(HttpStatusCode status, JsonElement root)
    {
        MemoryFields(root, "code", "message", "phase");
        _ = String(root, "message"); _ = String(root, "phase");
        return ((int)status, String(root, "code")) switch
        {
            (400, "MEMORY_INVALID") => DesktopError.MemoryInvalid,
            (400 or 413, "INVALID_REQUEST") => DesktopError.InvalidRequest,
            (404, "MEMORY_NOT_FOUND") => DesktopError.MemoryNotFound,
            (409, "MEMORY_REVISION_CONFLICT") => DesktopError.MemoryRevisionConflict,
            (409, "MEMORY_LIMIT_EXCEEDED") => DesktopError.MemoryLimitExceeded,
            (503, "MEMORY_STORAGE_UNAVAILABLE") => DesktopError.MemoryStorageUnavailable,
            (503, "MEMORY_SCHEMA_UNSUPPORTED") => DesktopError.MemorySchemaUnsupported,
            (403, "POLICY_DENIED") => DesktopError.PolicyDenied,
            (500, "INTERNAL_ERROR") => DesktopError.InternalError,
            _ => throw Invalid()
        };
    }
    private static MemoryItem ChangedMemory(JsonElement root, Guid id, long revision, MemoryStatus? status)
    {
        var item = ParseMemory(root, id);
        if (item.Revision <= revision || status.HasValue && item.Status != status.Value) throw Invalid();
        return item;
    }
    private static MemoryItem ParseMemory(JsonElement root, Guid? expectedId)
    {
        MemoryFields(root, "id", "type", "title", "content", "status", "revision", "source", "createdAt", "updatedAt");
        if (!Guid.TryParseExact(String(root, "id"), "D", out var id) || id == Guid.Empty || expectedId.HasValue && id != expectedId) throw Invalid();
        var type = String(root, "type") switch { "PREFERENCE" => MemoryType.PREFERENCE, "PROJECT_NOTE" => MemoryType.PROJECT_NOTE, _ => throw Invalid() };
        var status = String(root, "status") switch { "ACTIVE" => MemoryStatus.ACTIVE, "ARCHIVED" => MemoryStatus.ARCHIVED, _ => throw Invalid() };
        string title = String(root, "title"), content = String(root, "content");
        var revisionJson = Property(root, "revision");
        if (revisionJson.ValueKind != JsonValueKind.Number || !revisionJson.TryGetInt64(out long revision) || revision < 1
            || String(root, "source") != "MANUAL" || !MemoryValidation.ValidText(type, title, content)) throw Invalid();
        var created = MemoryTime(String(root, "createdAt")); var updated = MemoryTime(String(root, "updatedAt"));
        if (updated < created) throw Invalid();
        return new MemoryItem(id, type, title, content, status, revision, MemorySource.MANUAL, created, updated);
    }
    private static DateTimeOffset MemoryTime(string text)
    {
        if (!Regex.IsMatch(text, @"\A\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})\z")
            || !DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out var value)) throw Invalid();
        return value;
    }
    private static int MemoryInteger(JsonElement root, string name)
    {
        var value = Property(root, name);
        return value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out int number) ? number : throw Invalid();
    }
    private static void MemoryFields(JsonElement root, params string[] fields)
    {
        if (root.ValueKind != JsonValueKind.Object || root.EnumerateObject().Count() != fields.Length
            || root.EnumerateObject().Any(x => !fields.Contains(x.Name, StringComparer.Ordinal))) throw Invalid();
    }
}
