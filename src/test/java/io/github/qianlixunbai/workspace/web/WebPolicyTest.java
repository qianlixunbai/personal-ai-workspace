package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebPolicyTest {
    @Test void canonicalApprovalAndAllAddressPublicPolicy() throws Exception {
        assertEquals("https://example.com/", WebTarget.parse("https://Example.COM.:443").url());
        assertEquals("/a%2fb?x=+&x=%2F&", WebTarget.parse("https://example.com/a%2fb?x=+&x=%2F&").requestTarget());
        assertEquals("/?", WebTarget.parse("https://example.com?").requestTarget());
        for (String input : List.of("http://example.com", "https:example.com", "https://user@example.com",
                "https://example.com#", "https://example.com:0443", "https://example.com..", "https://-bad.example.com",
                "https://example.com:443@evil.com", "https://ex%61mple.com", "https://example.com\\evil",
                "https://example.com/a%", "https://example.com/a/../b", "https://example.com//b", "https://example.com/ä",
                "https://例子.com", "https://127.0.0.1", "https://127.1", "https://0x7f.1", "https://[::1]",
                "https://localhost", "https://a.localhost", "https://a.local", "https://a.home.arpa", "https://a.onion",
                "https://example.com/ ", "https://example.com/" + "x".repeat(2048), "https://" + "x".repeat(64) + ".com"))
            assertCode(ErrorCode.WEB_TARGET_INVALID, () -> WebTarget.parse(input));
        for (String address : List.of("0.1.2.3", "10.1.1.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "192.0.0.9", "192.0.2.1", "192.31.196.1", "192.52.193.1", "192.88.99.2",
                "192.168.0.1", "192.175.48.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
                "::", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1", "fec0::1", "ff00::1", "64:ff9b::808:808",
                "100::1", "2001::1", "2001:20::1", "2001:db8::1", "2002::1", "2620:4f:8000::1", "3fff::1"))
            assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName(address)), address);
        InetAddress public4 = InetAddress.getByName("8.8.8.8"), public6 = InetAddress.getByName("2606:4700:4700::1111");
        assertTrue(PublicAddressPolicy.isPublic(public4)); assertTrue(PublicAddressPolicy.isPublic(public6));
        assertFalse(PublicAddressPolicy.isPublic(Inet6Address.getByAddress(null, public6.getAddress(), 2)));
        byte[] mapped = new byte[16]; mapped[10] = mapped[11] = (byte) 255; mapped[12] = 8; mapped[13] = 8; mapped[14] = 8; mapped[15] = 8;
        assertFalse(PublicAddressPolicy.isPublic(Inet6Address.getByAddress(null, mapped, 0)));
        assertEquals(2, ValidatedAddressSet.validate("example.com", new InetAddress[]{public4, public4, public6}).addresses().size());
        assertCode(ErrorCode.WEB_TARGET_NOT_PUBLIC, () -> ValidatedAddressSet.validate("example.com",
                new InetAddress[]{public4, InetAddress.getByAddress(new byte[]{127, 0, 0, 1})}));
        assertCode(ErrorCode.WEB_DNS_FAILED, () -> ValidatedAddressSet.validate("example.com", new InetAddress[17]));
        var pinned = new PublicWebTransport.PinnedDns(ValidatedAddressSet.validate("example.com", new InetAddress[]{public4}));
        assertEquals("example.com", pinned.resolveCanonicalHostname("example.com"));
        assertFalse(pinned.resolve("example.com", 443).getFirst().isUnresolved());
        assertThrows(UnknownHostException.class, () -> pinned.resolve("other.example.com"));
    }
    @Test void timedOutAndCancelledSystemLookupsNeverEscapeOrSpawnReplacementWorkers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (WebResolver resolver = new WebResolver(host -> {
            assertEquals("example.com.", host); calls.incrementAndGet(); entered.countDown();
            boolean done = false;
            while (!done) try { done = release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            exited.countDown(); return new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};
        })) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                WebExecution timed = new WebExecution(System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                Future<?> first = executor.submit(() -> assertCode(ErrorCode.WEB_TIMEOUT,
                        () -> resolver.resolve(WebTarget.parse("https://example.com"), timed, Long.MAX_VALUE)));
                assertTrue(entered.await(1, TimeUnit.SECONDS)); first.get(4, TimeUnit.SECONDS);
                assertCode(ErrorCode.WEB_TIMEOUT, timed::check);
                assertCode(ErrorCode.WEB_DNS_FAILED, () -> resolver.resolve(WebTarget.parse("https://example.com"),
                        new WebExecution(Long.MAX_VALUE), Long.MAX_VALUE));
                assertEquals(1, calls.get()); release.countDown(); assertTrue(exited.await(1, TimeUnit.SECONDS));
            } finally { release.countDown(); executor.shutdownNow(); }
        }
        CountDownLatch blocked = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (WebResolver resolver = new WebResolver(host -> {
            blocked.countDown();
            try { finish.await(); } catch (InterruptedException ignored) { }
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};
        })) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                WebExecution cancelled = new WebExecution(Long.MAX_VALUE);
                Future<?> caller = executor.submit(() -> assertCode(ErrorCode.WEB_TIMEOUT,
                        () -> resolver.resolve(WebTarget.parse("https://example.com"), cancelled, Long.MAX_VALUE)));
                assertTrue(blocked.await(1, TimeUnit.SECONDS)); cancelled.invalidate(); caller.get(1, TimeUnit.SECONDS);
                assertCode(ErrorCode.WEB_TIMEOUT, cancelled::check);
            } finally { finish.countDown(); executor.shutdownNow(); }
        }
    }
    interface Checked { void run() throws Exception; }
    static void assertCode(ErrorCode code, Checked action) { assertEquals(code, assertThrows(WorkspaceException.class, action::run).error().code()); }
}
