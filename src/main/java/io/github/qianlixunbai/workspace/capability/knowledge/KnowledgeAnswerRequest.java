package io.github.qianlixunbai.workspace.capability.knowledge;

public record KnowledgeAnswerRequest(String question, String query) {
    @Override public String toString() { return "KnowledgeAnswerRequest[redacted]"; }
}
