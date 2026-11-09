using System.Net;
using System.Text;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed record ConversationSearchHit(Conversation Conversation, Guid TurnId, int Sequence, Guid MessageId, ConversationRole Role,
    string Snippet, int MatchStart, int MatchEnd, bool Before, bool After)
{ public override string ToString() => "ConversationSearchHit[redacted]"; }
public sealed record ConversationSearchPage(string Query, string Scope, IReadOnlyList<ConversationSearchHit> Items, bool HasMore, int Page, int Limit)
{ public override string ToString() => $"ConversationSearchPage[page={Page}]"; }

public sealed partial class RuntimeClient
{
    public static bool ValidConversationSearch(string? query, string scope, int page) => ConversationValidation.Content(query)
        && query!.EnumerateRunes().Count() <= 100 && scope is "ALL" or "ACTIVE" or "ARCHIVED" && page is >= 0 and <= 199999;

    public async Task<ConversationSearchPage> SearchConversationsAsync(string query, string scope, int page, CancellationToken ct)
    {
        if (!ValidConversationSearch(query, scope, page)) throw new DesktopException(DesktopError.ConversationInvalid);
        using var body = await SendConversationAsync(HttpMethod.Post, "/api/v1/conversations/search", new { query, scope, page }, HttpStatusCode.OK, ct);
        var root = body.RootElement; MemoryFields(root, "query", "scope", "items", "hasMore", "page", "limit");
        if (String(root, "query") != query || String(root, "scope") != scope || MemoryInteger(root, "page") != page || MemoryInteger(root, "limit") != 10) throw Invalid();
        var rows = Property(root, "items");
        if (Property(root, "hasMore").ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw Invalid();
        bool more = Property(root, "hasMore").GetBoolean();
        if (rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() > 10 || more && rows.GetArrayLength() != 10) throw Invalid();
        var items = rows.EnumerateArray().Select(row => {
            MemoryFields(row, "conversation", "turnId", "sequence", "messageId", "role", "snippet", "matchStart", "matchEnd", "before", "after");
            var conversation = ParseConversation(Property(row, "conversation"), null);
            int sequence = MemoryInteger(row, "sequence"), start = MemoryInteger(row, "matchStart"), end = MemoryInteger(row, "matchEnd");
            string snippet = String(row, "snippet"), role = String(row, "role");
            if (sequence is < 1 or > 1000 || scope != "ALL" && conversation.Status.ToString() != scope
                || role is not ("USER" or "ASSISTANT") || !ConversationValidation.Content(snippet) || snippet.EnumerateRunes().Count() > 220
                || start < 0 || end <= start || end > snippet.Length || snippet[start..end] != query
                || Property(row, "before").ValueKind is not (JsonValueKind.True or JsonValueKind.False)
                || Property(row, "after").ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw Invalid();
            return new ConversationSearchHit(conversation, ConversationId(row, "turnId"), sequence, ConversationId(row, "messageId"),
                Enum.Parse<ConversationRole>(role), snippet, start, end, Property(row, "before").GetBoolean(), Property(row, "after").GetBoolean());
        }).ToArray();
        if (items.Select(x => x.MessageId).Distinct().Count() != items.Length) throw Invalid();
        return new(query, scope, Array.AsReadOnly(items), more, page, 10);
    }
}
