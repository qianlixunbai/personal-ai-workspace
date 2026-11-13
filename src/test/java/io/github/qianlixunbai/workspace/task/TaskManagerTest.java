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
