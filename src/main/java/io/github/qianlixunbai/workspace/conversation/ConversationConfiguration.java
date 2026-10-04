package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.memory.MemoryStore;
import org.springframework.context.annotation.*;

@Configuration
public class ConversationConfiguration {
    @Bean(destroyMethod = "close")
    ConversationStore conversationStore(MemoryStore initializedDatabase) {
        var store = new ConversationStore(initializedDatabase.databaseFile());
        try { store.reconcilePending(); return store; }
        catch (RuntimeException failure) { store.close(); throw failure; }
    }
}
