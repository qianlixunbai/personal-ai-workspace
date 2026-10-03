package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.common.*;
import java.nio.charset.StandardCharsets;

public final class MemoryLimits {
    public static final int TOTAL_ITEMS = 1000;
    public static final int TITLE_CODE_POINTS = 160;
    public static final int CONTENT_UTF16 = 2000;
    public static final int CONTENT_UTF8_BYTES = 8 * 1024;
    public static final int QUERY_CODE_POINTS = 160;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int BUSY_TIMEOUT_MS = 3000;
    private MemoryLimits() {}

    static WorkspaceException error(ErrorCode code) { return new WorkspaceException(code, "MEMORY"); }
    static void text(MemoryItem.Type type, String title, String content) {
        if (type == null || title == null || content == null || title.strip().isEmpty() || content.strip().isEmpty()
                || !validUnicode(title) || !validUnicode(content)) throw error(ErrorCode.MEMORY_INVALID);
        int utf16 = content.length();
        int utf8Bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (title.codePointCount(0, title.length()) > TITLE_CODE_POINTS || utf16 > CONTENT_UTF16
                || utf8Bytes > CONTENT_UTF8_BYTES)
            throw error(ErrorCode.MEMORY_LIMIT_EXCEEDED);
        // Preserve original title/content, including surrounding whitespace and line endings.
    }
    static void revision(long revision) {
        if (revision < 1) throw error(ErrorCode.MEMORY_INVALID);
    }
    static void query(String query, int page, int limit) {
        if (page < 0 || limit < 1 || limit > MAX_PAGE_SIZE || query != null
                && (!validUnicode(query) || query.indexOf('\0') >= 0
                || query.codePointCount(0, query.length()) > QUERY_CODE_POINTS)) throw error(ErrorCode.MEMORY_INVALID);
    }
    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == 0) return false;
            if (Character.isHighSurrogate(ch)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(ch)) return false;
        }
        return true;
    }
}
