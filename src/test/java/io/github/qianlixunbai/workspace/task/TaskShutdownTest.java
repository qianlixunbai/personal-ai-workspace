package io.github.qianlixunbai.workspace.task;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real task executors and Spring destruction, with controlled local work; no model or database. */
class TaskShutdownTest {
    private static TaskManager manager() {
        return new TaskManager(TestSettings.withTasks(TestSettings.tasks(
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(1))));
    }
    private static void entered(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Local lifecycle stage was not reached");
    }
    private static void held(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static class Lease implements TaskManager.Lease {
        public void running() { }
        public void cancelled() { }
        public void close() { }
    }
    static class Resource implements AutoCloseable {
        final TaskManager tasks; final AtomicBoolean premature; final AtomicInteger closed;
        Resource(TaskManager tasks, AtomicBoolean premature, AtomicInteger closed) {
            this.tasks = tasks; this.premature = premature; this.closed = closed;
        }
        public void close() { if (!tasks.shutdownComplete()) premature.set(true); closed.incrementAndGet(); }
    }

    @Test void springCloseWaitsForWorkerAndLeaseBeforeResources() throws Exception {
        var tasks = manager();
        var context = new AnnotationConfigApplicationContext();
        var premature = new AtomicBoolean(); var resourcesClosed = new AtomicInteger();
        context.registerBean(TaskManager.class, () -> tasks);
        for (String name : List.of("conversationStore", "activeModelManager"))
            context.registerBean(name, Resource.class, () -> new Resource(tasks, premature, resourcesClosed),
                    definition -> definition.setDestroyMethodName("close"));
        context.refresh();
        var enteredWork = new CountDownLatch(1); var releaseWork = new CountDownLatch(1);
        var cancelled = new CountDownLatch(2); var enteredLease = new CountDownLatch(1); var releaseLease = new CountDownLatch(1);
        var outcomes = new CopyOnWriteArrayList<TaskStatus>(); var queuedRan = new AtomicBoolean();
        var closer = Executors.newSingleThreadExecutor();
        try {
            UUID id = UUID.randomUUID();
            tasks.submit(id, ClientIdentity.NATIVE_OWNER, "conversation", TestSettings.ask(), "local", c -> {
                enteredWork.countDown(); held(releaseWork); return "late result";
            }, (task, status, result, error) -> outcomes.add(status), new Lease() {
                public void cancelled() { cancelled.countDown(); }
                public void close() { enteredLease.countDown(); held(releaseLease); }
            });
            entered(enteredWork);
            tasks.submit(UUID.randomUUID(), ClientIdentity.NATIVE_OWNER, "conversation", TestSettings.ask(), "local", c -> {
                queuedRan.set(true); return "queued result";
            }, (task, status, result, error) -> outcomes.add(status), new Lease() {
                public void cancelled() { cancelled.countDown(); }
            });
            Future<?> stopped = closer.submit(context::close);
            entered(cancelled);
            assertFalse(stopped.isDone()); assertFalse(tasks.shutdownComplete()); assertEquals(0, resourcesClosed.get());
            assertEquals(TaskStatus.CANCELLED, tasks.get(id).status());
            assertThrows(WorkspaceException.class, () -> tasks.submit("ask", TestSettings.ask(), "local", c -> "new"));
            releaseWork.countDown(); entered(enteredLease);
            assertFalse(stopped.isDone()); assertEquals(0, resourcesClosed.get());
            releaseLease.countDown(); stopped.get(2, TimeUnit.SECONDS);
            assertTrue(tasks.shutdownComplete()); assertFalse(premature.get()); assertEquals(2, resourcesClosed.get());
            assertFalse(queuedRan.get()); assertEquals(List.of(TaskStatus.CANCELLED, TaskStatus.CANCELLED), outcomes);
        } finally {
            releaseWork.countDown(); releaseLease.countDown(); closer.shutdown();
            assertTrue(closer.awaitTermination(3, TimeUnit.SECONDS)); context.close();
        }
    }

    @Test void closeAlsoWaitsForConcurrentCancellationHook() throws Exception {
        var tasks = manager(); var workEntered = new CountDownLatch(1); var releaseWork = new CountDownLatch(1);
        var cancelEntered = new CountDownLatch(1); var releaseCancel = new CountDownLatch(1);
        var workerClosed = new CountDownLatch(1); var callers = Executors.newFixedThreadPool(2);
        try {
            UUID id = tasks.submit(UUID.randomUUID(), ClientIdentity.NATIVE_OWNER, "ask", TestSettings.ask(), "local", c -> {
                workEntered.countDown(); held(releaseWork); return "late";
            }, null, new Lease() {
                public void cancelled() { cancelEntered.countDown(); held(releaseCancel); }
                public void close() { workerClosed.countDown(); }
            }).taskId();
            entered(workEntered);
            Future<?> cancelling = callers.submit(() -> tasks.cancel(id)); entered(cancelEntered);
            releaseWork.countDown(); entered(workerClosed);
            Future<?> closing = callers.submit(tasks::close);
            assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            assertFalse(tasks.shutdownComplete());
            releaseCancel.countDown(); cancelling.get(2, TimeUnit.SECONDS); closing.get(2, TimeUnit.SECONDS);
            assertTrue(tasks.shutdownComplete());
        } finally {
            releaseWork.countDown(); releaseCancel.countDown(); callers.shutdown();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS)); tasks.close();
        }
    }

    @Test void incompleteDrainCannotBecomeSuccessAfterLateExit() throws Exception {
        for (String failure : List.of("deadline", "interruption", "cancel-hook")) {
            var tasks = manager(); var workEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var cancelled = new CountDownLatch(1); var exited = new CountDownLatch(1);
            var result = new AtomicReference<Throwable>(); var interrupted = new AtomicBoolean();
            tasks.submit(UUID.randomUUID(), ClientIdentity.NATIVE_OWNER, "ask", TestSettings.ask(), "local", c -> {
                workEntered.countDown(); held(release); return "late";
            }, null, new Lease() {
                public void cancelled() {
                    cancelled.countDown();
                    if (failure.equals("cancel-hook")) { release.countDown(); throw new IllegalStateException("synthetic cleanup failure"); }
                }
                public void close() { exited.countDown(); }
            });
            entered(workEntered);
            Thread closer = Thread.ofPlatform().unstarted(() -> {
                try { tasks.close(); } catch (Throwable thrown) { result.set(thrown); }
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            try {
                closer.start(); entered(cancelled);
                if (failure.equals("interruption")) closer.interrupt();
                closer.join(7000); assertFalse(closer.isAlive());
                assertInstanceOf(IllegalStateException.class, result.get());
                assertEquals(failure.equals("interruption"), interrupted.get());
                assertFalse(tasks.shutdownComplete());
                release.countDown(); entered(exited);
                assertThrows(IllegalStateException.class, tasks::close);
                assertFalse(tasks.shutdownComplete());
            } finally { release.countDown(); closer.join(7000); }
        }
    }
}
