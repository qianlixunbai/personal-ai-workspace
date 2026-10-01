package io.github.qianlixunbai.workspace.task;

import io.github.qianlixunbai.workspace.common.*;

public final class Cancellation {
    private boolean cancelled;
    private Runnable action;

    public synchronized void check() {
        if (cancelled) throw new WorkspaceException(ErrorCode.TASK_CANCELLED, "CANCELLATION");
    }
    public synchronized void attach(Runnable action) {
        if (cancelled) { action.run(); check(); }
        this.action = action;
    }
    public synchronized void detach() { action = null; }
    public synchronized void cancel() {
        cancelled = true;
        if (action != null) { action.run(); action = null; }
    }
}
