package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.capability.translate.*;
import io.github.qianlixunbai.workspace.task.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
public class TranslateController {
    private final TranslateService translate;
    public TranslateController(TranslateService translate) { this.translate = translate; }
    @GetMapping("/api/v1/capabilities/translate/readiness")
    public TranslateReadiness readiness(jakarta.servlet.http.HttpServletRequest request) {
        var query = request.getParameterMap();
        if (!query.isEmpty() && (query.size() != 1 || !query.containsKey("cacheIdentityVersion")
                || query.get("cacheIdentityVersion").length != 1 || !"1".equals(query.get("cacheIdentityVersion")[0])))
            throw new io.github.qianlixunbai.workspace.common.WorkspaceException(
                    io.github.qianlixunbai.workspace.common.ErrorCode.INVALID_REQUEST, "CACHE_IDENTITY_VERSION");
        // Bound and reject empty/trailing query components rather than silently ignoring them.
        String raw = request.getQueryString();
        if (raw != null && (raw.length() > 64 || raw.isEmpty() || raw.startsWith("&") || raw.endsWith("&") || raw.contains("&&")))
            throw new io.github.qianlixunbai.workspace.common.WorkspaceException(
                    io.github.qianlixunbai.workspace.common.ErrorCode.INVALID_REQUEST, "CACHE_IDENTITY_VERSION");
        return translate.readiness(!query.isEmpty());
    }
    @PostMapping("/api/v1/translate/tasks")
    public ResponseEntity<TaskView> submit(@Valid @RequestBody TranslateRequest request) {
        TaskView task = translate.submit(request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/tasks/" + task.taskId())).body(task);
    }
}
