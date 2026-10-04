using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal sealed record SelectedMemoryRef(string MemoryId, string Revision, int Position)
{ public override string ToString() => "SelectedMemoryRef[redacted]"; }
internal sealed record SelectedMemoryMetadata(string MemoryId, string Revision, int Position, string Title)
{ public override string ToString() => "SelectedMemoryMetadata[redacted]"; }
internal sealed record WorkspaceOperationView(string OperationId, TaskState Status, string? Result, WorkspaceSafeError? Error)
{ public override string ToString() => $"WorkspaceOperationView[status={Status}]"; }
internal sealed record WorkspaceSafeError(string Code, string Message);
internal sealed class WorkspaceOperationException(string code, string message) : Exception(message)
{ internal string Code { get; } = code; }

// Application-owned, transient and bounded. A document session is an ownership boundary,
// never a Runtime credential. Reload/route/close do not cancel admitted operations.
internal sealed class WorkspaceOperations : IDisposable
{
    internal const int Capacity = 16;
    private sealed class Entry(string session, AssistantOperation runner)
    {
        internal readonly string Session = session;
        internal readonly AssistantOperation Runner = runner;
        internal RuntimeTask Task = null!;
        internal string Capability = "";
        internal string PromptVersion = "";
        internal DesktopError? CommunicationError;
        internal DateTimeOffset? Finished;
    }
    private readonly RuntimeClient runtime;
    private readonly Func<CancellationToken, Task<IReadOnlyList<MemorySelection>?>> select;
    private readonly Action<string> copy;
    private readonly Func<DateTimeOffset> now;
    private readonly TimeSpan retention;
    private readonly TimeSpan? pollingInterval;
    private readonly CancellationTokenSource lifetime = new();
    private readonly Dictionary<string, Entry> entries = new(StringComparer.Ordinal);
    private readonly Dictionary<string, SelectedMemoryMetadata[]> selections = new(StringComparer.Ordinal);
    private readonly HashSet<string> documents = new(StringComparer.Ordinal);
    private readonly HashSet<Task> running = [];
    private readonly object sync = new();
    private readonly Timer cleanup;
    private int reservations;
    private bool disposed;
    internal WorkspaceOperations(RuntimeClient runtime,
        Func<CancellationToken, Task<IReadOnlyList<MemorySelection>?>> select, Action<string> copy,
        Func<DateTimeOffset>? now = null, TimeSpan? retention = null, TimeSpan? pollingInterval = null)
    { this.runtime = runtime; this.select = select; this.copy = copy; this.now = now ?? (() => DateTimeOffset.UtcNow);
        this.retention = retention ?? TimeSpan.FromMinutes(2); this.pollingInterval = pollingInterval;
        cleanup = new Timer(_ => { lock (sync) Prune(); }, null, TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(30)); }

