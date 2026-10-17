package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class MemoryConfiguration {
    @Bean
    io.github.qianlixunbai.workspace.backup.WorkspaceBackupService workspaceBackupService(MemoryStore store, RuntimeProperties properties) {
        return new io.github.qianlixunbai.workspace.backup.WorkspaceBackupService(store, properties.security().tokenFile());
    }
    @Bean
    MemoryBackupService memoryBackupService(@Value("${workspace.data-directory}") Path directory, RuntimeProperties properties) {
        return new MemoryBackupService(directory, properties.security().tokenFile());
    }
    @Bean(destroyMethod = "close")
    MemoryStore memoryStore(@Value("${workspace.data-directory}") Path directory, RuntimeProperties properties,
            @Value("${workspace.persistence.backend:sqlite}") String backend,
            @Qualifier("workspacePostgresqlDataSource") ObjectProvider<DataSource> postgresql) {
        return switch (backend) {
            case "sqlite" -> new MemoryStore(directory, properties.security().tokenFile());
            case "postgresql" -> new MemoryStore(postgresql.getObject(), directory);
            default -> throw new IllegalArgumentException("Unsupported Workspace persistence backend");
        };
    }
}
