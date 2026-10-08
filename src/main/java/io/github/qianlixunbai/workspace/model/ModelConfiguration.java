package io.github.qianlixunbai.workspace.model;

import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.provider.ollama.OllamaProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import java.io.IOException;

@Configuration
public class ModelConfiguration {
    @Bean(destroyMethod = "close")
    ActiveModelManager activeModelManager(ProfileResolver profiles, OllamaProvider provider, RuntimeProperties properties,
                                        @Value("${workspace.model-state-directory}") Path root,
                                        @Value("${workspace.data-directory}") Path data) {
        ModelStateStore store;
        try { store = new ModelStateStore(root, properties.security().tokenFile(), data); }
        catch (IOException | RuntimeException rejected) { store = null; }
        // Model state faults close AI admission while healthy domain data APIs remain available.
        return new ActiveModelManager(profiles, provider, properties, store);
    }
}
