package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.ActiveModelManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelControllerTest {
    @Test void nativeIntentRequiresExplicitRunnerAndPreservesBothAuthorizedIdentities() throws Exception {
        var manager = mock(ActiveModelManager.class);
        var controller = new ModelController(manager);
        var json = JsonMapper.builder().build();
        var body = new LinkedHashMap<String, Object>();
        body.put("action", "SWITCH"); body.put("catalogHandle", "33333333-3333-4333-8333-333333333333");
        body.put("candidateModel", "test-model:latest"); body.put("candidateRunner", "ggml"); body.put("candidateDigest", "a".repeat(64));
        body.put("expectedSelectionRevision", 1); body.put("expectedActiveModel", "test-model:latest");
        body.put("expectedActiveRunner", "llamacpp"); body.put("expectedActiveDigest", "c".repeat(64));
        body.put("recoveryGeneration", null); body.put("externalConfirmed", false);
        for (String field : List.of("candidateRunner", "expectedActiveRunner")) {
            var legacy = new LinkedHashMap<>(body); legacy.remove(field);
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(WorkspaceException.class,
                    () -> controller.mutate(request(json.writeValueAsBytes(legacy)))).error().code());
        }
        var invalid = new LinkedHashMap<>(body); invalid.put("candidateRunner", "default");
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(WorkspaceException.class,
                () -> controller.mutate(request(json.writeValueAsBytes(invalid)))).error().code());
        verifyNoInteractions(manager);
        controller.mutate(request(json.writeValueAsBytes(body)));
        verify(manager).manage(new ActiveModelManager.Intent(ActiveModelManager.Action.SWITCH,
                (String) body.get("catalogHandle"), "test-model:latest", "ggml", "a".repeat(64), 1,
                "test-model:latest", "llamacpp", "c".repeat(64), null, false));
    }
    private static MockHttpServletRequest request(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/api/v1/models/selection");
        request.setServletPath("/api/v1/models/selection"); request.setContentType("application/json"); request.setContent(body);
        return request;
    }
}
