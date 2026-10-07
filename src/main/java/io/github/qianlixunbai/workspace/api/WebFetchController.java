package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import io.github.qianlixunbai.workspace.web.WebFetchService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/web/fetches")
public final class WebFetchController {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final WebFetchService service;
    public WebFetchController(WebFetchService service) { this.service = service; }
    @PostMapping(consumes = "application/json") ResponseEntity<WebFetchService.View> submit(HttpServletRequest request) throws IOException {
        ClientIdentity identity = ClientIdentity.current();
        WebFetchService.nativeOnly(identity);
        JsonNode body;
        try { body = JSON.readTree(request.getInputStream()); }
        catch (tools.jackson.core.JacksonException ignored) { throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "WEB"); }
        if (body == null || !body.isObject() || body.size() != 2 || !body.has("operationId") || !body.get("operationId").isString()
                || !body.has("url") || !body.get("url").isString()) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "WEB");
        var view = service.submit(identity, body.get("operationId").asString(), body.get("url").asString());
        return ResponseEntity.accepted().location(URI.create("/api/v1/web/fetches/" + view.operationId())).body(view);
    }
    @GetMapping("/{id}") WebFetchService.View get(@PathVariable String id) {
        ClientIdentity identity = ClientIdentity.current(); WebFetchService.nativeOnly(identity); return service.get(identity, id);
    }
    @DeleteMapping("/{id}") WebFetchService.View cancel(@PathVariable String id) {
        ClientIdentity identity = ClientIdentity.current(); WebFetchService.nativeOnly(identity); return service.cancel(identity, id);
    }
}
