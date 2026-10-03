package io.github.qianlixunbai.workspace.memory;

import java.util.UUID;

/** Admission-time immutable data; no storage or source metadata. */
public record MemorySnapshot(UUID id, MemoryItem.Type type, String title, String content, long revision) {
    @Override public String toString() { return "MemorySnapshot[redacted]"; }
}
