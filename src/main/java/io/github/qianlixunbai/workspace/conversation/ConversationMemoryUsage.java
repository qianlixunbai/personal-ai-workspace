package io.github.qianlixunbai.workspace.conversation;

import java.util.*;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import static io.github.qianlixunbai.workspace.conversation.ConversationLimits.*;

/** Recorded selections, not proof that a model used or agreed with a memory. */
public final class ConversationMemoryUsage {
    public record Request(UUID memoryId, Long revision, int page) {
        public void validate() {
            id(memoryId);
            if (revision != null && revision < 1 || page < 0 || page > 99999)
                throw error(ErrorCode.CONVERSATION_INVALID);
        }
        @Override public String toString() { return "ConversationMemoryUsageRequest[redacted]"; }
    }
    public record Hit(Conversation conversation, UUID turnId, long sequence, Conversation.TurnStatus status, long revision) {
        @Override public String toString() { return "ConversationMemoryUsageHit[redacted]"; }
    }
    public record Page(UUID memoryId, Long revision, List<Hit> items, boolean hasMore, int page, int limit) {
        public Page { items = List.copyOf(items); }
        @Override public String toString() { return "ConversationMemoryUsagePage[page=" + page + "]"; }
    }
    private ConversationMemoryUsage() {}
}
