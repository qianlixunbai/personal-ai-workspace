package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.error;

public final class KnowledgeIngestion implements AutoCloseable {
    private final KnowledgeStore store;
    private final Semaphore capacity=new Semaphore(5);
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"knowledge-ingestion");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    public KnowledgeIngestion(KnowledgeStore store){this.store=store;}
    public KnowledgeStore.Job upload(String request,String doc,String expected,String filename,long size,InputStream input) {
        KnowledgeStore.id(request);var prior=store.findJob(request);if(prior!=null)return prior;
        if(closed||!capacity.tryAcquire())throw error(ErrorCode.KNOWLEDGE_QUEUE_FULL);
        boolean admitted=false,queued=false;
        try {
            var job=store.admit(request,doc,expected,filename,size);admitted=true;
            Path path=store.upload(request);PrivateKnowledgeDirectory.file(path);
            try(var output=FileChannel.open(path,StandardOpenOption.WRITE)) {
                byte[] buffer=new byte[65536];int n;long length=0;
                while((n=input.read(buffer))!=-1){if(store.cancelled(request))throw error(ErrorCode.KNOWLEDGE_CANCELLED);
                    if((length+=n)>KnowledgeLimits.SOURCE_BYTES||length>size)throw error(ErrorCode.KNOWLEDGE_SOURCE_TOO_LARGE);
                    var bytes=java.nio.ByteBuffer.wrap(buffer,0,n);while(bytes.hasRemaining())output.write(bytes);}
                if(length!=size)throw error(ErrorCode.KNOWLEDGE_INVALID_SOURCE);output.force(true);
            }
            executor.execute(()->process(request));queued=true;return store.job(request);
        }catch(WorkspaceException e){if(admitted)store.failed(request,e.error().code());throw e;}
        catch(RejectedExecutionException e){if(admitted)store.failed(request,ErrorCode.KNOWLEDGE_QUEUE_FULL);throw error(ErrorCode.KNOWLEDGE_QUEUE_FULL);}
        catch(Exception e){if(admitted)store.failed(request,ErrorCode.KNOWLEDGE_INTERRUPTED);throw error(ErrorCode.KNOWLEDGE_INTERRUPTED);}
        finally{if(!queued)capacity.release();}
    }
    private void process(String request) {
        try {
            store.parsing(request);var detail=store.detail(store.job(request).documentId());
            // Safe original filename belongs to the admitted job, including an update's newly selected type.
            String type;
            synchronized(store){try(var s=store.statement("SELECT type FROM jobs WHERE id=?",request);var r=s.executeQuery()){r.next();type=r.getString(1);}}
            var p=KnowledgeParser.parse(store.upload(request),type,()->closed||Thread.currentThread().isInterrupted()||store.cancelled(request));
            String digest=KnowledgeParser.sourceDigest(store.upload(request));store.complete(request,p,digest);
        }catch(WorkspaceException e){try{store.failed(request,e.error().code());}catch(Exception ignored){}}
        catch(Exception e){try{store.failed(request,ErrorCode.KNOWLEDGE_INGESTION_FAILED);}catch(Exception ignored){}}
        finally{try{store.cleanupCandidate(request);}catch(Exception ignored){}capacity.release();}
    }
    public void close(){closed=true;executor.shutdown();try{if(!executor.awaitTermination(10,TimeUnit.SECONDS)){executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);}}
        catch(InterruptedException e){Thread.currentThread().interrupt();executor.shutdownNow();}}
}
