package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Bounded, transient operation owner, independent of AI TaskManager and all stores. */
public final class WebFetchService implements AutoCloseable {
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public record View(String operationId, State state, WebFetchResult result, ApiError error) {
        @Override public String toString() { return "WebFetchView[" + state + "]"; }
    }
    @FunctionalInterface interface Fetcher { WebFetchResult fetch(WebTarget target, WebExecution execution); }
    private final WebPolicy policy;
    private final Fetcher fetcher;
    private final ScheduledExecutorService timer;
    private final LongSupplier nanoClock;
    private final ScheduledFuture<?> expiry;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(WebLimits.QUEUED), runnable -> daemon(runnable, "web-fetch"), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private boolean closed;
    WebFetchService(WebPolicy policy, Fetcher fetcher, ScheduledExecutorService timer, LongSupplier nanoClock) {
        this.policy = policy; this.fetcher = fetcher; this.timer = timer; this.nanoClock = nanoClock;
        expiry = timer.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }
    static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
    static ScheduledThreadPoolExecutor timer() {
        var executor = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "web-deadlines"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    public synchronized View submit(ClientIdentity identity, String operationId, String url) {
        nativeOnly(identity);
        purge();
        canonicalId(operationId);
        if (closed) throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "WEB");
        if (policy == WebPolicy.DISABLED) throw new WorkspaceException(ErrorCode.WEB_DISABLED, "ADMISSION");
        WebTarget target = WebTarget.parse(url);
        Operation existing = operations.get(operationId);
        if (existing != null) {
            if (!existing.target.url().equals(target.url())) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "ADMISSION");
            return existing.view();
        }
        if (operations.size() >= WebLimits.RETAINED) throw new WorkspaceException(ErrorCode.QUEUE_FULL, "ADMISSION");
        long admitted = System.nanoTime();
        Operation operation = new Operation(operationId, target,
                new WebExecution(admitted + TimeUnit.SECONDS.toNanos(WebLimits.TOTAL_SECONDS)));
        operations.put(operationId, operation);
        operation.queueDeadline = timer.schedule(() -> queueExpired(operation), WebLimits.QUEUE_SECONDS, TimeUnit.SECONDS);
        operation.totalDeadline = timer.schedule(() -> timedOut(operation), WebLimits.TOTAL_SECONDS, TimeUnit.SECONDS);
        try { worker.execute(operation); }
        catch (RejectedExecutionException ignored) {
            operations.remove(operationId); operation.cancelTimers();
            throw new WorkspaceException(ErrorCode.QUEUE_FULL, "ADMISSION");
        }
        return operation.view();
    }
    public synchronized View get(ClientIdentity identity, String id) { nativeOnly(identity); return require(id).view(); }
    public synchronized View cancel(ClientIdentity identity, String id) {
        nativeOnly(identity);
        Operation operation = require(id);
        if (!operation.terminal()) finish(operation, State.CANCELLED, null, null);
        return operation.view();
    }
    public static void nativeOnly(ClientIdentity identity) {
        if (identity == null || !identity.clientType().equals("native") || !identity.clientId().equals(ClientIdentity.NATIVE_OWNER))
            throw new WorkspaceException(ErrorCode.POLICY_DENIED, "WEB");
    }
    private static void canonicalId(String id) {
        try { if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException ignored) { throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "WEB"); }
    }
    private Operation require(String id) {
        purge(); canonicalId(id);
        Operation operation = operations.get(id);
        if (operation == null) throw new WorkspaceException(ErrorCode.WEB_FETCH_NOT_FOUND, "WEB");
        return operation;
    }
    private void purge() {
        long now = nanoClock.getAsLong();
        operations.values().removeIf(operation -> operation.terminalAt != null
                && now - operation.terminalAt >= TimeUnit.SECONDS.toNanos(WebLimits.RETENTION_SECONDS));
    }
    private synchronized void expire() { purge(); }
    private synchronized void queueExpired(Operation operation) {
        if (operation.state == State.QUEUED) finish(operation, State.FAILED, null, ApiError.of(ErrorCode.WEB_TIMEOUT, "QUEUE"));
    }
    private synchronized void timedOut(Operation operation) {
        if (!operation.terminal()) finish(operation, State.FAILED, null, ApiError.of(ErrorCode.WEB_TIMEOUT, "WEB"));
    }
    private void finish(Operation operation, State state, WebFetchResult result, ApiError error) {
        operation.execution.invalidate();
        worker.remove(operation);
        operation.cancelTimers();
        operation.state = state; operation.result = result; operation.error = error; operation.terminalAt = nanoClock.getAsLong();
    }
    @Override public synchronized void close() {
        closed = true;
        expiry.cancel(false);
        for (Operation operation : operations.values()) if (!operation.terminal()) finish(operation, State.CANCELLED, null, null);
        worker.shutdownNow();
        operations.clear();
    }
    private final class Operation implements Runnable {
        final String id; final WebTarget target; final WebExecution execution;
        State state = State.QUEUED; WebFetchResult result; ApiError error; Long terminalAt;
        ScheduledFuture<?> queueDeadline, totalDeadline;
        Operation(String id, WebTarget target, WebExecution execution) { this.id = id; this.target = target; this.execution = execution; }
        boolean terminal() { return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED; }
        View view() { return new View(id, state, result, error); }
        void cancelTimers() { if (queueDeadline != null) queueDeadline.cancel(false); if (totalDeadline != null) totalDeadline.cancel(false); }
        @Override public void run() {
            synchronized (WebFetchService.this) {
                if (terminal()) return;
                if (queueDeadline.getDelay(TimeUnit.NANOSECONDS) <= 0) {
                    finish(this, State.FAILED, null, ApiError.of(ErrorCode.WEB_TIMEOUT, "QUEUE")); return;
                }
                state = State.RUNNING; queueDeadline.cancel(false);
            }
            WebFetchResult acquired = null; ApiError failure = null;
            try { execution.check(); acquired = fetcher.fetch(target, execution); execution.check(); }
            catch (WorkspaceException controlled) { failure = controlled.error(); }
            catch (RuntimeException ignored) { failure = ApiError.of(ErrorCode.WEB_FETCH_FAILED, "WEB"); }
            synchronized (WebFetchService.this) {
                if (terminal()) return; // Cancellation/timeout wins; no late publication.
                try { execution.check(); }
                catch (WorkspaceException timedOut) { failure = timedOut.error(); }
                finish(this, failure == null ? State.SUCCEEDED : State.FAILED, failure == null ? acquired : null, failure);
            }
        }
        @Override public String toString() { return "WebFetchOperation[" + state + "]"; }
    }
}
