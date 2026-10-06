package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeLexicalIndexTest {
    @TempDir Path temporary;
    KnowledgeStore store(){return new KnowledgeStore(temporary.resolve("data"),temporary.resolve("auth/token"));}
    static void code(ErrorCode expected,org.junit.jupiter.api.function.Executable operation){assertEquals(expected,assertThrows(WorkspaceException.class,operation).error().code());}
    static void ready(KnowledgeLexicalIndex index)throws Exception {
        long end=System.nanoTime()+10_000_000_000L;
        while(!index.status().state().equals("READY")&&System.nanoTime()<end){if(index.status().state().equals("FAILED"))fail("Index rebuild failed");Thread.sleep(10);}
        assertEquals("READY",index.status().state());
    }
    static KnowledgeStore.Job ingest(KnowledgeStore store,KnowledgeIngestion ingestion,String id,String name,String text)throws Exception {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        var job=ingestion.upload(UUID.randomUUID().toString(),id,id==null?null:store.get(id).metadataVersion(),name,bytes.length,new ByteArrayInputStream(bytes));
        long end=System.nanoTime()+5_000_000_000L;
        while(Set.of("PENDING","PARSING").contains(store.job(job.requestId()).state())&&System.nanoTime()<end)Thread.sleep(5);
        return store.job(job.requestId());
    }
    @Test void deterministicUnicodeLatinCjkAndNoUserFtsSyntax() {
        assertEquals(LexicalAnalyzer.tokens("ＢＵＤＧＥＴ café"),LexicalAnalyzer.tokens("budget cafe\u0301"));
        assertEquals(List.of("u9884x","u7b97x","b9884x7b97x"),LexicalAnalyzer.tokens("预算"));
        assertEquals(5,LexicalAnalyzer.tokens("カタナ").size());assertEquals(3,LexicalAnalyzer.tokens("한글").size());
        assertEquals(LexicalAnalyzer.tokens("予算 かな"),LexicalAnalyzer.tokens("予算 かな"));
        var injection=LexicalAnalyzer.query("budget OR title:secret \" NEAR(foo)");
        assertTrue(injection.expression().matches("\"[wub][0-9a-fx]+\"( AND \"[wub][0-9a-fx]+\")*"));
        assertFalse(injection.expression().contains("title:"));assertFalse(injection.toString().contains("budget"));
        code(ErrorCode.KNOWLEDGE_SEARCH_INVALID,()->LexicalAnalyzer.query("\uD800"));
        code(ErrorCode.KNOWLEDGE_SEARCH_INVALID,()->LexicalAnalyzer.query("\0budget"));
        code(ErrorCode.KNOWLEDGE_SEARCH_INVALID,()->LexicalAnalyzer.query("\" : *"));
        code(ErrorCode.KNOWLEDGE_QUERY_TOO_COMPLEX,()->LexicalAnalyzer.query("x".repeat(129)));
        code(ErrorCode.KNOWLEDGE_QUERY_TOO_COMPLEX,()->LexicalAnalyzer.query("a b c d e f g h i j k l m n o p q r s t u v w x y z 0 1 2 3 4 5 6"));
    }
    @Test void exactChunkBoundariesNewlinesSectionsAndSupplementaryUnicode() {
        String text="# First\n"+"😀".repeat(2100)+"\n## Second\n预算";
        var source=KnowledgeParser.represent(text,"MARKDOWN",()->false);List<LexicalChunker.Chunk> chunks=new ArrayList<>();LexicalChunker.chunks(source,chunks::add);
        int last=0;StringBuilder joined=new StringBuilder();for(var chunk:chunks){assertEquals(last,chunk.startOffset());
            String body=text.substring(chunk.startOffset(),chunk.endOffset());assertTrue(body.codePointCount(0,body.length())<=2048);
            assertFalse(Character.isLowSurrogate(body.charAt(0)));assertTrue(body.getBytes(StandardCharsets.UTF_8).length<=8192);
            assertTrue(chunk.endLine()>=chunk.startLine());joined.append(body);last=chunk.endOffset();}
        assertEquals(text,joined.toString());assertEquals("Second",chunks.getLast().heading());assertEquals(3,chunks.getLast().startLine());
    }
    @Test void indexRankingAndCanonicalTieOrderAndSingleChunkAndSemantics()throws Exception {
        try(var store=store();var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {
            ingest(store,ingestion,null,"budget.txt","other filler");
            ingest(store,ingestion,null,"heading.md","# budget\nother filler");
            var body=ingest(store,ingestion,null,"body.txt","budget filler");
            ready(index);assertEquals(3,index.search("budget",10).hits().size());assertEquals("budget.txt",index.search("budget",10).hits().getFirst().title());
            var results=index.search("budget",10);assertEquals(results,index.search("budget",10));
            assertFalse(results.toString().contains("budget"));assertFalse(results.hits().getFirst().toString().contains("filler"));
            ingest(store,ingestion,null,"split.md","# One\nalpha\n# Two\nbeta");ready(index);assertTrue(index.search("alpha beta",10).hits().isEmpty());
            ingest(store,ingestion,null,"tie1.txt","tie exact");ingest(store,ingestion,null,"tie2.txt","tie exact ");ready(index);
            var ties=index.search("tie exact",10).hits();assertEquals(ties.stream().map(KnowledgeLexicalIndex.Hit::documentId).sorted().toList(),ties.stream().map(KnowledgeLexicalIndex.Hit::documentId).toList());
            code(ErrorCode.KNOWLEDGE_SEARCH_INVALID,()->index.search("budget",11));
            assertEquals(1,KnowledgeStore.SCHEMA_VERSION);assertEquals(4,store.scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"));
        }
    }
    @Test void activeOnlyRevisionChangesAndFailedUpdateKeepsCorpus()throws Exception {
        try(var store=store();var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {
            var job=ingest(store,ingestion,null,"note.md","# 预算\noriginal budget");ready(index);var refs=store.searchCorpus();String fingerprint=KnowledgeLexicalIndex.fingerprint(refs,KnowledgeLexicalIndex.engine());
            assertEquals(1,index.search("预算",10).hits().size());
            var failed=ingest(store,ingestion,job.documentId(),"bad.txt","bad\0binary");assertEquals("FAILED",failed.state());
            assertEquals(fingerprint,KnowledgeLexicalIndex.fingerprint(store.searchCorpus(),KnowledgeLexicalIndex.engine()));assertEquals(1,index.search("original",10).hits().size());
            var doc=store.get(job.documentId());store.lifecycle(doc.documentId(),doc.metadataVersion(),"ARCHIVED");ready(index);assertTrue(index.search("预算",10).hits().isEmpty());
            doc=store.get(job.documentId());store.lifecycle(doc.documentId(),doc.metadataVersion(),"ACTIVE");ready(index);assertEquals(1,index.search("预算",10).hits().size());
            ingest(store,ingestion,job.documentId(),"changed.txt","replacement only");ready(index);assertTrue(index.search("original",10).hits().isEmpty());assertEquals("2",index.search("replacement",10).hits().getFirst().sourceRevision());
            doc=store.get(job.documentId());store.delete(doc.documentId(),doc.metadataVersion());ready(index);assertTrue(index.search("replacement",10).hits().isEmpty());
        }
    }
    @Test void missingCorruptAndWrongVersionRebuildLeaveTruthAvailable()throws Exception {
        String id;Path database;
        try(var store=store();var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {id=ingest(store,ingestion,null,"recovery.txt","recover budget").documentId();ready(index);database=store.root().resolve("index/lexical.db");}
        for(int mode=0;mode<3;mode++) {
            if(mode==0)Files.delete(database);else if(mode==1)Files.writeString(database,"not a database");else try(var db=DriverManager.getConnection("jdbc:sqlite:"+database);var s=db.createStatement()){s.execute("UPDATE metadata SET v='wrong' WHERE k='analyzer'");}
            try(var store=store();var index=new KnowledgeLexicalIndex(store)){assertNotNull(store.get(id));ready(index);assertEquals(id,index.search("recover",10).hits().getFirst().documentId());}
        }
    }
    @Test void derivedChunkAndFileLimitsPreserveKnowledge()throws Exception {
        try(var store=store();var ingestion=new KnowledgeIngestion(store)) {
            var job=ingest(store,ingestion,null,"limit.txt","😀".repeat(2100)+" budget");
            try(var index=new KnowledgeLexicalIndex(store,KnowledgeLexicalIndex.MAX_BYTES,1)){awaitFailure(index);code(ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED,()->index.search("budget",10));assertNotNull(store.get(job.documentId()));}
            try(var index=new KnowledgeLexicalIndex(store,4096,100)){awaitFailure(index);code(ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED,()->index.search("budget",10));assertEquals("1",store.get(job.documentId()).currentReadyRevision());}
        }
    }
    static void awaitFailure(KnowledgeLexicalIndex index)throws Exception {long end=System.nanoTime()+5_000_000_000L;while(!index.status().state().equals("FAILED")&&System.nanoTime()<end)Thread.sleep(10);assertEquals("FAILED",index.status().state());}
    @Test void backupExcludesIndexAndRestoreRebuildHasIdenticalHits()throws Exception {
        try(var store=store();var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {
            ingest(store,ingestion,null,"parity.md","# 预算 budget\n预算 budget details");ready(index);var before=index.search("预算 budget",10);
            var bytes=new ByteArrayOutputStream();new KnowledgeBackup(store,temporary.resolve("auth/token")).export(bytes);
            Path restored=temporary.resolve("restored");new KnowledgeBackup(store,temporary.resolve("auth/token")).restore(new ByteArrayInputStream(bytes.toByteArray()),restored.toString());
            assertFalse(Files.exists(restored.resolve("knowledge/index")));
            try(var truth=new KnowledgeStore(restored,temporary.resolve("auth/token"));var rebuilt=new KnowledgeLexicalIndex(truth)){ready(rebuilt);assertEquals(before,rebuilt.search("预算 budget",10));}
        }
    }
    @Test void snippetsArePlainBoundedAndUnicodeSafe() {
        String text="😀".repeat(200)+"预算 <script>alert(1)</script> budget";
        var snippet=LexicalSnippet.create(text,LexicalAnalyzer.query("预算 budget").tokens());assertTrue(snippet.text().length()<=384);
        for(var range:snippet.ranges()){assertTrue(range.start()>=0&&range.end()<=snippet.text().length());assertFalse(Character.isLowSurrogate(snippet.text().charAt(range.start())));}
        assertFalse(snippet.toString().contains("预算"));
    }
    @Test void staleFailsClosedAndRepeatedRebuildsCoalesce()throws Exception {
        try(var store=store();var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {
            ingest(store,ingestion,null,"stale.txt","freshness budget");ready(index);
            synchronized(store){for(int i=0;i<200;i++)index.rebuild();
                code(ErrorCode.KNOWLEDGE_INDEX_NOT_READY,()->index.search("freshness",10));
                var field=KnowledgeLexicalIndex.class.getDeclaredField("executor");field.setAccessible(true);
                var executor=(java.util.concurrent.ThreadPoolExecutor)field.get(index);assertEquals(1,executor.getMaximumPoolSize());assertTrue(executor.getQueue().size()<=1);}
            ready(index);assertEquals(1,index.search("freshness",10).hits().size());
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+store.root().resolve("index/lexical.db"));var s=db.createStatement()){s.execute("UPDATE metadata SET v='"+"0".repeat(64)+"' WHERE k='fingerprint'");}
            code(ErrorCode.KNOWLEDGE_INDEX_NOT_READY,()->index.search("freshness",10));ready(index);
        }
    }
    @Test void queryDiscardsHitsWhenCorpusMutatesBeforeFinalFingerprint()throws Exception {
        try(var store=spy(store());var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {
            var job=ingest(store,ingestion,null,"race.txt","budget race");ready(index);
            String engine=KnowledgeLexicalIndex.engine(),before=KnowledgeLexicalIndex.fingerprint(store.searchCorpus(),engine);
            var queryThread=Thread.currentThread();var mutated=new java.util.concurrent.atomic.AtomicBoolean();
            // Existing source-read seam: SQL hits and the old source were read before truth changes.
            doAnswer(call->{var source=call.callRealMethod();
                if(Thread.currentThread()==queryThread&&mutated.compareAndSet(false,true)){
                    var doc=store.get(job.documentId());store.lifecycle(doc.documentId(),doc.metadataVersion(),"ARCHIVED");}
                return source;
            }).when(store).searchSource(any());
            synchronized(store){ // Hold publication back until the query's final truth check has run.
                code(ErrorCode.KNOWLEDGE_INDEX_NOT_READY,()->index.search("budget",10));
                assertTrue(mutated.get());assertNotEquals(before,KnowledgeLexicalIndex.fingerprint(store.searchCorpus(),engine));
                assertNotEquals("READY",index.status().state());
            }
            ready(index);assertTrue(index.search("budget",10).hits().isEmpty());assertEquals(0,index.status().indexedDocuments());
        }
    }
}
