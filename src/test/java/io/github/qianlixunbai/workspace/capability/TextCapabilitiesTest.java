package io.github.qianlixunbai.workspace.capability;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.capability.ask.*;
import io.github.qianlixunbai.workspace.capability.summarize.*;
import io.github.qianlixunbai.workspace.capability.translate.*;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TextCapabilitiesTest {
    @Test void batchAdmissionAndReadinessRequireTranslateAuthorizationAndLocalPolicy() {
        var p = TestSettings.settings(URI.create("http://127.0.0.1:1"));
        var request = new TranslateRequest(null, List.of(new TranslateRequest.Item(1, "private source")), "en", "zh-CN", null);
        FakeProvider provider = new FakeProvider();
        TaskManager manager = new TaskManager(p);
        try {
            var service = new TranslateService(submission(p, provider, manager));
            var client = new io.github.qianlixunbai.workspace.security.ClientIdentity("browser-no-translate", "browser-extension",
                    "Test", null, java.time.Instant.EPOCH, Set.of());
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(client, null, List.of()));
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class, () -> service.submit(request)).error().code());
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class, service::readiness).error().code());
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            var original = p.translate();
            var cloud = new ModelProfile(original.id(), original.provider(), original.model(), ModelProfile.Locality.CLOUD,
                    original.version(), original.contextBudget(), original.outputBudget(), original.temperature(), original.maxTextCharacters());
            var denied = new RuntimeProperties(p.security(), p.ollama(), p.tasks(), cloud, p.summarize(), p.ask());
            var deniedService = new TranslateService(submission(denied, provider, manager));
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class, () -> deniedService.submit(request)).error().code());
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class, deniedService::readiness).error().code());
            assertNull(provider.execution.get());
            assertFalse(request.toString().contains("private"));
            assertFalse(request.items().getFirst().toString().contains("private"));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); manager.close(); }
        // The largest legal language tags still keep the Runtime-owned batch prompt under the template reserve.
        assertTrue(TranslateBatchPrompt.system("abcdefgh-abcdefgh-abcdefgh-abcdefgh", "abcdefgh-abcdefgh-abcdefgh-abcdefgh")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 512);
    }
    private static class FakeProvider implements Provider {
        final AtomicReference<ProviderExecution> execution = new AtomicReference<>();
        public String id() { return "ollama"; }
        public ModelProfile.Locality locality() { return ModelProfile.Locality.LOCAL; }
        public Set<Capability> capabilities() { return Set.of(Capability.TEXT_GENERATION); }
        public ProviderReadiness readiness(ModelProfile p) { return null; }
        public String execute(ProviderExecution e, Cancellation c) { execution.set(e); return "safe output"; }
    }
    @Test void bothCapabilitiesEnforceLocalPolicyBeforeExecution() {
        RuntimeProperties p = TestSettings.settings(URI.create("http://127.0.0.1:1"));
        for (boolean ask : new boolean[]{false, true}) {
            ModelProfile original = ask ? p.ask() : p.summarize();
            ModelProfile cloud = new ModelProfile(original.id(), original.provider(), original.model(), ModelProfile.Locality.CLOUD,
                    original.version(), original.contextBudget(), original.outputBudget(), original.temperature(), original.maxTextCharacters());
            RuntimeProperties denied = new RuntimeProperties(p.security(), p.ollama(), p.tasks(), p.translate(),
                    ask ? p.summarize() : cloud, ask ? cloud : p.ask());
            FakeProvider provider = new FakeProvider();
            TaskManager manager = new TaskManager(denied);
            try {
                TextTaskSubmission submission = submission(denied, provider, manager);
                assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class, () -> {
                    if (ask) new AskService(submission).submit(new AskRequest("private question", null));
                    else new SummarizeService(submission).submit(new SummarizeRequest("private source", null, null));
                }).error().code());
                assertNull(provider.execution.get());
            } finally { manager.close(); }
        }
    }
    private static TextTaskSubmission submission(RuntimeProperties p, Provider provider, TaskManager manager) {
        return new TextTaskSubmission(new ProfileResolver(p), new ProviderRegistry(List.of(provider)), new ProviderPolicy(), manager);
    }
}
