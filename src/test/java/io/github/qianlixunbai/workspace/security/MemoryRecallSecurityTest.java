package io.github.qianlixunbai.workspace.security;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real filter with synthetic credential providers. No Profile, HTTP listener or Store. */
class MemoryRecallSecurityTest {
    private static final String ROUTE = "/api/v1/memory/recall";
    private static final String ORIGIN = "chrome-extension://" + "m".repeat(32);
    private static final byte[] QUERY = "{\"query\":\"synthetic-private-query\"}".getBytes(StandardCharsets.UTF_8);
    private static class Request extends MockHttpServletRequest {
        int reads;
        final boolean mayRead;
        Request(String method, String credential, String origin, boolean mayRead) {
            super(method, ROUTE); this.mayRead = mayRead;
            setServletPath(ROUTE); setRemoteAddr("127.0.0.1");
            if (credential != null) addHeader("Authorization", credential);
            if (origin != null) addHeader("Origin", origin);
            addHeader("Sec-Fetch-Site", "none"); addHeader("Sec-Fetch-Mode", "cors"); addHeader("Sec-Fetch-Dest", "empty");
            setContent(QUERY);
        }
        @Override public ServletInputStream getInputStream() {
            reads++; assertTrue(mayRead, "Rejected caller must not consume the query"); return super.getInputStream();
        }
    }
    @Test void recallRemainsNativeOnlyAndBodyBoundedWithNoQueryInErrors() throws Exception {
        var token = mock(LocalClientToken.class);
        var clients = mock(BrowserClients.class);
        var browser = new ClientIdentity("fixture", "browser", "fixture", ORIGIN, Instant.EPOCH, Set.of("translate"));
        when(token.matches("Bearer synthetic")).thenReturn(true);
        when(clients.authenticateCredential("Bearer br1.synthetic")).thenReturn(browser);
        when(clients.authenticate("Bearer br1.synthetic", ORIGIN)).thenReturn(browser);
        when(clients.allowsOrigin(ORIGIN, false)).thenReturn(true);
        var filter = new LocalClientFilter(token, clients);
        var accepted = new AtomicInteger();
        try {
            var remote = new Request("POST", "Bearer synthetic", null, false); remote.setRemoteAddr("192.0.2.1");
            var preflight = new Request("OPTIONS", null, ORIGIN, false); preflight.addHeader("Access-Control-Request-Method", "POST");
            var requests = List.of(new Request("POST", null, null, false),
                    new Request("POST", "Bearer incorrect", null, false),
                    new Request("POST", "Bearer br1.synthetic", null, false),
                    new Request("POST", "Bearer br1.synthetic", ORIGIN, false),
                    new Request("POST", "Bearer synthetic", "https://untrusted.example", false), remote, preflight);
            for (var request : requests) {
                SecurityContextHolder.clearContext();
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (a, b) -> fail("Denied caller reached the route"));
                assertEquals(request.getHeader("Authorization") != null && request.getHeader("Authorization").startsWith("Bearer br1.")
                        && ORIGIN.equals(request.getHeader("Origin")) ? 403 : 401, response.getStatus());
                assertEquals(0, request.reads); assertEquals("no-store", response.getHeader("Cache-Control"));
                assertFalse(response.getContentAsString().contains("synthetic-private-query"));
                if (request == preflight) assertNull(response.getHeader("Access-Control-Allow-Origin"));
            }
            SecurityContextHolder.clearContext();
            var nativeRequest = new Request("POST", "Bearer synthetic", null, true);
            var nativeResponse = new MockHttpServletResponse();
            filter.doFilter(nativeRequest, nativeResponse, (a, b) -> {
                assertSame(ClientIdentity.NATIVE, ClientIdentity.current());
                assertArrayEquals(QUERY, a.getInputStream().readAllBytes()); accepted.incrementAndGet();
            });
            assertEquals(200, nativeResponse.getStatus()); assertEquals(1, nativeRequest.reads);
            assertEquals("no-store", nativeResponse.getHeader("Cache-Control")); assertEquals(1, accepted.get());
            SecurityContextHolder.clearContext();
            var oversized = new Request("POST", "Bearer synthetic", null, true) {
                @Override public int getContentLength() { return -1; }
                @Override public long getContentLengthLong() { return -1; }
            };
            oversized.setContent(new byte[32769]);
            var response = new MockHttpServletResponse();
            filter.doFilter(oversized, response, (a, b) -> fail("Recall must keep the ordinary body limit"));
            assertEquals(413, response.getStatus()); assertEquals(1, oversized.reads);
            assertEquals("no-store", response.getHeader("Cache-Control"));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
