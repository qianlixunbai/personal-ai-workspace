package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.web.WebPolicyTest.assertCode;

class WebFetchServiceTest {
    @Test void boundedNativeAdmissionReconciliationQueueExpiryCancelRetentionAndRestart() throws Exception {
        var timer = WebFetchService.timer();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        MutableClock clock = new MutableClock();
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString(), third = UUID.randomUUID().toString();
        var service = new WebFetchService(WebPolicy.ASK_EVERY_TIME, (target, execution) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                exited.countDown();
            }
            return result(target);
        }, timer, clock);
        try (service) {
            assertCode(ErrorCode.POLICY_DENIED, () -> service.submit(new ClientIdentity("browser", "browser", "b", null, Instant.EPOCH, Set.of("translate")), first, "https://example.com"));
            assertCode(ErrorCode.INVALID_REQUEST, () -> service.submit(ClientIdentity.NATIVE, first.toUpperCase(Locale.ROOT), "https://example.com"));
            service.submit(ClientIdentity.NATIVE, first, "https://Example.COM:443");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(WebFetchService.State.RUNNING, service.submit(ClientIdentity.NATIVE, first, "https://example.com/").state());
            assertCode(ErrorCode.INVALID_REQUEST, () -> service.submit(ClientIdentity.NATIVE, first, "https://example.com/other"));
            service.submit(ClientIdentity.NATIVE, second, "https://example.com");
            service.submit(ClientIdentity.NATIVE, third, "https://example.com");
            assertCode(ErrorCode.QUEUE_FULL, () -> service.submit(ClientIdentity.NATIVE, UUID.randomUUID().toString(), "https://example.com"));
            assertEquals(ErrorCode.WEB_TIMEOUT, terminal(service, second, 6000).error().code());
            assertEquals(ErrorCode.WEB_TIMEOUT, terminal(service, third, 1000).error().code());
            assertEquals(1, calls.get());
            assertEquals(WebFetchService.State.CANCELLED, service.cancel(ClientIdentity.NATIVE, first).state());
            release.countDown(); assertTrue(exited.await(1, TimeUnit.SECONDS));
            assertEquals(WebFetchService.State.CANCELLED, service.get(ClientIdentity.NATIVE, first).state());
            assertNull(service.get(ClientIdentity.NATIVE, first).result());
            assertEquals(WebFetchService.State.CANCELLED, service.submit(ClientIdentity.NATIVE, first, "https://example.com").state());
            String succeeded = null;
            for (int i = 3; i < WebLimits.RETAINED; i++) {
                succeeded = UUID.randomUUID().toString(); service.submit(ClientIdentity.NATIVE, succeeded, "https://example.com");
                assertEquals(WebFetchService.State.SUCCEEDED, terminal(service, succeeded, 1000).state());
            }
            assertCode(ErrorCode.QUEUE_FULL, () -> service.submit(ClientIdentity.NATIVE, UUID.randomUUID().toString(), "https://example.com"));
            assertEquals(WebFetchService.State.SUCCEEDED, service.submit(ClientIdentity.NATIVE, succeeded, "https://example.com").state());
            assertEquals(14, calls.get());
            clock.now += TimeUnit.SECONDS.toNanos(120);
            assertCode(ErrorCode.WEB_FETCH_NOT_FOUND, () -> service.get(ClientIdentity.NATIVE, first));
            service.submit(ClientIdentity.NATIVE, first, "https://example.com");
            assertEquals(WebFetchService.State.SUCCEEDED, terminal(service, first, 1000).state());
        } finally { release.countDown(); timer.shutdownNow(); }
        var restartedTimer = WebFetchService.timer();
        try (var restarted = new WebFetchService(WebPolicy.ASK_EVERY_TIME, (target, execution) -> result(target), restartedTimer, clock)) {
            assertCode(ErrorCode.WEB_FETCH_NOT_FOUND, () -> restarted.get(ClientIdentity.NATIVE, first));
        } finally { restartedTimer.shutdownNow(); }
    }
    @Test void disabledPolicyPreventsAllLookupAndFetchWork() {
        var timer = WebFetchService.timer(); AtomicInteger calls = new AtomicInteger();
        try (var service = new WebFetchService(WebPolicy.DISABLED, (target, execution) -> { calls.incrementAndGet(); return result(target); }, timer, System::nanoTime)) {
            assertCode(ErrorCode.WEB_DISABLED, () -> service.submit(ClientIdentity.NATIVE, UUID.randomUUID().toString(), "https://example.com"));
            assertEquals(0, calls.get());
        } finally { timer.shutdownNow(); }
    }
    private static WebFetchResult result(WebTarget target) { return new WebFetchResult(target.url(), target.url(), target.hostname(), "", Instant.EPOCH, "text/plain", "web-extract-1", "bounded", false, false); }
    private static WebFetchService.View terminal(WebFetchService service, String id, int millis) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) {
            var view = service.get(ClientIdentity.NATIVE, id);
            if (view.state() != WebFetchService.State.RUNNING && view.state() != WebFetchService.State.QUEUED) return view;
            Thread.sleep(5);
        }
        throw new AssertionError("Web fixture did not become terminal");
    }
    private static final class MutableClock implements java.util.function.LongSupplier {
        volatile long now;
        public long getAsLong() { return now; }
    }
}
