package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.task.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final TaskManager tasks;
    public TaskController(TaskManager tasks) { this.tasks = tasks; }
    @GetMapping("/{id}") public TaskView get(@PathVariable UUID id) { return tasks.get(id, ClientIdentity.current().clientId()); }
    @DeleteMapping("/{id}") public TaskView cancel(@PathVariable UUID id) { return tasks.cancel(id, ClientIdentity.current().clientId()); }
}
