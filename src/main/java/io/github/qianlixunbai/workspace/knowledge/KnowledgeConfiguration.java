package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.Path;

@Configuration
public class KnowledgeConfiguration {
    @Bean(destroyMethod="close") KnowledgeStore knowledgeStore(@Value("${workspace.data-directory}") Path directory,RuntimeProperties p){return new KnowledgeStore(directory,p.security().tokenFile());}
    @Bean(destroyMethod="close") KnowledgeIngestion knowledgeIngestion(KnowledgeStore store){return new KnowledgeIngestion(store);}
}
