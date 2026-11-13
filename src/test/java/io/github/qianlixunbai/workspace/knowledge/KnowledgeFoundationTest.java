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
        PrivateKnowledgeDirectory.directory(data());
        Path memory = data().resolve("memory.db");
        PrivateKnowledgeDirectory.file(memory);
        Files.write(memory,bytes("untouched-memory-marker"));
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
            PrivateKnowledgeDirectory.protect(store.root(),true);
        }
        try(var store=open()){assertEquals(doc,store.get(doc.documentId()));assertEquals("READY",store.job(request).state());}
        assertArrayEquals(bytes("untouched-memory-marker"),Files.readAllBytes(memory));
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
}
