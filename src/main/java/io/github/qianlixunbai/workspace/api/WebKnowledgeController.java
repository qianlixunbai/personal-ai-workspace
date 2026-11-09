package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeStore;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import io.github.qianlixunbai.workspace.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/web/fetches")
public final class WebKnowledgeController {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final WebKnowledgeCapture capture;
    public WebKnowledgeController(WebKnowledgeCapture capture) { this.capture = capture; }
    @PostMapping(value = "/{id}/knowledge", consumes = "application/json")
    KnowledgeStore.Job save(@PathVariable String id, HttpServletRequest request) throws IOException {
        ClientIdentity identity = ClientIdentity.current(); WebFetchService.nativeOnly(identity);
        JsonNode body;
        try { body = JSON.readTree(request.getInputStream()); }
        catch (tools.jackson.core.JacksonException ignored) { throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "WEB_CAPTURE"); }
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("requestId") || !body.get("requestId").isString())
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "WEB_CAPTURE");
        return capture.save(identity, id, body.get("requestId").asString());
    }
}
