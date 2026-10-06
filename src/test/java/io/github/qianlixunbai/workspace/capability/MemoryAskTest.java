package io.github.qianlixunbai.workspace.capability;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.capability.ask.*;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MemoryAskTest {
    @TempDir Path temporary;
    @Test void acceptedTaskUsesAdmissionSnapshotEvenAfterDeletionAndOrdinaryAskNeverReadsStore() throws Exception {
        var p = TestSettings.settings(URI.create("http://127.0.0.1:1"));
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var execution = new AtomicReference<Provider.ProviderExecution>();
        Provider provider = new Provider() {
            public String id() { return "ollama"; }
            public ModelProfile.Locality locality() { return ModelProfile.Locality.LOCAL; }
            public Set<Capability> capabilities() { return Set.of(Capability.TEXT_GENERATION); }
            public ProviderReadiness readiness(ModelProfile profile) { return null; }
            public String execute(ProviderExecution e, Cancellation c) {
                execution.set(e); started.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return "private-answer";
            }
        };
        var manager = new TaskManager(p);
        try (var store = new MemoryStore(temporary.resolve("data"), temporary.resolve("auth/client-token"))) {
            var tasks = new TextTaskSubmission(new ProfileResolver(p), new ProviderRegistry(List.of(provider)), new ProviderPolicy(), manager);
            var item = store.create(MemoryItem.Type.PROJECT_NOTE, "private-title", "private-context");
            var accepted = new MemoryAskService(tasks, store).submit(new MemoryAskRequest("private-question", List.of(new MemoryReference(item.id(), 1)), "chat.balanced"));
            assertTrue(started.await(2, TimeUnit.SECONDS)); store.delete(item.id(), 1); release.countDown();
            var result = terminal(manager, accepted.taskId());
            assertEquals("ask", result.capability()); assertEquals("memory-ask-v1", result.promptVersion());
            assertEquals("private-answer", result.result());
            assertTrue(execution.get().input().contains("private-context"));
            assertEquals(MemoryAskPrompt.SYSTEM, execution.get().system());
            assertFalse(execution.get().toString().contains("private"));
            assertEquals(0, store.list(null, null, null, 0, 20).total());
            store.close(); // No available Memory store: ordinary Ask still succeeds.
            var ordinary = terminal(manager, new AskService(tasks).submit(new AskRequest("ordinary question", null)).taskId());
            assertEquals("ask-v1", ordinary.promptVersion());
            assertEquals("ordinary question", execution.get().input());
            assertEquals(AskPrompt.SYSTEM, execution.get().system());
        } finally { release.countDown(); manager.close(); }
    }
    @Test void sharedBudgetIncludesAllContentAndWrapperWithoutTruncationOrProfileExpansion() {
        var p = TestSettings.settings(URI.create("http://127.0.0.1:1"));
        Provider provider = new Provider() {
            public String id() { return "ollama"; }
            public ModelProfile.Locality locality() { return ModelProfile.Locality.LOCAL; }
            public Set<Capability> capabilities() { return Set.of(Capability.TEXT_GENERATION); }
            public ProviderReadiness readiness(ModelProfile profile) { return null; }
            public String execute(ProviderExecution e, Cancellation c) { return "safe"; }
        };
        var manager = new TaskManager(p);
        try (var store = new MemoryStore(temporary.resolve("data"), temporary.resolve("auth/client-token"))) {
            var tasks = new TextTaskSubmission(new ProfileResolver(p), new ProviderRegistry(List.of(provider)), new ProviderPolicy(), manager);
            var service = new MemoryAskService(tasks, store);
            var item = store.create(MemoryItem.Type.PROJECT_NOTE, "title", "x".repeat(2000));
            for (String question : List.of("q".repeat(1100), "\"".repeat(600))) {
                String combined = MemoryAskPrompt.input(question, store.snapshotForAsk(List.of(new MemoryReference(item.id(), 1))));
                assertTrue(question.length() <= 3000); assertTrue(combined.length() > 3000);
                assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(WorkspaceException.class, () -> service.submit(
                        new MemoryAskRequest(question, List.of(new MemoryReference(item.id(), 1)), null))).error().code());
                assertEquals("x".repeat(2000), store.get(item.id()).content());
            }
            assertEquals(8192, p.ask().contextBudget()); assertEquals(2048, p.ask().outputBudget()); assertEquals(3000, p.ask().maxTextCharacters());
            var utf8 = store.create(MemoryItem.Type.PROJECT_NOTE, "title", "中".repeat(1800));
            String question = "q".repeat(200);
            String combined = MemoryAskPrompt.input(question, store.snapshotForAsk(List.of(new MemoryReference(utf8.id(), 1))));
            assertTrue(combined.length() < 3000); assertTrue(combined.getBytes(StandardCharsets.UTF_8).length > 5632);
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(WorkspaceException.class, () -> service.submit(
                    new MemoryAskRequest(question, List.of(new MemoryReference(utf8.id(), 1)), null))).error().code());
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(WorkspaceException.class, () -> service.submit(
                    new MemoryAskRequest("q", List.of(new MemoryReference(item.id(), 1)), "translate.fast"))).error().code());
        } finally { manager.close(); }
    }
    private TaskView terminal(TaskManager manager, UUID id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            var view = manager.get(id);
            if (view.status() != TaskStatus.QUEUED && view.status() != TaskStatus.RUNNING) return view;
            Thread.sleep(5);
        }
        throw new AssertionError("Task deadline");
    }
}
