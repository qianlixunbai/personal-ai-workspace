package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import tools.jackson.databind.JsonNode;
import java.util.List;

/** An explicit current source revision, never a title or a model-authored filter. Null means all current sources. */
public record KnowledgeScope(String documentId,String sourceRevision) {
    public KnowledgeScope {
        KnowledgeStore.id(documentId);
        if(KnowledgeStore.version(sourceRevision)>10)throw KnowledgeParser.error(ErrorCode.KNOWLEDGE_SEARCH_INVALID);
    }
    public static KnowledgeScope read(JsonNode body) {
        if(!body.has("scope")||body.get("scope").isNull())return null;
        var s=body.get("scope");
        if(!s.isObject()||s.size()!=2||!s.has("documentId")||!s.get("documentId").isString()
                ||!s.has("sourceRevision")||!s.get("sourceRevision").isString())throw KnowledgeParser.error(ErrorCode.KNOWLEDGE_SEARCH_INVALID);
        return new KnowledgeScope(s.get("documentId").asString(),s.get("sourceRevision").asString());
    }
    void requireCurrent(List<KnowledgeStore.SearchRef> refs) {
        if(refs.stream().noneMatch(r->matches(r.documentId(),r.sourceRevision())))
            throw KnowledgeParser.error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
    }
    public boolean matches(String id,String revision){return documentId.equals(id)&&sourceRevision.equals(revision);}
    @Override public String toString(){return "KnowledgeScope[redacted]";}
}
