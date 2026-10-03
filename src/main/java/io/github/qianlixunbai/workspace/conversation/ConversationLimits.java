package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.common.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ConversationLimits {
    public static final String DEFAULT_TITLE = "New conversation";
    public static final int TITLE_POINTS = 160, CONTENT_UTF16 = 8192, CONTENT_BYTES = 8192;
    public static final int TOTAL_CONVERSATIONS = 1000, TURNS_PER_CONVERSATION = 1000;
    public static final int DEFAULT_LIMIT = 10, MAX_LIMIT = 10;
    private ConversationLimits() {}
    static WorkspaceException error(ErrorCode code) { return new WorkspaceException(code, "CONVERSATION"); }
    static void id(UUID id) { if (id == null || id.equals(new UUID(0, 0))) throw error(ErrorCode.CONVERSATION_INVALID); }
    static void unicode(String value) {
        if (value == null || value.strip().isEmpty()) throw error(ErrorCode.CONVERSATION_INVALID);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw error(ErrorCode.CONVERSATION_INVALID);
            if (Character.isHighSurrogate(c) && (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))))
                throw error(ErrorCode.CONVERSATION_INVALID);
        }
    }
    static String title(String value) {
        unicode(value);
        if (value.codePointCount(0, value.length()) > TITLE_POINTS) throw error(ErrorCode.CONVERSATION_INVALID);
        return value.strip();
    }
    static void content(String value) {
        unicode(value);
        if (value.length() > CONTENT_UTF16 || value.getBytes(StandardCharsets.UTF_8).length > CONTENT_BYTES)
            throw error(ErrorCode.CONVERSATION_LIMIT_EXCEEDED);
    }
    static void page(int page, int limit) {
        if (page < 0 || limit < 1 || limit > MAX_LIMIT) throw error(ErrorCode.CONVERSATION_INVALID);
    }
}
