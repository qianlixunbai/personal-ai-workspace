package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.RuntimeWorkShutdown;
import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import io.github.qianlixunbai.workspace.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production shutdown coordinator and actual local workers in a Spring context. */
public class RuntimeWorkShutdownTest {
    @TempDir Path root;
    private static final class Runtime implements AutoCloseable {
        final KnowledgeStore store;final KnowledgeIngestion ingestion;final KnowledgeLexicalIndex index;final TaskManager tasks;
        final GenericApplicationContext context=new GenericApplicationContext();
        Runtime(Path root,String name){
            store=new KnowledgeStore(root.resolve("data").resolve(name),root.resolve("credentials/client-token"));
            ingestion=new KnowledgeIngestion(store);index=KnowledgeLexicalIndex.unavailable(store);
            tasks=new TaskManager(TestSettings.withTasks(TestSettings.tasks(Duration.ofSeconds(30),Duration.ofSeconds(30),Duration.ofMinutes(1))));
            context.registerBean("knowledgeStore",KnowledgeStore.class,()->store,d->d.setDestroyMethodName("close"));
            context.registerBean(KnowledgeIngestion.class,()->ingestion,d->{d.setDependsOn("knowledgeStore");d.setDestroyMethodName("close");});
            context.registerBean(KnowledgeLexicalIndex.class,()->index,d->{d.setDependsOn("knowledgeStore");d.setDestroyMethodName("close");});
            context.registerBean(TaskManager.class,()->tasks,d->d.setDestroyMethodName("close"));
            context.refresh();
        }
        public void close(){context.close();}
    }
    @Test void coordinatorClosesContextOnlyAfterActualUploadDrainAndRejectsStickyFailure()throws Exception{
        for(boolean interrupt:List.of(false,true))try(var runtime=new Runtime(root,"runtime-work-"+interrupt)){
            var input=new KnowledgeIngestionShutdownTest.Upload();String request=UUID.randomUUID().toString();
            var failure=new AtomicReference<Throwable>();var returned=new AtomicBoolean();var interrupted=new AtomicBoolean();
            var uploader=Executors.newSingleThreadExecutor();
            var receiving=uploader.submit(()->assertThrows(WorkspaceException.class,
                    ()->runtime.ingestion.upload(request,null,null,"held.txt",1,input)));
            KnowledgeIngestionShutdownTest.entered(input.reading);
            Thread closer=Thread.ofPlatform().unstarted(()->{
                try{RuntimeWorkShutdown.closeContext(runtime.context);returned.set(true);}catch(Throwable e){failure.set(e);}
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            try{
                closer.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(!runtime.tasks.shutdownComplete()){assertTrue(System.nanoTime()<deadline);Thread.sleep(5);}
                assertTrue(runtime.context.isActive());assertFalse(returned.get());assertFalse(runtime.ingestion.shutdownComplete());
                assertEquals("PENDING",runtime.store.job(request).state());
                if(interrupt){
                    closer.interrupt();closer.join(2000);assertFalse(closer.isAlive());
                    assertInstanceOf(IllegalStateException.class,failure.get());assertTrue(interrupted.get());
                    assertTrue(runtime.context.isActive());assertFalse(returned.get());
                }
                input.release.countDown();receiving.get(2,TimeUnit.SECONDS);closer.join(2500);assertFalse(closer.isAlive());
                if(interrupt){
                    assertThrows(IllegalStateException.class,()->RuntimeWorkShutdown.closeContext(runtime.context));
                    assertTrue(runtime.context.isActive());assertFalse(runtime.ingestion.shutdownComplete());
                    assertEquals("CANCELLED",runtime.store.job(request).state());
                }else{
                    assertNull(failure.get());assertTrue(returned.get());assertFalse(runtime.context.isActive());
                    assertTrue(runtime.ingestion.shutdownComplete());assertTrue(runtime.index.shutdownComplete());
                    assertThrows(WorkspaceException.class,()->runtime.store.job(request));
                }
            }finally{input.release.countDown();closer.join(2000);uploader.shutdown();assertTrue(uploader.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
}
