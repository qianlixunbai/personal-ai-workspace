package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import java.util.concurrent.*;

/** Cancellation/deadline authority shared by the caller and each bounded phase. */
final class WebExecution {
    private final long deadline;
    private boolean invalid;
    private Runnable abort = () -> {};
    WebExecution(long deadline) { this.deadline = deadline; }
    synchronized void check() {
        if (invalid || System.nanoTime() >= deadline) throw timeout();
    }
    synchronized void attach(Runnable action) { check(); abort = action; }
    synchronized void detach() { abort = () -> {}; }
    synchronized void invalidate() {
        invalid = true;
        abort.run();
        abort = () -> {};
    }
    long remaining(long hopDeadline, int seconds) {
        check();
        long nanos = Math.min(TimeUnit.SECONDS.toNanos(seconds), Math.min(deadline, hopDeadline) - System.nanoTime());
        if (nanos <= 0) throw timeout();
        return nanos;
    }
    Phase phase(ScheduledExecutorService timer, long hopDeadline, int seconds) {
        ScheduledFuture<?> future = timer.schedule(this::invalidate, remaining(hopDeadline, seconds), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }
    static WorkspaceException timeout() { return new WorkspaceException(ErrorCode.WEB_TIMEOUT, "WEB"); }
    interface Phase extends AutoCloseable { @Override void close(); }
}
