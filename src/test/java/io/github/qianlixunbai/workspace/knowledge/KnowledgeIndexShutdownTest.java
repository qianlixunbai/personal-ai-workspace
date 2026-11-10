package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.WorkspaceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local derived index and Store; held monitors control work, never provide completion. */
public class KnowledgeIndexShutdownTest {
    @TempDir Path root;
    private KnowledgeStore store(String name){return new KnowledgeStore(root.resolve("data").resolve(name),root.resolve("credentials/client-token"));}
    private static void state(KnowledgeLexicalIndex index,String expected)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!index.status().state().equals(expected)){assertTrue(System.nanoTime()<deadline,"Index state deadline: "+expected);Thread.sleep(5);}
    }
    private static void blocked(Thread thread)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(thread.getState()!=Thread.State.BLOCKED){assertTrue(System.nanoTime()<deadline);Thread.sleep(5);}
    }
    @Test void closeWaitsForRebuildAndAdmittedSearchThenStaysUnavailable()throws Exception{
        for(String kind:List.of("worker","search"))try(var store=store("index-drain-"+kind)){
            var index=new KnowledgeLexicalIndex(store);state(index,"READY");
            assertTrue(index.search("synthetic",10).hits().isEmpty());
            var closer=Executors.newSingleThreadExecutor();var readerFailure=new AtomicReference<Throwable>();
            Thread reader=Thread.ofPlatform().unstarted(()->{try{index.search("synthetic",10);}catch(Throwable e){readerFailure.set(e);}});
            Future<?> closing;
            try{
                synchronized(store){
                    if(kind.equals("worker")){index.rebuild();state(index,"BUILDING");}
                    else{reader.start();blocked(reader);}
                    closing=closer.submit(index::close);state(index,"UNAVAILABLE");
                    assertThrows(TimeoutException.class,()->closing.get(100,TimeUnit.MILLISECONDS));assertFalse(index.shutdownComplete());
                }
                closing.get(3,TimeUnit.SECONDS);if(reader.isAlive())reader.join(2000);
                assertFalse(reader.isAlive());assertTrue(index.shutdownComplete());
                if(kind.equals("search"))assertInstanceOf(WorkspaceException.class,readerFailure.get());
                index.close();assertThrows(WorkspaceException.class,index::rebuild);
                assertThrows(WorkspaceException.class,()->index.search("synthetic",10));
                try(var files=Files.list(store.root().resolve("index/staging"))){assertEquals(0,files.count());}
                store.close();assertEquals("UNAVAILABLE",index.status().state()); // No read from the now-closed Store.
            }finally{closer.shutdown();assertTrue(closer.awaitTermination(3,TimeUnit.SECONDS));if(reader.isAlive())reader.join(2000);}
        }
    }
    @Test void timeoutOrInterruptionCannotBeReplacedByLateSearchExit()throws Exception{
        for(boolean interrupt:List.of(false,true))try(var store=store("index-failure-"+interrupt)){
            var index=new KnowledgeLexicalIndex(store);state(index,"READY");
            var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
            Thread reader=Thread.ofPlatform().unstarted(()->assertThrows(WorkspaceException.class,()->index.search("synthetic",10)));
            Thread closer=Thread.ofPlatform().unstarted(()->{
                try{index.close();}catch(Throwable e){failure.set(e);}interrupted.set(Thread.currentThread().isInterrupted());
            });
            try{
                synchronized(store){
                    reader.start();blocked(reader);closer.start();state(index,"UNAVAILABLE");if(interrupt)closer.interrupt();
                    closer.join(6500);assertFalse(closer.isAlive());assertInstanceOf(IllegalStateException.class,failure.get());
                    assertEquals(interrupt,interrupted.get());assertFalse(index.shutdownComplete());
                }
                reader.join(2000);assertFalse(reader.isAlive());
                assertThrows(IllegalStateException.class,index::close);assertFalse(index.shutdownComplete());
            }finally{if(reader.isAlive())reader.join(2000);if(closer.isAlive())closer.join(2000);}
        }
        // Restricted construction does not open index files, start a worker or install a callback.
        try(var store=store("index-disabled")){
            var disabled=KnowledgeLexicalIndex.unavailable(store);assertTrue(disabled.shutdownComplete());disabled.close();
            assertFalse(Files.exists(store.root().resolve("index")));assertEquals("UNAVAILABLE",disabled.status().state());
        }
    }
}
