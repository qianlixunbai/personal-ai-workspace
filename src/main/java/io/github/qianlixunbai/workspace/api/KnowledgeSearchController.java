package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.knowledge.KnowledgeLexicalIndex;
import io.github.qianlixunbai.workspace.common.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/knowledge/search")
public final class KnowledgeSearchController {
    private final KnowledgeLexicalIndex index;
    public KnowledgeSearchController(KnowledgeLexicalIndex index){this.index=index;}
    @PostMapping KnowledgeLexicalIndex.Results search(@RequestBody JsonNode body){
        KnowledgeController.nativeOnly();
        if(!body.isObject()||!body.has("query")||!body.get("query").isString()
                ||body.size()!=(body.has("limit")?2:1)||body.has("limit")&&(!body.get("limit").isIntegralNumber()||!body.get("limit").canConvertToInt()))
            throw new WorkspaceException(ErrorCode.KNOWLEDGE_SEARCH_INVALID,"KNOWLEDGE");
        return index.search(body.get("query").asString(),body.has("limit")?body.get("limit").asInt():null);
    }
    @GetMapping("/status") KnowledgeLexicalIndex.Status status(){KnowledgeController.nativeOnly();return index.status();}
    @PostMapping("/rebuild") KnowledgeLexicalIndex.Status rebuild(@RequestBody JsonNode body){KnowledgeController.nativeOnly();
        if(!body.isObject()||body.size()!=0)throw new WorkspaceException(ErrorCode.KNOWLEDGE_SEARCH_INVALID,"KNOWLEDGE");return index.rebuild();}
}
