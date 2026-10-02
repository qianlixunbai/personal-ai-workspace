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
public final class TaskManager {
    @FunctionalInterface public interface Work { String execute(Cancellation cancellation); }
    private final RuntimeProperties.Tasks settings;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;
    private final Map<UUID, Job> tasks = new HashMap<>();
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

    public synchronized TaskView submit(String capability, ModelProfile profile, String promptVersion, Work work) {
        return submit(ClientIdentity.NATIVE_OWNER, capability, profile, promptVersion, work);
    }

    public synchronized TaskView submit(String ownerClientId, String capability, ModelProfile profile, String promptVersion, Work work) {
        Objects.requireNonNull(ownerClientId);
        expire();
        if (closed || tasks.size() >= settings.maxRetained()) throw new WorkspaceException(ErrorCode.QUEUE_FULL, "ADMISSION");
        Job job = new Job(ownerClientId, capability, profile.publicInfo(), promptVersion, work);
        tasks.put(job.id, job);
        job.deadline = timer.schedule(() -> timeout(job, TaskStatus.QUEUED, "QUEUE"),
                settings.queueTimeout().toNanos(), TimeUnit.NANOSECONDS);
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

    public synchronized TaskView cancel(UUID id) {
        return cancel(id, ClientIdentity.NATIVE_OWNER);
    }

    public synchronized TaskView get(UUID id, String ownerClientId) { return find(id, ownerClientId).view(); }

    public synchronized TaskView cancel(UUID id, String ownerClientId) {
        Job job = find(id, ownerClientId);
        if (job.status == TaskStatus.QUEUED || job.status == TaskStatus.RUNNING) {
            finish(job, TaskStatus.CANCELLED, null, ApiError.of(ErrorCode.TASK_CANCELLED, "CANCELLATION"));
            workers.remove(job);
            job.work = null;
            job.cancellation.cancel();
        }
        return job.view();
    }

    private synchronized void timeout(Job job, TaskStatus expected, String phase) {
        if (job.status != expected) return;
        finish(job, TaskStatus.TIMED_OUT, null, ApiError.of(ErrorCode.TASK_TIMEOUT, phase));
        workers.remove(job);
        job.work = null;
        job.cancellation.cancel();
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

    private void finish(Job job, TaskStatus status, String result, ApiError error) {
        job.status = status;
        job.result = result;
        job.error = error;
        job.finishedAt = Instant.now();
        if (job.deadline != null) job.deadline.cancel(false);
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        for (Job job : tasks.values()) {
            if (job.status == TaskStatus.QUEUED || job.status == TaskStatus.RUNNING) {
                finish(job, TaskStatus.CANCELLED, null, ApiError.of(ErrorCode.TASK_CANCELLED, "SHUTDOWN"));
                job.cancellation.cancel();
                job.work = null;
            }
        }
        timer.shutdownNow();
        workers.shutdownNow();
        tasks.clear();
    }

    private final class Job implements Runnable {
        final UUID id = UUID.randomUUID();
        final Instant createdAt = Instant.now();
        final ModelProfile.PublicProfile profile;
        final String promptVersion;
        final String capability;
        final String ownerClientId;
        final Cancellation cancellation = new Cancellation();
        TaskStatus status = TaskStatus.QUEUED;
        Work work;
        String result;
        ApiError error;
        Instant finishedAt;
        ScheduledFuture<?> deadline;
        boolean inWorker;

        Job(String ownerClientId, String capability, ModelProfile.PublicProfile profile, String promptVersion, Work work) {
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
                        settings.executionTimeout().toNanos(), TimeUnit.NANOSECONDS);
                execution = work;
                work = null;
            }
            try {
                cancellation.check();
                String output = execution.execute(cancellation);
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
                synchronized (TaskManager.this) { inWorker = false; }
            }
        }

        TaskView view() {
            return new TaskView(id, capability, status, profile, promptVersion, createdAt, finishedAt,
                    status == TaskStatus.SUCCEEDED ? result : null, error);
        }
    }
}
