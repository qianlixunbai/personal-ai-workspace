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
    private final AtomicReference<String> tags = new AtomicReference<>("{\"models\":[{\"name\":\"test-model:latest\"}]}");
    private final AtomicReference<String> chat = new AtomicReference<>("{\"model\":\"test-model:latest\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}");
    private final AtomicReference<String> payload = new AtomicReference<>();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final CountDownLatch chatEntered = new CountDownLatch(1);
    private volatile long delay;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
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
        assertEquals("test-model:latest", body.path("model").asString());
        assertFalse(body.path("stream").asBoolean());
        assertFalse(body.path("think").asBoolean());
        assertEquals(8192, body.path("options").path("num_ctx").asInt());
        assertEquals(2048, body.path("options").path("num_predict").asInt());
        assertTrue(provider.readiness(TestSettings.profile()).modelAvailable());
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
        tags.set("{\"models\":[{\"name\":\"test-model:latest\"}]}");
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
        return new Provider.ProviderExecution(TestSettings.profile(), PrivacyMode.LOCAL_ONLY, "Translate only", "hello");
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
