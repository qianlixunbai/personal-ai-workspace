package io.github.qianlixunbai.workspace.model;

import io.github.qianlixunbai.workspace.api.*;
import io.github.qianlixunbai.workspace.security.*;
import io.github.qianlixunbai.workspace.task.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Production MVC route, real native state writer and model-free HTTP; no provider ownership claim. */
public class ExecutionInspectionTest {
    @TempDir Path root;
    private static MockMvc api(ActiveModelManager models) {
        return MockMvcBuilders.standaloneSetup(new ModelController(models)).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    private static tools.jackson.databind.JsonNode read(MockMvc api, String state) throws Exception {
        var response = api.perform(get("/api/v1/models/executions")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var body = ModelFoundationTest.JSON.readTree(response.getContentAsByteArray());
        assertEquals(Set.of("recordState", "recoverySupported", "requests"), new HashSet<>(body.propertyNames()));
        assertEquals(state, body.path("recordState").asString()); assertFalse(body.path("recoverySupported").asBoolean());
        for (var row : body.path("requests"))
            assertEquals(Set.of("requestId", "operation", "model", "runner", "digest", "selectionRevision", "completion"), new HashSet<>(row.propertyNames()));
        return body;
    }
    @Test void inspectionExplainsCurrentHistoricalAndIncompleteRecordsWithoutRecovery() throws Exception {
        var support = new ModelFoundationTest(); support.root = root;
        Path state = root.resolve("execution-inspection"); Path file = state.resolve("execution-guard.json");
        byte[] original;
        try (var fixture = support.new Fixture()) {
            try (var models = new ActiveModelManager(fixture.profiles, fixture.provider, fixture.properties,
                    new ModelStateStore(state, support.token(), support.data()))) {
                var api = api(models); read(api, "NONE");
                try (var lease = models.reserve("translate.fast", "inspection")) {
                    assertEquals("OK", fixture.provider.execute(ModelFoundationTest.request(lease), new Cancellation()));
                    var completed = read(api, "CURRENT_SESSION").path("requests").get(0);
                    assertEquals("COMPLETED", completed.path("completion").asString());
                    String completedId = completed.path("requestId").asString();
                    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                    fixture.server.removeContext("/api/chat");
                    fixture.server.createContext("/api/chat", exchange -> {
                        try (exchange) {
                            exchange.getRequestBody().readAllBytes(); entered.countDown();
                            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        }
                    });
                    try (var worker = Executors.newSingleThreadExecutor()) {
                        var attempt = worker.submit(() -> fixture.provider.execute(ModelFoundationTest.request(lease), new Cancellation()));
                        try {
                            assertTrue(entered.await(3, TimeUnit.SECONDS)); original = Files.readAllBytes(file);
                            var rows = read(api, "CURRENT_SESSION").path("requests"); assertEquals(2, rows.size());
                            assertEquals(completedId, rows.get(0).path("requestId").asString());
                            assertEquals("COMPLETED", rows.get(0).path("completion").asString());
                            assertEquals("IN_PROGRESS", rows.get(1).path("completion").asString());
                            assertArrayEquals(original, Files.readAllBytes(file));
                        } finally { release.countDown(); }
                        assertThrows(ExecutionException.class, () -> attempt.get(3, TimeUnit.SECONDS));
                    }
                    fixture.provider.close(); // Local inspection must still work without usable provider transport.
                    var rows = read(api, "CURRENT_SESSION").path("requests");
                    assertEquals("COMPLETED", rows.get(0).path("completion").asString());
                    assertEquals("UNKNOWN", rows.get(1).path("completion").asString());
                    assertTrue(models.status().uncertain());
                    assertArrayEquals(original, Files.readAllBytes(file));
                }
                assertEquals(400, api.perform(get("/api/v1/models/executions").queryParam("path", "elsewhere")).andReturn().getResponse().getStatus());
                var browser = new ClientIdentity("synthetic-browser", "browser", "synthetic", "https://example.invalid", java.time.Instant.EPOCH, Set.of("translate"));
                var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
                try {
                    security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(browser, null, List.of()));
                    assertEquals(403, api.perform(get("/api/v1/models/executions")).andReturn().getResponse().getStatus());
                } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            }
            try (var reopened = new ActiveModelManager(fixture.profiles, fixture.provider, fixture.properties,
                    new ModelStateStore(state, support.token(), support.data()))) {
                var rows = read(api(reopened), "PREVIOUS_SESSION").path("requests");
                assertEquals(2, rows.size()); for (var row : rows) assertEquals("UNKNOWN", row.path("completion").asString());
                assertTrue(reopened.status().uncertain()); assertArrayEquals(original, Files.readAllBytes(file));
            }
            // Only this test's newly created synthetic marker is changed, after releasing its writer.
            for (String kind : List.of("LEGACY", "UNREADABLE", "INCOMPLETE")) {
                Files.writeString(file, kind.equals("LEGACY") ? "{\"version\":1,\"unresolved\":true}" : "incomplete synthetic marker");
                if (kind.equals("INCOMPLETE")) WindowsPrivateAcl.createFile(state.resolve("execution-guard.pending"));
                byte[] before = Files.readAllBytes(file);
                try (var reopened = new ActiveModelManager(fixture.profiles, fixture.provider, fixture.properties,
                        new ModelStateStore(state, support.token(), support.data()))) {
                    assertEquals(0, read(api(reopened), kind).path("requests").size());
                    assertTrue(reopened.status().uncertain()); assertArrayEquals(before, Files.readAllBytes(file));
                }
            }
        }
    }
}
