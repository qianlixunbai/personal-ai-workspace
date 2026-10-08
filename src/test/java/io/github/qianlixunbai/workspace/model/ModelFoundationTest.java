package io.github.qianlixunbai.workspace.model;

import com.sun.net.httpserver.*;
import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.capability.TextTaskSubmission;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.conversation.*;
import io.github.qianlixunbai.workspace.memory.MemoryStore;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.provider.ollama.*;
import io.github.qianlixunbai.workspace.security.LocalClientToken;
import io.github.qianlixunbai.workspace.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelFoundationTest {
    @TempDir Path root;
    static final String MODEL = "test-model:latest", CANDIDATE = "candidate:latest", DIGEST = "a".repeat(64), NEXT = "c".repeat(64);
    static final JsonMapper JSON = JsonMapper.builder().build();
    Path state() { return root.resolve("model-state"); }
    Path token() { return root.resolve("credentials/client-token"); }
    Path data() { return root.resolve("data"); }
    ModelStateStore store() throws Exception { return new ModelStateStore(state(), token(), data()); }
    static void code(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(WorkspaceException.class, action).error().code());
    }

    @Test void privateAtomicSelectionCasStrictRecoveryAndIndependentGuard() throws Exception {
        try (var writer = store()) {
            assertNull(writer.selection()); assertFalse(writer.unresolved());
            if (Files.readAttributes(state(), java.nio.file.attribute.BasicFileAttributes.class).fileKey() == null)
                assertThrows(java.io.IOException.class, () -> Files.move(state(), root.resolve("moved-model-state")));
            assertThrows(Exception.class, this::store); // Stable lock excludes another writer.
            assertEquals(1, writer.commit(0, MODEL, DIGEST).selectionRevision());
            assertThrows(java.io.IOException.class, () -> writer.commit(0, CANDIDATE, NEXT));
            assertEquals(MODEL, writer.selection().model());
            writer.armGuard(); writer.clearGuard();
            assertFalse(Files.exists(state().resolve("execution-guard.json")));
            writer.armGuard(); // A crash/restart is not completion evidence.
        }
        String committed = Files.readString(state().resolve("active-model.json"));
        try (var restarted = store()) {
            assertEquals(1, restarted.selection().selectionRevision()); assertTrue(restarted.unresolved());
            assertThrows(java.io.IOException.class, restarted::clearGuard);
            Files.writeString(state().resolve("active-model.pending"), committed);
            assertEquals(MODEL, restarted.selection().model()); // Pending never supersedes committed state.
            assertThrows(java.io.IOException.class, () -> restarted.commit(1, CANDIDATE, NEXT));
        }
        Files.delete(state().resolve("active-model.json"));
        assertThrows(java.io.IOException.class, this::store); // Pending blocks YAML bootstrap.
        Files.delete(state().resolve("active-model.pending"));
        Path selection = state().resolve("active-model.json");
        for (String damaged : List.of(committed.replace("\"version\":1", "\"version\":2"), committed + " {}",
                committed.replace("\"version\":1", "\"version\":1,\"version\":1"),
                committed.replace("\"selectionRevision\":1", "\"selectionRevision\":1.5"),
                committed.replace("\"selectionRevision\":1", "\"selectionRevision\":9007199254740992"),
                committed.replace("\"provider\":\"ollama\"", "\"provider\":null"), committed.replace("{", "{\"extra\":1,"),
                "\uFEFF" + committed, "x".repeat(4097))) {
            Files.writeString(selection, damaged);
            assertThrows(java.io.IOException.class, this::store);
        }
        Files.write(selection, new byte[]{(byte) 0xc3, 0x28});
        assertThrows(java.io.IOException.class, this::store);
        Files.writeString(selection, committed);
        try (var writer = store()) {
            if (Files.readAttributes(selection, java.nio.file.attribute.BasicFileAttributes.class).fileKey() == null) {
                assertThrows(java.io.IOException.class, () -> Files.writeString(selection, committed.replace(MODEL, CANDIDATE)));
                assertEquals(MODEL, writer.selection().model()); // Windows OS handle rejects the external write.
            } else {
                Files.writeString(selection, committed.replace(MODEL, CANDIDATE));
                assertThrows(java.io.IOException.class, writer::selection);
            }
        }
        assertThrows(java.io.IOException.class, () -> new ModelStateStore(data().resolve("model-state"), token(), data()));
        // A directory in place of selection is rejected without following it.
        Files.delete(selection); Files.createDirectory(selection);
        assertThrows(java.io.IOException.class, this::store);
        Files.delete(selection);
        var acl = Files.getFileAttributeView(state(), java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            var original = acl.getAcl();
            try {
                var readOnly = java.nio.file.attribute.AclEntry.newBuilder(original.getFirst())
                        .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA).build();
                acl.setAcl(List.of(readOnly));
                assertThrows(java.io.IOException.class, this::store);
            } finally { acl.setAcl(original); }
        } else {
            var original = Files.getPosixFilePermissions(state());
            try {
                Files.setPosixFilePermissions(state(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---"));
                assertThrows(java.io.IOException.class, this::store);
            } finally { Files.setPosixFilePermissions(state(), original); }
        }
    }

    @Test void localAdmissionDigestDriftTrustedCompletionAndUncertaintySurviveRestart() throws Exception {
        try (var fixture = new Fixture()) {
            var base = fixture.properties; var p = base.ask();
            var ambiguous = new RuntimeProperties(base.security(), base.ollama(), base.tasks(), base.translate(), base.summarize(),
                    new ModelProfile(p.id(), p.provider(), CANDIDATE, p.locality(), p.version(), p.contextBudget(), p.outputBudget(), p.temperature(), p.maxTextCharacters()));
            try (var rejected = new ActiveModelManager(new ProfileResolver(ambiguous), fixture.provider, ambiguous, store())) {
                code(ErrorCode.MODEL_CONFIGURATION_INVALID, () -> rejected.reserve("chat.balanced", "ask-v1"));
                assertEquals(0, fixture.probes.get());
            }
            try (var manager = fixture.manager()) {
                try (var lease = manager.reserve("translate.fast", "translate-v1")) {
                    assertEquals(0, lease.model().revision()); assertEquals(DIGEST, lease.model().digest());
                }
                assertFalse(Files.exists(state().resolve("active-model.json"))); // YAML bootstrap never writes selection.
                int probes = fixture.probes.get();
                for (String bad : List.of(OllamaFixtures.show().replace("\"completion\"", "\"embedding\""),
                        OllamaFixtures.show().replace("32768", "4096"),
                        OllamaFixtures.show().replace("\"qwen35\"", "\"unknown\""),
                        OllamaFixtures.show().replace("/isolated/models/blobs/", "https://remote.example/"),
                        OllamaFixtures.show().replace("\"details\":", "\"remote_host\":\"https://remote.example\",\"details\":"),
                        OllamaFixtures.show().replace("\"details\":", "\"manifests\":[{\"runner\":\"unknown\"}],\"details\":"))) {
                    fixture.show.set(bad);
                    code(ErrorCode.POLICY_DENIED, () -> manager.reserve("translate.fast", "translate-v1"));
                }
                assertEquals(probes, fixture.probes.get()); assertEquals(0, fixture.userCalls.get());
                fixture.show.set(OllamaFixtures.show()); fixture.cloudTag.set(true);
                code(ErrorCode.POLICY_DENIED, () -> manager.reserve("translate.fast", "translate-v1"));
                fixture.cloudTag.set(false); fixture.driftDuringShow.set(true);
                code(ErrorCode.MODEL_IDENTITY_CHANGED, () -> manager.reserve("translate.fast", "translate-v1"));
                fixture.driftDuringShow.set(false); fixture.digest.set(DIGEST);
                fixture.show.set(OllamaFixtures.show().replace("/isolated/models/blobs/", "C:\\\\isolated\\\\models\\\\blobs\\\\"));
                try (var windowsLocal = manager.reserve("translate.fast", "translate-v1")) { assertEquals(DIGEST, windowsLocal.model().digest()); }
                fixture.show.set(OllamaFixtures.show()); fixture.digest.set(NEXT);
                code(ErrorCode.MODEL_IDENTITY_CHANGED, () -> manager.reserve("translate.fast", "translate-v1"));
                fixture.digest.set(DIGEST); fixture.version.set("0.39.0");
                code(ErrorCode.POLICY_DENIED, () -> manager.reserve("translate.fast", "translate-v1"));
                fixture.version.set("0.40.0");
                try (var lease = manager.reserve("translate.fast", "translate-v1")) {
                    fixture.digest.set(NEXT);
                    code(ErrorCode.MODEL_IDENTITY_CHANGED, () -> fixture.provider.execute(request(lease), new Cancellation()));
                }
                assertEquals(0, fixture.userCalls.get()); fixture.digest.set(DIGEST);
                var tasks = new TaskManager(fixture.properties);
                try (AutoCloseable taskCleanup = tasks::close) {
                    var text = fixture.text(manager, tasks);
                    fixture.output.set("");
                    assertEquals(TaskStatus.FAILED, terminal(tasks, text.submit("translate", "translate.fast", "translate-v1", "Translate", "hello").taskId()).status());
                    awaitIdle(manager); assertFalse(manager.status().uncertain());
                    assertFalse(Files.exists(state().resolve("execution-guard.json"))); // Protocol completion precedes business output validation.
                    fixture.malformed.set(true);
                    assertEquals(TaskStatus.FAILED, terminal(tasks, text.submit("translate", "translate.fast", "translate-v1", "Translate", "hello").taskId()).status());
                    awaitIdle(manager); assertTrue(manager.status().uncertain());
                    code(ErrorCode.MODEL_EXECUTION_UNCERTAIN, () -> manager.reserve("translate.fast", "translate-v1"));
                    code(ErrorCode.MODEL_EXECUTION_UNCERTAIN, () -> manager.switchInternal(0, CANDIDATE, NEXT));
                    assertFalse(manager.readiness("translate.fast").modelAvailable());
                }
            }
            int outbound = fixture.probes.get() + fixture.userCalls.get();
            try (var restarted = fixture.manager()) {
                assertTrue(restarted.status().uncertain());
                code(ErrorCode.MODEL_EXECUTION_UNCERTAIN, () -> restarted.reserve("chat.balanced", "ask-v1"));
                assertFalse(restarted.readiness("translate.fast").modelAvailable());
                assertEquals(outbound, fixture.probes.get() + fixture.userCalls.get());
            }
            Path blockedState = root.resolve("blocked-model-state");
            try (var blocked = new ActiveModelManager(fixture.profiles, fixture.provider, fixture.properties,
                    new ModelStateStore(blockedState, token(), data()))) {
                try (var lease = blocked.reserve("translate.fast", "translate-v1")) {
                    Files.writeString(blockedState.resolve("execution-guard.pending"), "{\"version\":1,\"unresolved\":true}");
                    int before = fixture.userCalls.get(); fixture.malformed.set(false);
                    code(ErrorCode.MODEL_STATE_UNAVAILABLE, () -> fixture.provider.execute(request(lease), new Cancellation()));
                    assertEquals(before, fixture.userCalls.get()); // Marker failure is proven zero inference send.
                }
            }
            try (var restarted = new ActiveModelManager(fixture.profiles, fixture.provider, fixture.properties,
                    new ModelStateStore(blockedState, token(), data()))) {
                assertTrue(restarted.status().uncertain()); // Guard pending cannot disappear through restart.
            }
        }
    }

    @Test void oneSwitchOwnerFrozenTasksQueuedCancellationDrainAndZeroRejectedUserTurn() throws Exception {
        try (var fixture = new Fixture(); var manager = fixture.manager()) {
            var tasks = new TaskManager(fixture.properties);
            try (AutoCloseable taskCleanup = tasks::close;
             var memory = new MemoryStore(data(), token()); var conversations = new ConversationStore(memory.databaseFile())) {
            var text = fixture.text(manager, tasks);
            var conversation = new ConversationExecution(conversations, memory, fixture.profiles, text, tasks);
            try (var reserved = text.reserve("translate", "translate.fast", "translate-v1")) {
                code(ErrorCode.MODEL_SWITCH_CONFLICT, () -> manager.switchInternal(0, CANDIDATE, NEXT));
                assertEquals(MODEL, reserved.profile().model());
            }
            int probes = fixture.probes.get();
            code(ErrorCode.MODEL_SELECTION_REVISION_CONFLICT, () -> manager.switchInternal(1, CANDIDATE, NEXT));
            code(ErrorCode.MODEL_IDENTITY_CHANGED, () -> manager.switchInternal(0, CANDIDATE, DIGEST));
            assertFalse(Files.exists(state().resolve("active-model.json"))); assertEquals(probes, fixture.probes.get());
            try (var unchanged = manager.reserve("translate.fast", "translate-v1")) {
                assertEquals(MODEL, unchanged.model().model()); assertEquals(0, unchanged.model().revision());
            }
            assertEquals(probes, fixture.probes.get()); // Read-only candidate rejection needs no old-model reload.
            fixture.probeEntered = new CountDownLatch(1); fixture.probeRelease = new CountDownLatch(1);
            var switched = CompletableFuture.runAsync(() -> manager.switchInternal(0, CANDIDATE, NEXT));
            assertTrue(fixture.probeEntered.await(2, TimeUnit.SECONDS));
            var c = conversations.create("synthetic");
            code(ErrorCode.MODEL_SWITCH_CONFLICT, () -> conversation.submit(c.id(), new ConversationExecution.Request("USER", List.of())));
            assertEquals(0, conversations.detail(c.id(), 0, 10).totalTurns());
            code(ErrorCode.MODEL_SWITCH_CONFLICT, () -> manager.switchInternal(0, MODEL, DIGEST));
            fixture.probeRelease.countDown(); switched.get(3, TimeUnit.SECONDS);
            fixture.probeRelease = null;
            try (var snapshot = text.reserve("translate", "translate.fast", "translate-v1")) {
                assertEquals(CANDIDATE, snapshot.model().model()); assertEquals(1, snapshot.model().revision());
                assertEquals("test-v1", snapshot.profile().version());
            }
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var lease = text.reserve("translate", "translate.fast", "translate-batch-v1");
            TaskView running = text.submitReserved(lease, "translate", "Translate", "hello", 5, output -> {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return output;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            TaskView queued = text.submit("translate", "translate.fast", "translate-v1", "Translate", "queued");
            assertEquals(TaskStatus.QUEUED, queued.status());
            code(ErrorCode.MODEL_SWITCH_CONFLICT, () -> manager.switchInternal(1, MODEL, DIGEST));
            tasks.cancel(queued.taskId()); tasks.cancel(running.taskId());
            assertEquals(TaskStatus.CANCELLED, tasks.get(running.taskId()).status());
            assertEquals(1, manager.status().draining());
            code(ErrorCode.MODEL_SWITCH_CONFLICT, () -> manager.switchInternal(1, MODEL, DIGEST));
            release.countDown(); awaitIdle(manager);
            assertFalse(manager.status().uncertain()); assertEquals(1, fixture.userCalls.get());
            manager.switchInternal(1, MODEL, DIGEST);
            assertEquals(TaskStatus.CANCELLED, tasks.get(running.taskId()).status());
            assertEquals("test-v1", tasks.get(running.taskId()).profile().version());
            assertEquals(2, JSON.readTree(Files.readString(state().resolve("active-model.json"))).path("selectionRevision").asLong());
            }
        }
        try (var fixture = new Fixture(); var restarted = fixture.manager()) {
            try (var lease = restarted.reserve("chat.balanced", "ask-v1")) {
                assertEquals(MODEL, lease.model().model()); assertEquals(2, lease.model().revision());
            }
            String committed = Files.readString(state().resolve("active-model.json"));
            fixture.probeEntered = new CountDownLatch(1); fixture.probeRelease = new CountDownLatch(1);
            var failed = CompletableFuture.runAsync(() -> restarted.switchInternal(2, CANDIDATE, NEXT));
            assertTrue(fixture.probeEntered.await(2, TimeUnit.SECONDS));
            Files.writeString(state().resolve("active-model.pending"), committed); // Deterministic pre-rename publication fault.
            fixture.probeRelease.countDown();
            var failure = assertThrows(ExecutionException.class, () -> failed.get(3, TimeUnit.SECONDS));
            assertEquals(ErrorCode.MODEL_STATE_UNAVAILABLE, ((WorkspaceException) failure.getCause()).error().code());
            assertEquals(committed, Files.readString(state().resolve("active-model.json")));
            assertFalse(restarted.status().ready()); assertTrue(Files.exists(state().resolve("execution-guard.json")));
        }
        try (var fixture = new Fixture(); var restarted = fixture.manager()) {
            assertTrue(restarted.status().uncertain());
            code(ErrorCode.MODEL_EXECUTION_UNCERTAIN, () -> restarted.reserve("chat.balanced", "ask-v1"));
            assertEquals(0, fixture.probes.get());
        }
    }

    @Test void metadataOnlyCandidateRejectionPreservesReadyActiveButLoadedFailureRequiresRecovery() throws Exception {
        try (var fixture = new Fixture(); var manager = fixture.manager()) {
            manager.switchInternal(0, MODEL, DIGEST);
            String committed = Files.readString(state().resolve("active-model.json"));
            var before = manager.status();
            assertTrue(before.ready());
            int probes = fixture.probes.get();
            fixture.show.set(OllamaFixtures.show().replace("\"completion\"", "\"embedding\""));
            code(ErrorCode.POLICY_DENIED, () -> manager.switchInternal(1, CANDIDATE, NEXT));
            fixture.show.set(OllamaFixtures.show());
            code(ErrorCode.MODEL_IDENTITY_CHANGED, () -> manager.switchInternal(1, CANDIDATE, DIGEST));
            code(ErrorCode.MODEL_SELECTION_REVISION_CONFLICT, () -> manager.switchInternal(0, CANDIDATE, NEXT));
            assertEquals(before, manager.status());
            assertEquals(committed, Files.readString(state().resolve("active-model.json")));
            assertEquals(probes, fixture.probes.get()); assertEquals(0, fixture.userCalls.get());
            assertEquals(0, fixture.candidateCalls.get());
            assertFalse(Files.exists(state().resolve("execution-guard.json")));
            assertTrue(manager.readiness("translate.fast").modelAvailable());
            try (var lease = manager.reserve("translate.fast", "translate-v1")) {
                assertEquals(MODEL, lease.model().model()); assertEquals(1, lease.model().revision());
                assertEquals("OK", fixture.provider.execute(request(lease), new Cancellation()));
            }
            assertEquals(probes, fixture.probes.get()); // Serving A used its previous validation, without another probe.
            assertEquals(1, fixture.userCalls.get()); assertEquals(0, fixture.candidateCalls.get());
            fixture.probeOutput.set(""); // Trusted completion, but candidate text validation fails after load.
            code(ErrorCode.PROVIDER_RESPONSE_INVALID, () -> manager.switchInternal(1, CANDIDATE, NEXT));
            assertEquals(1, fixture.candidateCalls.get()); assertFalse(manager.status().ready());
            assertFalse(manager.status().uncertain()); // Completion and unknown old residency are distinct facts.
            assertEquals(ActiveModelManager.Fact.UNKNOWN, manager.status().loaded());
            assertEquals(committed, Files.readString(state().resolve("active-model.json")));
            assertTrue(Files.exists(state().resolve("execution-guard.json")));
            code(ErrorCode.PROVIDER_RESPONSE_INVALID, () -> manager.reserve("translate.fast", "translate-v1"));
            assertFalse(manager.readiness("translate.fast").modelAvailable());
            assertEquals(probes + 1, fixture.probes.get()); // No automatic recovery load of A.
        }
        try (var fixture = new Fixture(); var restarted = fixture.manager()) {
            assertTrue(restarted.status().uncertain());
            code(ErrorCode.MODEL_EXECUTION_UNCERTAIN, () -> restarted.reserve("translate.fast", "translate-v1"));
            assertEquals(0, fixture.probes.get()); assertEquals(0, fixture.userCalls.get());
        }
    }

    static Provider.ProviderExecution request(ActiveModelManager.Reservation lease) {
        return new Provider.ProviderExecution(lease.profile(), PrivacyMode.LOCAL_ONLY, "Translate", "hello",
                List.of(new Provider.ChatMessage("user", "hello")), lease);
    }
    static ModelProfile withModel(ModelProfile p, String model) {
        return new ModelProfile(p.id(), p.provider(), model, p.locality(), p.version(), p.contextBudget(), p.outputBudget(), p.temperature(), p.maxTextCharacters());
    }
    static TaskView terminal(TaskManager tasks, UUID id) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < end) {
            TaskView view = tasks.get(id);
            if (view.status() != TaskStatus.QUEUED && view.status() != TaskStatus.RUNNING) return view;
            Thread.sleep(5);
        }
        throw new AssertionError("Task did not finish");
    }
    static void awaitIdle(ActiveModelManager manager) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < end) {
            var status = manager.status();
            if (status.reserved() + status.queued() + status.executing() + status.draining() == 0) return;
            Thread.sleep(5);
        }
        throw new AssertionError("Model operation did not exit");
    }
    final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        final AtomicReference<String> show = new AtomicReference<>(OllamaFixtures.show()), digest = new AtomicReference<>(DIGEST),
                version = new AtomicReference<>("0.40.0"), output = new AtomicReference<>("OK"), probeOutput = new AtomicReference<>("OK");
        final AtomicBoolean malformed = new AtomicBoolean();
        final AtomicBoolean cloudTag = new AtomicBoolean(), driftDuringShow = new AtomicBoolean();
        final AtomicInteger probes = new AtomicInteger(), userCalls = new AtomicInteger(), candidateCalls = new AtomicInteger();
        volatile CountDownLatch probeEntered, probeRelease;
        final RuntimeProperties properties;
        final ProfileResolver profiles;
        final OllamaProvider provider;
        Fixture() throws Exception {
            server.setExecutor(executor);
            server.createContext("/api/version", e -> respond(e, JSON.writeValueAsString(Map.of("version", version.get()))));
            server.createContext("/api/tags", e -> {
                var a = JSON.readTree(OllamaFixtures.tags(MODEL, digest.get())).path("models").get(0);
                if (cloudTag.get()) ((tools.jackson.databind.node.ObjectNode) a).put("remote_host", "https://remote.example");
                var b = JSON.readTree(OllamaFixtures.tags(CANDIDATE, NEXT)).path("models").get(0);
                respond(e, JSON.writeValueAsString(Map.of("models", List.of(a, b))));
            });
            server.createContext("/api/show", e -> {
                if (driftDuringShow.get()) digest.set(NEXT);
                respond(e, show.get());
            });
            server.createContext("/api/chat", e -> {
                var input = JSON.readTree(e.getRequestBody().readAllBytes());
                if (input.path("model").asString().equals(CANDIDATE + ":local")) candidateCalls.incrementAndGet();
                boolean probe = input.path("messages").get(0).path("content").asString().equals("Reply with OK only.");
                if (probe) {
                    probes.incrementAndGet();
                    if (probeRelease != null) {
                        probeEntered.countDown();
                        try { probeRelease.await(3, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                } else userCalls.incrementAndGet();
                respond(e, !probe && malformed.get() ? "malformed" : OllamaFixtures.completed(input.path("model").asString(), probe ? probeOutput.get() : output.get()));
            });
            server.start();
            var base = TestSettings.settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            properties = new RuntimeProperties(new RuntimeProperties.Security(token()),
                    new RuntimeProperties.Ollama(base.ollama().baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofSeconds(1), 1048576),
                    TestSettings.tasks(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(30)), base.translate(),
                    withModel(base.summarize(), "test-model"), withModel(base.ask(), "registry.ollama.ai/library/test-model:latest:local"));
            new LocalClientToken(properties); // Independent credentials; no production-root fallback.
            profiles = new ProfileResolver(properties); provider = new OllamaProvider(properties, new ProviderPolicy());
        }
        ActiveModelManager manager() throws Exception { return new ActiveModelManager(profiles, provider, properties, store()); }
        TextTaskSubmission text(ActiveModelManager manager, TaskManager tasks) {
            return new TextTaskSubmission(profiles, new ProviderRegistry(List.of(provider)), new ProviderPolicy(), tasks, manager);
        }
        void respond(HttpExchange e, String body) throws java.io.IOException {
            try (e) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes);
            }
        }
        public void close() {
            if (probeRelease != null) probeRelease.countDown();
            provider.close(); server.stop(0); executor.shutdownNow();
        }
    }
}
