package io.github.qianlixunbai.workspace.web;

import io.github.qianlixunbai.workspace.common.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.apache.hc.core5.http.*;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;
import org.jsoup.select.*;

final class WebContentExtractor {
    static final String VERSION = "web-extract-1";
    private static final Map<String, String> CHARSETS = Map.ofEntries(
            Map.entry("utf-8", "UTF-8"), Map.entry("utf8", "UTF-8"),
            Map.entry("us-ascii", "US-ASCII"), Map.entry("ascii", "US-ASCII"), Map.entry("iso-ir-6", "US-ASCII"),
            Map.entry("iso-8859-1", "ISO-8859-1"), Map.entry("iso8859-1", "ISO-8859-1"),
            Map.entry("latin1", "ISO-8859-1"), Map.entry("latin-1", "ISO-8859-1"),
            Map.entry("windows-1252", "windows-1252"), Map.entry("cp1252", "windows-1252"),
            Map.entry("gb18030", "GB18030"));
    record Admission(String mime, Charset charset) { @Override public String toString() { return "Admission[redacted]"; } }
    record Extracted(String title, String text, boolean titleTruncated, boolean textTruncated) {
        @Override public String toString() { return "Extracted[redacted]"; }
    }
    Admission admit(ClassicHttpResponse response) {
        Header[] types = response.getHeaders("Content-Type");
        if (types.length != 1) throw unsupported();
        Parameters type = parameters(types[0].getValue());
        if (!Set.of("text/html", "text/plain", "application/xhtml+xml").contains(type.name)) throw unsupported();
        Header[] encodings = response.getHeaders("Content-Encoding");
        if (encodings.length > 1 || encodings.length == 1 && !"identity".equalsIgnoreCase(encodings[0].getValue().strip())) throw invalid();
        Header[] dispositions = response.getHeaders("Content-Disposition");
        if (dispositions.length > 1) throw invalid();
        if (dispositions.length == 1 && parameters(dispositions[0].getValue()).name.equals("attachment")) throw invalid();
        String selected = type.values.getOrDefault("charset", "utf-8").toLowerCase(Locale.ROOT);
        String charset = CHARSETS.get(selected);
        if (charset == null) throw invalid();
        return new Admission(type.name, Charset.forName(charset));
    }
    Extracted extract(byte[] bytes, Admission admission) {
        if (bytes.length > WebLimits.BODY) throw tooLarge();
        int offset = 0;
        if (starts(bytes, 0xef, 0xbb, 0xbf)) {
            if (!admission.charset.equals(StandardCharsets.UTF_8)) throw invalid();
            offset = 3;
        } else if (starts(bytes, 0xff, 0xfe) || starts(bytes, 0xfe, 0xff)
                || starts(bytes, 0, 0, 0xfe, 0xff)) throw invalid();
        String decoded;
        try {
            decoded = admission.charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException ignored) { throw invalid(); }
        if (decoded.getBytes(StandardCharsets.UTF_8).length > WebLimits.DECODED) throw tooLarge();
        controls(decoded);
        if (decoded.startsWith("%PDF-") || decoded.startsWith("PK\u0003\u0004")) throw invalid();
        BoundedText text = new BoundedText(WebLimits.TEXT_UNITS, WebLimits.TEXT_UNITS, WebLimits.TEXT_BYTES);
        BoundedText title = new BoundedText(Integer.MAX_VALUE, WebLimits.TITLE_SCALARS, WebLimits.TITLE_BYTES);
        if (admission.mime.equals("text/plain")) text.append(decoded);
        else {
            // String parsing only. No jsoup Connection, URL parsing, subresources or XML/DTD retrieval.
            Document dom = Parser.htmlParser().setMaxDepth(128).parseInput(decoded, "");
            dom.select("script,style,template,iframe,frame,frameset,object,embed,applet,video,audio,picture,svg,math,canvas,noscript").remove();
            for (Element candidate : dom.select("title")) {
                title.append(candidate.text());
                if (!title.value().isEmpty()) break;
            }
            NodeTraversor.traverse(new NodeVisitor() {
                public void head(Node node, int depth) {
                    if (node instanceof Element element && (element.isBlock() || element.normalName().equals("br"))) text.boundary();
                    if (node instanceof TextNode leaf) text.append(leaf.getWholeText());
                }
                public void tail(Node node, int depth) { if (node instanceof Element element && element.isBlock()) text.boundary(); }
            }, dom.body());
            // Only bounded strings escape this method; the DOM is not retained.
        }
        return new Extracted(title.value(), text.value(), title.truncated, text.truncated);
    }
    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }
    private static void controls(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t' || c >= 127 && c <= 159) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }
    /** RFC-style token/quoted-string parameters; no tolerant comma or duplicate-parameter fallback. */
    private static Parameters parameters(String input) {
        if (input == null) throw invalid();
        int separator = input.indexOf(';');
        String name = (separator < 0 ? input : input.substring(0, separator)).strip().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9!#$&^_.+*-]+(?:/[a-z0-9!#$&^_.+*-]+)?")) throw invalid();
        Map<String, String> values = new HashMap<>();
        int index = separator < 0 ? input.length() : separator;
        while (index < input.length()) {
            if (input.charAt(index++) != ';') throw invalid();
            while (index < input.length() && ows(input.charAt(index))) index++;
            int start = index;
            while (index < input.length() && token(input.charAt(index))) index++;
            if (start == index) throw invalid();
            String key = input.substring(start, index).toLowerCase(Locale.ROOT);
            while (index < input.length() && ows(input.charAt(index))) index++;
            if (index >= input.length() || input.charAt(index++) != '=') throw invalid();
            while (index < input.length() && ows(input.charAt(index))) index++;
            String value;
            if (index < input.length() && input.charAt(index) == '"') {
                index++; StringBuilder quoted = new StringBuilder(); boolean closed = false;
                while (index < input.length()) {
                    char c = input.charAt(index++);
                    if (c == '"') { closed = true; break; }
                    if (c == '\\') { if (index >= input.length()) throw invalid(); c = input.charAt(index++); }
                    if (c < 32 || c >= 127) throw invalid();
                    quoted.append(c);
                }
                if (!closed) throw invalid();
                value = quoted.toString();
            } else {
                start = index;
                while (index < input.length() && token(input.charAt(index))) index++;
                if (index == start) throw invalid();
                value = input.substring(start, index);
            }
            if (values.putIfAbsent(key, value) != null) throw invalid();
            while (index < input.length() && ows(input.charAt(index))) index++;
        }
        return new Parameters(name, values);
    }
    private record Parameters(String name, Map<String, String> values) {}
    private static boolean ows(char c) { return c == ' ' || c == '\t'; }
    private static boolean token(char c) { return c > 32 && c < 127 && "()<>@,;:\\\"/[]?={}".indexOf(c) < 0; }
    private static WorkspaceException unsupported() { return new WorkspaceException(ErrorCode.WEB_CONTENT_TYPE_UNSUPPORTED, "CONTENT"); }
    static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.WEB_CONTENT_INVALID, "CONTENT"); }
    static WorkspaceException tooLarge() { return new WorkspaceException(ErrorCode.WEB_RESPONSE_TOO_LARGE, "CONTENT"); }

    private static final class BoundedText {
        final int unitsLimit, scalarsLimit, bytesLimit;
        final StringBuilder buffer = new StringBuilder();
        int scalars, bytes; boolean space, truncated;
        BoundedText(int units, int scalars, int bytes) { unitsLimit = units; scalarsLimit = scalars; bytesLimit = bytes; }
        void boundary() { space = !buffer.isEmpty(); }
        void append(String value) {
            controls(value);
            for (int i = 0; i < value.length();) {
                int cp = value.codePointAt(i); i += Character.charCount(cp);
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { boundary(); continue; }
                if (truncated) continue;
                int size = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
                int padding = space ? 1 : 0;
                if (buffer.length() + padding + Character.charCount(cp) > unitsLimit
                        || scalars + padding + 1 > scalarsLimit || bytes + padding + size > bytesLimit) { truncated = true; continue; }
                if (space) { buffer.append(' '); bytes++; scalars++; space = false; }
                buffer.appendCodePoint(cp); bytes += size; scalars++;
            }
        }
        String value() { return buffer.toString(); }
    }
}
