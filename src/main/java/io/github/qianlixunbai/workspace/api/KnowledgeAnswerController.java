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
    private final io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory history;
    private final io.github.qianlixunbai.workspace.task.TaskManager tasks;
    public KnowledgeAnswerController(KnowledgeAnswerService service,io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory history,
            io.github.qianlixunbai.workspace.task.TaskManager tasks){this.service=service;this.history=history;this.tasks=tasks;}
    @GetMapping public Object list(@RequestParam(defaultValue="0") int page) { KnowledgeController.nativeOnly();return history.list(page); }
    @GetMapping("/{id}") public Object history(@PathVariable java.util.UUID id) {
        KnowledgeController.nativeOnly();var entry=history.get(id);
        try { return new io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory.Entry(entry.question(),entry.query(),tasks.get(id),entry.scope()); }
        catch(WorkspaceException e) { if(e.error().code()!=ErrorCode.TASK_NOT_FOUND)throw e;return entry; }
    }
    @DeleteMapping("/{id}") public Object delete(@PathVariable java.util.UUID id) {
        KnowledgeController.nativeOnly();history.delete(id);return java.util.Map.of("deleted",true);
    }
    @PostMapping public ResponseEntity<TaskView> submit(@RequestBody JsonNode body) {
        KnowledgeController.nativeOnly();
        if(!body.isObject()||body.size()!=2+(body.has("scope")?1:0)||!body.has("question")||!body.get("question").isString()
                ||!body.has("query")||!body.get("query").isString())throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"REQUEST");
        var task=service.submit(new KnowledgeAnswerRequest(body.get("question").asString(),body.get("query").asString(),io.github.qianlixunbai.workspace.knowledge.KnowledgeScope.read(body)));
        return ResponseEntity.accepted().location(URI.create("/api/v1/tasks/"+task.taskId())).body(task);
    }
}
