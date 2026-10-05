package io.github.qianlixunbai.workspace.security;

import io.github.qianlixunbai.workspace.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.util.List;
import io.github.qianlixunbai.workspace.memory.MemoryBackup;

final class LocalClientFilter extends OncePerRequestFilter {
    private final LocalClientToken token;
    private final BrowserClients clients;
    private final JsonMapper json = JsonMapper.builder().build();
    LocalClientFilter(LocalClientToken token, BrowserClients clients) { this.token = token; this.clients = clients; }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean health = request.getMethod().equals("GET") && List.of("/actuator/health",
                "/actuator/health/liveness", "/actuator/health/readiness").contains(request.getServletPath());
        if (health) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        if (!List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())) {
            reject(response, 401, ErrorCode.UNAUTHORIZED); return;
        }
        String origin = request.getHeader("Origin"), path = request.getServletPath(), method = request.getMethod();
        String authorization = request.getHeader("Authorization");
        boolean exchange = path.equals("/api/v1/security/pairings/exchange");
        ClientIdentity identity;
        if (origin == null && authorization != null && authorization.startsWith("Bearer br1.")) {
            // Chrome privileged GET can omit Origin. The prefix selects validation, never grants trust.
            identity = clients.authenticateCredential(authorization);
            if (identity == null || !method.equals("GET") || !browserMetadata(request)) {
                reject(response, 401, ErrorCode.UNAUTHORIZED); return;
            }
            if (!browserGetRoute(path) || !identity.allowedCapabilities().contains("translate")) {
                reject(response, 403, ErrorCode.POLICY_DENIED); return;
            }
            // No Origin exists to bind or echo; host permissions govern Chrome response readability.
        } else if (origin == null) {
            if (exchange || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                    || !token.matches(authorization)) {
                reject(response, 401, ErrorCode.UNAUTHORIZED); return;
            }
            identity = ClientIdentity.NATIVE;
        } else {
            // Privileged extension requests: none/cors/empty. Navigation, webpage and cross-site traffic fail closed.
            if (!BrowserClients.validOrigin(origin) || !browserMetadata(request)) {
                reject(response, 401, ErrorCode.UNAUTHORIZED); return;
            }
            if (method.equals("OPTIONS")) {
                String requestedMethod = request.getHeader("Access-Control-Request-Method");
                String headers = request.getHeader("Access-Control-Request-Headers");
                boolean safeHeaders = headers == null || java.util.Arrays.stream(headers.split(","))
                        .allMatch(h -> List.of("authorization", "content-type").contains(h.strip().toLowerCase(java.util.Locale.ROOT)));
                if (!clients.allowsOrigin(origin, exchange) || !browserRoute(path, requestedMethod, exchange) || !safeHeaders) {
                    reject(response, 401, ErrorCode.UNAUTHORIZED); return;
                }
                cors(response, origin);
                response.setHeader("Access-Control-Allow-Methods", requestedMethod);
                response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
                response.setStatus(204); return;
            }
            if (exchange) {
                if (!method.equals("POST") || !clients.allowsOrigin(origin, true) || request.getHeader("Authorization") != null) {
                    reject(response, 401, ErrorCode.UNAUTHORIZED); return;
                }
                // This principal can only reach exchange, whose one-time proof is validated before issuance.
                identity = new ClientIdentity("pairing", "pairing", "Pairing", origin, java.time.Instant.EPOCH, java.util.Set.of());
            } else {
                identity = clients.authenticate(authorization, origin);
                if (identity == null) { reject(response, 401, ErrorCode.UNAUTHORIZED); return; }
                cors(response, origin);
                if (!browserRoute(path, method, false) || !identity.allowedCapabilities().contains("translate")) {
                    reject(response, 403, ErrorCode.POLICY_DENIED); return;
                }
            }
            cors(response, origin);
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                identity, null, List.of(new SimpleGrantedAuthority("ROLE_LOCAL_CLIENT"))));
        if (List.of("POST", "PUT", "PATCH", "DELETE").contains(method)) {
            boolean knowledgeBackup=identity.clientType().equals("native") && method.equals("POST")
                    && List.of("/api/v1/knowledge/backup/validate","/api/v1/knowledge/backup/restore").contains(path);
            if(knowledgeBackup){if(request.getContentLengthLong()>io.github.qianlixunbai.workspace.knowledge.KnowledgeLimits.BACKUP_BYTES){
                reject(response,413,ErrorCode.KNOWLEDGE_BACKUP_TOO_LARGE);return;}
                chain.doFilter(request,response);return;
            }
            boolean knowledgeUpload=identity.clientType().equals("native") && method.equals("POST") && path.equals("/api/v1/knowledge/imports");
            if(knowledgeUpload) {
                if(request.getContentLengthLong()>io.github.qianlixunbai.workspace.knowledge.KnowledgeLimits.SOURCE_BYTES) {
                    reject(response,413,ErrorCode.KNOWLEDGE_SOURCE_TOO_LARGE);return;
                }
                // Only this exact native route bypasses JSON buffering. Ingestion bounds the stream.
                chain.doFilter(request,response);return;
            }
            boolean workspaceBackup = identity.clientType().equals("native") && method.equals("POST")
                    && List.of("/api/v1/workspace/backup/restore", "/api/v1/workspace/backup/validate").contains(path);
            if (workspaceBackup) {
                if (request.getContentLengthLong() > io.github.qianlixunbai.workspace.backup.WorkspaceBackupService.MAX_BYTES) {
                    reject(response, 413, ErrorCode.WORKSPACE_BACKUP_TOO_LARGE); return;
                }
                // Authentication/Browser denial has already completed. Controller parses a bounded stream.
                chain.doFilter(request, response); return;
            }
            boolean restore = identity.clientType().equals("native") && method.equals("POST")
                    && path.equals("/api/v1/memory/backup/restore");
            int limit = restore ? MemoryBackup.MAX_RESTORE_BYTES : 32768;
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) { reject(response, 413, restore ? ErrorCode.MEMORY_BACKUP_TOO_LARGE : ErrorCode.INVALID_REQUEST); return; }
            request = new BufferedRequest(request, body);
        }
        chain.doFilter(request, response);
    }

    private static boolean browserRoute(String path, String method, boolean exchange) {
        if (exchange) return "POST".equals(method);
        return path.equals("/api/v1/translate/tasks") && "POST".equals(method)
                || browserGetRoute(path) && "GET".equals(method)
                || taskRoute(path) && "DELETE".equals(method);
    }

    private static boolean browserGetRoute(String path) {
        return path.equals("/api/v1/capabilities/translate/readiness") || taskRoute(path);
    }

    private static boolean taskRoute(String path) {
        return path.matches("/api/v1/tasks/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static boolean browserMetadata(HttpServletRequest request) {
        return "none".equals(request.getHeader("Sec-Fetch-Site"))
                && "cors".equals(request.getHeader("Sec-Fetch-Mode")) && "empty".equals(request.getHeader("Sec-Fetch-Dest"));
    }

    private static void cors(HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
        response.setHeader("Access-Control-Expose-Headers", "Location");
    }

    private void reject(HttpServletResponse response, int status, ErrorCode code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(json.writeValueAsBytes(ApiError.of(code, "HTTP")));
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                public int read() { return input.read(); }
                public boolean isFinished() { return input.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
