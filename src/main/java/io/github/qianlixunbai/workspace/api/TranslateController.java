package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.capability.translate.*;
import io.github.qianlixunbai.workspace.task.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/translate")
public class TranslateController {
    private final TranslateService translate;
    public TranslateController(TranslateService translate) { this.translate = translate; }
    @PostMapping("/tasks")
    public ResponseEntity<TaskView> submit(@Valid @RequestBody TranslateRequest request) {
        TaskView task = translate.submit(request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/tasks/" + task.taskId())).body(task);
    }
}
