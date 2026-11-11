using System.Net;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed record ConversationMemoryUsageHit(Conversation Conversation, Guid TurnId, int Sequence, ConversationTurnStatus Status, long Revision)
{ public override string ToString() => "ConversationMemoryUsageHit[redacted]"; }
public sealed record ConversationMemoryUsagePage(Guid MemoryId, long? Revision, IReadOnlyList<ConversationMemoryUsageHit> Items, bool HasMore, int Page, int Limit)
{ public override string ToString() => $"ConversationMemoryUsagePage[page={Page}]"; }

public sealed partial class RuntimeClient
{
    private static long UsageRevision(JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt64(out long revision) || revision < 1) throw Invalid();
        return revision;
    }
    public async Task<ConversationMemoryUsagePage> GetConversationMemoryUsageAsync(Guid memoryId, long? revision, int page, CancellationToken ct)
    {
        ConversationValidation.Id(memoryId);
        if (revision is < 1 || page is < 0 or > 99999) throw new DesktopException(DesktopError.ConversationInvalid);
        using var body = await SendConversationAsync(HttpMethod.Post, "/api/v1/conversations/memory-usage", new { memoryId, revision, page }, HttpStatusCode.OK, ct);
        var root = body.RootElement; MemoryFields(root, "memoryId", "revision", "items", "hasMore", "page", "limit");
        long? echoed = Property(root, "revision").ValueKind == JsonValueKind.Null ? null : UsageRevision(Property(root, "revision"));
        if (ConversationId(root, "memoryId") != memoryId || echoed != revision || MemoryInteger(root, "page") != page
            || MemoryInteger(root, "limit") != 10 || Property(root, "hasMore").ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw Invalid();
        bool more = Property(root, "hasMore").GetBoolean(); var rows = Property(root, "items");
        if (rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() > 10 || more && rows.GetArrayLength() != 10) throw Invalid();
        var items = rows.EnumerateArray().Select(row => {
            MemoryFields(row, "conversation", "turnId", "sequence", "status", "revision");
            int sequence = MemoryInteger(row, "sequence"); long selected = UsageRevision(Property(row, "revision")); string status = String(row, "status");
            if (sequence is < 1 or > 1000 || selected < 1 || revision.HasValue && selected != revision.Value
                || status is not ("SUCCEEDED" or "FAILED" or "CANCELLED" or "TIMED_OUT")) throw Invalid();
            return new ConversationMemoryUsageHit(ParseConversation(Property(row, "conversation"), null), ConversationId(row, "turnId"),
                sequence, Enum.Parse<ConversationTurnStatus>(status), selected);
        }).ToArray();
        if (items.Select(x => x.TurnId).Distinct().Count() != items.Length) throw Invalid();
        return new(memoryId, revision, Array.AsReadOnly(items), more, page, 10);
    }
}
