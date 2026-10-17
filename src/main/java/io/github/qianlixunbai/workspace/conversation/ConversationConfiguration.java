package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.memory.MemoryStore;
import org.springframework.context.annotation.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class ConversationConfiguration {
    @Bean(destroyMethod = "close")
    ConversationStore conversationStore(MemoryStore initializedDatabase,
            @Qualifier("workspacePostgresqlDataSource") ObjectProvider<DataSource> postgresql) {
        var store = initializedDatabase.postgresql() ? new ConversationStore(postgresql.getObject())
                : new ConversationStore(initializedDatabase.databaseFile());
        try { store.reconcilePending(); return store; }
        catch (RuntimeException failure) { store.close(); throw failure; }
    }
}
