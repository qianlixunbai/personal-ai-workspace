package io.github.qianlixunbai.workspace.provider.ollama;

import com.sun.net.httpserver.*;
import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.Cancellation;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OllamaProviderTest {
    private HttpServer server;
    private ExecutorService executor;
    private OllamaProvider provider;
    private final AtomicInteger tagStatus = new AtomicInteger(200), chatStatus = new AtomicInteger(200);
    private final AtomicReference<String> tags = new AtomicReference<>(OllamaFixtures.tags("test-model:latest", "a".repeat(64)));
    private final AtomicReference<String> show = new AtomicReference<>(OllamaFixtures.show());
    private final AtomicReference<String> chat = new AtomicReference<>(OllamaFixtures.completed("test-model:latest:local", "你好"));
    private final AtomicReference<String> payload = new AtomicReference<>();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final CountDownLatch chatEntered = new CountDownLatch(1);
    private volatile long delay;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/version", e -> respond(e, 200, "{\"version\":\"0.40.0\"}"));
        server.createContext("/api/show", e -> respond(e, 200, show.get()));
        server.createContext("/api/tags", e -> respond(e, tagStatus.get(), tags.get()));
        server.createContext("/api/chat", e -> {
            chatCalls.incrementAndGet();
            payload.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            chatEntered.countDown();
            try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            respond(e, chatStatus.get(), chat.get());
        });
        server.start();
        provider = new OllamaProvider(TestSettings.settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort())), new ProviderPolicy());
    }
    @AfterEach void close() { provider.close(); server.stop(0); executor.shutdownNow(); }

    @Test void realHttpParsingAndGenerationSettings() {
        assertEquals("你好", provider.execute(execution(), new Cancellation()));
        var body = JsonMapper.builder().build().readTree(payload.get());
        assertEquals("test-model:latest:local", body.path("model").asString());
        assertFalse(body.path("stream").asBoolean());
        assertFalse(body.path("think").asBoolean());
        assertEquals(8192, body.path("options").path("num_ctx").asInt());
        assertEquals(2048, body.path("options").path("num_predict").asInt());
        assertTrue(provider.readiness(TestSettings.profile()).modelAvailable());
    }
    @Test void installedDefaultMetadataRequiresStableSupportedVersionAndPositiveLocalSource() throws Exception {
        var json = JsonMapper.builder().build();
        tools.jackson.databind.JsonNode fixture;
        try (var resource = getClass().getResourceAsStream("/ollama/qwen35-local-v0.40.0.json")) {
            fixture = json.readTree(resource);
        }
        tags.set(json.writeValueAsString(java.util.Map.of("models", java.util.List.of(fixture.path("tag")))));
        show.set(json.writeValueAsString(fixture.path("show")));
        var evidence = provider.admitLocal("qwen3.5:4b", 8192, new Cancellation());
        assertEquals("a".repeat(64), evidence.digest()); assertEquals(262144, evidence.contextLimit());
        for (String requirement : java.util.List.of("0.40.1", "0.41.0", "1.0.0", "unknown", "0.17.1-cloud", "0.017.1")) {
            var rejected = fixture.path("show").deepCopy();
            ((tools.jackson.databind.node.ObjectNode) rejected).put("requires", requirement);
            show.set(json.writeValueAsString(rejected));
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                    () -> provider.admitLocal("qwen3.5:4b", 8192, new Cancellation())).error().code());
        }
        for (String remote : java.util.List.of("remote_host", "remote_model", "source", "runner")) {
            var rejected = fixture.path("show").deepCopy();
            ((tools.jackson.databind.node.ObjectNode) rejected).put(remote, "unknown");
            show.set(json.writeValueAsString(rejected));
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                    () -> provider.admitLocal("qwen3.5:4b", 8192, new Cancellation())).error().code());
        }
        assertEquals(0, chatCalls.get()); // Metadata compatibility never grants load/inference or Vision execution.
    }
    @Test void unavailableMissingAndRawErrorAreControlled() {
        tagStatus.set(503); tags.set("private secret raw error /internal/path");
        assertCode(ErrorCode.PROVIDER_UNAVAILABLE);
        assertEquals(0, chatCalls.get());
        assertFalse(provider.readiness(TestSettings.profile()).available());
        tagStatus.set(200); tags.set("{\"models\":[]}");
        assertCode(ErrorCode.MODEL_UNAVAILABLE);
        assertTrue(provider.readiness(TestSettings.profile()).available());
        assertFalse(provider.readiness(TestSettings.profile()).modelAvailable());
        tags.set(OllamaFixtures.tags("test-model:latest", "a".repeat(64)));
        chatStatus.set(500); chat.set("private secret raw error /internal/path");
        WorkspaceException failure = assertCode(ErrorCode.PROVIDER_UNAVAILABLE);
        assertFalse(failure.error().message().contains("secret"));
        assertNull(failure.getCause());
    }
    @Test void malformedOversizeIncompleteAndRedirectsAreRejected() {
        for (String bad : new String[]{"not-json", "null", "{} {}", "x".repeat(2048),
                "{\"done\":false,\"message\":{\"content\":\"partial\"}}",
                "{\"model\":\"test-model:latest\",\"done\":true,\"done_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"truncated\"}}"}) {
            chat.set(bad); assertCode(ErrorCode.PROVIDER_RESPONSE_INVALID);
        }
        chatStatus.set(302);
        assertCode(ErrorCode.PROVIDER_RESPONSE_INVALID);
    }
    @Test void providerTimeout() {
        delay = 1500;
        WorkspaceException timeout = assertCode(ErrorCode.TASK_TIMEOUT);
        assertEquals("PROVIDER", timeout.error().phase());
    }
    @Test void inFlightCancellation() throws Exception {
        delay = 1500;
        Cancellation cancellation = new Cancellation();
        CompletableFuture<WorkspaceException> result = CompletableFuture.supplyAsync(() ->
                assertThrows(WorkspaceException.class, () -> provider.execute(execution(), cancellation)));
        assertTrue(chatEntered.await(1, TimeUnit.SECONDS));
        cancellation.cancel();
        assertEquals(ErrorCode.TASK_CANCELLED, result.get(1, TimeUnit.SECONDS).error().code());
    }
    @Test void connectionAndRequestTimeoutClassification() {
        assertEquals("CONNECT", OllamaProvider.classify(new CompletionException(
                new java.net.http.HttpConnectTimeoutException("raw sensitive message"))).error().phase());
        assertEquals("PROVIDER", OllamaProvider.classify(new java.net.http.HttpTimeoutException("private")).error().phase());
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, OllamaProvider.classify(new java.net.ConnectException("private")).error().code());
    }
    @Test void finalEgressPolicyRejectsCloudBeforeHttp() {
        var p = TestSettings.profile();
        var cloud = new io.github.qianlixunbai.workspace.model.ModelProfile(p.id(), p.provider(), p.model(),
                io.github.qianlixunbai.workspace.model.ModelProfile.Locality.CLOUD, p.version(), p.contextBudget(), p.outputBudget(), p.temperature(), p.maxTextCharacters());
        assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class, () -> provider.execute(
                new Provider.ProviderExecution(cloud, PrivacyMode.CLOUD_OPTIONAL, "private", "private"), new Cancellation())).error().code());
        assertEquals(0, chatCalls.get());
    }
    private WorkspaceException assertCode(ErrorCode code) {
        WorkspaceException failure = assertThrows(WorkspaceException.class, () -> provider.execute(execution(), new Cancellation()));
        assertEquals(code, failure.error().code()); return failure;
    }
    private Provider.ProviderExecution execution() {
        return new Provider.ProviderExecution(TestSettings.profile(), PrivacyMode.LOCAL_ONLY, "Translate only", "hello",
                java.util.List.of(new Provider.ChatMessage("user", "hello")), TestSettings.reservation(TestSettings.profile(), "test"));
    }
    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
