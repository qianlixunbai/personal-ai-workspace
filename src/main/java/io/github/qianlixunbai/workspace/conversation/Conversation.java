package io.github.qianlixunbai.workspace.conversation;

import java.time.Instant;
import java.util.*;

public record Conversation(UUID id, String title, Status status, Instant createdAt, Instant updatedAt) {
    public enum Status { ACTIVE, ARCHIVED }
    public enum TurnStatus { PENDING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
    public enum Role { USER, ASSISTANT }
    public enum FailureCode { EXECUTION_INTERRUPTED, PROVIDER_UNAVAILABLE, MODEL_UNAVAILABLE, QUEUE_FULL, POLICY_DENIED, EXECUTION_FAILED, STORAGE_UNAVAILABLE }
    public record Message(UUID id, UUID turnId, Role role, String content, Instant createdAt) {
        @Override public String toString() { return "Message[id=" + id + ",role=" + role + "]"; }
    }
    public record Turn(UUID id, UUID conversationId, long sequence, TurnStatus status,
                       Instant createdAt, Instant updatedAt, Message userMessage, Message assistantMessage,
                       UUID taskId, FailureCode failureCode, List<Selection> memories) {
        public Turn { memories = List.copyOf(memories); }
        @Override public String toString() { return "Turn[id=" + id + ",sequence=" + sequence + ",status=" + status + "]"; }
    }
    public record Selection(UUID memoryId, long revision, int position) {
        @Override public String toString() { return "Selection[redacted]"; }
    }
    public record Page(List<Conversation> items, long total, int page, int limit) {
        public Page { items = List.copyOf(items); }
        @Override public String toString() { return "ConversationPage[total=" + total + ",page=" + page + "]"; }
    }
    public record Detail(Conversation conversation, List<Turn> turns, long totalTurns, int page, int limit) {
        public Detail { turns = List.copyOf(turns); }
        @Override public String toString() { return "ConversationDetail[id=" + conversation.id() + ",totalTurns=" + totalTurns + "]"; }
    }
    @Override public String toString() { return "Conversation[id=" + id + ",status=" + status + "]"; }
}
