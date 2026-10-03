using System.Globalization;
using System.Net;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient
{
    private static string ConversationPath(Guid id) { ConversationValidation.Id(id); return $"/api/v1/conversations/{id:D}"; }
    private Task<JsonDocument> SendConversationAsync(HttpMethod method, string path, object? body, HttpStatusCode expected,
        CancellationToken ct, Action<HttpResponseMessage, JsonDocument>? validate = null) =>
        SendAsync(method, path, body is null ? null : JsonSerializer.SerializeToUtf8Bytes(body), true, expected, ct,
            validate: validate, endpointErrorMap: ConversationError);
    public async Task<Conversation> CreateConversationAsync(string? title, CancellationToken ct)
    {
        if (title is not null && !ConversationValidation.Title(title)) throw new DesktopException(DesktopError.ConversationInvalid);
        using var body = await SendConversationAsync(HttpMethod.Post, "/api/v1/conversations", new { title }, HttpStatusCode.Created, ct,
            (response, document) =>
            {
                var item = ParseConversation(document.RootElement, null);
                if (item.Status != ConversationStatus.ACTIVE || response.Headers.Location?.OriginalString != ConversationPath(item.Id)) throw Invalid();
            });
        return ParseConversation(body.RootElement, null);
    }
    public async Task<Conversation> RenameConversationAsync(Guid id, string title, CancellationToken ct)
    {
        if (!ConversationValidation.Title(title)) throw new DesktopException(DesktopError.ConversationInvalid);
        using var body = await SendConversationAsync(HttpMethod.Patch, ConversationPath(id), new { title }, HttpStatusCode.OK, ct);
        return ParseConversation(body.RootElement, id);
    }
    public Task<Conversation> ArchiveConversationAsync(Guid id, CancellationToken ct) => ConversationLifecycleAsync(id, true, ct);
    public Task<Conversation> UnarchiveConversationAsync(Guid id, CancellationToken ct) => ConversationLifecycleAsync(id, false, ct);
    private async Task<Conversation> ConversationLifecycleAsync(Guid id, bool archive, CancellationToken ct)
    {
        using var body = await SendConversationAsync(HttpMethod.Post, ConversationPath(id) + (archive ? "/archive" : "/unarchive"), null, HttpStatusCode.OK, ct);
        var item = ParseConversation(body.RootElement, id);
        if (item.Status != (archive ? ConversationStatus.ARCHIVED : ConversationStatus.ACTIVE)) throw Invalid();
        return item;
    }
    public async Task DeleteConversationAsync(Guid id, CancellationToken ct)
    { using var body = await SendConversationAsync(HttpMethod.Delete, ConversationPath(id), null, HttpStatusCode.NoContent, ct); }
    public async Task<ConversationPage> ListConversationsAsync(ConversationStatus status, int page, int limit, CancellationToken ct)
    {
        ConversationValidation.Page(page, limit);
        if (!Enum.IsDefined(status)) throw new DesktopException(DesktopError.ConversationInvalid);
        using var body = await SendConversationAsync(HttpMethod.Get, "/api/v1/conversations?status=" + status
            + "&page=" + page.ToString(CultureInfo.InvariantCulture) + "&limit=" + limit.ToString(CultureInfo.InvariantCulture), null, HttpStatusCode.OK, ct);
        var root = body.RootElement; MemoryFields(root, "items", "total", "page", "limit");
        int total = MemoryInteger(root, "total"); var itemsJson = Property(root, "items");
        CheckConversationPage(root, itemsJson, total, page, limit);
        var items = itemsJson.EnumerateArray().Select(x => ParseConversation(x, null)).ToArray();
        if (items.Select(x => x.Id).Distinct().Count() != items.Length || items.Any(x => x.Status != status)
            || !items.SequenceEqual(items.OrderByDescending(x => x.UpdatedAt).ThenBy(x => x.Id.ToString("D"), StringComparer.Ordinal))) throw Invalid();
        return new ConversationPage(Array.AsReadOnly(items), total, page, limit);
    }
    public async Task<ConversationDetail> GetConversationAsync(Guid id, int page, int limit, CancellationToken ct)
    {
        ConversationValidation.Page(page, limit);
        using var body = await SendConversationAsync(HttpMethod.Get, ConversationPath(id) + "?page=" + page.ToString(CultureInfo.InvariantCulture)
            + "&limit=" + limit.ToString(CultureInfo.InvariantCulture), null, HttpStatusCode.OK, ct);
        var root = body.RootElement; MemoryFields(root, "conversation", "turns", "totalTurns", "page", "limit");
        var conversation = ParseConversation(Property(root, "conversation"), id);
        int total = MemoryInteger(root, "totalTurns"); var turnsJson = Property(root, "turns");
        CheckConversationPage(root, turnsJson, total, page, limit);
        var turns = turnsJson.EnumerateArray().Select(x => ParseConversationTurn(x, id)).ToArray();
        if (turns.Select(x => x.Id).Distinct().Count() != turns.Length
            || turns.Where((x, i) => x.Sequence != (long)page * limit + i + 1).Any()) throw Invalid();
        var messageIds = turns.SelectMany(x => x.AssistantMessage is null ? new[] { x.UserMessage.Id } : new[] { x.UserMessage.Id, x.AssistantMessage.Id }).ToArray();
        if (messageIds.Distinct().Count() != messageIds.Length) throw Invalid();
        return new ConversationDetail(conversation, Array.AsReadOnly(turns), total, page, limit);
    }
    private static void CheckConversationPage(JsonElement root, JsonElement array, int total, int page, int limit)
    {
        if (total is < 0 or > 1000 || MemoryInteger(root, "page") != page || MemoryInteger(root, "limit") != limit
            || array.ValueKind != JsonValueKind.Array || array.GetArrayLength() != Math.Min(limit, Math.Max(0L, total - (long)page * limit))) throw Invalid();
    }
    private static Guid ConversationId(JsonElement root, string name)
    {
        string text = String(root, name);
        if (!Guid.TryParseExact(text, "D", out var id) || id == Guid.Empty || text != id.ToString("D")) throw Invalid();
        return id;
    }
    private static Conversation ParseConversation(JsonElement root, Guid? expected)
    {
        MemoryFields(root, "id", "title", "status", "createdAt", "updatedAt");
        var id = ConversationId(root, "id"); string title = String(root, "title");
        var status = String(root, "status") switch { "ACTIVE" => ConversationStatus.ACTIVE, "ARCHIVED" => ConversationStatus.ARCHIVED, _ => throw Invalid() };
        var created = MemoryTime(String(root, "createdAt")); var updated = MemoryTime(String(root, "updatedAt"));
        if (expected.HasValue && id != expected || !ConversationValidation.Title(title) || updated < created) throw Invalid();
        return new Conversation(id, title, status, created, updated);
    }
    private static ConversationTurn ParseConversationTurn(JsonElement root, Guid conversationId)
    {
        MemoryFields(root, "id", "conversationId", "sequence", "status", "createdAt", "updatedAt", "userMessage", "assistantMessage");
        var id = ConversationId(root, "id"); var owner = ConversationId(root, "conversationId");
        long sequence = MemoryInteger(root, "sequence");
        string state = String(root, "status");
        if (!Enum.TryParse<ConversationTurnStatus>(state, false, out var status) || !Enum.IsDefined(status) || state != status.ToString()) throw Invalid();
        var created = MemoryTime(String(root, "createdAt")); var updated = MemoryTime(String(root, "updatedAt"));
        var user = ParseConversationMessage(Property(root, "userMessage"), id, ConversationRole.USER);
        var assistantJson = Property(root, "assistantMessage");
        var assistant = assistantJson.ValueKind == JsonValueKind.Null ? null : ParseConversationMessage(assistantJson, id, ConversationRole.ASSISTANT);
        if (owner != conversationId || sequence is < 1 or > 1000 || updated < created || user.CreatedAt != created
            || (status == ConversationTurnStatus.SUCCEEDED) != (assistant is not null)
            || assistant is not null && (assistant.CreatedAt < created || assistant.CreatedAt > updated || assistant.Id == user.Id)) throw Invalid();
        return new ConversationTurn(id, owner, sequence, status, created, updated, user, assistant);
    }
    private static ConversationMessage ParseConversationMessage(JsonElement root, Guid turnId, ConversationRole role)
    {
        MemoryFields(root, "id", "turnId", "role", "content", "createdAt");
        var id = ConversationId(root, "id"); string content = String(root, "content");
        if (ConversationId(root, "turnId") != turnId || String(root, "role") != role.ToString() || !ConversationValidation.Content(content)) throw Invalid();
        return new ConversationMessage(id, turnId, role, content, MemoryTime(String(root, "createdAt")));
    }
    private static DesktopError ConversationError(HttpStatusCode status, JsonElement root)
    {
        MemoryFields(root, "code", "message", "phase"); _ = String(root, "message"); _ = String(root, "phase");
        return ((int)status, String(root, "code")) switch
        {
            (400, "CONVERSATION_INVALID") => DesktopError.ConversationInvalid,
            (400 or 413, "INVALID_REQUEST") => DesktopError.InvalidRequest,
            (404, "CONVERSATION_NOT_FOUND") => DesktopError.ConversationNotFound,
            (409, "CONVERSATION_CONFLICT") => DesktopError.ConversationConflict,
            (409, "CONVERSATION_LIMIT_EXCEEDED") => DesktopError.ConversationLimitExceeded,
            (503, "CONVERSATION_STORAGE_UNAVAILABLE") => DesktopError.ConversationStorageUnavailable,
            (403, "POLICY_DENIED") => DesktopError.PolicyDenied,
            (500, "INTERNAL_ERROR") => DesktopError.InternalError,
            _ => throw Invalid()
        };
    }
}
