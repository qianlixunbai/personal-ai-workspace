package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.capability.ask.*;
import io.github.qianlixunbai.workspace.task.TaskView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/memory/ask")
public class MemoryAskController {
    private final MemoryAskService service;
    public MemoryAskController(MemoryAskService service) { this.service = service; }
    @PostMapping("/tasks")
    public ResponseEntity<TaskView> submit(@Valid @RequestBody MemoryAskRequest request) {
        TaskView task = service.submit(request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/tasks/" + task.taskId())).body(task);
    }
}
