using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal interface IWebFetchConfirmation
{
    Task<bool> ConfirmAsync(WebFetchTarget target, CancellationToken cancellation);
}
internal sealed record WorkspaceWebFetchView(string OperationId, string State, WebFetchResult? Result, WorkspaceSafeError? Error)
{ public override string ToString() => "WorkspaceWebFetchView[redacted]"; }
internal sealed record WorkspaceWebFetchSubmission(string Outcome, string? OperationId, WorkspaceWebFetchView? Operation)
{ public override string ToString() => "WorkspaceWebFetchSubmission[redacted]"; }

// Only transient Desktop authority. Runtime remains execution truth and the sole public network owner.
internal sealed class WorkspaceWebFetch(RuntimeClient runtime, IWebFetchConfirmation confirmation)
{
    internal static readonly TimeSpan ConfirmationLifetime = TimeSpan.FromSeconds(60);
    internal const int MaximumOperations = 16;
    private sealed class Authority
    {
        internal readonly CancellationTokenSource Lifetime = new();
        internal readonly Dictionary<Guid, WebFetchTarget> Operations = [];
    }
    private readonly object sync = new();
    private string? session;
    private Authority? authority;
    private CancellationTokenSource? pending;

    internal void BeginSession(string value)
    {
        lock (sync)
        {
            if (authority is not null) throw new InvalidOperationException("Web session already active.");
            session = value; authority = new();
        }
    }
    internal void EndSession(string value)
    {
        lock (sync)
        {
            if (session != value || authority is null) return;
            var old = authority; authority = null; session = null;
            old.Operations.Clear(); old.Lifetime.Cancel(); old.Lifetime.Dispose();
            pending?.Cancel();
        }
    }
    private Authority Require(string value)
    {
        if (session != value || authority is null) throw new OperationCanceledException();
        return authority;
    }
    private void Current(string value, Authority known, CancellationToken token)
    { token.ThrowIfCancellationRequested(); if (!ReferenceEquals(Require(value), known)) throw new OperationCanceledException(); }
    internal async Task<WorkspaceWebFetchSubmission> SubmitAsync(string value, string url, CancellationToken token)
    {
        var target = WebFetchTarget.Parse(url); // Captured once before the native dialog; never reread React state.
        Authority known; CancellationTokenSource intent;
        long started = Stopwatch.GetTimestamp();
        lock (sync)
        {
            known = Require(value); token.ThrowIfCancellationRequested();
            if (pending is not null || known.Operations.Count >= MaximumOperations) throw new DesktopException(DesktopError.QueueFull);
            intent = CancellationTokenSource.CreateLinkedTokenSource(token, known.Lifetime.Token);
            intent.CancelAfter(ConfirmationLifetime); pending = intent;
        }
        Guid id;
        Task<WebFetchOperation> submission;
        try
        {
            bool allowed;
            try { allowed = await confirmation.ConfirmAsync(target, intent.Token).WaitAsync(intent.Token); }
            catch (OperationCanceledException)
            {
                lock (sync) Current(value, known, token);
                return new("CANCELLED", null, null);
            }
            lock (sync)
            {
                Current(value, known, token);
                if (!allowed || intent.IsCancellationRequested || Stopwatch.GetElapsedTime(started) >= ConfirmationLifetime)
                    return new("CANCELLED", null, null);
                // Linearization point: invalidation and one-time consumption use the same lock.
                // Start the direct local POST under this lock; there is no later mutable approval step.
                id = Guid.NewGuid(); known.Operations.Add(id, target);
                submission = runtime.SubmitWebFetchAsync(id, target.Url, token);
            }
        }
        finally
        {
            lock (sync) { if (ReferenceEquals(pending, intent)) pending = null; }
            intent.Cancel(); intent.Dispose();
        }
        try
        {
            var operation = await submission;
            lock (sync) { Current(value, known, token); return new("ACCEPTED", id.ToString("D"), View(known, operation)); }
        }
        catch (DesktopException e) when (e.Error == DesktopError.OutcomeUnknown)
        {
            // Same UUID, one GET only. Never replay POST or allocate a replacement ID.
            Task<WebFetchOperation> reconciliation;
            lock (sync) { Current(value, known, token); reconciliation = runtime.GetWebFetchAsync(id, token); }
            try
            {
                var operation = await reconciliation;
                lock (sync) { Current(value, known, token); return new("ACCEPTED", id.ToString("D"), View(known, operation)); }
            }
            catch (DesktopException)
            { lock (sync) { Current(value, known, token); return new("UNKNOWN", id.ToString("D"), null); } }
        }
        catch (DesktopException)
        { lock (sync) { Current(value, known, token); known.Operations.Remove(id); } throw; }
    }
    internal async Task<WorkspaceWebFetchView> OperationAsync(string value, string operationId, bool cancel, CancellationToken token)
    {
        if (!WorkspaceBridge.CanonicalId(operationId)) throw new DesktopException(DesktopError.InvalidRequest);
        Guid id = Guid.ParseExact(operationId, "D"); Authority known; Task<WebFetchOperation> request;
        lock (sync)
        {
            known = Require(value); token.ThrowIfCancellationRequested();
            if (!known.Operations.ContainsKey(id)) throw new DesktopException(DesktopError.WebFetchNotFound);
            request = cancel ? runtime.CancelWebFetchAsync(id, token) : runtime.GetWebFetchAsync(id, token);
        }
        var operation = await request;
        lock (sync) { Current(value, known, token); return View(known, operation); }
    }
    private static WorkspaceWebFetchView View(Authority known, WebFetchOperation operation)
    {
        if (!known.Operations.TryGetValue(operation.OperationId, out var target)
            || operation.Result is { } result && (result.RequestedUrl != target.Url || result.Hostname != target.Hostname))
            throw new DesktopException(DesktopError.InvalidResponse);
        return new(operation.OperationId.ToString("D"), operation.State.ToString(), operation.Result,
            operation.Error is { } error ? new(error.ToString(), ErrorText.For(error)) : null);
    }
}
