package io.github.qianlixunbai.workspace.task;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import jakarta.annotation.PreDestroy;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Component
// Async work outlives its submitting service. Keep its actual resources alive until drain.
@org.springframework.context.annotation.DependsOn({"conversationStore", "activeModelManager"})
public final class TaskManager {
    @FunctionalInterface public interface Work { Object execute(Cancellation cancellation); }
    /** Callbacks always run outside the task monitor. */
    public interface Lease extends AutoCloseable {
        void running();
        void cancelled();
        @Override void close();
    }
    /** Serialized with cancellation/deadlines, before publishing the terminal task. */
    @FunctionalInterface public interface Completion { void finish(UUID taskId, TaskStatus status, Object result, ApiError error); }
    private final RuntimeProperties.Tasks settings;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;
    private final Map<UUID, Job> tasks = new HashMap<>();
    private final Object shutdown = new Object();
    private static final long DRAIN_NANOS = TimeUnit.SECONDS.toNanos(5);
    private int cancelling;
    private volatile boolean shutdownFailed;
    private boolean closed;

    public TaskManager(RuntimeProperties properties) {
        settings = properties.tasks();
        workers = new ThreadPoolExecutor(settings.concurrency(), settings.concurrency(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), Thread.ofPlatform().name("ai-task-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("task-deadlines").factory());
        deadlines.setRemoveOnCancelPolicy(true);
        timer = deadlines;
        timer.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    // Keep durable history open until active tasks have published their shutdown outcomes.
    @org.springframework.beans.factory.annotation.Autowired
    public TaskManager(RuntimeProperties properties, io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory history) {
        this(properties);
    }

    public synchronized TaskView submit(String capability, ModelProfile profile, String promptVersion, Work work) {
        return submit(ClientIdentity.NATIVE_OWNER, capability, profile, promptVersion, work);
    }

    public synchronized TaskView submit(String ownerClientId, String capability, ModelProfile profile, String promptVersion, Work work) {
        return submit(UUID.randomUUID(), ownerClientId, capability, profile, promptVersion, work, null);
    }
    public synchronized TaskView submit(UUID taskId, String ownerClientId, String capability, ModelProfile profile,
                                        String promptVersion, Work work, Completion completion) {
        return submit(taskId, ownerClientId, capability, profile, promptVersion, work, completion, null);
    }
    public synchronized TaskView submit(UUID taskId, String ownerClientId, String capability, ModelProfile profile,
                                        String promptVersion, Work work, Completion completion, Lease lease) {
        Objects.requireNonNull(ownerClientId);
        expire();
        if (closed || tasks.size() >= settings.maxRetained()) throw new WorkspaceException(ErrorCode.QUEUE_FULL, "ADMISSION");
        if (tasks.containsKey(taskId)) throw new WorkspaceException("vision".equals(capability) ? ErrorCode.DUPLICATE_TASK_ID : ErrorCode.INTERNAL_ERROR, "TASK_ID");
        Job job = new Job(taskId, ownerClientId, capability, profile.publicInfo(), promptVersion, work, completion, lease);
        tasks.put(job.id, job);
        job.deadline = timer.schedule(() -> timeout(job, TaskStatus.QUEUED, "QUEUE"),
                budget(capability,settings.queueTimeout(),30).toNanos(), TimeUnit.NANOSECONDS);
        try { workers.execute(job); }
        catch (RejectedExecutionException failure) {
            tasks.remove(job.id);
            job.deadline.cancel(false);
            job.work = null;
            throw new WorkspaceException(ErrorCode.QUEUE_FULL, "QUEUE");
        }
        return job.view();
    }

    public synchronized TaskView get(UUID id) { return get(id, ClientIdentity.NATIVE_OWNER); }

    public TaskView cancel(UUID id) {
        return cancel(id, ClientIdentity.NATIVE_OWNER);
    }

    public synchronized TaskView get(UUID id, String ownerClientId) { return find(id, ownerClientId).view(); }

    public TaskView cancel(UUID id, String ownerClientId) {
        Job job; boolean release = false, cancelled = false; TaskView view;
        synchronized (this) {
            job = find(id, ownerClientId);
            if (job.status == TaskStatus.QUEUED || job.status == TaskStatus.RUNNING) {
                finish(job, TaskStatus.CANCELLED, null, ApiError.of(ErrorCode.TASK_CANCELLED, "CANCELLATION"));
                workers.remove(job);
                job.work = null;
                release = !job.inWorker; cancelled = true;
                cancelling++;
            }
            view = job.view();
        }
        endCancelledLease(job, cancelled, release);
        return view;
    }

    private void timeout(Job job, TaskStatus expected, String phase) {
        boolean release;
        synchronized (this) {
            if (job.status != expected) return;
            finish(job, TaskStatus.TIMED_OUT, null, ApiError.of(ErrorCode.TASK_TIMEOUT, phase));
            workers.remove(job);
            job.work = null;
            release = !job.inWorker;
            cancelling++;
        }
        endCancelledLease(job, true, release);
    }
    private void endCancelledLease(Job job, boolean cancelled, boolean release) {
        if (!cancelled) return;
        // Transport/lease hooks can need the worker's locks. Never invoke them under this monitor.
        try {
            try { job.cancellation.cancel(); }
            finally {
                if (job.lease != null) {
                    try { job.lease.cancelled(); }
                    finally { if (release) job.lease.close(); }
                }
            }
        } catch (RuntimeException | Error failure) {
            shutdownFailed = true;
            throw failure;
        } finally {
            synchronized (this) { cancelling--; notifyAll(); }
        }
    }
    private static java.time.Duration budget(String capability,java.time.Duration configured,long seconds) {
        var maximum=java.time.Duration.ofSeconds(seconds);
        return "vision".equals(capability)&&configured.compareTo(maximum)>0?maximum:configured;
    }

    private Job find(UUID id, String ownerClientId) {
        expire();
        Job job = tasks.get(id);
        if (job == null || !job.ownerClientId.equals(ownerClientId)) throw new WorkspaceException(ErrorCode.TASK_NOT_FOUND, "TASK");
        return job;
    }

    private synchronized void expire() {
        Instant cutoff = Instant.now().minus(settings.retention());
        tasks.values().removeIf(job -> job.finishedAt != null && !job.inWorker && job.finishedAt.isBefore(cutoff));
    }

    private void finish(Job job, TaskStatus status, Object result, ApiError error) {
        if (job.completion != null) {
            try { job.completion.finish(job.id, status, result, error); }
            catch (RuntimeException persistenceFailure) {
                status = TaskStatus.FAILED; result = null;
                error = ApiError.of("knowledge-answer".equals(job.capability) ? ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE : ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE, "PERSISTENCE");
                // A failed successful commit rolls back. If storage remains unavailable,
                // durable PENDING stays for startup reconciliation; never report success.
                try { job.completion.finish(job.id, status, null, error); }
                catch (RuntimeException ignored) { }
            }
            job.completion = null;
        }
        job.status = status;
        job.result = result;
        job.error = error;
        job.finishedAt = Instant.now();
        if (job.deadline != null) job.deadline.cancel(false);
    }

    @PreDestroy
    public void close() {
        synchronized (shutdown) {
            List<Job> cancelled = new ArrayList<>();
            Set<Job> release = new HashSet<>();
            synchronized (this) {
                closed = true;
                for (Job job : tasks.values()) {
                    if (job.status == TaskStatus.QUEUED || job.status == TaskStatus.RUNNING) {
                        finish(job, TaskStatus.CANCELLED, null, ApiError.of(ErrorCode.TASK_CANCELLED, "SHUTDOWN"));
                        job.work = null;
                        cancelled.add(job); cancelling++;
                        if (!job.inWorker) release.add(job);
                    }
                }
                timer.shutdownNow();
                workers.shutdownNow();
            }
            for (Job job : cancelled) {
                try { endCancelledLease(job, true, release.contains(job)); }
                catch (RuntimeException failure) { shutdownFailed = true; } // Continue cancelling other tasks.
            }
            // A terminal TaskView is not a worker/lease exit. Include timer callbacks and any
            // cancel caller currently outside the task monitor; none may outlive a successful close.
            long deadline = System.nanoTime() + DRAIN_NANOS;
            try {
                if (!workers.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)
                        || !timer.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) shutdownFailed = true;
                synchronized (this) {
                    while (cancelling != 0 && remaining(deadline) > 0)
                        TimeUnit.NANOSECONDS.timedWait(this, remaining(deadline));
                    if (!shutdownComplete()) shutdownFailed = true;
                    if (!shutdownFailed) tasks.clear();
                }
            } catch (InterruptedException interrupted) {
                shutdownFailed = true;
                Thread.currentThread().interrupt();
            }
            if (shutdownFailed) throw new IllegalStateException("Local task shutdown incomplete");
        }
    }
    private static long remaining(long deadline) { return Math.max(0, deadline - System.nanoTime()); }

    /** This live instance only. Never evidence of external provider completion or other writers. */
    public synchronized boolean shutdownComplete() {
        return closed && !shutdownFailed && cancelling == 0 && workers.isTerminated() && timer.isTerminated();
    }

    private final class Job implements Runnable {
        final UUID id;
        final Instant createdAt = Instant.now();
        final ModelProfile.PublicProfile profile;
        final String promptVersion;
        final String capability;
        final String ownerClientId;
        final Cancellation cancellation = new Cancellation();
        final Lease lease;
        TaskStatus status = TaskStatus.QUEUED;
        Work work;
        Completion completion;
        Object result;
        ApiError error;
        Instant finishedAt;
        ScheduledFuture<?> deadline;
        boolean inWorker;

        Job(UUID id, String ownerClientId, String capability, ModelProfile.PublicProfile profile, String promptVersion, Work work, Completion completion, Lease lease) {
            this.id = id; this.completion = completion;
            this.lease = lease;
            this.ownerClientId = ownerClientId;
            this.capability = capability; this.profile = profile; this.promptVersion = promptVersion; this.work = work;
        }

        public void run() {
            Work execution;
            synchronized (TaskManager.this) {
                if (status != TaskStatus.QUEUED) return;
                status = TaskStatus.RUNNING;
                inWorker = true;
                deadline.cancel(false);
                deadline = timer.schedule(() -> timeout(this, TaskStatus.RUNNING, "EXECUTION"),
                        budget(capability,settings.executionTimeout(),150).toNanos(), TimeUnit.NANOSECONDS);
                execution = work;
                work = null;
            }
            try {
                if (lease != null) lease.running();
                cancellation.check();
                Object output = execution.execute(cancellation);
                if (!(output instanceof String || output instanceof TaskResult))
                    throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "RESULT_TYPE");
                synchronized (TaskManager.this) {
                    if (status == TaskStatus.RUNNING) finish(this, TaskStatus.SUCCEEDED, output, null);
                }
            } catch (WorkspaceException failure) {
                synchronized (TaskManager.this) {
                    if (status == TaskStatus.RUNNING) {
                        TaskStatus outcome = switch (failure.error().code()) {
                            case TASK_TIMEOUT -> TaskStatus.TIMED_OUT;
                            case TASK_CANCELLED -> TaskStatus.CANCELLED;
                            default -> TaskStatus.FAILED;
                        };
                        finish(this, outcome, null, failure.error());
                    }
                }
            } catch (RuntimeException failure) {
                synchronized (TaskManager.this) {
                    if (status == TaskStatus.RUNNING)
                        finish(this, TaskStatus.FAILED, null, ApiError.of(ErrorCode.INTERNAL_ERROR, "EXECUTION"));
                }
            } finally {
                try { if (lease != null) lease.close(); }
                catch (RuntimeException | Error failure) { shutdownFailed = true; throw failure; }
                finally { synchronized (TaskManager.this) { inWorker = false; } }
            }
        }

        TaskView view() {
            return new TaskView(id, capability, status, profile, promptVersion, createdAt, finishedAt,
                    status == TaskStatus.SUCCEEDED ? result : null, error);
        }
    }
}
