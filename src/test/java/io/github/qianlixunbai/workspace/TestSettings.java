package io.github.qianlixunbai.workspace;

import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public final class TestSettings {
    /** Existing capability tests isolate model ownership with mocks; real ownership uses ModelFoundationTest. */
    public static io.github.qianlixunbai.workspace.model.ActiveModelManager models(RuntimeProperties p) {
        var manager = org.mockito.Mockito.mock(io.github.qianlixunbai.workspace.model.ActiveModelManager.class);
        org.mockito.Mockito.when(manager.reserve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    var profile = new io.github.qianlixunbai.workspace.model.ProfileResolver(p).resolve(call.getArgument(0));
                    return reservation(profile, call.getArgument(1));
                });
        return manager;
    }
    public static io.github.qianlixunbai.workspace.model.ActiveModelManager.Reservation reservation(ModelProfile p, String promptVersion) {
        var lease = org.mockito.Mockito.mock(io.github.qianlixunbai.workspace.model.ActiveModelManager.Reservation.class);
        org.mockito.Mockito.when(lease.profile()).thenReturn(p);
        org.mockito.Mockito.when(lease.promptVersion()).thenReturn(promptVersion);
        org.mockito.Mockito.when(lease.model()).thenReturn(new io.github.qianlixunbai.workspace.model.ActiveModelManager.ExecutionModel(p.model(), "a".repeat(64), 0));
        return lease;
    }
    private TestSettings() {}
    public static ModelProfile profile() {
        return new ModelProfile("translate.fast", "ollama", "test-model:latest", ModelProfile.Locality.LOCAL,
                "test-v1", 8192, 2048, 0.1, 4000);
    }
    public static ModelProfile summarize() {
        return new ModelProfile("summarize.fast", "ollama", "test-model:latest", ModelProfile.Locality.LOCAL,
                "test-v1", 8192, 1024, 0.1, 6000);
    }
    public static ModelProfile ask() {
        return new ModelProfile("chat.balanced", "ollama", "test-model:latest", ModelProfile.Locality.LOCAL,
                "test-v1", 8192, 2048, 0.4, 3000);
    }
    public static RuntimeProperties settings(URI base) {
        return new RuntimeProperties(new RuntimeProperties.Security(Path.of("target/test-token")),
                new RuntimeProperties.Ollama(base, Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofMillis(300), 1024),
                tasks(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3)), profile(), summarize(), ask());
    }
    public static RuntimeProperties.Tasks tasks(Duration queue, Duration execution, Duration retention) {
        return new RuntimeProperties.Tasks(1, 1, 4, queue, execution, retention);
    }
    public static RuntimeProperties withTasks(RuntimeProperties.Tasks tasks) {
        RuntimeProperties p = settings(URI.create("http://127.0.0.1:1"));
        return new RuntimeProperties(p.security(), p.ollama(), tasks, p.translate(), p.summarize(), p.ask());
    }
}
