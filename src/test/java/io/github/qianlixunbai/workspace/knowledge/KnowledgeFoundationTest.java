package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeFoundationTest {
    @TempDir Path temporary;
    Path data(){return temporary.resolve("data");}Path auth(){return temporary.resolve("auth/token");}
    KnowledgeStore open(){return new KnowledgeStore(data(),auth());}
    static void code(ErrorCode expected,org.junit.jupiter.api.function.Executable operation){var e=assertThrows(WorkspaceException.class,operation);assertEquals(expected,e.error().code());assertNull(e.getCause());}
    static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    KnowledgeStore.Job importBytes(KnowledgeStore s,KnowledgeIngestion in,String doc,String version,String name,byte[] bytes)throws Exception {
        var admitted=in.upload(UUID.randomUUID().toString(),doc,version,name,bytes.length,new ByteArrayInputStream(bytes));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);KnowledgeStore.Job job;
        do{job=s.job(admitted.requestId());if(!Set.of("PENDING","PARSING").contains(job.state()))return job;Thread.sleep(5);}while(System.nanoTime()<until);
        fail("Ingestion did not terminate");return null;
    }
    @Test void independentDatabasePrivateOriginalAndExactVersionedRepresentationSurviveRestart()throws Exception {
        Files.createDirectories(data());Files.write(data().resolve("memory.db"),bytes("untouched-memory-marker"));
        KnowledgeDocument doc;String request;byte[] source=bytes("\uFEFF中文\r\nline two\rthree\n");
        try(var store=open();var ingestion=new KnowledgeIngestion(store)){
            var job=importBytes(store,ingestion,null,null,"fixture.txt",source);assertEquals("READY",job.state());request=job.requestId();doc=store.get(job.documentId());
            assertEquals("2",doc.metadataVersion());assertEquals("1",doc.currentReadyRevision());
            assertArrayEquals(source,Files.readAllBytes(store.source(doc.documentId(),"1")));
            var detail=store.detail(doc.documentId());var revision=detail.revisions().getFirst();
            assertEquals(KnowledgeParser.sourceDigest(store.source(doc.documentId(),"1")),revision.sourceDigest());
            assertEquals("text-1",revision.parserVersion());assertEquals("lf-1",revision.normalizationVersion());
            var preview=store.preview(doc.documentId(),"1",0);assertEquals("中文\nline two\nthree\n",preview.text());
            assertEquals(new KnowledgeParser.Locator("TXT_LINES",1,3,0,preview.text().length(),null,null),preview.locators().getFirst());
            assertFalse(preview.toString().contains("line two"));assertFalse(doc.toString().contains("fixture"));
            var acl=Files.getFileAttributeView(store.root(),java.nio.file.attribute.AclFileAttributeView.class);
            if(acl!=null){var owner=Files.getOwner(store.root());assertTrue(acl.getAcl().stream().allMatch(e->e.principal().equals(owner)));}
        }
        try(var store=open()){assertEquals(doc,store.get(doc.documentId()));assertEquals("READY",store.job(request).state());}
        assertArrayEquals(bytes("untouched-memory-marker"),Files.readAllBytes(data().resolve("memory.db")));
    }
    @Test void strictUtf8BinaryAndMeaningfulTextAdmissionWithFailedUpdatePreservingReady()throws Exception {
        try(var store=open();var ingestion=new KnowledgeIngestion(store)){
            var first=importBytes(store,ingestion,null,null,"good.txt",bytes("good original"));var doc=store.get(first.documentId());
            for(var pair:List.of(Map.entry(new byte[]{(byte)0xc3,0x28},ErrorCode.KNOWLEDGE_INVALID_UTF8),
                    Map.entry(bytes("text\0binary"),ErrorCode.KNOWLEDGE_INVALID_SOURCE),Map.entry(new byte[]{1,2,3},ErrorCode.KNOWLEDGE_INVALID_SOURCE),
                    Map.entry(bytes(" \n\t "),ErrorCode.KNOWLEDGE_INVALID_SOURCE))) {
                var job=importBytes(store,ingestion,doc.documentId(),doc.metadataVersion(),"bad.txt",pair.getKey());
                assertEquals("FAILED",job.state());assertEquals(pair.getValue().name(),job.errorCode());assertEquals("1",store.get(doc.documentId()).currentReadyRevision());
                assertEquals("good original",store.preview(doc.documentId(),"1",0).text());
            }
            code(ErrorCode.KNOWLEDGE_UNSUPPORTED_TYPE,()->KnowledgeParser.type("file.pdf"));
            code(ErrorCode.KNOWLEDGE_INVALID_SOURCE,()->KnowledgeParser.filename("C:\\private.txt"));
            code(ErrorCode.KNOWLEDGE_SOURCE_TOO_LARGE,()->ingestion.upload(UUID.randomUUID().toString(),null,null,"big.txt",KnowledgeLimits.SOURCE_BYTES+1L,InputStream.nullInputStream()));
            code(ErrorCode.KNOWLEDGE_INVALID_SOURCE,()->ingestion.upload(UUID.randomUUID().toString(),null,null,"short.txt",10,new ByteArrayInputStream(bytes("abc"))));
        }
    }
    @Test void duplicateAndChangedSourceRevisionSemanticsAndOptimisticLifecycle()throws Exception {
        try(var store=open();var ingestion=new KnowledgeIngestion(store)){
            var first=importBytes(store,ingestion,null,null,"first.txt",bytes("same bytes"));var d=store.get(first.documentId());
            var same=importBytes(store,ingestion,d.documentId(),d.metadataVersion(),"same.txt",bytes("same bytes"));
            assertEquals("1",same.sourceRevision());assertEquals(1,store.detail(d.documentId()).revisions().size());assertEquals(d.metadataVersion(),store.get(d.documentId()).metadataVersion());
            var other=importBytes(store,ingestion,null,null,"other.txt",bytes("same bytes"));assertEquals("KNOWLEDGE_DUPLICATE_SOURCE",other.errorCode());
            var changed=importBytes(store,ingestion,d.documentId(),d.metadataVersion(),"changed.md",bytes("# Heading\nnew bytes"));assertEquals("2",changed.sourceRevision());
            var stale=d;code(ErrorCode.KNOWLEDGE_REVISION_CONFLICT,()->store.lifecycle(stale.documentId(),stale.metadataVersion(),"ARCHIVED"));
            d=store.get(d.documentId());var archived=store.lifecycle(d.documentId(),d.metadataVersion(),"ARCHIVED");
            assertEquals(1,store.list("ARCHIVED",0).total());var restored=store.lifecycle(d.documentId(),archived.metadataVersion(),"ACTIVE");
            assertEquals(d.documentId(),restored.documentId());assertEquals("2",restored.currentReadyRevision());assertEquals(2,store.detail(d.documentId()).revisions().size());
            store.delete(d.documentId(),restored.metadataVersion());String deleted=d.documentId();code(ErrorCode.KNOWLEDGE_NOT_FOUND,()->store.get(deleted));
            assertFalse(Files.exists(store.root().resolve("sources").resolve(deleted)));assertEquals(0,store.scalar("SELECT count(*) FROM jobs WHERE doc_id=?",deleted));
        }
    }
    @Test void markdownLocatorsIgnoreFencedHeadingsAndPreviewRemainsLiteral()throws Exception {
        Path input=temporary.resolve("source.md");Files.writeString(input,"# One\n<script>plain</script>\n```\n# inside\n```\n## Two\n![x](https://invalid/image)\n");
        var p=KnowledgeParser.parse(input,"MARKDOWN",()->false);assertEquals(2,p.locators().size());
        assertEquals("One",p.locators().get(0).heading());assertEquals(1,p.locators().get(0).startLine());assertEquals(5,p.locators().get(0).endLine());
        assertEquals("line-6",p.locators().get(1).section());assertTrue(p.text().contains("<script>plain</script>"));
        assertEquals(p,KnowledgeParser.parse(input,"MARKDOWN",()->false));Files.delete(input);assertTrue(p.text().contains("# inside"));
    }
    @Test void lineBlockTextAndCorpusLimitsAreEnforced()throws Exception {
        code(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED,()->KnowledgeParser.represent("x\n".repeat(100001),"TXT",()->false));
        code(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED,()->KnowledgeParser.represent("# h\n".repeat(10001),"MARKDOWN",()->false));
        code(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED,()->KnowledgeParser.represent("x".repeat(500001),"TXT",()->false));
        try(var store=open();var ingestion=new KnowledgeIngestion(store)){
            for(int i=0;i<500;i++)store.reconstructDocument(new KnowledgeDocument(UUID.randomUUID().toString(),"fixture.txt","ACTIVE","1",null,"2026-10-05T00:00:00Z","2026-10-05T00:00:00Z","INTERRUPTED",null));
            code(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED,()->ingestion.upload(UUID.randomUUID().toString(),null,null,"limit.txt",1,new ByteArrayInputStream(bytes("x"))));
        }
    }
    @Test void unfinishedPublicationAndStagingReconcileWithoutDeletingUnknownData()throws Exception {
        String request=UUID.randomUUID().toString(),doc;
        try(var store=open()){
            doc=store.admit(request,null,null,"crash.txt",7).documentId();store.parsing(request);
            PrivateKnowledgeDirectory.directory(store.source(doc,"1").getParent());Files.write(store.source(doc,"1"),bytes("partial"));
            Files.write(store.upload(request),bytes("partial"));Files.write(store.root().resolve("staging/unknown-user-file"),bytes("preserve"));
        }
        try(var store=open()){
            assertEquals("INTERRUPTED",store.job(request).state());assertNull(store.get(doc).currentReadyRevision());
            assertFalse(Files.exists(store.source(doc,"1")));assertFalse(Files.exists(store.upload(request)));
            assertTrue(Files.exists(store.root().resolve("staging/unknown-user-file")));
        }
    }
    @Test void schemaCorruptionMissingSourceAndForeignProcessFailClosed()throws Exception {
        try(var store=open()){code(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE,this::open);store.exec("PRAGMA user_version=99");}
        code(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED,this::open);
    }
    @Test void boundedQueueAndAdmittedCancellationDoNotPublish()throws Exception {
        try(var store=open();var ingestion=new KnowledgeIngestion(store);var callers=Executors.newFixedThreadPool(5)){
            CountDownLatch entered=new CountDownLatch(5),release=new CountDownLatch(1);List<String> requests=new ArrayList<>();List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<5;i++){String request=UUID.randomUUID().toString();requests.add(request);tasks.add(callers.submit(()->{
                try{ingestion.upload(request,null,null,"queue.txt",1,new InputStream(){boolean emitted;public int read(){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}if(emitted)return -1;emitted=true;return 'x';}});}
                catch(WorkspaceException ignored){} }));}
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            code(ErrorCode.KNOWLEDGE_QUEUE_FULL,()->ingestion.upload(UUID.randomUUID().toString(),null,null,"full.txt",1,InputStream.nullInputStream()));
            var job=store.job(requests.getFirst());store.cancel(job.requestId(),job.documentId());release.countDown();for(var t:tasks)t.get(5,TimeUnit.SECONDS);
            assertEquals("CANCELLED",store.job(job.requestId()).state());assertNull(store.get(job.documentId()).currentReadyRevision());
        }
    }
    @Test void boundedPreviewPreservesSurrogatePairsAndWorstCaseJsonBudget()throws Exception {
        try(var store=open();var ingestion=new KnowledgeIngestion(store)){
            String text="\t".repeat(4095)+"😀"+"x";var job=importBytes(store,ingestion,null,null,"preview.txt",bytes(text));
            var first=store.preview(job.documentId(),"1",0);assertEquals(4095,first.text().length());assertEquals(4095,first.nextOffset());
            assertEquals("😀x",store.preview(job.documentId(),"1",first.nextOffset()).text());
            code(ErrorCode.INVALID_REQUEST,()->store.preview(job.documentId(),"1",4096));
            String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(first);
            assertTrue(bytes(json).length<64*1024);assertFalse(json.contains(temporary.toString()));
        }
    }
    @Test void tenRevisionLimitStillAllowsSameBytesNoOpAndCorpusReservationIsBounded()throws Exception {
        try(var store=open();var ingestion=new KnowledgeIngestion(store)){
            var first=importBytes(store,ingestion,null,null,"limit.txt",bytes("revision-1"));String doc=first.documentId();
            for(int i=2;i<=10;i++)assertEquals(Integer.toString(i),importBytes(store,ingestion,doc,store.get(doc).metadataVersion(),"limit.txt",bytes("revision-"+i)).sourceRevision());
            assertEquals("10",importBytes(store,ingestion,doc,store.get(doc).metadataVersion(),"limit.txt",bytes("revision-10")).sourceRevision());
            assertEquals("KNOWLEDGE_LIMIT_EXCEEDED",importBytes(store,ingestion,doc,store.get(doc).metadataVersion(),"limit.txt",bytes("revision-11")).errorCode());
            assertEquals("10",store.get(doc).currentReadyRevision());
            for(int i=0;i<255;i++)store.admit(UUID.randomUUID().toString(),null,null,"reserved.txt",KnowledgeLimits.SOURCE_BYTES);
            code(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED,()->ingestion.upload(UUID.randomUUID().toString(),null,null,"quota.txt",KnowledgeLimits.SOURCE_BYTES,InputStream.nullInputStream()));
        }
    }
    @Test void deleteJournalRollsBackWhileDocumentExistsAndCompletesAfterCommit()throws Exception {
        String id,token=UUID.randomUUID().toString();
        try(var store=open();var ingestion=new KnowledgeIngestion(store)){
            var job=importBytes(store,ingestion,null,null,"delete.txt",bytes("journal content"));id=job.documentId();
            store.exec("INSERT INTO deletes VALUES(?,?,?,?)",id,token,"1",2);
            Files.move(store.root().resolve("sources").resolve(id),store.root().resolve("staging").resolve(token+".delete"));
        }
        try(var store=open()){
            assertTrue(Files.exists(store.source(id,"1")));assertEquals(0,store.scalar("SELECT count(*) FROM deletes"));
            store.exec("INSERT INTO deletes VALUES(?,?,?,?)",id,token,"1",2);Files.move(store.root().resolve("sources").resolve(id),store.root().resolve("staging").resolve(token+".delete"));
            store.exec("DELETE FROM documents WHERE id=?",id);
        }
        try(var store=open()){assertFalse(Files.exists(store.root().resolve("staging").resolve(token+".delete")));assertEquals(0,store.scalar("SELECT count(*) FROM deletes"));}
    }
}
