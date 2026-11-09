package io.github.qianlixunbai.workspace.capability.knowledge;

public record KnowledgeAnswerRequest(String question, String query, io.github.qianlixunbai.workspace.knowledge.KnowledgeScope scope) {
    public KnowledgeAnswerRequest(String question,String query){this(question,query,null);}
    @Override public String toString() { return "KnowledgeAnswerRequest[redacted]"; }
}
