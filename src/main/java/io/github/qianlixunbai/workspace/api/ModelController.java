package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.security.ClientIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/models")
public final class ModelController {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ActiveModelManager models;
    public ModelController(ActiveModelManager models) { this.models = models; }
    private static void nativeOnly(HttpServletRequest request) {
        if (!"native".equals(ClientIdentity.current().clientType())) throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL");
        if (request.getQueryString() != null) invalid();
    }
    @GetMapping("/catalog") ActiveModelManager.Catalog catalog(HttpServletRequest request) { nativeOnly(request); return models.catalog(); }
    @GetMapping({"/status", "/recovery"}) ActiveModelManager.ManagementStatus status(HttpServletRequest request) { nativeOnly(request); return models.managementStatus(); }
    @PostMapping(value = {"/selection", "/release", "/validation", "/recovery"}, consumes = "application/json")
    ActiveModelManager.ManagementStatus mutate(HttpServletRequest request) throws IOException {
        nativeOnly(request);
        JsonNode body;
        try { byte[] bytes = request.getInputStream().readNBytes(4097); if (bytes.length > 4096) invalid(); body = JSON.readTree(bytes); }
        catch (RuntimeException malformed) { invalid(); return null; }
        Set<String> fields = Set.of("action", "catalogHandle", "candidateModel", "candidateDigest", "expectedSelectionRevision",
                "expectedActiveModel", "expectedActiveDigest", "recoveryGeneration", "externalConfirmed");
        if (body == null || !body.isObject() || body.size() != fields.size()) invalid();
        for (String field : body.propertyNames()) if (!fields.contains(field)) invalid();
        ActiveModelManager.Action action;
        try { action = ActiveModelManager.Action.valueOf(text(body, "action", false)); }
        catch (IllegalArgumentException malformed) { invalid(); return null; }
        String route = request.getServletPath().substring("/api/v1/models/".length());
        if (!(route.equals("selection") && (action == ActiveModelManager.Action.SWITCH || action == ActiveModelManager.Action.RELEASE_OLD_THEN_SWITCH)
                || route.equals("release") && action == ActiveModelManager.Action.RELEASE
                || route.equals("validation") && action == ActiveModelManager.Action.VALIDATE
                || route.equals("recovery") && action == ActiveModelManager.Action.RECOVER)) invalid();
        JsonNode revision = body.path("expectedSelectionRevision");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.asLong() < 0 || revision.asLong() > ModelStateStore.MAX_REVISION
                || !body.path("externalConfirmed").isBoolean()) invalid();
        String model = text(body, "candidateModel", false), digest = text(body, "candidateDigest", false), handle = text(body, "catalogHandle", false);
        String old = text(body, "expectedActiveModel", true), oldDigest = text(body, "expectedActiveDigest", true), generation = text(body, "recoveryGeneration", true);
        if (!ModelStateStore.validModel(model) || !ModelStateStore.validDigest(digest) || !handle.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || (old == null) != (oldDigest == null) || old != null && (!ModelStateStore.validModel(old) || !ModelStateStore.validDigest(oldDigest))
                || generation != null && !generation.matches("[0-9a-f-]{36}")) invalid();
        return models.manage(new ActiveModelManager.Intent(action, handle, model, digest, revision.asLong(), old, oldDigest, generation, body.path("externalConfirmed").asBoolean()));
    }
    private static String text(JsonNode body, String field, boolean nullable) {
        JsonNode node = body.path(field);
        if (nullable && node.isNull()) return null;
        if (!node.isString() || node.asString().length() > 256) invalid();
        return node.asString();
    }
    private static void invalid() { throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "MODEL"); }
}
