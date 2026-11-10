package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual private Store, staging files and ingestion; synthetic bytes only. */
public class KnowledgeIngestionShutdownTest {
    @TempDir Path root;
    private KnowledgeStore store(String name){return new KnowledgeStore(root.resolve("data").resolve(name),root.resolve("credentials/client-token"));}
    static void entered(CountDownLatch latch)throws Exception{assertTrue(latch.await(2,TimeUnit.SECONDS));}
    static void held(CountDownLatch latch){
        boolean interrupted=false;
        for(;;)try{latch.await();break;}catch(InterruptedException ignored){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
    static class Upload extends InputStream {
        final CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
        public int read(){reading.countDown();held(release);return 'x';}
        public int read(byte[] bytes,int offset,int length){bytes[offset]=(byte)read();return 1;}
    }
    private static void admissionStopped(KnowledgeIngestion ingestion)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        for(;;){
            var error=assertThrows(WorkspaceException.class,()->ingestion.upload("invalid",null,null,"new.txt",1,InputStream.nullInputStream()));
            if(error.error().code()==ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE)return;
            assertTrue(System.nanoTime()<deadline,"Admission did not stop");Thread.sleep(5);
        }
    }
    @Test void uploadsAndQueuedJobsDrainBeforeStoreCanClose()throws Exception{
        try(var store=store("ingestion-drain")){
            var ingestion=new KnowledgeIngestion(store);var callers=Executors.newFixedThreadPool(2);
            var input=new Upload();String request=UUID.randomUUID().toString();
            try{
                var receiving=callers.submit(()->assertEquals(ErrorCode.KNOWLEDGE_CANCELLED,
                        assertThrows(WorkspaceException.class,()->ingestion.upload(request,null,null,"held.txt",1,input)).error().code()));
                entered(input.reading);assertTrue(Files.exists(store.upload(request)));
                var closing=callers.submit(ingestion::close);admissionStopped(ingestion);
                assertThrows(TimeoutException.class,()->closing.get(100,TimeUnit.MILLISECONDS));assertFalse(ingestion.shutdownComplete());
                assertEquals("PENDING",store.job(request).state());
                input.release.countDown();receiving.get(2,TimeUnit.SECONDS);closing.get(2,TimeUnit.SECONDS);
                assertTrue(ingestion.shutdownComplete());assertEquals("CANCELLED",store.job(request).state());
                assertFalse(Files.exists(store.upload(request)));ingestion.close();
            }finally{input.release.countDown();callers.shutdown();assertTrue(callers.awaitTermination(3,TimeUnit.SECONDS));}

            var queued=new KnowledgeIngestion(store);var closer=Executors.newSingleThreadExecutor();
            List<String> requests=List.of(UUID.randomUUID().toString(),UUID.randomUUID().toString());Future<?> closing;
            try{
                synchronized(store){
                    for(String id:requests)queued.upload(id,null,null,"queued.txt",1,new ByteArrayInputStream(new byte[]{'x'}));
                    closing=closer.submit(queued::close);admissionStopped(queued);
                    assertThrows(TimeoutException.class,()->closing.get(100,TimeUnit.MILLISECONDS));
                    assertFalse(queued.shutdownComplete());
                }
                closing.get(2,TimeUnit.SECONDS);assertTrue(queued.shutdownComplete());
                for(String id:requests){assertEquals("CANCELLED",store.job(id).state());assertFalse(Files.exists(store.upload(id)));}
            }finally{closer.shutdown();assertTrue(closer.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test void uploadDrainFailureCannotBecomeSuccessAfterLateExit()throws Exception{
        for(boolean interrupt:List.of(false,true))try(var store=store("ingestion-failure-"+interrupt)){
            var ingestion=new KnowledgeIngestion(store);var input=new Upload();var uploader=Executors.newSingleThreadExecutor();
            var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
            var receiving=uploader.submit(()->assertThrows(WorkspaceException.class,
                    ()->ingestion.upload(UUID.randomUUID().toString(),null,null,"held.txt",1,input)));
            entered(input.reading);
            Thread closer=Thread.ofPlatform().unstarted(()->{
                try{ingestion.close();}catch(Throwable e){failure.set(e);}interrupted.set(Thread.currentThread().isInterrupted());
            });
            try{
                closer.start();admissionStopped(ingestion);if(interrupt)closer.interrupt();
                closer.join(6500);assertFalse(closer.isAlive());assertInstanceOf(IllegalStateException.class,failure.get());
                assertEquals(interrupt,interrupted.get());assertFalse(ingestion.shutdownComplete());
                input.release.countDown();receiving.get(2,TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class,ingestion::close);assertFalse(ingestion.shutdownComplete());
            }finally{input.release.countDown();closer.join(2000);uploader.shutdown();assertTrue(uploader.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
}
