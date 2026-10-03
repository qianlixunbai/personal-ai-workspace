package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.common.*;
import java.util.*;

public record MemoryReference(UUID id, long revision) {
    public static void validate(List<MemoryReference> references) {
        if (references == null || references.isEmpty() || references.size() > 4)
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "MEMORY_SELECTION");
        Set<UUID> ids = new HashSet<>();
        for (var ref : references) {
            if (ref == null || ref.id() == null || ref.id().equals(new UUID(0, 0)) || ref.revision() <= 0 || !ids.add(ref.id()))
                throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "MEMORY_SELECTION");
        }
    }
    @Override public String toString() { return "MemoryReference[redacted]"; }
}
