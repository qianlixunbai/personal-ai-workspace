package io.github.qianlixunbai.workspace.knowledge;

public record KnowledgeDocument(String documentId, String title, String status, String metadataVersion,
        String currentReadyRevision, String createdAt, String updatedAt, String processingState, String requestId) {
    @Override public String toString() { return "KnowledgeDocument[documentId="+documentId+",status="+status+"]"; }
}
