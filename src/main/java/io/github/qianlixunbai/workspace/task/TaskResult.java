package io.github.qianlixunbai.workspace.task;

import java.util.List;

/** Narrow structured results. Existing text results remain JSON strings. */
public sealed interface TaskResult permits TaskResult.TranslationBatch, TaskResult.KnowledgeAnswer {
    record KnowledgeAnswer(String answer, List<KnowledgeCitation> citations) implements TaskResult {
        public KnowledgeAnswer { citations = List.copyOf(citations); }
        @Override public String toString() { return "KnowledgeAnswer[citationCount=" + citations.size() + ",redacted]"; }
    }
    record KnowledgeCitation(String documentId, String title, String sourceRevision, String sourceType,
            int startOffset, int endOffset, int startLine, int endLine, String heading, KnowledgeLocator locator) {
        @Override public String toString() { return "KnowledgeCitation[redacted]"; }
    }
    // Heading is already present on the citation; the structural locator need not duplicate it.
    record KnowledgeLocator(String type, int startLine, int endLine, int startOffset, int endOffset, String section) {
        @Override public String toString() { return "KnowledgeLocator[type=" + type + "]"; }
    }
    record TranslationBatch(List<Translation> items) implements TaskResult {
        public TranslationBatch { items = List.copyOf(items); }
        @Override public String toString() { return "TranslationBatch[itemCount=" + items.size() + "]"; }
    }
    record Translation(int id, String translation) {
        @Override public String toString() { return "Translation[id=" + id + ",redacted]"; }
    }
}
