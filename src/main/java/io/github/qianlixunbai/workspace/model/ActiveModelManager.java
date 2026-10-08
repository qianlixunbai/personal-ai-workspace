package io.github.qianlixunbai.workspace.model;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.policy.PrivacyMode;
import io.github.qianlixunbai.workspace.provider.Provider;
import io.github.qianlixunbai.workspace.provider.ollama.OllamaProvider;
import io.github.qianlixunbai.workspace.task.*;
import java.io.IOException;
import java.util.*;

/** The sole runtime selection, switch and AI reservation owner. No HTTP/bridge mutation in MMF-1. */
public final class ActiveModelManager implements AutoCloseable {
    public enum Fact { TRUE, FALSE, UNKNOWN }
    public enum Phase { RESERVED, QUEUED, RUNNING, DRAINING }
    public record ExecutionModel(String model, String digest, long revision) {
        @Override public String toString() { return "ExecutionModel[private]"; }
    }
    public record Status(boolean configured, Fact installed, Fact loaded, boolean active, boolean ready,
                         int reserved, int queued, int executing, int draining, boolean switching,
                         boolean uncertain, String recoveryGeneration, ErrorCode error) {}
    private final Object gate = new Object();
    // Guard IO is serialized independently. Never enter IO while holding gate or a domain/task lock.
    private final Object operations = new Object();
    private final ProfileResolver profiles;
    private final OllamaProvider provider;
    private final ModelStateStore store;
    private final int capacity, contextBudget;
    private final Set<Reservation> leases = new HashSet<>();
    private String configuredModel, configuredDigest;
    private long revision;
    private ExecutionModel active;
    private boolean switching, closed, uncertain, ready;
    private boolean storeFailed;
    private boolean recoveryLoadRequiresConfirmation;
    private Fact installed = Fact.UNKNOWN, loaded = Fact.UNKNOWN;
    private ErrorCode error;
    private int outbound;
    private String recoveryGeneration = UUID.randomUUID().toString();

    public ActiveModelManager(ProfileResolver profiles, OllamaProvider provider, RuntimeProperties properties, ModelStateStore store) {
        this.profiles = profiles; this.provider = provider; this.store = store;
        capacity = properties.tasks().concurrency() + properties.tasks().queueCapacity();
        var all = List.of(properties.translate(), properties.summarize(), properties.ask());
        contextBudget = all.stream().mapToInt(ModelProfile::contextBudget).max().orElseThrow();
        try {
            if (store == null) throw new IOException("Model state unavailable");
            uncertain = store.unresolved();
            var selection = store.selection();
            if (selection != null) {
                configuredModel = selection.model(); configuredDigest = selection.digest(); revision = selection.selectionRevision();
            } else {
                ModelProfile legacy = all.getFirst();
                String canonical = ModelStateStore.canonicalModel(legacy.model());
                if (canonical == null || all.stream().anyMatch(p -> !canonical.equals(ModelStateStore.canonicalModel(p.model()))
                        || !"ollama".equals(p.provider()) || p.locality() != ModelProfile.Locality.LOCAL))
                    throw new IllegalArgumentException("Ambiguous legacy model selection");
                configuredModel = canonical;
            }
            if (uncertain) error = ErrorCode.MODEL_EXECUTION_UNCERTAIN;
        } catch (IOException invalid) { storeFailed = true; error = ErrorCode.MODEL_STATE_UNAVAILABLE; }
        catch (IllegalArgumentException invalid) { error = ErrorCode.MODEL_CONFIGURATION_INVALID; }
    }

