package io.github.qianlixunbai.workspace.model;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.provider.ollama.OllamaProvider;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class ProfilePolicyTest {
    @Test void profileResolvesAndCloudIsDeniedInEveryMode() {
        RuntimeProperties settings = TestSettings.settings(URI.create("http://localhost:11434"));
        ProfileResolver profiles = new ProfileResolver(settings);
        ModelProfile profile = profiles.resolve("translate.fast");
        assertEquals("ollama", profile.provider());
        assertEquals(ModelProfile.Locality.LOCAL, profile.locality());
        assertEquals("test-model:latest", profile.model());
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(WorkspaceException.class,
                () -> profiles.resolve("test-model:latest")).error().code());
        ProviderPolicy policy = new ProviderPolicy();
        Provider local = new OllamaProvider(settings, policy);
        ModelProfile cloud = new ModelProfile(profile.id(), "ollama", profile.model(), ModelProfile.Locality.CLOUD,
                profile.version(), profile.contextBudget(), profile.outputBudget(), profile.temperature(), profile.maxTextCharacters());
        for (PrivacyMode mode : PrivacyMode.values()) {
            assertDoesNotThrow(() -> policy.verify(profile, local, mode));
            assertEquals(ErrorCode.POLICY_DENIED, assertThrows(WorkspaceException.class,
                    () -> policy.verify(cloud, local, mode)).error().code());
        }
        assertThrows(WorkspaceException.class, () -> new ProviderRegistry(java.util.List.of(local)).resolve("cloud"));
    }

    @Test void arbitraryEndpointsAndUnsafeBudgetsAreRejected() {
        for (String url : new String[]{"https://example.com:443", "http://192.168.1.10:11434",
                "http://127.0.0.1:11434/proxy", "http://user:password@localhost:11434",
                "http://localhost:11434?url=remote"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OllamaProvider(TestSettings.settings(URI.create(url)), new ProviderPolicy()));
        }
        RuntimeProperties p = TestSettings.settings(URI.create("http://127.0.0.1:11434"));
        RuntimeProperties invalid = new RuntimeProperties(p.security(), p.ollama(),
                TestSettings.tasks(java.time.Duration.ZERO, java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)), p.translate());
        assertThrows(IllegalArgumentException.class, () -> new RuntimeConfiguration(invalid));
    }
}
