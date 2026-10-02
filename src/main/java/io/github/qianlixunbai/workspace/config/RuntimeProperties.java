package io.github.qianlixunbai.workspace.config;

import io.github.qianlixunbai.workspace.model.ModelProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties("workspace")
public record RuntimeProperties(@Valid @NotNull Security security,
                                @Valid @NotNull Ollama ollama,
                                @Valid @NotNull Tasks tasks,
                                @Valid @NotNull ModelProfile translate,
                                @Valid @NotNull ModelProfile summarize,
                                @Valid @NotNull ModelProfile ask) {
    public record Security(@NotNull Path tokenFile) {}
    public record Ollama(@NotNull URI baseUrl, @NotNull Duration connectTimeout,
                         @NotNull Duration requestTimeout, @NotNull Duration healthTimeout,
                         @Min(1024) @Max(4194304) int maxResponseBytes) {}
    public record Tasks(@Min(1) @Max(2) int concurrency,
                        @Min(1) @Max(32) int queueCapacity,
                        @Min(4) @Max(256) int maxRetained,
                        @NotNull Duration queueTimeout, @NotNull Duration executionTimeout,
                        @NotNull Duration retention) {}
}
