package io.github.qianlixunbai.workspace.knowledge;

public record KnowledgeDocumentRevision(String documentId, String sourceRevision, String sourceDigest,
        String originalFilename, String sourceType, long byteLength, String importedAt,
        String parserVersion, String normalizationVersion, String representationDigest, int lineCount) {
    @Override public String toString() { return "KnowledgeDocumentRevision[documentId="+documentId+",sourceRevision="+sourceRevision+"]"; }
}
