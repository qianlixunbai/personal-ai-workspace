package io.github.qianlixunbai.workspace.model;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.task.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production transport and state writer, with a model-free loopback service. */
public class ExternalRequestLifecycleTest {
    @TempDir Path root;

    @Test void requestCompletionCannotCrossAttempts() throws Exception {
        var support = new ModelFoundationTest(); support.root = root;
        Path state = root.resolve("request-bindings");
        try (var fixture = support.new Fixture();
             var models = new ActiveModelManager(fixture.profiles, fixture.provider, fixture.properties,
                 new ModelStateStore(state, support.token(), support.data()));
             var lease = models.reserve("translate.fast", "ownership-fixture");
             var other = models.reserve("translate.fast", "ownership-fixture")) {
            // Production provider creates and consumes a fresh capability on each actual HTTP exchange.
            assertEquals("OK", fixture.provider.execute(ModelFoundationTest.request(lease), new Cancellation()));
            assertEquals("OK", fixture.provider.execute(ModelFoundationTest.request(lease), new Cancellation()));
            var http = java.net.http.HttpRequest.newBuilder(fixture.properties.ollama().baseUrl().resolve("/api/chat"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            var first = lease.bindRequest(fixture.provider, http);
            assertThrows(WorkspaceException.class, () -> lease.trustedCompletion(first));
            lease.prepareCheckedSend(first, null);
            assertThrows(WorkspaceException.class, () -> lease.bindRequest(fixture.provider, http));
            // Controlled write delegation for adversarial callbacks; no claim of a remote execution here.
            lease.checkedWrite(first, () -> {}, java.io.OutputStream.nullOutputStream(), new byte[]{1}, 0, 1);
            lease.trustedCompletion(first);
            assertThrows(WorkspaceException.class, () -> lease.trustedCompletion(first));
            lease.providerExited(first);
            var second = lease.bindRequest(fixture.provider, http);
            var foreign = other.bindRequest(fixture.provider, http);
            lease.prepareCheckedSend(second, null);
            lease.checkedWrite(second, () -> {}, java.io.OutputStream.nullOutputStream(), new byte[]{1}, 0, 1);
            byte[] guard = Files.readAllBytes(state.resolve("execution-guard.json"));
            assertThrows(WorkspaceException.class, () -> lease.trustedCompletion(first));
            assertThrows(WorkspaceException.class, () -> lease.trustedCompletion(foreign));
            assertThrows(WorkspaceException.class, () -> lease.providerExited(first));
            assertThrows(WorkspaceException.class, () -> lease.checkedWrite(first, () -> {},
                java.io.OutputStream.nullOutputStream(), new byte[]{1}, 0, 1));
            lease.providerExited(second); // No terminal response for the second request.
            assertTrue(models.status().uncertain());
            assertThrows(WorkspaceException.class, () -> lease.trustedCompletion(second));
            assertArrayEquals(guard, Files.readAllBytes(state.resolve("execution-guard.json")));
            other.providerExited(foreign);
        }
        try (var restarted = new ModelStateStore(state, support.token(), support.data())) {
            assertTrue(restarted.unresolved());
            assertThrows(java.io.IOException.class, restarted::clearGuard);
        }
    }

    @Test void singleSendAndUncertainLocalExit() throws Exception {
        var support = new ModelFoundationTest(); support.root = root;
        try (var fixture = support.new Fixture()) {
            for (String mode : new String[]{"disconnect", "cancel", "timeout", "shutdown"}) {
                Path state = root.resolve("state-" + mode);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var ended = new CountDownLatch(1);
                var requests = new AtomicInteger();
                try (var models = new ActiveModelManager(fixture.profiles, fixture.provider, fixture.properties,
                        new ModelStateStore(state, support.token(), support.data()));
                     var worker = Executors.newSingleThreadExecutor()) {
                    // Bootstrap success exercises the same single-shot transport before fault injection.
                    var lease = models.reserve("translate.fast", "ownership-fixture");
                    fixture.server.removeContext("/api/chat");
                    fixture.server.createContext("/api/chat", exchange -> {
                        try (exchange) {
                            exchange.getRequestBody().readAllBytes();
                            requests.incrementAndGet(); entered.countDown();
                            if (!mode.equals("disconnect")) {
                                try { assertTrue(release.await(12, TimeUnit.SECONDS)); }
                                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                                // A terminal response sent after the client left must not recover its guard.
                                try { fixture.respond(exchange, io.github.qianlixunbai.workspace.provider.ollama.OllamaFixtures.completed(
                                        ModelFoundationTest.MODEL + ":local", "late")); }
                                catch (java.io.IOException expectedClosedClient) { }
                            }
                        } finally { ended.countDown(); }
                    });
                    var cancellation = new Cancellation();
                    var outcome = worker.submit(() -> {
                        try (lease) {
                            return assertThrows(WorkspaceException.class,
                                () -> fixture.provider.execute(ModelFoundationTest.request(lease), cancellation)).error().code();
                        }
                    });
                    assertTrue(entered.await(4, TimeUnit.SECONDS));
                    assertTrue(Files.exists(state.resolve("execution-guard.json")));
                    if (mode.equals("cancel")) cancellation.cancel();
                    if (mode.equals("shutdown")) fixture.provider.close();
                    ErrorCode expected = mode.equals("cancel") ? ErrorCode.TASK_CANCELLED
                        : mode.equals("timeout") ? ErrorCode.TASK_TIMEOUT : ErrorCode.PROVIDER_UNAVAILABLE;
                    assertEquals(expected, outcome.get(7, TimeUnit.SECONDS));
                    assertEquals(1, requests.get()); // No automatic retry after the server consumed the request.
                    assertTrue(models.status().uncertain());
                    assertEquals(0, models.status().executing());
                    byte[] guard = Files.readAllBytes(state.resolve("execution-guard.json"));
                    if (!mode.equals("disconnect")) assertEquals(1, ended.getCount());
                    release.countDown(); assertTrue(ended.await(4, TimeUnit.SECONDS));
                    assertArrayEquals(guard, Files.readAllBytes(state.resolve("execution-guard.json")));
                } finally { release.countDown(); }
                try (var restarted = new ModelStateStore(state, support.token(), support.data())) {
                    assertTrue(restarted.unresolved());
                    assertThrows(java.io.IOException.class, restarted::clearGuard);
                }
                // Restore the synthetic response for the next scenario's bootstrap.
                fixture.server.removeContext("/api/chat");
                fixture.server.createContext("/api/chat", exchange -> {
                    var body = ModelFoundationTest.JSON.readTree(exchange.getRequestBody().readAllBytes());
                    fixture.respond(exchange, io.github.qianlixunbai.workspace.provider.ollama.OllamaFixtures.completed(
                        body.path("model").asString(), "OK"));
                });
            }
        }
    }
}
