using System.Text;

namespace PersonalAiWorkspace.Core;

public enum ConversationStatus { ACTIVE, ARCHIVED }
public enum ConversationTurnStatus { PENDING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
public enum ConversationRole { USER, ASSISTANT }
public sealed record Conversation(Guid Id, string Title, ConversationStatus Status, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt)
{
    public override string ToString() => $"Conversation[id={Id:D},status={Status}]";
}
public sealed record ConversationMessage(Guid Id, Guid TurnId, ConversationRole Role, string Content, DateTimeOffset CreatedAt)
{
    public override string ToString() => $"ConversationMessage[id={Id:D},role={Role}]";
}
public sealed record ConversationTurn(Guid Id, Guid ConversationId, long Sequence, ConversationTurnStatus Status,
    DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt, ConversationMessage UserMessage, ConversationMessage? AssistantMessage)
{
    public override string ToString() => $"ConversationTurn[id={Id:D},sequence={Sequence},status={Status}]";
}
public sealed record ConversationPage(IReadOnlyList<Conversation> Items, int Total, int Page, int Limit)
{
    public override string ToString() => $"ConversationPage[total={Total},page={Page}]";
}
public sealed record ConversationDetail(Conversation Conversation, IReadOnlyList<ConversationTurn> Turns, int TotalTurns, int Page, int Limit)
{
    public override string ToString() => $"ConversationDetail[id={Conversation.Id:D},totalTurns={TotalTurns}]";
}
internal static class ConversationValidation
{
    internal static void Id(Guid id) { if (id == Guid.Empty) throw new DesktopException(DesktopError.ConversationInvalid); }
    internal static bool Title(string? title) => MemoryValidation.Unicode(title, 160)
        && MemoryValidation.ValidText(MemoryType.PROJECT_NOTE, title, "x");
    internal static bool Content(string? text) => MemoryValidation.Unicode(text, 8192)
        && text!.Length <= 8192 && Encoding.UTF8.GetByteCount(text) <= 8192
        && text.Any(ch => !(ch is >= '\u001c' and <= '\u001f' || char.IsWhiteSpace(ch) && ch is not ('\u0085' or '\u00a0' or '\u2007' or '\u202f')));
    internal static void Page(int page, int limit)
    { if (page < 0 || limit is < 1 or > 10) throw new DesktopException(DesktopError.ConversationInvalid); }
}