    internal void BeginSession(string session) { lock (sync) { if (!disposed) documents.Add(session); } }
    internal void EndSession(string session) { lock (sync) { documents.Remove(session); selections.Remove(session); } }
    private void RequireSession(string session)
    { if (disposed || !documents.Contains(session)) throw Missing(); }
    internal async Task<object> SelectAsync(string session, CancellationToken cancellation)
    {
        lock (sync) RequireSession(session);
        var selected = await select(cancellation);
        cancellation.ThrowIfCancellationRequested();
        lock (sync)
        {
            RequireSession(session);
            if (selected is null) return new { changed = false, selectedMemoryRefs = Array.Empty<SelectedMemoryMetadata>() };
            new MemoryAskInput("validation", selected.Select(x => x.Reference).ToArray()).Validate();
            var result = selected.Select((x, index) => new SelectedMemoryMetadata(x.Id.ToString("D"),
                x.Revision.ToString(CultureInfo.InvariantCulture), index, x.Title)).ToArray();
            selections[session] = result;
            return new { changed = true, selectedMemoryRefs = result };
        }
    }
    internal async Task<WorkspaceOperationView> SubmitAsync(string session, AssistantInput input, SelectedMemoryRef[] references)
    {
        input.Validate();
        MemoryReference[] memories;
        SelectedMemoryMetadata[]? selection;
        lock (sync)
        {
            RequireSession(session); Prune();
            selections.TryGetValue(session, out selection);
            if (references.Length > 0 && (input.Action != AssistantAction.Ask || selection is null
                || references.Length != selection.Length || references.Where((x, i) =>
                    x.MemoryId != selection[i].MemoryId || x.Revision != selection[i].Revision || x.Position != i).Any()))
                throw new WorkspaceOperationException("MEMORY_SELECTION_REQUIRED", "请通过原生窗口重新选择 Memory，或清除选择。");
            memories = references.Select(x => new MemoryReference(Guid.ParseExact(x.MemoryId, "D"),
                long.Parse(x.Revision, CultureInfo.InvariantCulture))).ToArray();
            if (memories.Length > 0) new MemoryAskInput(input.Text, memories).Validate();
            if (entries.Count + reservations >= Capacity)
                throw new WorkspaceOperationException("OPERATION_CAPACITY", "当前工作区操作容量已满，请等待已接受的操作结束。");
            reservations++;
        }
        var admitted = new TaskCompletionSource<WorkspaceOperationView>(TaskCreationOptions.RunContinuationsAsynchronously);
        var runner = new AssistantOperation(runtime, pollingInterval);
        string id = Guid.NewGuid().ToString("D");
        var entry = new Entry(session, runner)
        {
            Capability = input.Action.ToString().ToLowerInvariant(),
            PromptVersion = memories.Length > 0 ? "memory-ask-v1" : input.Action.ToString().ToLowerInvariant() + "-v1"
        };
        async Task Run()
        {
            bool registered = false;
            try
            {
                await runner.RunAsync(input, task =>
                {
                    lock (sync)
                    {
                        if (!registered)
                        {
                            reservations--; registered = true;
                            if (!disposed) entries.Add(id, entry);
                            if (selections.TryGetValue(session, out var current) && ReferenceEquals(current, selection)) selections.Remove(session);
                        }
                        entry.Task = task;
                        if (task.Terminal) entry.Finished = now();
                        admitted.TrySetResult(View(id, entry));
                    }
                }, lifetime.Token, memories);
            }
            catch (DesktopException error)
            {
                var safe = runner.AdmissionOutcomeUnknown ? new DesktopException(DesktopError.OutcomeUnknown) : error;
                lock (sync) { entry.CommunicationError = safe.Error; entry.Finished = now(); }
                admitted.TrySetException(safe);
            }
            catch (OperationCanceledException) { admitted.TrySetCanceled(); }
            catch (Exception) { admitted.TrySetException(new DesktopException(DesktopError.InternalError)); }
            finally { lock (sync) { if (!registered) reservations--; } }
        }
        var work = Run();
        lock (sync) running.Add(work);
        _ = work.ContinueWith(completed => { lock (sync) running.Remove(completed); }, TaskScheduler.Default);
        return await admitted.Task;
    }
    internal WorkspaceOperationView Get(string session, string id)
    {
        lock (sync)
        {
            var entry = Owned(session, id);
            if (entry.CommunicationError is { } error) throw new DesktopException(error);
            // The shared AssistantOperation is the sole poller. Its RuntimeClient checks the
            // bound task ID, capability and prompt version on every GET and cancellation.
            return View(id, entry);
        }
    }
    internal object Cancel(string session, string id)
    {
        lock (sync)
        {
            var entry = Owned(session, id);
            if (entry.Task.Terminal || entry.CommunicationError is not null) throw new DesktopException(DesktopError.InvalidRequest);
            entry.Runner.RequestCancel();
            return new { requested = true };
        }
    }
    internal object Copy(string session, string id)
    {
        string result;
        lock (sync)
        {
            var entry = Owned(session, id);
            if (entry.Task.Status != TaskState.SUCCEEDED || entry.CommunicationError is not null || entry.Task.Result is null)
                throw new DesktopException(DesktopError.InvalidRequest);
            result = entry.Task.Result;
        }
        try { copy(result); }
        catch (Exception) { throw new WorkspaceOperationException("CLIPBOARD_UNAVAILABLE", "系统剪贴板暂时不可用，请稍后再次点击复制。"); }
        return new { copied = true };
    }
    private Entry Owned(string session, string id)
    { RequireSession(session); Prune(); return entries.TryGetValue(id, out var entry) && entry.Session == session ? entry : throw Missing(); }
    private static WorkspaceOperationException Missing() => new("OPERATION_NOT_FOUND", "此操作不可用或已过期，请检查工作区与 Runtime 状态。");
    private static WorkspaceOperationView View(string id, Entry entry) => new(id, entry.Task.Status, entry.Task.Result,
        entry.Task.Error is { } error ? new(error.ToString(), ErrorText.For(error)) : null);
    private void Prune()
    { foreach (string id in entries.Where(x => x.Value.Finished is { } finished && now() - finished >= retention).Select(x => x.Key).ToArray()) entries.Remove(id); }
    internal async Task ShutdownAsync()
    {
        Task[] work;
        lock (sync) { foreach (var entry in entries.Values) if (!entry.Task.Terminal) entry.Runner.RequestCancel(); work = running.ToArray(); }
        await Task.WhenAny(Task.WhenAll(work), Task.Delay(5000));
        Dispose();
        await Task.WhenAny(Task.WhenAll(work), Task.Delay(2500));
    }
    public void Dispose()
    {
        lock (sync) { if (disposed) return; disposed = true; documents.Clear(); selections.Clear(); entries.Clear(); }
        lifetime.Cancel();
        cleanup.Dispose();
        // Operations can still be performing bounded best-effort cleanup. Do not dispose
        // their cancellation source while continuations may read its token.
    }
}
