package io.github.qianlixunbai.workspace.task;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TaskManagerTest {
    @Test
    void ownersIsolateRunningQueuedAndRetainedTasksAndOwnerCancellationWinsLateResult() throws Exception {
        try (ManagerScope scope = new ManagerScope(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3))) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
            UUID running = scope.manager.submit("browser-a", "translate", TestSettings.profile(), "p1", c -> {
                entered.countDown(); await(release); exited.countDown(); return "private text";
            }).taskId();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            UUID queued = scope.manager.submit("browser-a", "translate", TestSettings.profile(), "p1", c -> "private text").taskId();
            assertEquals(TaskStatus.QUEUED, scope.manager.get(queued, "browser-a").status());
            for (UUID id : new UUID[]{running, queued}) {
                for (String wrongOwner : new String[]{"browser-b", "native-local"}) {
                    assertEquals(ErrorCode.TASK_NOT_FOUND, assertThrows(WorkspaceException.class, () -> scope.manager.get(id, wrongOwner)).error().code());
                    assertEquals(ErrorCode.TASK_NOT_FOUND, assertThrows(WorkspaceException.class, () -> scope.manager.cancel(id, wrongOwner)).error().code());
                }
            }
            assertEquals(TaskStatus.RUNNING, scope.manager.get(running, "browser-a").status());
            assertEquals(TaskStatus.CANCELLED, scope.manager.cancel(queued, "browser-a").status());
            assertEquals(TaskStatus.CANCELLED, scope.manager.cancel(running, "browser-a").status());
            UUID barrier = scope.manager.submit("browser-a", "translate", TestSettings.profile(), "p1", c -> "barrier").taskId();
            release.countDown(); assertTrue(exited.await(2, TimeUnit.SECONDS));
            // One worker: finishing its next job proves the cancelled work's result was processed.
            assertEquals(TaskStatus.SUCCEEDED, terminal(scope.manager, barrier, "browser-a").status());
            assertNull(scope.manager.get(running, "browser-a").result());
            assertEquals(TaskStatus.CANCELLED, scope.manager.get(running, "browser-a").status());
            assertThrows(WorkspaceException.class, () -> scope.manager.get(running, "browser-b"));
            for (UUID retained : new UUID[]{running, queued, barrier}) {
                for (String wrongOwner : new String[]{"browser-b", ClientIdentity.NATIVE_OWNER}) {
                    assertEquals(ErrorCode.TASK_NOT_FOUND, assertThrows(WorkspaceException.class,
                            () -> scope.manager.get(retained, wrongOwner)).error().code());
                    assertEquals(ErrorCode.TASK_NOT_FOUND, assertThrows(WorkspaceException.class,
                            () -> scope.manager.cancel(retained, wrongOwner)).error().code());
                }
            }
        }
    }
    @Test
    void boundedQueueCancellationAndLateSuccess() throws Exception {
        try (ManagerScope scope = new ManagerScope(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3))) {
            TaskManager manager = scope.manager;
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
            AtomicBoolean queuedRan = new AtomicBoolean();
            UUID running = manager.submit("summarize", TestSettings.summarize(), "p1", cancellation -> {
                entered.countDown(); await(release); exited.countDown(); return "private text";
            }).taskId();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            UUID queued = manager.submit("ask", TestSettings.ask(), "p1", cancellation -> {
                queuedRan.set(true); return "private text";
            }).taskId();
            assertEquals(ErrorCode.QUEUE_FULL, assertThrows(WorkspaceException.class,
                    () -> manager.submit("translate", TestSettings.profile(), "p1", c -> "private text")).error().code());
            assertEquals(TaskStatus.CANCELLED, manager.cancel(queued).status());
            UUID replacement = manager.submit("translate", TestSettings.profile(), "p1", c -> "private text").taskId();
            assertEquals(TaskStatus.CANCELLED, manager.cancel(running).status());
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            var success = terminal(manager, replacement);
            assertEquals(TaskStatus.SUCCEEDED, success.status());
            assertEquals("private text", success.result());
            assertEquals(success, manager.cancel(replacement));
            assertFalse(queuedRan.get());
            assertNull(manager.get(running).result());
            assertNull(manager.get(queued).result());
            assertEquals(TaskStatus.CANCELLED, manager.cancel(running).status());
        }
    }

    @Test
    void queueAndExecutionTimeoutsRemainDistinctAndLateResultsAreDiscarded() throws Exception {
        try (ManagerScope scope = new ManagerScope(Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(3))) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            UUID running = scope.manager.submit("ask", TestSettings.ask(), "p1", c -> {
                entered.countDown(); await(release); return "private text";
            }).taskId();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            UUID queued = scope.manager.submit("summarize", TestSettings.summarize(), "p1", c -> "private text").taskId();
            TaskView queueTimeout = terminal(scope.manager, queued);
            assertEquals(TaskStatus.TIMED_OUT, queueTimeout.status());
            assertEquals("QUEUE", queueTimeout.error().phase());
            TaskView executionTimeout = terminal(scope.manager, running);
            assertEquals("EXECUTION", executionTimeout.error().phase());
            assertEquals(TaskStatus.TIMED_OUT, executionTimeout.status());
            UUID barrier = scope.manager.submit("translate", TestSettings.profile(), "p1", c -> "barrier").taskId();
            release.countDown();
            assertEquals(TaskStatus.SUCCEEDED, terminal(scope.manager, barrier).status());
            assertEquals(TaskStatus.TIMED_OUT, scope.manager.get(running).status());
            assertNull(scope.manager.get(running).result());
        }
    }

    @Test
    void resultRetentionIsBoundedAndExpires() throws Exception {
        try (ManagerScope scope = new ManagerScope(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(1))) {
            UUID first = null;
            for (int n = 0; n < 4; n++) {
                UUID id = scope.manager.submit("translate", TestSettings.profile(), "p1", c -> "private text").taskId();
                if (first == null) first = id;
                assertEquals(TaskStatus.SUCCEEDED, terminal(scope.manager, id).status());
            }
            assertEquals(ErrorCode.QUEUE_FULL, assertThrows(WorkspaceException.class,
                    () -> scope.manager.submit("translate", TestSettings.profile(), "p1", c -> "excess")).error().code());
            UUID expired = first;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (true) {
                try { scope.manager.get(expired); }
                catch (WorkspaceException failure) {
                    assertEquals(ErrorCode.TASK_NOT_FOUND, failure.error().code());
                    break;
                }
                assertTrue(System.nanoTime() < deadline, "Retained task did not expire");
                Thread.sleep(5);
            }
            assertNotNull(scope.manager.submit("translate", TestSettings.profile(), "p1", c -> "new"));
        }
    }

    @Test void cancelledHookPropagatesAndUnexpectedFailuresAreSanitized() throws Exception {
        Cancellation token = new Cancellation();
        AtomicBoolean stopped = new AtomicBoolean();
        token.cancel();
        assertThrows(WorkspaceException.class, () -> token.attach(() -> stopped.set(true)));
        assertTrue(stopped.get());
        try (ManagerScope scope = new ManagerScope(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3))) {
            UUID id = scope.manager.submit("translate", TestSettings.profile(), "p1", c -> {
                throw new IllegalStateException("raw secret path private text");
            }).taskId();
            TaskView failure = terminal(scope.manager, id);
            assertEquals(TaskStatus.FAILED, failure.status());
            assertNull(failure.result());
            assertEquals(ErrorCode.INTERNAL_ERROR, failure.error().code());
            assertFalse(failure.error().message().contains("secret"));
        }
    }

    @Test void structuredResultIsImmutableAndDiagnosticsAreRedactedAndArbitraryObjectsAreRejected() throws Exception {
        var mutable = new java.util.ArrayList<>(List.of(new TaskResult.Translation(1, "private translation")));
        var batch = new TaskResult.TranslationBatch(mutable);
        mutable.clear();
        assertEquals(1, batch.items().size());
        assertThrows(UnsupportedOperationException.class, () -> batch.items().clear());
        assertFalse(batch.toString().contains("private"));
        assertFalse(batch.items().getFirst().toString().contains("private"));
        try (ManagerScope scope = new ManagerScope(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3))) {
            var accepted = scope.manager.submit("translate", TestSettings.profile(), "p1", c -> batch);
            var success = terminal(scope.manager, accepted.taskId());
            assertEquals(TaskStatus.SUCCEEDED, success.status());
            assertEquals(batch, success.result());
            var task = scope.manager.submit("translate", TestSettings.profile(), "p1", c -> java.util.Map.of("arbitrary", "private"));
            assertEquals(ErrorCode.INTERNAL_ERROR, terminal(scope.manager, task.taskId()).error().code());
        }
    }
    public static TaskView terminal(TaskManager manager, UUID id) throws Exception {
        return terminal(manager, id, ClientIdentity.NATIVE_OWNER);
    }
    private static TaskView terminal(TaskManager manager, UUID id, String owner) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            TaskView view = manager.get(id, owner);
            if (view.status() != TaskStatus.QUEUED && view.status() != TaskStatus.RUNNING) return view;
            Thread.sleep(5);
        }
        throw new AssertionError("Task did not terminate");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
    }
    private static final class ManagerScope implements AutoCloseable {
        final TaskManager manager;
        ManagerScope(Duration queue, Duration execution, Duration retention) {
            manager = new TaskManager(TestSettings.withTasks(TestSettings.tasks(queue, execution, retention)));
        }
        public void close() { manager.close(); }
    }
}
