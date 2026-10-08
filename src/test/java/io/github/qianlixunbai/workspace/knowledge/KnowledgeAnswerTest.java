package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.capability.*;
import io.github.qianlixunbai.workspace.capability.knowledge.*;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.*;
import io.github.qianlixunbai.workspace.policy.*;
import io.github.qianlixunbai.workspace.provider.*;
import io.github.qianlixunbai.workspace.task.*;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeEvidenceAdmission.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeAnswerTest {
    @TempDir Path temporary;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static class LocalProvider implements Provider {
        volatile ProviderExecution execution;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        public String id(){return "ollama";}
        public ModelProfile.Locality locality(){return ModelProfile.Locality.LOCAL;}
        public Set<Capability> capabilities(){return Set.of(Capability.TEXT_GENERATION);}
        public ProviderReadiness readiness(ModelProfile p){return null;}
        public String execute(ProviderExecution e,Cancellation c){execution=e;entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException x){throw new IllegalStateException();}return "{\"answer\":\"fixture answer\",\"citations\":[\"S1\"]}";}
    }
    private TextTaskSubmission submission(LocalProvider provider,TaskManager manager){var p=TestSettings.settings(URI.create("http://127.0.0.1:1"));return new TextTaskSubmission(new ProfileResolver(p),new ProviderRegistry(List.of(provider)),new ProviderPolicy(),manager,TestSettings.models(p));}
    private static TaskView terminal(TaskManager manager,TaskView task)throws Exception {
        long end=System.nanoTime()+5_000_000_000L;while(Set.of(TaskStatus.QUEUED,TaskStatus.RUNNING).contains(task.status())&&System.nanoTime()<end){Thread.sleep(5);task=manager.get(task.taskId());}return task;
    }
    @Test void atomicExactCaptureIsFrozenThroughUpdateArchiveDeleteAndProviderHasNoStoreLock()throws Exception {
        var manager=new TaskManager(TestSettings.settings(URI.create("http://127.0.0.1:1")));
        try(var store=new KnowledgeStore(temporary.resolve("data"),temporary.resolve("auth/token"));var ingestion=new KnowledgeIngestion(store);
            var realIndex=new KnowledgeLexicalIndex(store)) {
            String text="# "+"题".repeat(120)+"\nprivate fixture budget 😀\n";
            var job=KnowledgeLexicalIndexTest.ingest(store,ingestion,null,"fixture.md",text);KnowledgeLexicalIndexTest.ready(realIndex);
            var index=spy(realIndex);doAnswer(call->{assertTrue(Thread.holdsLock(store));return realIndex.search("budget",10);}).when(index).search("budget",10);
            var owner=new KnowledgeEvidenceAdmission(store,index);var snapshot=owner.capture("budget");var e=snapshot.items().getFirst();
            assertEquals(text,e.text());assertEquals(0,e.citation().startOffset());assertEquals(text.length(),e.citation().endOffset());
            assertEquals("题".repeat(120),e.citation().heading());assertEquals("MARKDOWN_SECTION_LINES",e.citation().locator().type());
            assertThrows(UnsupportedOperationException.class,()->snapshot.items().clear());assertFalse(e.toString().contains("private"));
            var provider=new LocalProvider();var accepted=new KnowledgeAnswerService(owner,submission(provider,manager)).submit(new KnowledgeAnswerRequest("private question","budget"));
            assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
            // These synchronized mutations complete while inference is still held, proving lock release.
            KnowledgeLexicalIndexTest.ingest(store,ingestion,job.documentId(),"fixture.md","replacement budget");
            store.lifecycle(job.documentId(),store.get(job.documentId()).metadataVersion(),"ARCHIVED");
            store.delete(job.documentId(),store.get(job.documentId()).metadataVersion());
            provider.release.countDown();var done=terminal(manager,accepted);assertEquals(TaskStatus.SUCCEEDED,done.status());
            var input=JSON.readTree(provider.execution.input());assertEquals(text,input.path("evidence").get(0).path("text").asString());
            assertEquals(4,input.path("evidence").get(0).size());assertFalse(provider.execution.input().contains(job.documentId()));
            var result=(TaskResult.KnowledgeAnswer)done.result();assertEquals("1",result.citations().getFirst().sourceRevision());assertEquals(e.citation(),result.citations().getFirst());
            assertFalse(result.toString().contains("fixture answer"));assertFalse(new KnowledgeAnswerRequest("private question","budget").toString().contains("private"));
            KnowledgeLexicalIndexTest.ready(realIndex);KnowledgeLexicalIndexTest.code(ErrorCode.INVALID_REQUEST,()->owner.capture("budget"));
        } finally {manager.close();}
    }
    @Test void derivedRangeDisagreementFailsClosedBeforeTaskCreation()throws Exception {
        try(var store=new KnowledgeStore(temporary.resolve("data"),temporary.resolve("auth/token"));var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {
            KnowledgeLexicalIndexTest.ingest(store,ingestion,null,"fixture.txt","budget exact source");KnowledgeLexicalIndexTest.ready(index);
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+store.root().resolve("index/lexical.db"));var s=db.createStatement()){s.execute("UPDATE chunks SET start=1");}
            var provider=new LocalProvider();var manager=new TaskManager(TestSettings.settings(URI.create("http://127.0.0.1:1")));try {
                var service=new KnowledgeAnswerService(new KnowledgeEvidenceAdmission(store,index),submission(provider,manager));
                KnowledgeLexicalIndexTest.code(ErrorCode.KNOWLEDGE_INDEX_NOT_READY,()->service.submit(new KnowledgeAnswerRequest("question","budget")));assertNull(provider.execution);
            } finally {manager.close();}
        }
    }
    private static Evidence item(int label,String text) {return new Evidence("S"+label,new TaskResult.KnowledgeCitation(UUID.randomUUID().toString(),"fixture.txt","1","TXT",0,text.length(),1,1,null,new TaskResult.KnowledgeLocator("TXT_LINES",1,1,0,text.length(),null)),text,"internal");}
    @Test void serializedCompleteRankedPrefixUsesSharedCharacterAndByteBudget()throws Exception {
        var owner=mock(KnowledgeEvidenceAdmission.class);var snapshot=new Snapshot(List.of(item(1,"x".repeat(1200)),item(2,"中".repeat(2048)),item(3,"small")));
        when(owner.capture("budget")).thenReturn(snapshot);
        var provider=new LocalProvider();provider.release.countDown();var manager=new TaskManager(TestSettings.settings(URI.create("http://127.0.0.1:1")));try {
            var tasks=submission(provider,manager);assertFalse(tasks.fitsInput("chat.balanced","中".repeat(2000)));assertFalse(tasks.fitsInput("chat.balanced","x".repeat(3001)));
            var service=new KnowledgeAnswerService(owner,tasks);assertEquals(TaskStatus.SUCCEEDED,terminal(manager,service.submit(new KnowledgeAnswerRequest("question","budget"))).status());
            var input=JSON.readTree(provider.execution.input());assertEquals(1,input.path("evidence").size());assertEquals(snapshot.items().getFirst().text(),input.path("evidence").get(0).path("text").asString());
            when(owner.capture("budget")).thenReturn(new Snapshot(List.of(item(1,"\"".repeat(2048)))));provider.execution=null;
            KnowledgeLexicalIndexTest.code(ErrorCode.INVALID_REQUEST,()->service.submit(new KnowledgeAnswerRequest("question","budget")));assertNull(provider.execution);
        } finally {manager.close();}
    }
    @Test void strictJsonRejectsWholeOutputAndMapsOnlyAdmittedLabels() {
        var snapshot=new Snapshot(List.of(item(1,"private evidence"),item(2,"second evidence")));
        var valid=KnowledgeAnswerParser.parse("{\"answer\":\"plain answer\",\"citations\":[\"S2\",\"S1\"]}",snapshot);
        assertEquals(List.of(snapshot.items().get(1).citation(),snapshot.items().get(0).citation()),valid.citations());assertThrows(UnsupportedOperationException.class,()->valid.citations().clear());
        for(String invalid:List.of("[]","{\"answer\":\"a\",\"citations\":[\"S1\"],\"extra\":1}","{\"answer\":\"a\",\"answer\":\"b\",\"citations\":[\"S1\"]}","{\"answer\":\"a\",\"citations\":[\"S1\"]} {}","```json\n{}\n```","{\"answer\":true,\"citations\":[\"S1\"]}","{\"answer\":\" \",\"citations\":[\"S1\"]}","{\"answer\":\"a\",\"citations\":[]}","{\"answer\":\"a\",\"citations\":[1]}","{\"answer\":\"a\",\"citations\":[\"S1\",\"S1\"]}","{\"answer\":\"a\",\"citations\":[\"S1\",\"S99\"]}","{\"answer\":\"a\",\"citations\":\"S1\"}"))
            KnowledgeLexicalIndexTest.code(ErrorCode.PROVIDER_RESPONSE_INVALID,()->KnowledgeAnswerParser.parse(invalid,snapshot));
        KnowledgeLexicalIndexTest.code(ErrorCode.PROVIDER_RESPONSE_INVALID,()->KnowledgeAnswerParser.parse(JSON.writeValueAsString(Map.of("answer","x".repeat(2049),"citations",List.of("S1"))),snapshot));
        assertTrue(KnowledgeAnswerPrompt.SYSTEM.getBytes(StandardCharsets.UTF_8).length<=512);
    }
}
