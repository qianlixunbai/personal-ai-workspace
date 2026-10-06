package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.capability.knowledge.*;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.task.TaskView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/knowledge/answer/tasks")
public final class KnowledgeAnswerController {
    private final KnowledgeAnswerService service;
    public KnowledgeAnswerController(KnowledgeAnswerService service){this.service=service;}
    @PostMapping public ResponseEntity<TaskView> submit(@RequestBody JsonNode body) {
        KnowledgeController.nativeOnly();
        if(!body.isObject()||body.size()!=2||!body.has("question")||!body.get("question").isString()
                ||!body.has("query")||!body.get("query").isString())throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"REQUEST");
        var task=service.submit(new KnowledgeAnswerRequest(body.get("question").asString(),body.get("query").asString()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/tasks/"+task.taskId())).body(task);
    }
}
