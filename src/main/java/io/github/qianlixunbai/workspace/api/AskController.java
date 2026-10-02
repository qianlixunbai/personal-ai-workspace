package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.capability.ask.*;
import io.github.qianlixunbai.workspace.task.TaskView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/ask")
public class AskController {
    private final AskService service;
    public AskController(AskService service) { this.service = service; }
    @PostMapping("/tasks")
    public ResponseEntity<TaskView> submit(@Valid @RequestBody AskRequest request) {
        TaskView task = service.submit(request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/tasks/" + task.taskId())).body(task);
    }
}