    public Reservation reserve(String profileId, String promptVersion) {
        ensureActive();
        Reservation lease;
        synchronized (gate) {
            checkGate();
            if (!ready || active == null) throw unavailable();
            if (leases.size() >= capacity) throw new WorkspaceException(ErrorCode.QUEUE_FULL, "MODEL_RESERVATION");
            lease = new Reservation(active, bind(profiles.resolve(profileId), active.model()), promptVersion, false);
            leases.add(lease);
        }
        try {
            verifyStore();
            var evidence = provider.admitLocal(lease.model.model(), contextBudget, new Cancellation());
            if (!lease.model.digest().equals(evidence.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
            return lease;
        } catch (RuntimeException rejected) {
            synchronized (gate) { ready = false; error = code(rejected); installed = error == ErrorCode.MODEL_UNAVAILABLE ? Fact.FALSE : Fact.UNKNOWN; }
            lease.close();
            throw rejected;
        }
    }

    /** Only the configured selection may perform bounded bootstrap validation. No selection write. */
    private void ensureActive() {
        String model, digest;
        long selectedRevision;
        synchronized (gate) {
            checkGate();
            if (active != null && ready) return;
            if (recoveryLoadRequiresConfirmation) throw unavailable();
            if (!leases.isEmpty()) throw new WorkspaceException(ErrorCode.MODEL_SWITCH_CONFLICT, "MODEL");
            if (configuredModel == null) throw unavailable();
            switching = true;
            model = configuredModel; digest = configuredDigest == null && active != null ? active.digest() : configuredDigest; selectedRevision = revision;
        }
        try {
            verifyStore();
            var candidate = provider.admitLocal(model, contextBudget, new Cancellation());
            if (digest != null && !digest.equals(candidate.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
            ExecutionModel snapshot = new ExecutionModel(model, candidate.digest(), selectedRevision);
            try (var operation = operation(snapshot, false)) {
                probe(operation);
                synchronized (gate) {
                    if (uncertain || closed) throw unavailable();
                    active = snapshot; ready = true; installed = Fact.TRUE; error = null;
                    // Probe completion proves execution completion, not a fresh /api/ps observation.
                    loaded = Fact.UNKNOWN;
                }
            }
            synchronized (gate) { if (uncertain) throw unavailable(); }
        } catch (RuntimeException failure) {
            synchronized (gate) { ready = false; error = code(failure); installed = error == ErrorCode.MODEL_UNAVAILABLE ? Fact.FALSE : Fact.UNKNOWN; }
            throw failure;
        } finally { synchronized (gate) { switching = false; } }
    }

    private Reservation operation(ExecutionModel snapshot, boolean candidateSwitch) {
        var profile = bind(profiles.resolve("translate.fast"), snapshot.model());
        Reservation operation = new Reservation(snapshot, profile, "model-probe-v1", candidateSwitch);
        synchronized (gate) { leases.add(operation); }
        return operation;
    }
    private void probe(Reservation operation) {
        operation.running();
        ModelProfile profile = operation.profile();
        ExecutionModel snapshot = operation.model();
        String output = provider.execute(new Provider.ProviderExecution(profile, PrivacyMode.LOCAL_ONLY,
                "Reply with OK only.", "OK", List.of(new Provider.ChatMessage("user", "OK")), operation), new Cancellation());
        if (output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > profile.outputBudget() * 4)
            throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_PROBE");
        var checked = provider.admitLocal(snapshot.model(), contextBudget, new Cancellation());
        if (!snapshot.digest().equals(checked.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
    }

    /** Package-private: deterministic fixture contract only until MMF-2 native/cache gates exist. */
    void switchInternal(long expectedRevision, String candidateModel, String candidateDigest) {
        if (!ModelStateStore.validModel(candidateModel) || !ModelStateStore.validDigest(candidateDigest))
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "MODEL_CANDIDATE");
        boolean previousReady;
        Fact previousInstalled, previousLoaded;
        ErrorCode previousError;
        synchronized (gate) {
            checkGate();
            if (revision != expectedRevision) throw new WorkspaceException(ErrorCode.MODEL_SELECTION_REVISION_CONFLICT, "MODEL");
            if (revision == ModelStateStore.MAX_REVISION) throw new WorkspaceException(ErrorCode.MODEL_SELECTION_REVISION_CONFLICT, "MODEL");
            if (!leases.isEmpty()) throw new WorkspaceException(ErrorCode.MODEL_SWITCH_CONFLICT, "MODEL");
            previousReady = ready; previousInstalled = installed; previousLoaded = loaded; previousError = error;
            switching = true;
        }
        try {
            verifyStore();
            var checked = provider.admitLocal(candidateModel, contextBudget, new Cancellation());
            if (!candidateDigest.equals(checked.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
            try (var operation = operation(new ExecutionModel(candidateModel, candidateDigest, expectedRevision + 1), true)) {
                probe(operation);
                synchronized (gate) { if (uncertain || closed) throw unavailable(); }
                var committed = store.commit(expectedRevision, candidateModel, candidateDigest);
                synchronized (gate) {
                    configuredModel = committed.model(); configuredDigest = committed.digest(); revision = committed.selectionRevision();
                    active = new ExecutionModel(configuredModel, configuredDigest, revision);
                    ready = true; installed = Fact.TRUE; loaded = Fact.UNKNOWN; error = null;
                    recoveryLoadRequiresConfirmation = false;
                }
            }
            synchronized (gate) { if (uncertain) throw unavailable(); }
        } catch (IOException failure) {
            synchronized (gate) { storeFailed = true; error = ErrorCode.MODEL_STATE_UNAVAILABLE; }
            throw new WorkspaceException(ErrorCode.MODEL_STATE_UNAVAILABLE, "MODEL_COMMIT");
        } catch (RuntimeException failure) {
            synchronized (gate) {
                if (!recoveryLoadRequiresConfirmation && !uncertain && !storeFailed && !closed) {
                    // Read-only rejection cannot evict the old Active or invalidate its text validation.
                    ready = previousReady; installed = previousInstalled; loaded = previousLoaded; error = previousError;
                } else {
                    // Once candidate send was attempted, old residency is unknown. Never reload here.
                    ready = false; loaded = Fact.UNKNOWN;
                    if (!uncertain && !storeFailed) error = code(failure);
                }
            }
            throw failure;
        } finally { synchronized (gate) { switching = false; } }
    }

    public Provider.ProviderReadiness readiness(String profileId) {
        String model, digest;
        long selectedRevision = -1;
        ExecutionModel observed = null;
        try {
            if (profiles.resolve(profileId).locality() != ModelProfile.Locality.LOCAL)
                throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_READINESS");
            synchronized (gate) {
                checkGate();
                if (recoveryLoadRequiresConfirmation) throw unavailable();
                model = configuredModel; digest = active == null ? configuredDigest : active.digest();
                selectedRevision = revision; observed = active;
            }
            verifyStore();
            var evidence = provider.admitLocal(model, contextBudget, new Cancellation());
            if (digest != null && !digest.equals(evidence.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
            synchronized (gate) {
                checkGate();
                if (revision != selectedRevision || active != observed || recoveryLoadRequiresConfirmation)
                    throw new WorkspaceException(ErrorCode.MODEL_SWITCH_CONFLICT, "MODEL_READINESS");
                installed = Fact.TRUE;
            }
            // MMF-1 preserves legacy metadata availability. No load, inference, task or reservation.
            // Internal ready remains false until the first admission's bounded text validation completes.
            return new Provider.ProviderReadiness("ollama", profileId, true, true, null);
        } catch (WorkspaceException failure) {
            synchronized (gate) {
                if (selectedRevision == revision && observed == active && !switching) {
                    ready = false; error = failure.error().code();
                    installed = error == ErrorCode.MODEL_UNAVAILABLE ? Fact.FALSE : Fact.UNKNOWN;
                }
            }
            return new Provider.ProviderReadiness("ollama", profileId, failure.error().code() == ErrorCode.MODEL_UNAVAILABLE,
                    false, failure.error());
        }
    }
    public Status status() {
        synchronized (gate) {
            return new Status(configuredModel != null, installed, loaded, active != null, ready && !switching && !uncertain && !storeFailed && !closed,
                    count(Phase.RESERVED), count(Phase.QUEUED), count(Phase.RUNNING), count(Phase.DRAINING),
                    switching, uncertain, recoveryGeneration, error);
        }
    }
    private int count(Phase phase) { return (int) leases.stream().filter(l -> l.phase == phase).count(); }
    private void checkGate() {
        if (uncertain) throw new WorkspaceException(ErrorCode.MODEL_EXECUTION_UNCERTAIN, "MODEL");
        if (closed || storeFailed || configuredModel == null) throw unavailable();
        if (switching) throw new WorkspaceException(ErrorCode.MODEL_SWITCH_CONFLICT, "MODEL");
    }
    private WorkspaceException unavailable() {
        return new WorkspaceException(error == null ? ErrorCode.MODEL_UNAVAILABLE : error, "MODEL");
    }
    private void verifyStore() {
        try { store.selection(); }
        catch (IOException failure) {
            synchronized (gate) { storeFailed = true; ready = false; error = ErrorCode.MODEL_STATE_UNAVAILABLE; }
            throw new WorkspaceException(ErrorCode.MODEL_STATE_UNAVAILABLE, "MODEL_STATE");
        }
    }
    private static ModelProfile bind(ModelProfile p, String model) {
        // Public profile version stays legacy-compatible until MMF-2's cross-repository cache gate.
        return new ModelProfile(p.id(), "ollama", model, p.locality(), p.version(), p.contextBudget(), p.outputBudget(), p.temperature(), p.maxTextCharacters());
    }
    private static ErrorCode code(RuntimeException error) {
        return error instanceof WorkspaceException controlled ? controlled.error().code() : ErrorCode.INTERNAL_ERROR;
    }

    public final class Reservation implements TaskManager.Lease {
        private final ExecutionModel model;
        private final ModelProfile profile;
        private final String promptVersion;
        private final boolean candidateSwitch;
        private Phase phase = Phase.RESERVED;
        private boolean sent, complete, released, transferred, reportedUncertainty;
        private Reservation(ExecutionModel model, ModelProfile profile, String promptVersion, boolean candidateSwitch) {
            this.model = model; this.profile = profile; this.promptVersion = promptVersion; this.candidateSwitch = candidateSwitch;
        }
        public ExecutionModel model() { return model; }
        public ModelProfile profile() { return profile; }
        public String promptVersion() { return promptVersion; }
        public void transferToTask() { synchronized (gate) { transferred = true; phase = Phase.QUEUED; } }
        public void running() { synchronized (gate) { if (phase != Phase.DRAINING) phase = Phase.RUNNING; } }
        public void cancelled() { synchronized (gate) { phase = Phase.DRAINING; } }
        public void closeUnlessTransferred() { synchronized (gate) { if (transferred) return; } close(); }

        /** Provider calls at its final boundary, immediately before sendAsync. */
        public void beforeSend() {
            synchronized (operations) {
                synchronized (gate) {
                    if (released || closed || storeFailed || uncertain) throw unavailable();
                    if (sent && !complete) throw new WorkspaceException(ErrorCode.MODEL_EXECUTION_UNCERTAIN, "MODEL");
                }
                try { store.armGuard(); }
                catch (IOException failure) {
                    synchronized (gate) { storeFailed = true; ready = false; error = ErrorCode.MODEL_STATE_UNAVAILABLE; }
                    throw new WorkspaceException(ErrorCode.MODEL_STATE_UNAVAILABLE, "EXECUTION_GUARD");
                }
                synchronized (gate) {
                    if (candidateSwitch) {
                        // The guarded transport boundary is the first possible candidate load/eviction.
                        ready = false; loaded = Fact.UNKNOWN; recoveryLoadRequiresConfirmation = true;
                    }
                }
                if (!sent) outbound++;
                sent = true; complete = false;
            }
        }
        /** Trusted protocol completion is independent of output validation or user task cancellation. */
        public void trustedCompletion() { synchronized (operations) { complete = true; } }
        public void admissionFailed(ErrorCode code) {
            synchronized (gate) { ready = false; installed = code == ErrorCode.MODEL_UNAVAILABLE ? Fact.FALSE : Fact.UNKNOWN; error = code; }
        }
        public void providerExited() {
            synchronized (operations) { reportUncertainty(); }
        }
        private void reportUncertainty() {
            if (sent && !complete && !reportedUncertainty) {
                reportedUncertainty = true;
                synchronized (gate) {
                    uncertain = true; ready = false; error = ErrorCode.MODEL_EXECUTION_UNCERTAIN;
                    recoveryGeneration = UUID.randomUUID().toString();
                }
            }
        }
        @Override public void close() {
            synchronized (operations) {
                if (released) return;
                released = true;
                if (sent) outbound--;
                reportUncertainty();
                boolean safe;
                synchronized (gate) { safe = !uncertain && !closed && !storeFailed && !recoveryLoadRequiresConfirmation; }
                if (sent && outbound == 0 && safe) {
                    try { store.clearGuard(); }
                    catch (IOException failure) {
                        synchronized (gate) {
                            uncertain = true; ready = false; error = ErrorCode.MODEL_EXECUTION_UNCERTAIN;
                            recoveryGeneration = UUID.randomUUID().toString();
                        }
                    }
                }
                // Retain the logical lease through guard IO; switch cannot observe an exit gap.
                synchronized (gate) { leases.remove(this); }
            }
        }
        @Override public String toString() { return "ModelReservation[private]"; }
    }
    @Override public void close() throws IOException {
        synchronized (operations) {
            synchronized (gate) { closed = true; ready = false; }
            if (store != null) store.close();
        }
    }
}
