using System;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal interface IModelConfirmation { Task<bool> ConfirmAsync(ModelIntent intent, CancellationToken cancellation); }
internal sealed record WorkspaceModelSnapshot(ModelStatus Status, ModelCatalog? Catalog, WorkspaceSafeError? Error);
internal sealed record WorkspaceModelOutcome(string Outcome, WorkspaceModelSnapshot? Snapshot);

// Narrow model authority. No general approval registry, credentials or mutable React choice.
internal sealed class WorkspaceModels(RuntimeClient runtime, IModelConfirmation confirmation)
{
    internal static readonly TimeSpan ConfirmationLifetime = TimeSpan.FromSeconds(60);
    private sealed class Authority { internal readonly CancellationTokenSource Lifetime = new(); internal WorkspaceModelSnapshot? Snapshot; }
    private readonly object sync = new();
    private string? session;
    private Authority? authority;
    private CancellationTokenSource? pending;
    internal void BeginSession(string value)
    { lock (sync) { if (authority is not null) throw new InvalidOperationException(); session = value; authority = new(); } }
    internal void EndSession(string value)
    {
        lock (sync) {
            if (session != value || authority is null) return;
            var old = authority; authority = null; session = null; old.Snapshot = null;
            old.Lifetime.Cancel(); old.Lifetime.Dispose(); pending?.Cancel();
        }
    }
    private Authority Require(string value)
    { if (session != value || authority is null) throw new OperationCanceledException(); return authority; }
    private void Current(string value, Authority known, CancellationToken token)
    { token.ThrowIfCancellationRequested(); if (!ReferenceEquals(Require(value), known)) throw new OperationCanceledException(); }
    internal async Task<WorkspaceModelSnapshot> InspectAsync(string value, CancellationToken token, bool recovery = false)
    {
        Authority known;
        lock (sync) { known = Require(value); token.ThrowIfCancellationRequested(); if (pending is not null) throw new DesktopException(DesktopError.ModelSwitchConflict); }
        var status = await runtime.GetModelStatusAsync(token, recovery);
        ModelCatalog? catalog = null; WorkspaceSafeError? error = null;
        try {
            catalog = await runtime.GetModelCatalogAsync(token);
            if (catalog.SelectionRevision != status.SelectionRevision) throw new DesktopException(DesktopError.ModelSelectionRevisionConflict);
        } catch (DesktopException failure) { catalog = null; error = new(failure.Error.ToString(), ErrorText.For(failure.Error)); }
        lock (sync) { Current(value, known, token); known.Snapshot = new(status, catalog, error); return known.Snapshot; }
    }
    internal async Task<WorkspaceModelOutcome> MutateAsync(string value, string action, string handle, long revision, string? generation, CancellationToken token)
    {
        Authority known; ModelIntent frozen; CancellationTokenSource intent;
        long started = Stopwatch.GetTimestamp();
        lock (sync) {
            known = Require(value); token.ThrowIfCancellationRequested();
            if (pending is not null) throw new DesktopException(DesktopError.ModelSwitchConflict);
            var snapshot = known.Snapshot ?? throw new DesktopException(DesktopError.ModelCatalogStale);
            if (revision != snapshot.Status.SelectionRevision || snapshot.Catalog?.SelectionRevision != revision)
                throw new DesktopException(DesktopError.ModelSelectionRevisionConflict);
            var candidate = snapshot.Catalog.Models.SingleOrDefault(x => x.Handle == handle) ?? throw new DesktopException(DesktopError.ModelCatalogStale);
            if (!Enum.TryParse<ModelAction>(action, out var kind) || !Enum.IsDefined(kind) || kind.ToString() != action)
                throw new DesktopException(DesktopError.InvalidRequest);
            if (kind == ModelAction.RECOVER ? generation != snapshot.Status.RecoveryGeneration || !snapshot.Status.Uncertain : generation is not null)
                throw new DesktopException(DesktopError.ModelExecutionUncertain);
            if (kind is ModelAction.RECOVER or ModelAction.VALIDATE && (candidate.Model != snapshot.Status.ConfiguredModel
                || snapshot.Status.ConfiguredDigest is { } digest && candidate.Digest != digest)) throw new DesktopException(DesktopError.ModelIdentityChanged);
            if (kind == ModelAction.RELEASE_OLD_THEN_SWITCH && (snapshot.Status.ActiveModel is null || snapshot.Status.ActiveModel == candidate.Model))
                throw new DesktopException(DesktopError.InvalidRequest);
            frozen = new(kind, candidate.Handle, candidate.Model, candidate.Digest, revision,
                snapshot.Status.ActiveModel, snapshot.Status.ActiveDigest, generation,
                kind is ModelAction.RELEASE or ModelAction.RELEASE_OLD_THEN_SWITCH or ModelAction.RECOVER);
            intent = CancellationTokenSource.CreateLinkedTokenSource(token, known.Lifetime.Token);
            intent.CancelAfter(ConfirmationLifetime); pending = intent;
        }
        try {
            bool allowed;
            try { allowed = await confirmation.ConfirmAsync(frozen, intent.Token).WaitAsync(intent.Token); }
            catch (OperationCanceledException) { lock (sync) { Current(value, known, token); return new("CANCELLED", null); } }
            Task<ModelStatus> mutation;
            lock (sync) {
                Current(value, known, token);
                if (!allowed || intent.IsCancellationRequested || Stopwatch.GetElapsedTime(started) >= ConfirmationLifetime) return new("CANCELLED", null);
                // Consume this captured authority and start exactly one POST under the session lock.
                known.Snapshot = null;
                mutation = runtime.MutateModelAsync(frozen, token);
            }
            try {
                var status = await mutation;
                lock (sync) { Current(value, known, token); return new("COMPLETED", new(status, null, null)); }
            } catch (DesktopException failure) when (failure.Error == DesktopError.OutcomeUnknown) {
                Task<ModelStatus> read;
                lock (sync) { Current(value, known, token); read = runtime.GetModelStatusAsync(token); }
                try {
                    var status = await read;
                    lock (sync) { Current(value, known, token); return new("UNKNOWN", new(status, null, null)); }
                } catch (DesktopException) { lock (sync) { Current(value, known, token); return new("UNKNOWN", null); } }
            }
        } finally {
            lock (sync) { if (ReferenceEquals(pending, intent)) pending = null; }
            intent.Cancel(); intent.Dispose();
        }
    }
}
