package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.task.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeAnswerHistory;
import io.github.qianlixunbai.workspace.common.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final TaskManager tasks;
    private final KnowledgeAnswerHistory history;
    public TaskController(TaskManager tasks,KnowledgeAnswerHistory history) { this.tasks = tasks; this.history=history; }
    @GetMapping("/{id}") public TaskView get(@PathVariable UUID id) { return read(id,false); }
    @DeleteMapping("/{id}") public TaskView cancel(@PathVariable UUID id) { return read(id,true); }
    private TaskView read(UUID id,boolean cancel) {
        try { return cancel?tasks.cancel(id,ClientIdentity.current().clientId()):tasks.get(id,ClientIdentity.current().clientId()); }
        catch(WorkspaceException e) {
            if(e.error().code()!=ErrorCode.TASK_NOT_FOUND||!ClientIdentity.NATIVE.equals(ClientIdentity.current()))throw e;
            return history.get(id).task(); // Read a terminal receipt; never requeue a historical execution.
        }
    }
}
