package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.error;

public final class KnowledgeIngestion implements AutoCloseable {
    private final KnowledgeStore store;
    private final Semaphore capacity=new Semaphore(5);
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"knowledge-ingestion");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private final Object lifecycle=new Object(),closeLock=new Object();
    private int uploads;
    private boolean shutdownFailed,drained;
    public KnowledgeIngestion(KnowledgeStore store){this.store=store;}
    static KnowledgeIngestion unavailable(KnowledgeStore store){var value=new KnowledgeIngestion(store);value.close();return value;}
    public KnowledgeStore.Job upload(String request,String doc,String expected,String filename,long size,InputStream input) {
        // The caller writes staging files before the executor sees the job. Count that
        // entire lifetime, including admission and failure cleanup, before touching Store.
        synchronized(lifecycle){if(closed)throw error(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);uploads++;}
        try{return receive(request,doc,expected,filename,size,input);}
        finally{synchronized(lifecycle){uploads--;lifecycle.notifyAll();}}
    }
    private KnowledgeStore.Job receive(String request,String doc,String expected,String filename,long size,InputStream input) {
        KnowledgeStore.id(request);var prior=store.findJob(request);if(prior!=null)return prior;
        if(closed||!capacity.tryAcquire())throw error(ErrorCode.KNOWLEDGE_QUEUE_FULL);
        boolean admitted=false,queued=false;
        try {
            store.admitUpload(request,doc,expected,filename,size);admitted=true;
            Path path=store.upload(request);PrivateKnowledgeDirectory.file(path);
            try(var output=FileChannel.open(path,StandardOpenOption.WRITE)) {
                byte[] buffer=new byte[65536];int n;long length=0;
                while((n=input.read(buffer))!=-1){if(closed||store.cancelled(request))throw error(ErrorCode.KNOWLEDGE_CANCELLED);
                    if((length+=n)>KnowledgeLimits.SOURCE_BYTES||length>size)throw error(ErrorCode.KNOWLEDGE_SOURCE_TOO_LARGE);
                    var bytes=java.nio.ByteBuffer.wrap(buffer,0,n);while(bytes.hasRemaining())output.write(bytes);}
                if(closed)throw error(ErrorCode.KNOWLEDGE_CANCELLED);
                if(length!=size)throw error(ErrorCode.KNOWLEDGE_INVALID_SOURCE);output.force(true);
            }
            synchronized(lifecycle){
                if(closed)throw error(ErrorCode.KNOWLEDGE_CANCELLED);
                var receipt=enqueueWithReceipt(()->store.job(request),executor,()->process(request));queued=true;
                return receipt;
            }
        }catch(WorkspaceException e){if(admitted)fail(request,e.error().code());throw e;}
        catch(RejectedExecutionException e){if(admitted)fail(request,ErrorCode.KNOWLEDGE_QUEUE_FULL);throw error(ErrorCode.KNOWLEDGE_QUEUE_FULL);}
        catch(Exception e){if(admitted)fail(request,ErrorCode.KNOWLEDGE_INTERRUPTED);throw error(ErrorCode.KNOWLEDGE_INTERRUPTED);}
        finally{if(!queued){try{if(admitted)store.finishImport(request);}catch(Exception failed){cleanupFailed();}capacity.release();}}
    }
    static KnowledgeStore.Job enqueueWithReceipt(Supplier<KnowledgeStore.Job> readReceipt,Executor executor,Runnable work) {
        // The uploader still owns the job here. Once exposed to the worker it can
        // finish, release ownership and be pruned by another admission before we return.
        var receipt=readReceipt.get();
        executor.execute(work); // Rejection must still fail the upload, never return acceptance.
        return receipt;
    }
    private void process(String request) {
        try {
            if(closed)throw error(ErrorCode.KNOWLEDGE_CANCELLED);
            store.parsing(request);
            // Safe original filename belongs to the admitted job, including an update's newly selected type.
            String type;
            synchronized(store){try(var s=store.statement("SELECT type FROM jobs WHERE id=?",request);var r=s.executeQuery()){r.next();type=r.getString(1);}}
            var p=KnowledgeParser.parse(store.upload(request),type,()->closed||Thread.currentThread().isInterrupted()||store.cancelled(request));
            String digest=KnowledgeParser.sourceDigest(store.upload(request));
            if(closed)throw error(ErrorCode.KNOWLEDGE_CANCELLED);
            store.complete(request,p,digest);
        }catch(WorkspaceException e){fail(request,e.error().code());}
        catch(Exception e){fail(request,ErrorCode.KNOWLEDGE_INGESTION_FAILED);}
        finally{try{store.finishImport(request);}catch(Exception failed){cleanupFailed();}capacity.release();}
    }
    private void fail(String request,ErrorCode code){try{store.failed(request,code);}catch(Exception failed){cleanupFailed();}}
    private void cleanupFailed(){synchronized(lifecycle){shutdownFailed=true;}}
    public boolean shutdownComplete(){synchronized(lifecycle){return drained&&!shutdownFailed;}}
    public void close(){
        synchronized(closeLock){
            synchronized(lifecycle){
                closed=true;
                // Queued jobs run only their cancelled/cleanup path; successful close must
                // not silently drop admitted PENDING jobs or their capacity permits.
                executor.shutdown();
                if(shutdownFailed)throw new IllegalStateException("Knowledge ingestion did not drain");
                if(drained)return;
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            try{
                if(!executor.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))throw new IllegalStateException();
                synchronized(lifecycle){
                    while(uploads!=0){long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalStateException();TimeUnit.NANOSECONDS.timedWait(lifecycle,remaining);}
                    if(shutdownFailed)throw new IllegalStateException();
                    drained=true;
                }
            }catch(InterruptedException failure){
                cleanupFailed();executor.shutdownNow();Thread.currentThread().interrupt();
                throw new IllegalStateException("Knowledge ingestion drain interrupted",failure);
            }catch(RuntimeException failure){
                cleanupFailed();executor.shutdownNow();throw new IllegalStateException("Knowledge ingestion did not drain",failure);
            }
        }
    }
}
