package io.github.qianlixunbai.workspace.security;

import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.Set;

/** Public metadata only. Credentials and verifiers never belong in this principal. */
public record ClientIdentity(String clientId, String clientType, String displayName, String origin,
                             Instant createdAt, Set<String> allowedCapabilities) {
    public ClientIdentity { allowedCapabilities = Set.copyOf(allowedCapabilities); }
    @Override public String toString() { return "ClientIdentity[" + clientType + "]"; }
    public static final String NATIVE_OWNER = "native-local";
    public static final ClientIdentity NATIVE = new ClientIdentity(NATIVE_OWNER, "native", "Local native clients",
            null, Instant.EPOCH, Set.of("translate", "summarize", "ask"));

    public static ClientIdentity current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ClientIdentity client) return client;
        // In-process service callers retain the native trust domain; HTTP always passes the authentication filter.
        return NATIVE;
    }
}
