package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Restricted raw ASCII URI contract, deliberately not a browser URL normalizer. */
public final class WebTarget {
    private static final Set<String> LOCAL_SUFFIXES = Set.of("localhost", "local", "localdomain", "internal",
            "intranet", "lan", "home", "corp", "test", "invalid", "example", "onion", "alt", "arpa");
    private final URI uri;
    private final String hostname;
    private WebTarget(URI uri, String hostname) { this.uri = uri; this.hostname = hostname; }

    public static WebTarget parse(String supplied) {
        if (!ascii(supplied) || supplied.indexOf('#') >= 0 || supplied.indexOf('\\') >= 0) throw invalid();
        try {
            URI input = new URI(supplied);
            if (!input.isAbsolute() || input.isOpaque() || !"https".equalsIgnoreCase(input.getScheme())) throw invalid();
            String authority = input.getRawAuthority();
            if (authority == null || authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0
                    || authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0) throw invalid();
            int colon = authority.indexOf(':');
            String host = colon < 0 ? authority : authority.substring(0, colon);
            if (colon >= 0 && !authority.substring(colon).equals(":443")) throw invalid();
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.length() > 253 || host.indexOf('.') < 0) throw invalid();
            String[] labels = host.split("\\.", -1);
            for (String label : labels)
                if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw invalid();
            // Numeric final labels cover dotted/short/octal/hex address authorities without interpreting them.
            String last = labels[labels.length - 1];
            if (last.matches("[0-9]+|0x[0-9a-f]+") || LOCAL_SUFFIXES.contains(last)) throw invalid();
            String path = input.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (!path.startsWith("/") || path.startsWith("//")) throw invalid();
            // Apache and URI relative resolution may normalize literal dot segments. Reject rather than change approval.
            for (String part : path.split("/", -1)) if (part.equals(".") || part.equals("..")) throw invalid();
            String canonical = "https://" + host + path + (input.getRawQuery() == null ? "" : "?" + input.getRawQuery());
            if (!ascii(canonical)) throw invalid();
            return new WebTarget(new URI(canonical), host);
        } catch (java.net.URISyntaxException ignored) { throw invalid(); }
    }
    static boolean ascii(String value) {
        if (value == null || value.isEmpty() || value.length() > WebLimits.URL) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) <= 32 || value.charAt(i) >= 127) return false;
        return true;
    }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.WEB_TARGET_INVALID, "TARGET"); }
    public String url() { return uri.toASCIIString(); }
    public String hostname() { return hostname; }
    URI uri() { return uri; }
    String requestTarget() { return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()); }
    @Override public String toString() { return "WebTarget[redacted]"; }
}
