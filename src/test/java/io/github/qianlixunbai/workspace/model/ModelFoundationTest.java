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

    static ActiveModelManager.Intent intent(ActiveModelManager manager, ActiveModelManager.Action action, String model, boolean external) {
        var status = manager.managementStatus(); var catalog = manager.catalog();
        var entry = catalog.models().stream().filter(e -> e.model().equals(model)).findFirst().orElseThrow();
        return new ActiveModelManager.Intent(action, entry.handle(), entry.model(), entry.runner(), entry.digest(), status.selectionRevision(),
                status.activeModel(), status.activeRunner(), status.activeDigest(), action == ActiveModelManager.Action.RECOVER ? status.recoveryGeneration() : null, external);
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
        final AtomicBoolean blockNextShow = new AtomicBoolean();
        volatile CountDownLatch showEntered, showRelease;
        final AtomicBoolean malformed = new AtomicBoolean();
        final AtomicBoolean malformedRelease = new AtomicBoolean();
        final AtomicBoolean cloudTag = new AtomicBoolean(), driftDuringShow = new AtomicBoolean();
        final AtomicInteger probes = new AtomicInteger(), userCalls = new AtomicInteger(), candidateCalls = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicReference<String> releasedModel = new AtomicReference<>(), tagsOverride = new AtomicReference<>();
        volatile CountDownLatch probeEntered, probeRelease;
        final RuntimeProperties properties;
        final ProfileResolver profiles;
        final OllamaProvider provider;
        Fixture() throws Exception {
            server.setExecutor(executor);
            server.createContext("/api/version", e -> respond(e, JSON.writeValueAsString(Map.of("version", version.get()))));
            server.createContext("/api/tags", e -> {
                if (tagsOverride.get() != null) { respond(e, tagsOverride.get()); return; }
                var a = JSON.readTree(OllamaFixtures.tags(MODEL, digest.get())).path("models").get(0);
                if (cloudTag.get()) ((tools.jackson.databind.node.ObjectNode) a).put("remote_host", "https://remote.example");
                var b = JSON.readTree(OllamaFixtures.tags(CANDIDATE, NEXT)).path("models").get(0);
                respond(e, JSON.writeValueAsString(Map.of("models", List.of(a, b))));
            });
            server.createContext("/api/show", e -> {
                if (blockNextShow.compareAndSet(true, false)) {
                    showEntered.countDown();
                    try { showRelease.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                if (driftDuringShow.get()) digest.set(NEXT);
                respond(e, show.get());
            });
            server.createContext("/api/ps", e -> respond(e, "{\"models\":[]}"));
            server.createContext("/api/generate", e -> {
                var input = JSON.readTree(e.getRequestBody().readAllBytes());
                assertEquals(Set.of("model", "runner", "stream", "keep_alive"), new HashSet<>(input.propertyNames()));
                assertEquals("ggml", input.path("runner").asString());
                assertEquals(0, input.path("keep_alive").asInt()); assertFalse(input.path("stream").asBoolean());
                releases.incrementAndGet(); releasedModel.set(input.path("model").asString());
                respond(e, malformedRelease.get() ? "{\"done\":true}" : JSON.writeValueAsString(Map.of("model", input.path("model").asString(), "created_at", "2026-10-08T01:00:00Z", "response", "", "done", true, "done_reason", "unload")));
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
            if (showRelease != null) showRelease.countDown();
            provider.close(); server.stop(0); executor.shutdownNow();
        }
    }
}
