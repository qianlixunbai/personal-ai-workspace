package io.github.qianlixunbai.workspace;

import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public final class TestSettings {
    private TestSettings() {}
    public static ModelProfile profile() {
        return new ModelProfile("translate.fast", "ollama", "test-model:latest", ModelProfile.Locality.LOCAL,
                "test-v1", 8192, 2048, 0.1, 4000);
    }
    public static RuntimeProperties settings(URI base) {
        return new RuntimeProperties(new RuntimeProperties.Security(Path.of("target/test-token")),
                new RuntimeProperties.Ollama(base, Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofMillis(300), 1024),
                tasks(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3)), profile());
    }
    public static RuntimeProperties.Tasks tasks(Duration queue, Duration execution, Duration retention) {
        return new RuntimeProperties.Tasks(1, 1, 4, queue, execution, retention);
    }
    public static RuntimeProperties withTasks(RuntimeProperties.Tasks tasks) {
        RuntimeProperties p = settings(URI.create("http://127.0.0.1:1"));
        return new RuntimeProperties(p.security(), p.ollama(), tasks, p.translate());
    }
}
