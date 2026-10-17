package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class KnowledgeConfiguration {
    @Bean(destroyMethod="close") KnowledgeStore knowledgeStore(@Value("${workspace.data-directory}") Path directory,RuntimeProperties p,
            @Value("${workspace.persistence.backend:sqlite}") String backend,
            @Qualifier("workspacePostgresqlDataSource") ObjectProvider<DataSource> data){
        return switch(backend){case "sqlite"->new KnowledgeStore(directory,p.security().tokenFile());
            case "postgresql"->new KnowledgeStore(data.getObject(),directory,p.security().tokenFile());
            default->throw new IllegalArgumentException("Unsupported Knowledge persistence backend");};
    }
    @Bean(destroyMethod="close") KnowledgeIngestion knowledgeIngestion(KnowledgeStore store){return new KnowledgeIngestion(store);}
    @Bean KnowledgeBackup knowledgeBackup(KnowledgeStore store,RuntimeProperties p){return new KnowledgeBackup(store,p.security().tokenFile());}
    @Bean(destroyMethod="close") KnowledgeLexicalIndex knowledgeLexicalIndex(KnowledgeStore store){return new KnowledgeLexicalIndex(store);}
}
