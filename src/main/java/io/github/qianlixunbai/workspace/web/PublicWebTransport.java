package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.apache.hc.client5.http.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.*;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.*;
import org.apache.hc.client5.http.ssl.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.*;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.*;

/** The exclusive public-network owner. One basic manager, socket and client per validated hop. */
final class PublicWebTransport {
    @FunctionalInterface interface Resolution {
        ValidatedAddressSet resolve(WebTarget target, WebExecution execution, long deadline);
    }
    private final Resolution resolver;
    private final ScheduledExecutorService timer;
    private final SSLContext tls;
    private final int socketPort;
    private final WebContentExtractor extractor = new WebContentExtractor();
    PublicWebTransport(WebResolver resolver, ScheduledExecutorService timer) { this(resolver::resolve, timer, publicTls(), 443); }
    // Test-only trust/port seam is not configuration, a Spring bean or a public API.
    PublicWebTransport(Resolution resolver, ScheduledExecutorService timer, SSLContext tls, int socketPort) {
        this.resolver = resolver; this.timer = timer; this.tls = tls; this.socketPort = socketPort;
    }
    private static SSLContext publicTls() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[0], null, new SecureRandom()); // No ambient client keys.
            return context;
        } catch (GeneralSecurityException ignored) { throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "WEB"); }
    }
    WebFetchResult fetch(WebTarget initial, WebExecution execution) {
        WebTarget current = initial;
        Set<String> visited = new HashSet<>(); visited.add(current.url());
        for (int redirects = 0;; redirects++) {
            execution.check();
            long hopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WebLimits.HOP_SECONDS);
            try (var hop = execution.phase(timer, hopDeadline, WebLimits.HOP_SECONDS)) {
                ValidatedAddressSet addresses = resolver.resolve(current, execution, hopDeadline);
                Hop result = hop(current, addresses, execution, hopDeadline);
                execution.check();
                if (result.extracted != null) return new WebFetchResult(initial.url(), current.url(), initial.hostname(),
                        result.extracted.title(), Instant.now(), result.mime, WebContentExtractor.VERSION,
                        result.extracted.text(), result.extracted.titleTruncated(), result.extracted.textTruncated());
                if (redirects >= WebLimits.REDIRECTS) throw redirectDenied();
                WebTarget next;
                try {
                    if (!WebTarget.ascii(result.location) || result.location.indexOf('#') >= 0
                            || result.location.indexOf('\\') >= 0) throw redirectDenied();
                    URI reference = new URI(result.location);
                    // URI.resolve's empty-path/query behavior differs from RFC 3986. Preserve the base raw path.
                    URI resolved = !reference.isAbsolute() && reference.getRawAuthority() == null && reference.getRawPath().isEmpty()
                            ? new URI("https://" + current.hostname() + current.uri().getRawPath()
                                    + (reference.getRawQuery() == null ? (current.uri().getRawQuery() == null ? "" : "?" + current.uri().getRawQuery())
                                    : "?" + reference.getRawQuery())) : current.uri().resolve(reference);
                    next = WebTarget.parse(resolved.toASCIIString());
                } catch (java.net.URISyntaxException | IllegalArgumentException | WorkspaceException ignored) { throw redirectDenied(); }
                if (!next.hostname().equals(initial.hostname()) || !visited.add(next.url())) throw redirectDenied();
                current = next;
            }
        }
    }
    // Also consumed by the socket fixture with an already-validated local address set.
    Hop hop(WebTarget target, ValidatedAddressSet addresses, WebExecution execution, long deadline) {
        execution.check();
        if (!target.hostname().equals(addresses.hostname())) throw new WorkspaceException(ErrorCode.WEB_FETCH_FAILED, "CONNECT");
        var config = Http1Config.custom().setMaxLineLength(WebLimits.HEADER_LINE)
                .setMaxHeaderCount(WebLimits.HEADER_FIELDS).setMaxEmptyLineCount(1).build(); // One status line, no empty prelude.
        var factory = ManagedHttpClientConnectionFactory.builder().http1Config(config)
                .responseParserFactory(new WebHeaderParser(config, execution)).build();
        var operator = new PinnedOperator(target.hostname(), addresses, execution, deadline);
        var manager = new BasicHttpClientConnectionManager(operator, factory);
        manager.setSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofSeconds(WebLimits.HEADERS_SECONDS)).build());
        manager.setConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(WebLimits.CONNECT_SECONDS))
                .setSocketTimeout(Timeout.ofSeconds(WebLimits.HEADERS_SECONDS)).build());
        manager.setTlsConfig(TlsConfig.custom().setHandshakeTimeout(Timeout.ofSeconds(WebLimits.TLS_SECONDS)).build());
        var request = new HttpGet(target.uri());
        // Set the approved origin-form verbatim, including escaped separators and an empty query.
        request.setPath(target.requestTarget());
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Accept", "text/html, text/plain, application/xhtml+xml");
        request.setHeader("Accept-Encoding", "identity");
        request.setConfig(RequestConfig.custom().setAuthenticationEnabled(false)
                .setResponseTimeout(Timeout.ofSeconds(WebLimits.HEADERS_SECONDS)).build());
        ClassicHttpResponse response = null;
        var client = HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableContentCompression().disableAuthCaching().disableConnectionState()
                .setDefaultAuthSchemeRegistry(RegistryBuilder.<org.apache.hc.client5.http.auth.AuthSchemeFactory>create().build())
                .setUserAgent("PersonalAiWorkspace-Web/1").build();
        try {
            response = client.executeOpen(new HttpHost("https", target.hostname(), 443), request, null);
            operator.headersComplete();
            execution.check();
            int status = response.getCode();
            if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                Header[] locations = response.getHeaders("Location");
                if (locations.length != 1 || !WebTarget.ascii(locations[0].getValue())) throw redirectDenied();
                return new Hop(locations[0].getValue(), null, null);
            }
            if (status != 200) throw new WorkspaceException(ErrorCode.WEB_FETCH_FAILED, "RESPONSE");
            WebContentExtractor.Admission admission = extractor.admit(response);
            if (response.containsHeader("Trailer")) throw WebContentExtractor.invalid();
            HttpEntity entity = response.getEntity();
            if (entity == null) throw WebContentExtractor.invalid();
            if (entity.getContentLength() > WebLimits.BODY) throw WebContentExtractor.tooLarge();
            operator.bodyTimeout();
            InputStream stream = entity.getContent();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                execution.check();
                operator.bodyTimeout();
                int read;
                try (var idle = execution.phase(timer, deadline, WebLimits.BODY_IDLE_SECONDS)) { read = stream.read(buffer); }
                execution.check();
                if (read < 0) break;
                if (body.size() + read > WebLimits.BODY) throw WebContentExtractor.tooLarge();
                body.write(buffer, 0, read);
            }
            if (entity.getTrailers() != null && !entity.getTrailers().get().isEmpty()) throw WebContentExtractor.invalid();
            var extracted = extractor.extract(body.toByteArray(), admission);
            execution.check();
            return new Hop(null, admission.mime(), extracted);
        } catch (SSLException ignored) {
            execution.check(); throw new WorkspaceException(ErrorCode.WEB_TLS_FAILED, "TLS");
        } catch (SocketTimeoutException ignored) {
            execution.invalidate(); throw WebExecution.timeout();
        } catch (IOException ignored) {
            execution.check(); throw new WorkspaceException(ErrorCode.WEB_FETCH_FAILED, "HTTP");
        } finally {
            operator.headersComplete();
            // Immediate close precedes response/entity close: redirects and rejected bodies are never drained.
            manager.close(CloseMode.IMMEDIATE);
            if (response != null) try { response.close(); } catch (IOException ignored) { }
            client.close(CloseMode.IMMEDIATE);
            execution.detach();
        }
    }
    private static WorkspaceException redirectDenied() { return new WorkspaceException(ErrorCode.WEB_REDIRECT_DENIED, "REDIRECT"); }
    record Hop(String location, String mime, WebContentExtractor.Extracted extracted) {
        @Override public String toString() { return "Hop[redacted]"; }
    }
    static final class PinnedDns implements DnsResolver {
        private final ValidatedAddressSet set;
        PinnedDns(ValidatedAddressSet set) { this.set = set; }
        private void approved(String host) throws UnknownHostException {
            if (!set.hostname().equals(host)) throw new UnknownHostException("Unapproved Web authority");
        }
        public InetAddress[] resolve(String host) throws UnknownHostException { approved(host); return set.addresses().toArray(InetAddress[]::new); }
        public String resolveCanonicalHostname(String host) throws UnknownHostException { approved(host); return set.hostname(); }
        @Override public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
            approved(host);
            if (port != 443) throw new UnknownHostException("Unapproved Web port");
            return set.addresses().stream().map(address -> new InetSocketAddress(address, port)).toList();
        }
    }
    /** Apache's default operator retries IOException across addresses, including TLS failure.
     * This operator finishes bounded TCP attempts BEFORE a single verified TLS upgrade. */
    private final class PinnedOperator implements HttpClientConnectionOperator {
        final String hostname; final PinnedDns dns; final WebExecution execution; final long deadline;
        Socket socket; WebExecution.Phase headers;
        PinnedOperator(String hostname, ValidatedAddressSet set, WebExecution execution, long deadline) {
            this.hostname = hostname; dns = new PinnedDns(set); this.execution = execution; this.deadline = deadline;
        }
        public void connect(ManagedHttpClientConnection connection, HttpHost host, InetSocketAddress local,
                            TimeValue timeout, SocketConfig config, HttpContext context) throws IOException {
            execution.check();
            if (!host.getSchemeName().equals("https") || !host.getHostName().equals(hostname)
                    || host.getPort() != 443 || host.getAddress() != null || local != null) throw new IOException("Unapproved route");
            List<InetSocketAddress> approved = dns.resolve(hostname, 443);
            long connectDeadline = System.nanoTime() + execution.remaining(deadline, WebLimits.CONNECT_SECONDS);
            try (var connect = execution.phase(timer, connectDeadline, WebLimits.CONNECT_SECONDS)) {
                for (int i = 0; i < Math.min(WebLimits.ADDRESS_ATTEMPTS, approved.size()); i++) {
                    execution.check();
                    socket = new Socket(Proxy.NO_PROXY);
                    Socket attempt = socket;
                    try {
                        execution.attach(() -> close(attempt));
                        connection.bind(attempt);
                        InetSocketAddress remote = new InetSocketAddress(approved.get(i).getAddress(), socketPort);
                        attempt.connect(remote, millis(execution.remaining(connectDeadline, WebLimits.CONNECT_SECONDS)));
                        break;
                    } catch (IOException failure) {
                        close(attempt); execution.check();
                        if (i + 1 >= Math.min(WebLimits.ADDRESS_ATTEMPTS, approved.size())) throw failure;
                    } catch (RuntimeException failure) {
                        close(attempt); throw failure;
                    }
                }
            }
            execution.check();
            try (var handshake = execution.phase(timer, deadline, WebLimits.TLS_SECONDS)) {
                socket.setSoTimeout(millis(execution.remaining(deadline, WebLimits.TLS_SECONDS)));
                Socket raw = socket;
                SSLSocket secure = new DefaultClientTlsStrategy(tls, HostnameVerificationPolicy.BOTH, new DefaultHostnameVerifier())
                        .upgrade(socket, hostname, 443, null, context);
                // Apache layers TLS with autoClose=false. Closing raw first aborts blocked TLS reads immediately.
                execution.attach(() -> { close(raw); close(secure); });
                socket = secure;
                connection.bind(secure, raw);
            }
            execution.check();
            socket.setSoTimeout(millis(execution.remaining(deadline, WebLimits.HEADERS_SECONDS)));
            headers = execution.phase(timer, deadline, WebLimits.HEADERS_SECONDS);
        }
        public void upgrade(ManagedHttpClientConnection connection, HttpHost host, HttpContext context) throws IOException {
            throw new IOException("Unexpected TLS upgrade");
        }
        void headersComplete() { if (headers != null) { headers.close(); headers = null; } }
        void bodyTimeout() throws SocketException { socket.setSoTimeout(millis(execution.remaining(deadline, WebLimits.BODY_IDLE_SECONDS))); }
        private static int millis(long nanos) { return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)); }
        private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    }
}
