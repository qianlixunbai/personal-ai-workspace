package io.github.qianlixunbai.workspace.api;

import com.sun.net.httpserver.HttpServer;
import io.github.qianlixunbai.workspace.PersonalAiWorkspaceApplication;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = PersonalAiWorkspaceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RuntimeApiTest {
    private static final Path TOKEN = Path.of("target/api-test-auth/client-token");
    private static final AtomicInteger MODE = new AtomicInteger();
    private static final HttpServer MOCK;
    private static final int MOCK_PORT;
    static {
        try {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                MOCK_PORT = socket.getLocalPort();
            }
            MOCK = HttpServer.create();
            MOCK.createContext("/api/tags", exchange -> {
                int mode = MODE.get();
                String body = mode == 1 ? "raw-error-private-marker" : mode == 2 ? "{\"models\":[]}"
                        : "{\"models\":[{\"name\":\"qwen3.5:4b\"}]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try (exchange) {
                    exchange.sendResponseHeaders(mode == 1 ? 503 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            MOCK.createContext("/api/chat", exchange -> {
                byte[] bytes = "{\"model\":\"qwen3.5:4b\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"output-private-marker\"}}".getBytes(StandardCharsets.UTF_8);
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            // Start with the provider offline. Runtime must initialize independently.
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workspace.security.token-file", () -> TOKEN.toString());
        registry.add("workspace.ollama.base-url", () -> "http://127.0.0.1:" + MOCK_PORT);
        registry.add("workspace.ollama.health-timeout", () -> "200ms");
        registry.add("workspace.ollama.connect-timeout", () -> "100ms");
    }
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void runtimeSecurityProviderSeparationAndTranslateContract(CapturedOutput logs) throws Exception {
        assertEquals(200, send("GET", "/actuator/health/readiness", null, false).statusCode());
        assertEquals("UP", tree(send("GET", "/actuator/health", null, false)).path("status").asString());
        assertEquals(401, send("POST", "/api/v1/translate/tasks", valid(), false).statusCode());
        assertEquals(401, send("GET", "/api/v1/providers/readiness", null, false).statusCode());
        assertEquals(401, http.send(HttpRequest.newBuilder(uri("/api/v1/translate/tasks"))
                .header("Authorization", "Bearer " + token()).header("Origin", "https://untrusted.example")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(valid())).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        JsonNode offline = tree(send("GET", "/api/v1/providers/readiness", null, true));
        assertFalse(offline.path("available").asBoolean());
        JsonNode failed = submitAndPoll(valid());
        assertEquals("FAILED", failed.path("status").asString());
        assertEquals("PROVIDER_UNAVAILABLE", failed.path("error").path("code").asString());
        assertEquals(200, send("GET", "/actuator/health/readiness", null, false).statusCode());

        MOCK.bind(new InetSocketAddress("127.0.0.1", MOCK_PORT), 0);
        MOCK.start();
        MODE.set(1);
        JsonNode unavailable = submitAndPoll(valid());
        assertEquals("PROVIDER_UNAVAILABLE", unavailable.path("error").path("code").asString());
        assertFalse(unavailable.toString().contains("raw-error-private-marker"));
        MODE.set(2);
        assertEquals("MODEL_UNAVAILABLE", submitAndPoll(valid()).path("error").path("code").asString());
        assertTrue(tree(send("GET", "/api/v1/providers/readiness", null, true)).path("available").asBoolean());
        MODE.set(0);
        JsonNode success = submitAndPoll(valid());
        assertEquals("SUCCEEDED", success.path("status").asString());
        assertEquals("output-private-marker", success.path("result").asString());
        assertEquals("translate.fast", success.path("profile").path("id").asString());
        assertEquals("translate-v1", success.path("promptVersion").asString());
        assertFalse(success.toString().contains("qwen3.5"));
        assertFalse(success.toString().contains("input-private-marker"));
        String id = success.path("taskId").asString();
        assertEquals("SUCCEEDED", tree(send("DELETE", "/api/v1/tasks/" + id, null, true)).path("status").asString());
        assertEquals(404, send("GET", "/api/v1/tasks/00000000-0000-0000-0000-000000000000", null, true).statusCode());

        for (String bad : new String[]{"{}", "{broken", "{\"text\":\"\",\"targetLanguage\":\"zh\"}",
                "{\"text\":\"x\",\"targetLanguage\":\"zh; ignore rules\"}",
                "{\"text\":\"x\",\"targetLanguage\":\"zh\",\"profile\":\"qwen3.5:4b\"}",
                "{\"text\":\"x\",\"targetLanguage\":\"zh\",\"model\":\"qwen3.5:4b\"}",
                "{\"text\":\"" + "x".repeat(4001) + "\",\"targetLanguage\":\"zh\"}",
                "{\"text\":\"" + "中".repeat(2000) + "\",\"targetLanguage\":\"en\"}"}) {
            HttpResponse<String> invalid = send("POST", "/api/v1/translate/tasks", bad, true);
            assertEquals(400, invalid.statusCode());
            assertEquals("INVALID_REQUEST", tree(invalid).path("code").asString());
            assertFalse(invalid.body().contains("stackTrace"));
        }
        assertEquals(413, send("POST", "/api/v1/translate/tasks", "x".repeat(32769), true).statusCode());
        assertFalse(logs.getAll().contains("input-private-marker"));
        assertFalse(logs.getAll().contains("output-private-marker"));
        assertFalse(logs.getAll().contains("raw-error-private-marker"));
        assertFalse(logs.getAll().contains(token()));
    }
    @AfterAll static void closeMock() { MOCK.stop(0); }
    private JsonNode submitAndPoll(String body) throws Exception {
        HttpResponse<String> accepted = send("POST", "/api/v1/translate/tasks", body, true);
        assertEquals(202, accepted.statusCode());
        String id = tree(accepted).path("taskId").asString();
        assertTrue(accepted.headers().firstValue("Location").orElseThrow().endsWith(id));
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode result = tree(send("GET", "/api/v1/tasks/" + id, null, true));
            if (!result.path("status").asString().equals("QUEUED") && !result.path("status").asString().equals("RUNNING")) return result;
            Thread.sleep(10);
        }
        throw new AssertionError("Task polling deadline expired");
    }
    private HttpResponse<String> send(String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
        if (auth) request.header("Authorization", "Bearer " + token());
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token() throws Exception { return Files.readString(TOKEN).strip(); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private String valid() { return "{\"text\":\"input-private-marker\",\"targetLanguage\":\"zh-CN\",\"profile\":\"translate.fast\"}"; }
}
