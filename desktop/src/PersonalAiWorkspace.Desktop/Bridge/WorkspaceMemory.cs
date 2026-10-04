using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal sealed record MemoryMetadata(Guid Id, MemoryType Type, string Title, MemoryStatus Status,
    string Revision, MemorySource Source, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt)
{ public override string ToString() => $"MemoryMetadata[id={Id:D},type={Type},status={Status},revision={Revision}]"; }
internal sealed record MemoryView(Guid Id, MemoryType Type, string Title, string Content, MemoryStatus Status,
    string Revision, MemorySource Source, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt)
{ public override string ToString() => $"MemoryView[id={Id:D},type={Type},status={Status},revision={Revision}]"; }

// Only bounded session authorization is retained here; Runtime owns all durable data.
internal sealed class WorkspaceMemory(RuntimeClient runtime)
{
    internal const int PageSize = 20;
    private readonly Dictionary<string, HashSet<Guid>> sessions = new(StringComparer.Ordinal);
    private readonly object sync = new();
    internal void BeginSession(string session) { lock (sync) sessions[session] = []; }
    internal void EndSession(string session) { lock (sync) sessions.Remove(session); }
    private HashSet<Guid> Require(string session, Guid? id = null)
    {
        if (!sessions.TryGetValue(session, out var known) || id.HasValue && !known.Contains(id.Value))
            throw new DesktopException(DesktopError.MemoryNotFound);
        return known;
    }
    private void Authorize(string session, HashSet<Guid> known, Guid id)
    {
        if (!sessions.TryGetValue(session, out var current) || !ReferenceEquals(current, known)) return;
        if (!known.Contains(id) && known.Count >= 1000) known.Remove(known.First());
        known.Add(id);
    }
    internal int AuthorizationCount(string session) { lock (sync) return Require(session).Count; }
    internal static MemoryMetadata Metadata(MemoryItem value) => new(value.Id, value.Type, value.Title, value.Status,
        value.Revision.ToString(CultureInfo.InvariantCulture), value.Source, value.CreatedAt, value.UpdatedAt);
    internal static MemoryView Project(MemoryItem value) => new(value.Id, value.Type, value.Title, value.Content, value.Status,
        value.Revision.ToString(CultureInfo.InvariantCulture), value.Source, value.CreatedAt, value.UpdatedAt);
    internal async Task<object> ListAsync(string session, MemoryQuery query, CancellationToken ct)
    {
        HashSet<Guid> known; lock (sync) known = Require(session);
        var result = await runtime.ListMemoryAsync(query with { Limit = PageSize }, ct);
        lock (sync) foreach (var item in result.Items) Authorize(session, known, item.Id);
        return new { items = result.Items.Select(Metadata).ToArray(), result.Total, result.Page, result.Limit };
    }
    internal async Task<MemoryView> GetAsync(string session, Guid id, CancellationToken ct)
    {
        lock (sync) Require(session, id);
        return Project(await runtime.GetMemoryAsync(id, ct));
    }
    internal async Task<MemoryView> CreateAsync(string session, MemoryCreateInput input, CancellationToken ct)
    {
        HashSet<Guid> known; lock (sync) known = Require(session);
        var result = await runtime.CreateMemoryAsync(input, ct);
        lock (sync) Authorize(session, known, result.Id);
        return Project(result);
    }
    internal async Task<MemoryView> UpdateAsync(string session, Guid id, MemoryUpdateInput input, CancellationToken ct)
    {
        lock (sync) Require(session, id);
        return Project(await runtime.UpdateMemoryAsync(id, input, ct));
    }
    internal async Task<object> LifecycleAsync(string session, Guid id, long revision, string method, CancellationToken ct)
    {
        HashSet<Guid> known; lock (sync) known = Require(session, id);
        if (method == "memory.delete")
        {
            await runtime.DeleteMemoryAsync(id, revision, ct);
            lock (sync) if (sessions.TryGetValue(session, out var current) && ReferenceEquals(current, known)) known.Remove(id);
            return new { deleted = true };
        }
        return Project(method == "memory.archive" ? await runtime.ArchiveMemoryAsync(id, revision, ct)
            : await runtime.RestoreMemoryAsync(id, revision, ct));
    }
}
