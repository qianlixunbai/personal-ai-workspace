using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

// Only session authorization lives here. Runtime owns history and execution, including
// after a document/window disappears. There is no operation registry or transcript cache.
internal sealed class WorkspaceConversations(RuntimeClient runtime,
    Func<CancellationToken, Task<IReadOnlyList<MemorySelection>?>> select)
{
    private sealed record Binding(Guid TaskId, int Page);
    private sealed class Session
    {
        internal readonly HashSet<Guid> Known = [];
        internal readonly Dictionary<(Guid Conversation, Guid Turn), Binding> Pending = [];
        internal Guid? SelectionOwner;
        internal SelectedMemoryMetadata[] Selection = [];
        internal long SelectionEpoch;
        internal bool NeedsReview;
        internal readonly HashSet<Guid> Sending = [];
    }
    private readonly Dictionary<string, Session> sessions = new(StringComparer.Ordinal);
    private readonly object sync = new();
    internal void BeginSession(string session) { lock (sync) sessions[session] = new(); }
    internal void EndSession(string session) { lock (sync) sessions.Remove(session); }
    private static DesktopException Denied() => new(DesktopError.ConversationNotFound);
    private Session Require(string session, Guid? id = null)
    {
        if (!sessions.TryGetValue(session, out var state) || id.HasValue && !state.Known.Contains(id.Value)) throw Denied();
        return state;
    }
    private bool Current(string session, Session state) => sessions.TryGetValue(session, out var current) && ReferenceEquals(current, state);
    private static void Authorize(Session state, Guid id)
    {
        // Runtime capacity is 1000; evict old authorization when external deletions/new
        // creations would otherwise grow the session indefinitely.
        if (!state.Known.Contains(id) && state.Known.Count >= 1000) Forget(state, state.Known.First());
        state.Known.Add(id);
    }
    private static void Forget(Session state, Guid id)
    {
        state.Known.Remove(id);
        foreach (var key in state.Pending.Keys.Where(x => x.Conversation == id).ToArray()) state.Pending.Remove(key);
        Clear(state, id);
    }
    private static void Clear(Session state, Guid id)
    {
        if (state.SelectionOwner != id) return;
        state.SelectionEpoch++; state.SelectionOwner = null; state.Selection = []; state.NeedsReview = false;
    }
    private static object ConversationView(Conversation value) => new
    { id = value.Id, value.Title, value.Status, value.CreatedAt, value.UpdatedAt };
    private static object MessageView(ConversationMessage value) => new
    { messageId = value.Id, value.Role, value.Content, value.CreatedAt };
    internal static object Project(ConversationDetail value) => new
    {
        conversation = ConversationView(value.Conversation), value.TotalTurns, value.Page, value.Limit,
        turns = value.Turns.Select(turn => new
        {
            turnId = turn.Id, turn.Sequence, turn.Status, turn.CreatedAt, turn.UpdatedAt,
            userMessage = MessageView(turn.UserMessage),
            assistantMessage = turn.AssistantMessage is null ? null : MessageView(turn.AssistantMessage),
            turn.FailureCode,
            memoryReferences = (turn.Memories ?? []).Select(memory => new
            { memory.MemoryId, revision = memory.Revision.ToString(CultureInfo.InvariantCulture), memory.Position }).ToArray(),
            canCancel = turn.Status == ConversationTurnStatus.PENDING && turn.TaskId.HasValue
        }).ToArray()
    };
    internal async Task<object> ListAsync(string session, ConversationStatus status, int page, CancellationToken ct)
    {
        Session state; lock (sync) state = Require(session);
        var value = await runtime.ListConversationsAsync(status, page, 10, ct);
        lock (sync) { if (Current(session, state)) foreach (var item in value.Items) Authorize(state, item.Id); }
        return new { items = value.Items.Select(ConversationView).ToArray(), value.Total, value.Page, value.Limit };
    }
    internal async Task<object> GetAsync(string session, Guid id, int page, CancellationToken ct)
    {
        Session state; lock (sync) state = Require(session, id);
        var detail = await runtime.GetConversationAsync(id, page, 10, ct);
        lock (sync)
        {
            if (Current(session, state))
            {
                foreach (var key in state.Pending.Keys.Where(x => x.Conversation == id && state.Pending[x].Page == page).ToArray()) state.Pending.Remove(key);
                foreach (var turn in detail.Turns.Where(x => x.Status == ConversationTurnStatus.PENDING && x.TaskId.HasValue))
                    state.Pending[(id, turn.Id)] = new(turn.TaskId!.Value, page);
                if (detail.Conversation.Status == ConversationStatus.ARCHIVED) Clear(state, id);
            }
        }
        return Project(detail);
    }
    internal async Task<object> CreateAsync(string session, CancellationToken ct)
    {
        Session state; lock (sync) state = Require(session);
        var value = await runtime.CreateConversationAsync(null, ct);
        lock (sync) { if (Current(session, state)) Authorize(state, value.Id); }
        return ConversationView(value);
    }
    internal async Task<object> MutateAsync(string session, Guid id, string method, string? title, CancellationToken ct)
    {
        Session state; lock (sync) state = Require(session, id);
        if (method == "conversations.delete")
        {
            await runtime.DeleteConversationAsync(id, ct);
            lock (sync) { if (Current(session, state)) Forget(state, id); }
            return new { deleted = true };
        }
        var value = method switch
        {
            "conversations.rename" => await runtime.RenameConversationAsync(id, title!, ct),
            "conversations.archive" => await runtime.ArchiveConversationAsync(id, ct),
            "conversations.unarchive" => await runtime.UnarchiveConversationAsync(id, ct),
            _ => throw new InvalidOperationException()
        };
        lock (sync) { if (Current(session, state) && value.Status == ConversationStatus.ARCHIVED) Clear(state, id); }
        return ConversationView(value);
    }
    internal object ClearMemories(string session, Guid id)
    { lock (sync) { Clear(Require(session, id), id); return new { cleared = true }; } }
    internal async Task<object> SelectAsync(string session, Guid id, CancellationToken ct)
    {
        Session state; long epoch;
        lock (sync)
        {
            state = Require(session, id);
            if (state.SelectionOwner != id) { state.SelectionEpoch++; state.Selection = []; state.NeedsReview = false; }
            state.SelectionOwner = id; epoch = state.SelectionEpoch;
        }
        // Check ACTIVE and pending truth, without authorizing cancellation through this read.
        var first = await runtime.GetConversationAsync(id, 0, 10, ct);
        var latest = first.TotalTurns <= 10 ? first : await runtime.GetConversationAsync(id, (first.TotalTurns - 1) / 10, 10, ct);
        if (latest.Conversation.Status != ConversationStatus.ACTIVE || latest.Turns.Any(x => x.Status == ConversationTurnStatus.PENDING))
            throw new DesktopException(DesktopError.ConversationConflict);
        var chosen = await select(ct); ct.ThrowIfCancellationRequested();
        lock (sync)
        {
            Require(session, id);
            if (!Current(session, state) || state.SelectionOwner != id || epoch != state.SelectionEpoch) throw Denied();
            if (chosen is null) return new { changed = false, selectedMemoryRefs = Array.Empty<SelectedMemoryMetadata>() };
            new MemoryAskInput("validation", chosen.Select(x => x.Reference).ToArray()).Validate();
            state.Selection = chosen.Select((x, index) => new SelectedMemoryMetadata(x.Id.ToString("D"),
                x.Revision.ToString(CultureInfo.InvariantCulture), index, x.Title)).ToArray();
            state.SelectionEpoch++; state.NeedsReview = false;
            return new { changed = true, selectedMemoryRefs = state.Selection };
        }
    }
    internal async Task<object> SendAsync(string session, Guid id, string message, SelectedMemoryRef[] references)
    {
        new AskInput(message).Validate();
        Session state; long epoch;
        lock (sync)
        {
            state = Require(session, id); epoch = state.SelectionEpoch;
            if (references.Length > 0 && (state.SelectionOwner != id || state.NeedsReview || references.Length != state.Selection.Length
                || references.Where((x, i) => x.MemoryId != state.Selection[i].MemoryId || x.Revision != state.Selection[i].Revision || x.Position != i).Any()))
                throw new WorkspaceOperationException("MEMORY_SELECTION_REQUIRED", "请重新选择或清除本轮 Memory。");
            if (!state.Sending.Add(id)) throw new DesktopException(DesktopError.ConversationConflict);
        }
        bool posting = false, consume = false;
        try
        {
            // The document lifetime cannot abort a durable admission or own its task.
            var current = await runtime.GetConversationAsync(id, 0, 10, CancellationToken.None);
            if (current.Conversation.Status != ConversationStatus.ACTIVE) throw new DesktopException(DesktopError.ConversationConflict);
            var memories = references.Select(x => new MemoryReference(Guid.ParseExact(x.MemoryId, "D"), long.Parse(x.Revision, CultureInfo.InvariantCulture))).ToArray();
            lock (sync)
            {
                Require(session, id);
                if (!Current(session, state)) throw Denied();
                if (references.Length > 0 && (epoch != state.SelectionEpoch || state.SelectionOwner != id || state.NeedsReview))
                    throw new WorkspaceOperationException("MEMORY_SELECTION_REQUIRED", "请重新选择或清除本轮 Memory。");
            }
            posting = true;
            var accepted = await runtime.SubmitConversationTurnAsync(id, message, memories, CancellationToken.None);
            consume = true;
            return new { accepted = true, conversationId = accepted.ConversationId, turnId = accepted.TurnId };
        }
        catch (DesktopException error)
        {
            if (posting && error.Error == DesktopError.MemorySelectionStale)
            { lock (sync) { if (Current(session, state) && epoch == state.SelectionEpoch) state.NeedsReview = true; } }
            // Runtime can finalize a durable FAILED turn before returning a
            // rejection (including a policy failure). Only the documented
            // pre-admission stale response permits keeping picker authority.
            consume = posting && error.Error != DesktopError.MemorySelectionStale;
            if (posting && error.Error is DesktopError.RuntimeUnavailable or DesktopError.ClientTimeout or DesktopError.InvalidResponse)
                throw new DesktopException(DesktopError.OutcomeUnknown);
            throw;
        }
        catch (Exception) when (posting)
        {
            consume = true;
            throw new DesktopException(DesktopError.OutcomeUnknown);
        }
        finally
        {
            lock (sync)
            {
                state.Sending.Remove(id);
                if (consume && Current(session, state) && state.SelectionEpoch == epoch) Clear(state, id);
            }
        }
    }
    internal async Task<object> CancelAsync(string session, Guid id, Guid turnId, CancellationToken ct)
    {
        Session state; Binding binding;
        lock (sync)
        {
            state = Require(session, id);
            if (!state.Pending.TryGetValue((id, turnId), out binding!)) throw Denied();
        }
        var detail = await runtime.GetConversationAsync(id, binding.Page, 10, ct);
        var turn = detail.Turns.SingleOrDefault(x => x.Id == turnId);
        lock (sync)
        {
            Require(session, id);
            if (!Current(session, state) || !state.Pending.TryGetValue((id, turnId), out var owned) || owned != binding) throw Denied();
            if (turn is null || turn.Status != ConversationTurnStatus.PENDING || turn.TaskId != binding.TaskId)
            {
                state.Pending.Remove((id, turnId));
                if (turn is not null && turn.Status != ConversationTurnStatus.PENDING) return new { requested = true }; // Runtime won the race.
                throw Denied();
            }
        }
        await runtime.CancelConversationTaskAsync(binding.TaskId, ct);
        return new { requested = true };
    }
}
