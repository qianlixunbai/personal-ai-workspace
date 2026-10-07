package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.web.WebPolicyTest.assertCode;

class WebTransportTest {
    private static final String HOST = "fetch-fixture.example.com";
    private static final InetAddress LOCAL = loopback();
    private static InetAddress loopback() { try { return InetAddress.getByAddress(new byte[]{127, 0, 0, 1}); } catch (UnknownHostException e) { throw new AssertionError(e); } }
    private static WebExecution execution() { return new WebExecution(System.nanoTime() + TimeUnit.SECONDS.toNanos(30)); }
    private static String ok(String type, String extra, byte[] body) {
        return "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n" + extra + "\r\n"
                + new String(body, StandardCharsets.ISO_8859_1);
    }
    @Test void socketPinningAuthorityTlsAndManualRedirectAdmission() throws Exception {
        var timer = WebFetchService.timer();
        String oldProxy = System.getProperty("https.proxyHost"), oldSocks = System.getProperty("socksProxyHost");
        try (Fixture fixture = new Fixture()) {
            System.setProperty("https.proxyHost", "127.0.0.2"); System.setProperty("socksProxyHost", "127.0.0.2");
            AtomicInteger resolutions = new AtomicInteger(); AtomicBoolean mixed = new AtomicBoolean();
            PublicWebTransport.Resolution resolution = (target, control, deadline) -> {
                control.check(); resolutions.incrementAndGet();
                if (mixed.get() && resolutions.get() == 2)
                    return ValidatedAddressSet.validate(HOST, new InetAddress[]{publicAddress(), LOCAL});
                return new ValidatedAddressSet(target.hostname(), List.of(LOCAL));
            };
            var transport = new PublicWebTransport(resolution, timer, ssl(false), fixture.port());
            fixture.respond.set(request -> request.startsWith("GET /start? HTTP/1.1")
                    ? "HTTP/1.1 302 Found\r\nLocation: ?done\r\nSet-Cookie: secret=1\r\nContent-Length: 999999\r\n\r\n"
                    : ok("text/html; charset=\"UTF-8\"", "", "<title> A title </title><p>Hello <b>world</b></p><script>secret</script><iframe>hidden</iframe><audio>hidden</audio><video>hidden</video>".getBytes(StandardCharsets.UTF_8)));
            var result = transport.fetch(WebTarget.parse("https://" + HOST + "/start?"), execution());
            assertEquals(2, resolutions.get()); assertEquals(2, fixture.requests.size());
            assertEquals("https://" + HOST + "/start?done", result.finalUrl());
            assertEquals("A title", result.title()); assertEquals("Hello world", result.text());
            for (String request : fixture.requests) {
                assertTrue(request.contains("Host: " + HOST + "\r\n"));
                assertTrue(request.contains("Accept-Encoding: identity\r\n"));
                assertFalse(request.toLowerCase(Locale.ROOT).contains("cookie:"));
                assertFalse(request.toLowerCase(Locale.ROOT).contains("authorization:"));
                assertFalse(request.toLowerCase(Locale.ROOT).contains("referer:"));
            }
            assertEquals(List.of(HOST, HOST), fixture.sni);
            fixture.respond.set(request -> ok("text/plain", "", "raw".getBytes(StandardCharsets.UTF_8)));
            var raw = WebTarget.parse("https://" + HOST + "/a%2fb//c;v?x=+&x=%2F&");
            transport.fetch(raw, execution());
            assertTrue(fixture.requests.getLast().startsWith("GET /a%2fb//c;v?x=+&x=%2F& HTTP/1.1\r\n"));
            InetAddress refused = InetAddress.getByAddress(new byte[]{127, 0, 0, 2});
            transport.hop(raw, new ValidatedAddressSet(HOST, List.of(refused, LOCAL)), execution(), Long.MAX_VALUE);
            int connections = fixture.accepts.get();
            assertCode(ErrorCode.WEB_FETCH_FAILED, () -> transport.hop(raw, new ValidatedAddressSet(HOST,
                    List.of(refused, refused, LOCAL)), execution(), Long.MAX_VALUE));
            assertEquals(connections, fixture.accepts.get()); // Third address is never attempted.
            var untrusted = new PublicWebTransport(resolution, timer, SSLContext.getDefault(), fixture.port());
            assertCode(ErrorCode.WEB_TLS_FAILED, () -> untrusted.fetch(raw, execution()));
            int before = fixture.accepts.get();
            fixture.expectedSni.set("wrong.example.com");
            assertCode(ErrorCode.WEB_TLS_FAILED, () -> transport.hop(WebTarget.parse("https://wrong.example.com/"),
                    new ValidatedAddressSet("wrong.example.com", List.of(LOCAL, LOCAL)), execution(), Long.MAX_VALUE));
            assertEquals(before + 1, fixture.accepts.get()); // No retry after TLS failure.
            fixture.expectedSni.set(HOST);
            resolutions.set(0); mixed.set(true);
            fixture.respond.set(request -> "HTTP/1.1 302 Found\r\nLocation: /second\r\nContent-Length: 0\r\n\r\n");
            before = fixture.requests.size();
            assertCode(ErrorCode.WEB_TARGET_NOT_PUBLIC, () -> transport.fetch(WebTarget.parse("https://" + HOST), execution()));
            assertEquals(2, resolutions.get()); assertEquals(before + 1, fixture.requests.size());
            mixed.set(false); resolutions.set(0);
            fixture.respond.set(request -> "HTTP/1.1 302 Found\r\nLocation: https://other.example.com/\r\nContent-Length: 0\r\n\r\n");
            assertCode(ErrorCode.WEB_REDIRECT_DENIED, () -> transport.fetch(WebTarget.parse("https://" + HOST), execution()));
            assertEquals(1, resolutions.get());
            fixture.respond.set(request -> "HTTP/1.1 302 Found\r\nLocation: /\r\nContent-Length: 0\r\n\r\n");
            assertCode(ErrorCode.WEB_REDIRECT_DENIED, () -> transport.fetch(WebTarget.parse("https://" + HOST), execution()));
            fixture.respond.set(request -> null); before = fixture.requests.size();
            assertCode(ErrorCode.WEB_FETCH_FAILED, () -> transport.fetch(WebTarget.parse("https://" + HOST), execution()));
            assertEquals(before + 1, fixture.requests.size()); // Sent request/uncertain outcome is never retried.
        } finally {
            restore("https.proxyHost", oldProxy); restore("socksProxyHost", oldSocks); timer.shutdownNow();
        }
    }
    @Test void responseBoundsEncodingHeadersAndCancellationAtSocketBoundary() throws Exception {
        var timer = WebFetchService.timer();
        try (Fixture fixture = new Fixture()) {
            var transport = new PublicWebTransport((target, control, deadline) -> new ValidatedAddressSet(HOST, List.of(LOCAL)),
                    timer, ssl(false), fixture.port());
            WebTarget target = WebTarget.parse("https://" + HOST);
            fixture.respond.set(request -> "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 524289\r\n\r\n");
            assertCode(ErrorCode.WEB_RESPONSE_TOO_LARGE, () -> transport.fetch(target, execution()));
            fixture.respond.set(request -> "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n80001\r\n" + "x".repeat(524289) + "\r\n0\r\n\r\n");
            assertCode(ErrorCode.WEB_RESPONSE_TOO_LARGE, () -> transport.fetch(target, execution()));
            fixture.respond.set(request -> ok("image/png", "", new byte[0]));
            assertCode(ErrorCode.WEB_CONTENT_TYPE_UNSUPPORTED, () -> transport.fetch(target, execution()));
            fixture.respond.set(request -> ok("text/plain", "Content-Encoding: gzip\r\n", new byte[0]));
            assertCode(ErrorCode.WEB_CONTENT_INVALID, () -> transport.fetch(target, execution()));
            fixture.respond.set(request -> ok("text/plain; charset=UTF-16", "", new byte[0]));
            assertCode(ErrorCode.WEB_CONTENT_INVALID, () -> transport.fetch(target, execution()));
            fixture.respond.set(request -> ok("text/plain", "", new byte[]{(byte) 0xc3, 0x28}));
            assertCode(ErrorCode.WEB_CONTENT_INVALID, () -> transport.fetch(target, execution()));
            fixture.respond.set(request -> ok("text/plain", "", new byte[]{'a', 0, 'b'}));
            assertCode(ErrorCode.WEB_CONTENT_INVALID, () -> transport.fetch(target, execution()));
            fixture.respond.set(request -> ok("text/plain", "X-A: " + "x".repeat(8000) + "\r\nX-B: " + "x".repeat(8000)
                    + "\r\nX-C: " + "x".repeat(8000) + "\r\nX-D: " + "x".repeat(8000) + "\r\nX-E: " + "x".repeat(1000) + "\r\n", new byte[0]));
            assertCode(ErrorCode.WEB_RESPONSE_TOO_LARGE, () -> transport.fetch(target, execution()));
            var caller = Executors.newSingleThreadExecutor();
            try {
                CountDownLatch cancelledRelease = new CountDownLatch(1), cancelledEntered = new CountDownLatch(1);
                fixture.block = cancelledRelease;
                fixture.respond.set(request -> { cancelledEntered.countDown(); await(cancelledRelease); return ok("text/plain", "", new byte[0]); });
                WebExecution cancelled = execution();
                Future<?> future = caller.submit(() -> assertCode(ErrorCode.WEB_TIMEOUT, () -> transport.fetch(target, cancelled)));
                assertTrue(cancelledEntered.await(2, TimeUnit.SECONDS)); cancelled.invalidate(); future.get(1, TimeUnit.SECONDS);
                fixture.block.countDown();
                CountDownLatch deadlineRelease = new CountDownLatch(1); fixture.block = deadlineRelease;
                fixture.respond.set(request -> { await(deadlineRelease); return ok("text/plain", "", new byte[0]); });
                WebExecution deadline = new WebExecution(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400));
                future = caller.submit(() -> assertCode(ErrorCode.WEB_TIMEOUT, () -> transport.fetch(target, deadline)));
                future.get(2, TimeUnit.SECONDS);
                deadlineRelease.countDown();
                fixture.afterWrite = new CountDownLatch(1);
                fixture.respond.set(request -> "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1\r\n\r\n");
                future = caller.submit(() -> assertCode(ErrorCode.WEB_TIMEOUT, () -> transport.fetch(target, execution())));
                future.get(4, TimeUnit.SECONDS); // Body idle ceiling, independent of the longer total budget.
            } finally { if (fixture.block != null) fixture.block.countDown(); caller.shutdownNow(); }
        } finally { timer.shutdownNow(); }
    }
    @Test void strictDecodingInMemoryExtractionAndDualOutputBudgets() throws Exception {
        var extractor = new WebContentExtractor();
        String html = "<meta charset=UTF-16><title>" + "😀".repeat(170) + "</title><p>" + "你".repeat(5000) + "</p><style>hidden</style>";
        var result = extractor.extract(html.getBytes(StandardCharsets.UTF_8), new WebContentExtractor.Admission("text/html", StandardCharsets.UTF_8));
        assertEquals(160, result.title().codePointCount(0, result.title().length()));
        assertTrue(result.titleTruncated()); assertTrue(result.textTruncated());
        assertTrue(result.text().length() <= 4096); assertTrue(result.text().getBytes(StandardCharsets.UTF_8).length <= 8192);
        var response = new org.apache.hc.core5.http.message.BasicClassicHttpResponse(200);
        response.addHeader("Content-Type", "Text/Plain; charset=gb18030");
        assertEquals("中文", extractor.extract("中文".getBytes(java.nio.charset.Charset.forName("GB18030")), extractor.admit(response)).text());
        response.setHeader("Content-Type", "text/plain; charset=utf8; CHARSET=ascii");
        assertCode(ErrorCode.WEB_CONTENT_INVALID, () -> extractor.admit(response));
        response.setHeader("Content-Type", "text/plain; charset=\"UTF-8");
        assertCode(ErrorCode.WEB_CONTENT_INVALID, () -> extractor.admit(response));
        response.setHeader("Content-Type", "text/plain"); response.addHeader("Content-Type", "text/html");
        assertCode(ErrorCode.WEB_CONTENT_TYPE_UNSUPPORTED, () -> extractor.admit(response));
        response.removeHeaders("Content-Type"); response.addHeader("Content-Type", "text/plain"); response.addHeader("Content-Disposition", "attachment; filename=\"x.txt\"");
        assertCode(ErrorCode.WEB_CONTENT_INVALID, () -> extractor.admit(response));
        assertCode(ErrorCode.WEB_CONTENT_INVALID, () -> extractor.extract(new byte[]{(byte) 0xff, (byte) 0xfe}, new WebContentExtractor.Admission("text/plain", StandardCharsets.UTF_8)));
        assertEquals("A B", extractor.extract(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'A', '\n', 'B'}, new WebContentExtractor.Admission("text/plain", StandardCharsets.UTF_8)).text());
        byte[] expanded = new byte[300000]; Arrays.fill(expanded, (byte) 0xa0);
        assertCode(ErrorCode.WEB_RESPONSE_TOO_LARGE, () -> extractor.extract(expanded, new WebContentExtractor.Admission("text/plain", StandardCharsets.ISO_8859_1)));
    }
    private static InetAddress publicAddress() { try { return InetAddress.getByAddress(new byte[]{8, 8, 8, 8}); } catch (UnknownHostException impossible) { throw new AssertionError(); } }
    private static void restore(String key, String old) { if (old == null) System.clearProperty(key); else System.setProperty(key, old); }
    private static void await(CountDownLatch latch) { try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
    private static SSLContext ssl(boolean server) throws Exception {
        var certificate = CertificateFactory.getInstance("X.509").generateCertificate(WebTransportTest.class.getResourceAsStream("/web/fixture-cert.pem"));
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, null); store.setCertificateEntry("fixture", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        KeyManager[] keys = new KeyManager[0];
        if (server) {
            String pem = new String(WebTransportTest.class.getResourceAsStream("/web/fixture-key.pem").readAllBytes(), StandardCharsets.US_ASCII);
            byte[] bytes = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
            var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
            store.setKeyEntry("fixture", key, "test-only".toCharArray(), new java.security.cert.Certificate[]{certificate});
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); factory.init(store, "test-only".toCharArray()); keys = factory.getKeyManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS"); context.init(keys, trust.getTrustManagers(), new SecureRandom()); return context;
    }
    private static final class Fixture implements AutoCloseable {
        final SSLServerSocket server;
        final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> WebFetchService.daemon(runnable, "web-tls-fixture"));
        final AtomicReference<java.util.function.Function<String, String>> respond = new AtomicReference<>();
        final AtomicReference<String> expectedSni = new AtomicReference<>(HOST);
        final AtomicInteger accepts = new AtomicInteger();
        final List<String> requests = new CopyOnWriteArrayList<>(), sni = new CopyOnWriteArrayList<>();
        volatile CountDownLatch block, afterWrite;
        volatile SSLSocket active;
        Fixture() throws Exception {
            server = (SSLServerSocket) ssl(true).getServerSocketFactory().createServerSocket(0, 10, LOCAL);
            worker.submit(() -> {
                while (!server.isClosed()) {
                    try (SSLSocket socket = (SSLSocket) server.accept()) {
                        active = socket; accepts.incrementAndGet(); socket.setSoTimeout(4000);
                        SSLParameters parameters = socket.getSSLParameters();
                        parameters.setSNIMatchers(List.of(new SNIMatcher(0) {
                            public boolean matches(SNIServerName name) { String host = ((SNIHostName) name).getAsciiName(); sni.add(host); return host.equals(expectedSni.get()); }
                        }));
                        socket.setSSLParameters(parameters); socket.startHandshake();
                        ByteArrayOutputStream request = new ByteArrayOutputStream(); int matched = 0;
                        while (request.size() < 10000 && matched < 4) {
                            int value = socket.getInputStream().read(); if (value < 0) break;
                            request.write(value); matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : 0;
                        }
                        if (matched < 4) continue;
                        String raw = request.toString(StandardCharsets.ISO_8859_1); requests.add(raw);
                        String reply = respond.get().apply(raw);
                        if (reply != null) { socket.getOutputStream().write(reply.getBytes(StandardCharsets.ISO_8859_1)); socket.getOutputStream().flush(); }
                        CountDownLatch hold = afterWrite; if (hold != null) await(hold);
                    } catch (IOException ignored) { /* Cancelled/untrusted sockets are expected. */ }
                    finally { active = null; }
                }
            });
        }
        int port() { return server.getLocalPort(); }
        public void close() throws IOException { if (block != null) block.countDown(); if (afterWrite != null) afterWrite.countDown(); server.close(); if (active != null) active.close(); worker.shutdownNow(); }
    }
}
