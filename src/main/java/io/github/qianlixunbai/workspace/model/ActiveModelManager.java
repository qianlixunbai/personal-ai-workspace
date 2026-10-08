package io.github.qianlixunbai.workspace.model;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.policy.PrivacyMode;
import io.github.qianlixunbai.workspace.provider.Provider;
import io.github.qianlixunbai.workspace.provider.ollama.OllamaProvider;
import io.github.qianlixunbai.workspace.task.*;
import java.io.IOException;
import java.util.*;

/** The sole runtime selection, switch, recovery and AI reservation owner. */
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
    public enum Action { SWITCH, RELEASE_OLD_THEN_SWITCH, RELEASE, VALIDATE, RECOVER }
    public record CatalogEntry(String handle, String model, String digest, long contextLimit,
                               boolean completion, boolean localSourceVerified, boolean providerDeclaredVision) {}
    public record Catalog(long selectionRevision, List<CatalogEntry> models) {}
    public record Intent(Action action, String catalogHandle, String candidateModel, String candidateDigest,
                         long expectedSelectionRevision, String expectedActiveModel, String expectedActiveDigest,
                         String recoveryGeneration, boolean externalConfirmed) {}
    public record ManagementStatus(String configuredModel, String configuredDigest, String activeModel, String activeDigest,
                                   long selectionRevision, Fact installed, Fact loaded, boolean ready,
                                   int reserved, int queued, int executing, int draining, boolean switching, boolean uncertain,
                                   String recoveryGeneration, boolean validationRequired, ErrorCode error) {}
    private Map<String, CatalogEntry> catalogHandles = Map.of();
    private long catalogRevision = -1;
    private final java.time.Duration managementBudget;
    private String configuredModel, configuredDigest;
    private long revision;
    private ExecutionModel active;
    private boolean switching, closed, uncertain, ready;
    private boolean storeFailed;
    private boolean recoveryLoadRequiresConfirmation;
    private Fact installed = Fact.UNKNOWN, loaded = Fact.UNKNOWN;
    private ErrorCode error;
    private int outbound;
    private String cacheEpoch = newCacheEpoch();
    private boolean modelManagementEnabled;
    public record ReadinessSnapshot(Provider.ProviderReadiness readiness, ModelProfile.PublicProfile profile) {}
    private static String newCacheEpoch() { return "am1-" + UUID.randomUUID(); }
    private String recoveryGeneration = UUID.randomUUID().toString();

    public ActiveModelManager(ProfileResolver profiles, OllamaProvider provider, RuntimeProperties properties, ModelStateStore store) {
        this.profiles = profiles; this.provider = provider; this.store = store;
        capacity = properties.tasks().concurrency() + properties.tasks().queueCapacity();
        managementBudget = properties.tasks().executionTimeout().compareTo(java.time.Duration.ofSeconds(150)) < 0
                ? properties.tasks().executionTimeout() : java.time.Duration.ofSeconds(150);
        var all = List.of(properties.translate(), properties.summarize(), properties.ask());
        contextBudget = all.stream().mapToInt(ModelProfile::contextBudget).max().orElseThrow();
        try {
            if (store == null) throw new IOException("Model state unavailable");
            uncertain = store.unresolved();
            var selection = store.selection();
            if (selection != null) {
                configuredModel = selection.model(); configuredDigest = selection.digest(); revision = selection.selectionRevision();
                modelManagementEnabled = true; // Durable explicit selection preserves the legacy guard across restart.
            } else {
                ModelProfile legacy = all.getFirst();
                String canonical = ModelStateStore.canonicalModel(legacy.model());
                if (canonical == null || all.stream().anyMatch(p -> !canonical.equals(ModelStateStore.canonicalModel(p.model()))
                        || !"ollama".equals(p.provider()) || p.locality() != ModelProfile.Locality.LOCAL))
                    throw new IllegalArgumentException("Ambiguous legacy model selection");
                configuredModel = canonical;
            }
            var validation = store.validationRequired();
            if (validation != null) {
                checkValidationBinding(validation);
                configuredDigest = validation.digest(); // Also pins revision-zero legacy selection after recovery.
                recoveryLoadRequiresConfirmation = true;
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
            lease = new Reservation(active, bind(profiles.resolve(profileId), active.model(), cacheEpoch), promptVersion, false);
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
                    active = snapshot; cacheEpoch = newCacheEpoch(); ready = true; installed = Fact.TRUE; error = null;
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
        var profile = bind(profiles.resolve("translate.fast"), snapshot.model(), cacheEpoch);
        Reservation operation = new Reservation(snapshot, profile, "model-probe-v1", candidateSwitch);
        synchronized (gate) { leases.add(operation); }
        return operation;
    }
    private void probe(Reservation operation) { probe(operation, new Cancellation()); }
    private void probe(Reservation operation, Cancellation cancellation) {
        operation.running();
        ModelProfile profile = operation.profile();
        ExecutionModel snapshot = operation.model();
        String output = provider.execute(new Provider.ProviderExecution(profile, PrivacyMode.LOCAL_ONLY,
                "Reply with OK only.", "OK", List.of(new Provider.ChatMessage("user", "OK")), operation), cancellation);
        if (output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > profile.outputBudget() * 4)
            throw new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MODEL_PROBE");
        var checked = provider.admitLocal(snapshot.model(), contextBudget, cancellation);
        if (!snapshot.digest().equals(checked.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
    }

    /** Package-private: deterministic fixture contract only until MMF-2 native/cache gates exist. */
    void switchInternal(long expectedRevision, String candidateModel, String candidateDigest) {
        selectionOperation(expectedRevision, candidateModel, candidateDigest, null);
    }
    private void selectionOperation(long expectedRevision, String candidateModel, String candidateDigest, Intent intent) {
        if (!ModelStateStore.validModel(candidateModel) || !ModelStateStore.validDigest(candidateDigest))
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "MODEL_CANDIDATE");
        boolean previousReady;
        Fact previousInstalled, previousLoaded;
        ErrorCode previousError;
        synchronized (gate) {
            if (intent == null) checkGate(); else validateIntent(intent, false);
            if (revision != expectedRevision) throw new WorkspaceException(ErrorCode.MODEL_SELECTION_REVISION_CONFLICT, "MODEL");
            if (revision == ModelStateStore.MAX_REVISION) throw new WorkspaceException(ErrorCode.MODEL_SELECTION_REVISION_CONFLICT, "MODEL");
            if (!leases.isEmpty()) throw new WorkspaceException(ErrorCode.MODEL_SWITCH_CONFLICT, "MODEL");
            modelManagementEnabled = true; // Legacy cache clients must be blocked before any switching can begin.
            previousReady = ready; previousInstalled = installed; previousLoaded = loaded; previousError = error;
            switching = true;
        }
        try (var budget = new Budget(managementBudget)) {
            verifyStore();
            var checked = provider.admitLocal(candidateModel, contextBudget, budget.cancellation);
            if (!candidateDigest.equals(checked.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
            if (intent != null && intent.action() == Action.RELEASE_OLD_THEN_SWITCH) {
                try (var release = operation(new ExecutionModel(intent.expectedActiveModel(), intent.expectedActiveDigest(), expectedRevision), true)) {
                    release.running(); provider.release(release, contextBudget, budget.cancellation);
                }
                synchronized (gate) { if (uncertain || closed) throw unavailable(); }
            }
            try (var operation = operation(new ExecutionModel(candidateModel, candidateDigest, expectedRevision + 1), true)) {
                probe(operation, budget.cancellation);
                budget.cancellation.check();
                synchronized (gate) { if (uncertain || closed) throw unavailable(); }
                ModelStateStore.Selection committed;
                try {
                    committed = intent != null && intent.action() == Action.VALIDATE ? null : store.commit(expectedRevision, candidateModel, candidateDigest);
                    store.clearValidation();
                } catch (IOException failure) {
                    // Record persistence failure before lease close decides whether its guard is safely removable.
                    synchronized (gate) { storeFailed = true; ready = false; error = ErrorCode.MODEL_STATE_UNAVAILABLE; }
                    throw failure;
                }
                synchronized (gate) {
                    if (committed != null) {
                        configuredModel = committed.model(); configuredDigest = committed.digest(); revision = committed.selectionRevision();
                    }
                    active = new ExecutionModel(configuredModel, configuredDigest, revision);
                    if (committed == null) active = new ExecutionModel(candidateModel, candidateDigest, revision);
                    cacheEpoch = newCacheEpoch();
                    ready = true; installed = Fact.TRUE; loaded = Fact.UNKNOWN; error = null;
                    recoveryLoadRequiresConfirmation = false;
                }
            }
            synchronized (gate) { if (uncertain) throw unavailable(); }
        } catch (IOException failure) {
            synchronized (gate) { storeFailed = true; error = ErrorCode.MODEL_STATE_UNAVAILABLE; }
            throw new WorkspaceException(ErrorCode.MODEL_STATE_UNAVAILABLE, "MODEL_COMMIT");
        } catch (RuntimeException failure) {
            if (code(failure) == ErrorCode.TASK_CANCELLED) failure = new WorkspaceException(ErrorCode.TASK_TIMEOUT, "MODEL");
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

    public Catalog catalog() {
        long expected;
        synchronized (gate) { expected = revision; }
        verifyStore();
        try (var budget = new Budget(managementBudget)) {
            var entries = provider.catalog(contextBudget, budget.cancellation).stream().map(e ->
                    new CatalogEntry(UUID.randomUUID().toString(), e.model(), e.digest(), e.contextLimit(), true, true, e.providerDeclaredVision())).toList();
            synchronized (gate) {
                if (switching || revision != expected) throw new WorkspaceException(ErrorCode.MODEL_SWITCH_CONFLICT, "MODEL_CATALOG");
                var handles = new HashMap<String, CatalogEntry>();
                for (var entry : entries) handles.put(entry.handle(), entry);
                catalogHandles = Map.copyOf(handles); catalogRevision = expected;
                return new Catalog(expected, entries);
            }
        }
    }

    /** Inspection is metadata-only, even while the uncertainty guard is closed. */
    public ManagementStatus managementStatus() {
        String model, digest; long expected; ExecutionModel observed;
        synchronized (gate) { model = configuredModel; digest = configuredDigest == null && active != null ? active.digest() : configuredDigest; expected = revision; observed = active; }
        if (model != null) {
            Fact resident = Fact.UNKNOWN, present = Fact.UNKNOWN; ErrorCode failed = null;
            try (var budget = new Budget(managementBudget)) {
                verifyStore();
                var evidence = provider.admitLocal(model, contextBudget, budget.cancellation);
                if (digest != null && !digest.equals(evidence.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
                digest = evidence.digest(); present = Fact.TRUE;
                resident = provider.residency(model, digest, budget.cancellation);
            } catch (WorkspaceException failure) {
                failed = failure.error().code(); present = failed == ErrorCode.MODEL_UNAVAILABLE ? Fact.FALSE : present;
            }
            synchronized (gate) {
                if (revision == expected && active == observed && !switching) {
                    installed = present; loaded = resident;
                    if (failed != null) { ready = false; if (!uncertain && !storeFailed) error = failed; }
                }
            }
        }
        synchronized (gate) {
            return new ManagementStatus(configuredModel, revision == expected && Objects.equals(configuredModel, model) ? digest : configuredDigest,
                    active == null ? null : active.model(), active == null ? null : active.digest(), revision, installed, loaded,
                    ready && !switching && !uncertain && !storeFailed && !closed, count(Phase.RESERVED), count(Phase.QUEUED),
                    count(Phase.RUNNING), count(Phase.DRAINING), switching, uncertain, recoveryGeneration,
                    recoveryLoadRequiresConfirmation || active == null || !ready, error);
        }
    }

    /** All mutations enter the same gate as reservations. Native confirmation is owned by Desktop. */
    public ManagementStatus manage(Intent intent) {
        if (intent.action() == Action.SWITCH || intent.action() == Action.RELEASE_OLD_THEN_SWITCH || intent.action() == Action.VALIDATE) {
            selectionOperation(intent.expectedSelectionRevision(), intent.candidateModel(), intent.candidateDigest(), intent);
        } else {
            synchronized (gate) { validateIntent(intent, intent.action() == Action.RECOVER); switching = true; }
            try (var budget = new Budget(managementBudget)) {
                verifyStore();
                var checked = provider.admitLocal(intent.candidateModel(), contextBudget, budget.cancellation);
                if (!intent.candidateDigest().equals(checked.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
                if (intent.action() == Action.RELEASE) {
                    boolean selected;
                    synchronized (gate) { selected = Objects.equals(configuredModel, intent.candidateModel()); }
                    try (var release = operation(new ExecutionModel(intent.candidateModel(), intent.candidateDigest(), revision), selected)) {
                        release.running(); provider.release(release, contextBudget, budget.cancellation);
                        synchronized (gate) {
                            if (selected) { ready = false; recoveryLoadRequiresConfirmation = true; loaded = Fact.UNKNOWN; }
                        }
                    }
                } else {
                    synchronized (operations) {
                        // Keep the logical switch gate closed across metadata validation and private guard IO.
                        synchronized (gate) { validateRecovery(intent); }
                        budget.cancellation.check();
                        store.requireValidation(revision, intent.candidateModel(), intent.candidateDigest());
                        store.recoverGuard();
                        synchronized (gate) {
                            uncertain = false; ready = false; recoveryLoadRequiresConfirmation = true;
                            cacheEpoch = newCacheEpoch(); recoveryGeneration = UUID.randomUUID().toString(); error = null;
                        }
                    }
                }
            } catch (IOException failure) {
                synchronized (gate) { uncertain = true; ready = false; error = ErrorCode.MODEL_EXECUTION_UNCERTAIN; }
                throw new WorkspaceException(ErrorCode.MODEL_EXECUTION_UNCERTAIN, "MODEL_RECOVERY");
            } finally { synchronized (gate) { switching = false; } }
        }
        synchronized (gate) {
            // Mutation response is a snapshot, not another provider operation after the bounded mutation.
            return managementSnapshot(configuredDigest == null && active != null ? active.digest() : configuredDigest);
        }
    }

    private void validateIntent(Intent intent, boolean recovery) {
        if (closed || storeFailed) throw unavailable();
        if (switching || !leases.isEmpty()) throw new WorkspaceException(ErrorCode.MODEL_SWITCH_CONFLICT, "MODEL");
        if (!recovery && uncertain) throw new WorkspaceException(ErrorCode.MODEL_EXECUTION_UNCERTAIN, "MODEL");
        if (revision != intent.expectedSelectionRevision()) throw new WorkspaceException(ErrorCode.MODEL_SELECTION_REVISION_CONFLICT, "MODEL");
        var entry = catalogHandles.get(intent.catalogHandle());
        if (catalogRevision != revision || entry == null || !entry.model().equals(intent.candidateModel()) || !entry.digest().equals(intent.candidateDigest()))
            throw new WorkspaceException(ErrorCode.MODEL_CATALOG_STALE, "MODEL");
        if (!Objects.equals(intent.expectedActiveModel(), active == null ? null : active.model())
                || !Objects.equals(intent.expectedActiveDigest(), active == null ? null : active.digest()))
            throw new WorkspaceException(ErrorCode.MODEL_SELECTION_REVISION_CONFLICT, "MODEL");
        boolean external = intent.action() == Action.RELEASE || intent.action() == Action.RELEASE_OLD_THEN_SWITCH || recovery;
        if (intent.externalConfirmed() != external || !recovery && intent.recoveryGeneration() != null)
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "MODEL");
        if (intent.action() == Action.RELEASE_OLD_THEN_SWITCH && (active == null || active.model().equals(intent.candidateModel())))
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "MODEL");
        if (intent.action() == Action.VALIDATE || recovery) {
            String bound = configuredDigest == null && active != null ? active.digest() : configuredDigest;
            if (!Objects.equals(configuredModel, intent.candidateModel()) || bound != null && !bound.equals(intent.candidateDigest()))
                throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
        }
        if (recovery) validateRecovery(intent);
    }
    private void validateRecovery(Intent intent) {
        if (!uncertain || !leases.isEmpty() || !recoveryGeneration.equals(intent.recoveryGeneration()) || revision != intent.expectedSelectionRevision())
            throw new WorkspaceException(ErrorCode.MODEL_EXECUTION_UNCERTAIN, "MODEL_RECOVERY");
    }
    private ManagementStatus managementSnapshot(String digest) {
        return new ManagementStatus(configuredModel, digest, active == null ? null : active.model(), active == null ? null : active.digest(),
                revision, installed, loaded, ready && !switching && !uncertain && !storeFailed && !closed,
                count(Phase.RESERVED), count(Phase.QUEUED), count(Phase.RUNNING), count(Phase.DRAINING), switching, uncertain,
                recoveryGeneration, recoveryLoadRequiresConfirmation || active == null || !ready, error);
    }
    private static final class Budget implements AutoCloseable {
        final Cancellation cancellation = new Cancellation();
        private final Thread timer;
        Budget(java.time.Duration duration) {
            timer = Thread.ofVirtual().start(() -> {
                try { Thread.sleep(duration); cancellation.cancel(); }
                catch (InterruptedException finished) { Thread.currentThread().interrupt(); }
            });
        }
        public void close() { timer.interrupt(); }
    }

    public Provider.ProviderReadiness readiness(String profileId) {
        return cacheReadiness(profileId, false).readiness();
    }
    /** Metadata only; availability and public identity are captured together at the final gate check. */
    public ReadinessSnapshot cacheReadiness(String profileId, boolean legacyBrowser) {
        String model, digest, observedEpoch = null;
        long selectedRevision = -1;
        ExecutionModel observed = null;
        try {
            if (profiles.resolve(profileId).locality() != ModelProfile.Locality.LOCAL)
                throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_READINESS");
            synchronized (gate) {
                checkGate();
                if (recoveryLoadRequiresConfirmation || legacyBrowser && modelManagementEnabled) throw unavailable();
                observedEpoch = cacheEpoch;
                model = configuredModel; digest = active == null ? configuredDigest : active.digest();
                selectedRevision = revision; observed = active;
            }
            verifyStore();
            var evidence = provider.admitLocal(model, contextBudget, new Cancellation());
            if (digest != null && !digest.equals(evidence.digest())) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
            synchronized (gate) {
                checkGate();
                if (revision != selectedRevision || active != observed || !cacheEpoch.equals(observedEpoch)
                        || recoveryLoadRequiresConfirmation || legacyBrowser && modelManagementEnabled)
                    throw new WorkspaceException(ErrorCode.MODEL_SWITCH_CONFLICT, "MODEL_READINESS");
                installed = Fact.TRUE;
                // First-use metadata availability does not depend on the later text-validation probe.
                return new ReadinessSnapshot(new Provider.ProviderReadiness("ollama", profileId, true, true, null),
                        bind(profiles.resolve(profileId), model, cacheEpoch).publicInfo());
            }
        } catch (WorkspaceException failure) {
            synchronized (gate) {
                if (selectedRevision == revision && observed == active && !switching) {
                    ready = false; error = failure.error().code();
                    installed = error == ErrorCode.MODEL_UNAVAILABLE ? Fact.FALSE : Fact.UNKNOWN;
                }
            }
            return new ReadinessSnapshot(new Provider.ProviderReadiness("ollama", profileId, failure.error().code() == ErrorCode.MODEL_UNAVAILABLE,
                    false, failure.error()), null);
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
        try {
            store.selection();
            // Startup/publication bind the marker; immutable bytes/key detect subsequent external changes.
            // Do not compare an IO snapshot with a later in-memory selection publication during inspection.
            store.validationRequired();
        }
        catch (IOException failure) {
            synchronized (gate) { storeFailed = true; ready = false; error = ErrorCode.MODEL_STATE_UNAVAILABLE; }
            throw new WorkspaceException(ErrorCode.MODEL_STATE_UNAVAILABLE, "MODEL_STATE");
        }
    }
    private void checkValidationBinding(ModelStateStore.ValidationRequired validation) throws IOException {
        if (validation.selectionRevision() != revision || !Objects.equals(validation.model(), configuredModel)
                || configuredDigest != null && !configuredDigest.equals(validation.digest()))
            throw new IOException("Validation requirement does not match selection");
    }
    private static ModelProfile bind(ModelProfile p, String model, String epoch) {
        // Effective public version is frozen at admission; configured versions stay private.
        return new ModelProfile(p.id(), "ollama", model, p.locality(), epoch, p.contextBudget(), p.outputBudget(), p.temperature(), p.maxTextCharacters());
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
                try {
                    if (candidateSwitch) {
                        String selectedModel, selectedDigest; long selectedRevision;
                        synchronized (gate) {
                            selectedModel = configuredModel; selectedRevision = revision;
                            selectedDigest = configuredDigest == null && active != null ? active.digest() : configuredDigest;
                        }
                        if (selectedDigest == null) selectedDigest = selectedModel.equals(model.model()) ? model.digest()
                                : provider.admitLocal(selectedModel, contextBudget, new Cancellation()).digest();
                        store.requireValidation(selectedRevision, selectedModel, selectedDigest);
                    }
                    store.armGuard();
                }
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
                synchronized (gate) { safe = !uncertain && !closed && !storeFailed; }
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
