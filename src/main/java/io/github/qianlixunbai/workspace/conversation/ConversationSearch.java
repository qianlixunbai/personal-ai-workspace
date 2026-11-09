package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import java.util.*;
import static io.github.qianlixunbai.workspace.conversation.ConversationLimits.*;

/** Read-only literal message search. No derived index, model or transcript cache. */
public final class ConversationSearch {
    public enum Scope { ALL, ACTIVE, ARCHIVED }
    public record Request(String query, Scope scope, int page) {
        public void validate() {
            unicode(query);
            if (query.codePointCount(0, query.length()) > 100 || scope == null || page < 0 || page > 199999)
                throw error(ErrorCode.CONVERSATION_INVALID);
        }
        @Override public String toString() { return "ConversationSearchRequest[redacted]"; }
    }
    public record Hit(Conversation conversation, UUID turnId, long sequence, UUID messageId, Conversation.Role role,
                      String snippet, int matchStart, int matchEnd, boolean before, boolean after) {
        @Override public String toString() { return "ConversationSearchHit[redacted]"; }
    }
    public record Page(String query, Scope scope, List<Hit> items, boolean hasMore, int page, int limit) {
        public Page { items = List.copyOf(items); }
        @Override public String toString() { return "ConversationSearchPage[page=" + page + "]"; }
    }
    static Hit hit(Conversation conversation, UUID turn, long sequence, UUID message, Conversation.Role role, String content, String query) {
        int at = content.indexOf(query), end = at + query.length();
        if (at < 0) throw error(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE);
        int start = content.offsetByCodePoints(at, -Math.min(60, content.codePointCount(0, at)));
        int finish = content.offsetByCodePoints(end, Math.min(60, content.codePointCount(end, content.length())));
        return new Hit(conversation, turn, sequence, message, role, content.substring(start, finish), at - start, end - start, start > 0, finish < content.length());
    }
    private ConversationSearch() {}
}
