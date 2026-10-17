package io.github.qianlixunbai.workspace.capability.vision;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.task.TaskManager;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** One slot includes HTTP upload/decoder and remains transferred through actual task worker exit. */
@Component
public final class VisionSlot {
    private final AtomicBoolean occupied=new AtomicBoolean();
    public Slot acquire() {
        if(!occupied.compareAndSet(false,true)) throw new WorkspaceException(ErrorCode.QUEUE_FULL,"VISION_CAPACITY");
        return new Slot();
    }
    public final class Slot implements AutoCloseable {
        private final AtomicBoolean released=new AtomicBoolean();
        private boolean transferred;
        public TaskManager.Lease transfer(TaskManager.Lease owner) {
            transferred=true;
            return new TaskManager.Lease() {
                public void running() {owner.running();}
                public void cancelled() {owner.cancelled();}
                public void close() {try {owner.close();} finally {release();}}
            };
        }
        public void failedTransfer() { release(); }
        private void release() {if(released.compareAndSet(false,true)) occupied.set(false);}
        @Override public void close() {if(!transferred) release();}
    }
}
