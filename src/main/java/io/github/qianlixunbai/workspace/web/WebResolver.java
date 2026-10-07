package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import java.net.*;
import java.util.concurrent.*;

final class WebResolver implements AutoCloseable {
    @FunctionalInterface interface Lookup { InetAddress[] resolve(String absoluteHost) throws UnknownHostException; }
    private final Lookup lookup;
    // No queue or replacement worker. An uninterruptible lookup retains this one worker until it returns.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> { Thread t = new Thread(runnable, "web-dns"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    WebResolver() { this(InetAddress::getAllByName); }
    WebResolver(Lookup lookup) { this.lookup = lookup; }
    ValidatedAddressSet resolve(WebTarget target, WebExecution execution, long hopDeadline) {
        execution.check();
        long dnsDeadline = System.nanoTime() + execution.remaining(hopDeadline, WebLimits.DNS_SECONDS);
        Future<InetAddress[]> future;
        try { future = worker.submit(() -> lookup.resolve(target.hostname() + ".")); }
        catch (RejectedExecutionException ignored) { throw new WorkspaceException(ErrorCode.WEB_DNS_FAILED, "DNS"); }
        try {
            execution.attach(() -> future.cancel(true));
            InetAddress[] answers = future.get(execution.remaining(dnsDeadline, WebLimits.DNS_SECONDS), TimeUnit.NANOSECONDS);
            execution.check();
            if (System.nanoTime() >= dnsDeadline) { execution.invalidate(); throw WebExecution.timeout(); }
            ValidatedAddressSet validated = ValidatedAddressSet.validate(target.hostname(), answers);
            execution.check();
            if (System.nanoTime() >= dnsDeadline) { execution.invalidate(); throw WebExecution.timeout(); }
            return validated;
        } catch (TimeoutException | CancellationException ignored) {
            execution.invalidate();
            throw WebExecution.timeout();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); execution.invalidate(); throw WebExecution.timeout();
        } catch (ExecutionException ignored) {
            execution.check(); throw new WorkspaceException(ErrorCode.WEB_DNS_FAILED, "DNS");
        } finally { future.cancel(true); execution.detach(); }
    }
    @Override public void close() { worker.shutdownNow(); }
}
