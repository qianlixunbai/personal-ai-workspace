package io.github.qianlixunbai.workspace.memory;

import java.time.Instant;
import java.util.UUID;

public record MemoryItem(UUID id, Type type, String title, String content, Status status,
                         long revision, Source source, Instant createdAt, Instant updatedAt) {
    public enum Type { PREFERENCE, PROJECT_NOTE }
    public enum Status { ACTIVE, ARCHIVED }
    public enum Source { MANUAL }
    // Safe diagnostic representation: never render personal text.
    @Override public String toString() {
        return "MemoryItem[id=" + id + ",type=" + type + ",status=" + status + ",revision=" + revision + "]";
    }
}
