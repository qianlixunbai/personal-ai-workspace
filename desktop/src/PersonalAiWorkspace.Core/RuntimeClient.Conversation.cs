using System.Globalization;
using System.Net;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient
{
    private static string ConversationPath(Guid id) { ConversationValidation.Id(id); return $"/api/v1/conversations/{id:D}"; }
    private Task<JsonDocument> SendConversationAsync(HttpMethod method, string path, object? body, HttpStatusCode expected,
        CancellationToken ct, Action<HttpResponseMessage, JsonDocument>? validate = null, bool aiAdmission = false) =>
        SendAsync(method, path, body is null ? null : JsonSerializer.SerializeToUtf8Bytes(body), true, expected, ct,
            validate: validate, endpointErrorMap: (status, root) => ConversationError(status, root, aiAdmission));
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
        MemoryFields(root, "id", "conversationId", "sequence", "status", "createdAt", "updatedAt", "userMessage", "assistantMessage", "taskId", "failureCode", "memories");
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
        var taskJson = Property(root, "taskId");
        Guid? taskId = taskJson.ValueKind == JsonValueKind.Null ? null : ConversationId(root, "taskId");
        var failureJson = Property(root, "failureCode");
        ConversationFailureCode? failure = null;
        if (failureJson.ValueKind != JsonValueKind.Null) {
            string code = String(root, "failureCode");
            if (status != ConversationTurnStatus.FAILED || !Enum.TryParse<ConversationFailureCode>(code, out var parsed)
                || !Enum.IsDefined(parsed) || code != parsed.ToString()) throw Invalid();
            failure = parsed;
        }
        var refs = Property(root, "memories");
        if (refs.ValueKind != JsonValueKind.Array || refs.GetArrayLength() > 4) throw Invalid();
        var selections = refs.EnumerateArray().Select(x => {
            MemoryFields(x, "memoryId", "revision", "position");
            var revision = Property(x, "revision");
            if (revision.ValueKind != JsonValueKind.Number || !revision.TryGetInt64(out long value) || value <= 0) throw Invalid();
            return new ConversationSelection(ConversationId(x, "memoryId"), value, MemoryInteger(x, "position"));
        }).ToArray();
        if (selections.Where((x,i) => x.Position != i).Any() || selections.Select(x=>x.MemoryId).Distinct().Count()!=selections.Length) throw Invalid();
        return new ConversationTurn(id, owner, sequence, status, created, updated, user, assistant, taskId, failure, Array.AsReadOnly(selections));
    }
    private static ConversationMessage ParseConversationMessage(JsonElement root, Guid turnId, ConversationRole role)
    {
        MemoryFields(root, "id", "turnId", "role", "content", "createdAt");
        var id = ConversationId(root, "id"); string content = String(root, "content");
        if (ConversationId(root, "turnId") != turnId || String(root, "role") != role.ToString() || !ConversationValidation.Content(content)) throw Invalid();
        return new ConversationMessage(id, turnId, role, content, MemoryTime(String(root, "createdAt")));
    }
    private static DesktopError ConversationError(HttpStatusCode status, JsonElement root, bool aiAdmission)
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
            (409, "MEMORY_SELECTION_STALE") => DesktopError.MemorySelectionStale,
            (503, "MEMORY_STORAGE_UNAVAILABLE") => DesktopError.MemoryStorageUnavailable,
            (429, "QUEUE_FULL") => DesktopError.QueueFull,
            (403, "POLICY_DENIED") => DesktopError.PolicyDenied,
            (409, "MODEL_SWITCH_CONFLICT") when aiAdmission => DesktopError.ModelSwitchConflict,
            (409, "MODEL_SELECTION_REVISION_CONFLICT") when aiAdmission => DesktopError.ModelSelectionRevisionConflict,
            (409, "MODEL_EXECUTION_UNCERTAIN") when aiAdmission => DesktopError.ModelExecutionUncertain,
            (503, "MODEL_STATE_UNAVAILABLE") when aiAdmission => DesktopError.ModelStateUnavailable,
            (503, "MODEL_CONFIGURATION_INVALID") when aiAdmission => DesktopError.ModelConfigurationInvalid,
            (503, "MODEL_IDENTITY_CHANGED") when aiAdmission => DesktopError.ModelIdentityChanged,
            (503, "MODEL_UNAVAILABLE") when aiAdmission => DesktopError.ModelUnavailable,
            (503, "PROVIDER_UNAVAILABLE") when aiAdmission => DesktopError.ProviderUnavailable,
            (500, "PROVIDER_RESPONSE_INVALID") when aiAdmission => DesktopError.ProviderResponseInvalid,
            (504, "TASK_TIMEOUT") when aiAdmission => DesktopError.TimedOut,
            (500, "INTERNAL_ERROR") => DesktopError.InternalError,
            _ => throw Invalid()
        };
    }
    public async Task<ConversationAdmission> SubmitConversationTurnAsync(Guid conversationId, string message,
        IReadOnlyList<MemoryReference> memories, CancellationToken ct)
    {
        ConversationValidation.Id(conversationId); new AskInput(message).Validate();
        if (!ConversationValidation.Content(message)) throw new DesktopException(DesktopError.ConversationInvalid);
        if (memories is null) throw new DesktopException(DesktopError.InvalidRequest);
        if (memories.Count > 0) new MemoryAskInput(message, memories).Validate();
        using var document = await SendConversationAsync(HttpMethod.Post, ConversationPath(conversationId) + "/turns",
            new { message, memories = memories.Select(x=>new { id=x.Id, revision=x.Revision }).ToArray() }, HttpStatusCode.Accepted, ct,
            (response, body) => {
                if (response.Headers.Location?.OriginalString != $"/api/v1/tasks/{ConversationId(body.RootElement,"taskId"):D}") throw Invalid();
            }, aiAdmission: true);
        var root = document.RootElement;
        MemoryFields(root, "conversationId", "turnId", "taskId", "status", "memoryCount", "admittedSequences", "inputCharacters", "inputBytes");
        if (ConversationId(root,"conversationId") != conversationId || String(root,"status") != "QUEUED") throw Invalid();
        int count = MemoryInteger(root,"memoryCount"), chars = MemoryInteger(root,"inputCharacters"), bytes = MemoryInteger(root,"inputBytes");
        var sequences = Property(root,"admittedSequences");
        if (count != memories.Count || chars is < 1 or > 3000 || bytes is < 1 or > 5632 || sequences.ValueKind != JsonValueKind.Array) throw Invalid();
        var admitted = sequences.EnumerateArray().Select(x=>x.ValueKind == JsonValueKind.Number && x.TryGetInt64(out long value) && value is > 0 and <=1000 ? value : throw Invalid()).ToArray();
        if (admitted.Distinct().Count()!=admitted.Length || !admitted.SequenceEqual(admitted.Order())) throw Invalid();
        return new ConversationAdmission(conversationId, ConversationId(root,"turnId"), ConversationId(root,"taskId"), TaskState.QUEUED,
            count, Array.AsReadOnly(admitted), chars, bytes);
    }
    public Task<RuntimeTask> GetConversationTaskAsync(Guid taskId, CancellationToken ct) => GetAsync(taskId,"conversation",ct,"conversation-v1");
    public Task<RuntimeTask> CancelConversationTaskAsync(Guid taskId, CancellationToken ct) => CancelAsync(taskId,"conversation",ct,"conversation-v1");
}
