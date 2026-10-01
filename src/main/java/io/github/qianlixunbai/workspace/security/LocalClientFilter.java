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

final class LocalClientFilter extends OncePerRequestFilter {
    private final LocalClientToken token;
    private final JsonMapper json = JsonMapper.builder().build();
    LocalClientFilter(LocalClientToken token) { this.token = token; }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean health = request.getMethod().equals("GET") && List.of("/actuator/health",
                "/actuator/health/liveness", "/actuator/health/readiness").contains(request.getServletPath());
        if (health) { chain.doFilter(request, response); return; }
        if (request.getHeader("Origin") != null || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                || !token.matches(request.getHeader("Authorization"))) {
            reject(response, 401, ErrorCode.UNAUTHORIZED); return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "local-client", null, List.of(new SimpleGrantedAuthority("ROLE_LOCAL_CLIENT"))));
        if (request.getMethod().equals("POST")) {
            byte[] body = request.getInputStream().readNBytes(32769);
            if (body.length > 32768) { reject(response, 413, ErrorCode.INVALID_REQUEST); return; }
            request = new BufferedRequest(request, body);
        }
        chain.doFilter(request, response);
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
