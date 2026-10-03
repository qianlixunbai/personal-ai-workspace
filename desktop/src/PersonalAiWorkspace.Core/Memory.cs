using System.Text;

namespace PersonalAiWorkspace.Core;

public enum MemoryType { PREFERENCE, PROJECT_NOTE }
public enum MemoryStatus { ACTIVE, ARCHIVED }
public enum MemorySource { MANUAL }

public sealed record MemoryItem(Guid Id, MemoryType Type, string Title, string Content, MemoryStatus Status,
    long Revision, MemorySource Source, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt)
{
    public override string ToString() => $"MemoryItem[id={Id:D},type={Type},status={Status},revision={Revision}]";
}
public sealed record MemoryPage(IReadOnlyList<MemoryItem> Items, int Total, int Page, int Limit)
{
    public bool HasPrevious => Page > 0;
    public bool HasNext => ((long)Page + 1) * Limit < Total;
    public override string ToString() => $"MemoryPage[total={Total},page={Page},limit={Limit}]";
}
public sealed record MemoryCreateInput(MemoryType Type, string Title, string Content)
{
    public void Validate() => MemoryValidation.Text(Type, Title, Content);
    public override string ToString() => $"MemoryCreateInput[type={Type}]";
}
public sealed record MemoryUpdateInput(long ExpectedRevision, MemoryType Type, string Title, string Content)
{
    public void Validate() { MemoryValidation.Revision(ExpectedRevision); MemoryValidation.Text(Type, Title, Content); }
    public override string ToString() => $"MemoryUpdateInput[type={Type},expectedRevision={ExpectedRevision}]";
}
public sealed record MemoryQuery(string Query = "", MemoryStatus Status = MemoryStatus.ACTIVE,
    MemoryType? Type = null, int Page = 0, int Limit = 20)
{
    public void Validate()
    {
        if (!Enum.IsDefined(Status) || Type.HasValue && !Enum.IsDefined(Type.Value) || Page < 0 || Limit is < 1 or > 100
            || !MemoryValidation.Unicode(Query, 160)) throw new DesktopException(DesktopError.MemoryInvalid);
    }
    public override string ToString() => $"MemoryQuery[status={Status},type={Type},page={Page},limit={Limit}]";
}
internal static class MemoryValidation
{
    internal static bool Unicode(string? text, int maximumPoints)
    {
        if (text is null) return false;
        int points = 0;
        for (int i = 0; i < text.Length; i++)
        {
            char ch = text[i];
            if (ch == '\0' || char.IsLowSurrogate(ch)) return false;
            if (char.IsHighSurrogate(ch) && (++i == text.Length || !char.IsLowSurrogate(text[i]))) return false;
            if (++points > maximumPoints) return false;
        }
        return true;
    }
    // Java String.strip uses Character.isWhitespace, which excludes non-breaking spaces.
    private static bool NonBlank(string text) => text.Any(ch =>
        !(ch is >= '\u001c' and <= '\u001f' || char.IsWhiteSpace(ch) && ch is not ('\u0085' or '\u00a0' or '\u2007' or '\u202f')));
    internal static bool ValidText(MemoryType type, string? title, string? content) => Enum.IsDefined(type)
        && Unicode(title, 160) && Unicode(content, 2000) && NonBlank(title!) && NonBlank(content!)
        && content!.Length <= 2000 && Encoding.UTF8.GetByteCount(content) <= 8192;
    internal static void Text(MemoryType type, string title, string content)
    {
        if (!ValidText(type, title, content)) throw new DesktopException(DesktopError.MemoryInvalid);
    }
    internal static void Revision(long value)
    { if (value < 1) throw new DesktopException(DesktopError.MemoryInvalid); }
    internal static void Id(Guid value)
    { if (value == Guid.Empty) throw new DesktopException(DesktopError.MemoryInvalid); }
}
