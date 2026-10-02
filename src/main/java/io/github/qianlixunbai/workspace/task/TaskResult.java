package io.github.qianlixunbai.workspace.task;

import java.util.List;

/** The one current structured result shape. Existing text results remain JSON strings. */
public sealed interface TaskResult permits TaskResult.TranslationBatch {
    record TranslationBatch(List<Translation> items) implements TaskResult {
        public TranslationBatch { items = List.copyOf(items); }
        @Override public String toString() { return "TranslationBatch[itemCount=" + items.size() + "]"; }
    }
    record Translation(int id, String translation) {
        @Override public String toString() { return "Translation[id=" + id + ",redacted]"; }
    }
}
