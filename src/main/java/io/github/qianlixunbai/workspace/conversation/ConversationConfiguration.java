package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.memory.MemoryStore;
import org.springframework.context.annotation.*;

@Configuration
public class ConversationConfiguration {
    @Bean(destroyMethod = "close")
    ConversationStore conversationStore(MemoryStore initializedDatabase) {
        return new ConversationStore(initializedDatabase.databaseFile());
    }
}
