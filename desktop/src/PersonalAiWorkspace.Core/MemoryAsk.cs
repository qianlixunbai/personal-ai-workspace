namespace PersonalAiWorkspace.Core;

public sealed record MemoryReference(Guid Id, long Revision)
{
    public override string ToString() => "MemoryReference[redacted]";
}
// Display metadata lives only in the current window; preview content stays inside the picker.
public sealed record MemorySelection(Guid Id, long Revision, string Title, MemoryType Type)
{
    public MemoryReference Reference => new(Id, Revision);
    public override string ToString() => "MemorySelection[redacted]";
}
public sealed record MemoryAskInput(string Question, IReadOnlyList<MemoryReference> Memories)
{
    public void Validate()
    {
        new AskInput(Question).Validate();
        if (Memories is null || Memories.Count is < 1 or > 4
            || Memories.Any(x => x is null || x.Id == Guid.Empty || x.Revision <= 0)
            || Memories.Select(x => x.Id).Distinct().Count() != Memories.Count)
            throw new DesktopException(DesktopError.InvalidRequest);
    }
    public override string ToString() => "MemoryAskInput[redacted]";
}
