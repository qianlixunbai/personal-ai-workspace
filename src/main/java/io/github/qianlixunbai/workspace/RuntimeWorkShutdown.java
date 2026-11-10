package io.github.qianlixunbai.workspace;

import io.github.qianlixunbai.workspace.knowledge.KnowledgeIngestion;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeLexicalIndex;
import io.github.qianlixunbai.workspace.task.TaskManager;
import org.springframework.context.ConfigurableApplicationContext;

/** Local task and knowledge worker drain. No external provider or all-writer proof. */
public final class RuntimeWorkShutdown {
    private RuntimeWorkShutdown() {}

    public static void closeContext(ConfigurableApplicationContext context) {
        if(!context.isActive())throw new IllegalStateException("Runtime context is not active");
        // Capture actual live beans before destruction. Spring can log destroy failures
        // and return normally; context.isActive() alone does not prove worker shutdown.
        var tasks=context.getBean(TaskManager.class);
        var ingestion=context.getBean(KnowledgeIngestion.class);
        var index=context.getBean(KnowledgeLexicalIndex.class);
        tasks.close();ingestion.close();index.close();
        requireDrained(tasks,ingestion,index);
        context.close();
        requireDrained(tasks,ingestion,index);
        if(context.isActive())throw new IllegalStateException("Runtime context did not close");
    }

    private static void requireDrained(TaskManager tasks,KnowledgeIngestion ingestion,KnowledgeLexicalIndex index) {
        if(!tasks.shutdownComplete()||!ingestion.shutdownComplete()||!index.shutdownComplete())
            throw new IllegalStateException("Local runtime work did not drain");
    }
}
