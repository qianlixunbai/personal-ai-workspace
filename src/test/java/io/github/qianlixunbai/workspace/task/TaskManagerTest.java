package io.github.qianlixunbai.workspace.task;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TaskManagerTest {
    @Test void boundedQueueCancellationAndLateSuccess() throws Exception {
        try (ManagerScope scope = new ManagerScope(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3))) {
            TaskManager manager = scope.manager;
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
            AtomicBoolean queuedRan = new AtomicBoolean();
            UUID running = manager.submit("summarize", TestSettings.summarize(), "p1", cancellation -> {
                entered.countDown(); await(release); exited.countDown(); return "late private answer";
            }).taskId();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            UUID queued = manager.submit("ask", TestSettings.ask(), "p1", cancellation -> {
                queuedRan.set(true); return "should not execute";
            }).taskId();
            assertEquals(ErrorCode.QUEUE_FULL, assertThrows(WorkspaceException.class,
                    () -> manager.submit("translate", TestSettings.profile(), "p1", c -> "overflow")).error().code());
            assertEquals(TaskStatus.CANCELLED, manager.cancel(queued).status());
            UUID replacement = manager.submit("translate", TestSettings.profile(), "p1", c -> "replacement").taskId();
            assertEquals(TaskStatus.CANCELLED, manager.cancel(running).status());
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(TaskStatus.SUCCEEDED, terminal(manager, replacement).status());
            assertFalse(queuedRan.get());
            assertNull(manager.get(running).result());
            assertNull(manager.get(queued).result());
            assertEquals(TaskStatus.CANCELLED, manager.cancel(running).status());
        }
    }

    @Test void queueAndExecutionTimeoutsRemainDistinctAndLateResultsAreDiscarded() throws Exception {
        try (ManagerScope scope = new ManagerScope(Duration.ofMillis(60), Duration.ofMillis(150), Duration.ofSeconds(3))) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            UUID running = scope.manager.submit("ask", TestSettings.ask(), "p1", c -> {
                entered.countDown(); await(release); return "late";
            }).taskId();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            UUID queued = scope.manager.submit("summarize", TestSettings.summarize(), "p1", c -> "queued").taskId();
            TaskView queueTimeout = terminal(scope.manager, queued);
            assertEquals(TaskStatus.TIMED_OUT, queueTimeout.status());
            assertEquals("QUEUE", queueTimeout.error().phase());
            TaskView executionTimeout = terminal(scope.manager, running);
            assertEquals("EXECUTION", executionTimeout.error().phase());
            assertEquals(TaskStatus.TIMED_OUT, executionTimeout.status());
            release.countDown();
            assertNull(scope.manager.get(running).result());
        }
    }

    @Test void resultRetentionIsBoundedAndExpires() throws Exception {
        try (ManagerScope scope = new ManagerScope(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofMillis(400))) {
            UUID first = null;
            for (int n = 0; n < 4; n++) {
                UUID id = scope.manager.submit("translate", TestSettings.profile(), "p1", c -> "short lived").taskId();
                if (first == null) first = id;
                assertEquals(TaskStatus.SUCCEEDED, terminal(scope.manager, id).status());
            }
            assertEquals(ErrorCode.QUEUE_FULL, assertThrows(WorkspaceException.class,
                    () -> scope.manager.submit("translate", TestSettings.profile(), "p1", c -> "excess")).error().code());
            Thread.sleep(450);
            UUID expired = first;
            assertEquals(ErrorCode.TASK_NOT_FOUND, assertThrows(WorkspaceException.class,
                    () -> scope.manager.get(expired)).error().code());
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
            assertEquals(ErrorCode.INTERNAL_ERROR, failure.error().code());
            assertFalse(failure.error().message().contains("secret"));
        }
    }

    public static TaskView terminal(TaskManager manager, UUID id) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            TaskView view = manager.get(id);
            if (view.status() != TaskStatus.QUEUED && view.status() != TaskStatus.RUNNING) return view;
            Thread.sleep(5);
        }
        throw new AssertionError("Task did not terminate");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
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
