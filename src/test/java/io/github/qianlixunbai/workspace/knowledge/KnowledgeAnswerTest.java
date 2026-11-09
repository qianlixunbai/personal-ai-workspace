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
    private KnowledgeAnswerHistory history;
    @BeforeEach void openHistory(){history=new KnowledgeAnswerHistory(temporary.resolve("history-data"),temporary.resolve("auth/token"));}
    @AfterEach void closeHistory(){if(history!=null)history.close();}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static class LocalProvider implements Provider {
        volatile ProviderExecution execution;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        public String id(){return "ollama";}
        public ModelProfile.Locality locality(){return ModelProfile.Locality.LOCAL;}
        public Set<Capability> capabilities(){return Set.of(Capability.TEXT_GENERATION,Capability.STRUCTURED_OUTPUT);}
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
            // Delegate to the live asynchronous index; a spy copies mutable worker state.
            // Capture now uses the scope-aware overload even when the scope is absent.
            var index=mock(KnowledgeLexicalIndex.class,org.mockito.AdditionalAnswers.delegatesTo(realIndex));
            doAnswer(call->{assertTrue(Thread.holdsLock(store));return realIndex.search("budget",10,null);}).when(index).search("budget",10,null);
            var owner=new KnowledgeEvidenceAdmission(store,index);var snapshot=owner.capture("budget");var e=snapshot.items().getFirst();
            assertEquals(text,e.text());assertEquals(0,e.citation().startOffset());assertEquals(text.length(),e.citation().endOffset());
            assertEquals("题".repeat(120),e.citation().heading());assertEquals("MARKDOWN_SECTION_LINES",e.citation().locator().type());
            assertThrows(UnsupportedOperationException.class,()->snapshot.items().clear());assertFalse(e.toString().contains("private"));
            var provider=new LocalProvider();var accepted=new KnowledgeAnswerService(owner,submission(provider,manager),history).submit(new KnowledgeAnswerRequest("private question","budget"));
            assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
            assertEquals(ResponseFormat.KNOWLEDGE_ANSWER,provider.execution.responseFormat());
            // These synchronized mutations complete while inference is still held, proving lock release.
            KnowledgeLexicalIndexTest.ingest(store,ingestion,job.documentId(),"fixture.md","replacement budget");
            store.lifecycle(job.documentId(),store.get(job.documentId()).metadataVersion(),"ARCHIVED");
            store.delete(job.documentId(),store.get(job.documentId()).metadataVersion());
            provider.release.countDown();var done=terminal(manager,accepted);assertEquals(TaskStatus.SUCCEEDED,done.status());
            var input=JSON.readTree(provider.execution.input());assertEquals(text,input.path("evidence").get(0).path("text").asString());
            assertEquals(5,input.path("evidence").get(0).size());assertFalse(input.path("evidence").get(0).path("excerpt").asBoolean());assertFalse(provider.execution.input().contains(job.documentId()));
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
                var service=new KnowledgeAnswerService(new KnowledgeEvidenceAdmission(store,index),submission(provider,manager),history);
                KnowledgeLexicalIndexTest.code(ErrorCode.KNOWLEDGE_INDEX_NOT_READY,()->service.submit(new KnowledgeAnswerRequest("question","budget")));assertNull(provider.execution);
            } finally {manager.close();}
        }
    }
    @Test void explicitDocumentFiltersBeforeTopKAndFreezesExactRevisionIntoHistory()throws Exception {
        var manager=new TaskManager(TestSettings.settings(URI.create("http://127.0.0.1:1")));
        try(var store=new KnowledgeStore(temporary.resolve("data"),temporary.resolve("auth/token"));var ingestion=new KnowledgeIngestion(store);var index=new KnowledgeLexicalIndex(store)) {
            for(int i=0;i<12;i++)KnowledgeLexicalIndexTest.ingest(store,ingestion,null,"budget-"+i+".txt","budget unrelated source "+i);
            var selected=KnowledgeLexicalIndexTest.ingest(store,ingestion,null,"selected.txt","background ".repeat(55)+"budget chosen source");
            KnowledgeLexicalIndexTest.ready(index);var scope=new KnowledgeScope(selected.documentId(),"1");
            assertEquals(10,index.search("budget",10).hits().size());
            assertTrue(index.search("budget",10).hits().stream().noneMatch(h->h.documentId().equals(scope.documentId())));
            var hits=index.search("budget",10,scope).hits();assertEquals(1,hits.size());assertEquals(scope.documentId(),hits.getFirst().documentId());
            assertTrue(index.search("unrelated",10,scope).hits().isEmpty());
            var provider=new LocalProvider();var service=new KnowledgeAnswerService(new KnowledgeEvidenceAdmission(store,index),submission(provider,manager),history);
            var accepted=service.submit(new KnowledgeAnswerRequest("What is the selected budget?","budget",scope));
            assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
            KnowledgeLexicalIndexTest.ingest(store,ingestion,selected.documentId(),"selected.txt","budget replacement");
            provider.release.countDown();var done=terminal(manager,accepted);assertEquals(TaskStatus.SUCCEEDED,done.status());
            var input=JSON.readTree(provider.execution.input());assertEquals(1,input.path("evidence").size());
            assertTrue(input.path("evidence").get(0).path("text").asString().contains("chosen source"));
            assertFalse(provider.execution.input().contains("unrelated"));
            assertEquals(scope,history.get(done.taskId()).scope());
            var citation=((TaskResult.KnowledgeAnswer)done.result()).citations().getFirst();assertTrue(scope.matches(citation.documentId(),citation.sourceRevision()));
            KnowledgeLexicalIndexTest.code(ErrorCode.KNOWLEDGE_REVISION_CONFLICT,()->index.search("budget",10,scope));
            KnowledgeLexicalIndexTest.code(ErrorCode.KNOWLEDGE_REVISION_CONFLICT,()->service.submit(new KnowledgeAnswerRequest("again","budget",scope)));
            store.lifecycle(selected.documentId(),store.get(selected.documentId()).metadataVersion(),"ARCHIVED");
            KnowledgeLexicalIndexTest.code(ErrorCode.KNOWLEDGE_REVISION_CONFLICT,()->index.search("budget",10,new KnowledgeScope(selected.documentId(),"2")));
            var saved=history.get(done.taskId());assertEquals(done.result(),saved.task().result());
            history.close();history=new KnowledgeAnswerHistory(temporary.resolve("history-data"),temporary.resolve("auth/token"));
            assertEquals(scope,history.get(done.taskId()).scope());assertEquals(saved,history.get(done.taskId()));
        }finally{manager.close();}
    }
    private static Evidence item(int label,String text) {return new Evidence("S"+label,new TaskResult.KnowledgeCitation(UUID.randomUUID().toString(),"fixture.txt","1","TXT",0,text.length(),1,1,null,new TaskResult.KnowledgeLocator("TXT_LINES",1,1,0,text.length(),null)),text,"internal");}
    @Test void serializedCompleteRankedPrefixUsesSharedCharacterAndByteBudget()throws Exception {
        var owner=mock(KnowledgeEvidenceAdmission.class);var snapshot=new Snapshot(List.of(item(1,"x".repeat(1200)),item(2,"中".repeat(2048)),item(3,"small")));
        when(owner.capture("budget")).thenReturn(snapshot);
        var provider=new LocalProvider();provider.release.countDown();var manager=new TaskManager(TestSettings.settings(URI.create("http://127.0.0.1:1")));try {
            var tasks=submission(provider,manager);assertFalse(tasks.fitsInput("chat.balanced","中".repeat(2000)));assertFalse(tasks.fitsInput("chat.balanced","x".repeat(3001)));
            var service=new KnowledgeAnswerService(owner,tasks,history);assertEquals(TaskStatus.SUCCEEDED,terminal(manager,service.submit(new KnowledgeAnswerRequest("question","budget"))).status());
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
        String literal="合成标识 .cobalt、--verify-only 和 0.075 ms 保持原样。";
        assertEquals(literal,KnowledgeAnswerParser.parse(JSON.writeValueAsString(Map.of("answer",literal,"citations",List.of("S1"))),snapshot).answer());
        var schema=ResponseFormat.KNOWLEDGE_ANSWER.schema();
        ((tools.jackson.databind.node.ObjectNode)schema).remove("properties");
        assertTrue(ResponseFormat.KNOWLEDGE_ANSWER.schema().has("properties"));
        assertNull(ResponseFormat.TEXT.schema());
    }
    @Test void structuredContractCannotSilentlyDowngradeAndPackingCountsEscapingAndSchema()throws Exception {
        var owner=mock(KnowledgeEvidenceAdmission.class);when(owner.capture("budget")).thenReturn(new Snapshot(List.of(item(1,"budget synthetic source"))));
        var provider=new LocalProvider(){@Override public Set<Capability> capabilities(){return Set.of(Capability.TEXT_GENERATION);}};
        var manager=new TaskManager(TestSettings.settings(URI.create("http://127.0.0.1:1")));try {
            var tasks=submission(provider,manager);
            KnowledgeLexicalIndexTest.code(ErrorCode.POLICY_DENIED,()->new KnowledgeAnswerService(owner,tasks,history).submit(new KnowledgeAnswerRequest("question","budget")));
            assertNull(provider.execution);
            var reservation=tasks.reserve("knowledge-answer","chat.balanced",KnowledgeAnswerPrompt.VERSION);
            try {
                assertTrue(tasks.fitsInput(reservation,"short",ResponseFormat.KNOWLEDGE_ANSWER));
                // Control characters expand on the wire even though their UTF-8 representation is small.
                String escaped="x"+"\u0001".repeat(2000);
                assertTrue(tasks.fitsInput(reservation,escaped));
                assertFalse(tasks.fitsInput(reservation,escaped,ResponseFormat.KNOWLEDGE_ANSWER));
            } finally {reservation.closeUnlessTransferred();}
        } finally {manager.close();}
    }
    @Test void oversizedChineseEvidenceUsesExactBudgetedExcerptAndOriginalRevisionPreview()throws Exception {
        var manager=new TaskManager(TestSettings.settings(URI.create("http://127.0.0.1:1")));
        try(var store=new KnowledgeStore(temporary.resolve("data"),temporary.resolve("auth/token"));var ingestion=new KnowledgeIngestion(store);
            var index=new KnowledgeLexicalIndex(store)) {
            String source="# 合成资料\n背景😀说明。"+"背景说明。".repeat(194)+"\n"
                    +"只适用于琥珀项目。预算上限为十二万元。其他项目不可沿用这个预算。\n"+"后续说明。".repeat(195)+"\n";
            var job=KnowledgeLexicalIndexTest.ingest(store,ingestion,null,"fixture.md",source);KnowledgeLexicalIndexTest.ready(index);
            var owner=new KnowledgeEvidenceAdmission(store,index);var original=owner.capture("十二").items().getFirst();
            assertEquals(source,original.text()); // One valid K2 chunk; no change to ingestion or ranking.
            var provider=new LocalProvider();var tasks=submission(provider,manager);
            assertFalse(tasks.fitsInput("chat.balanced",source)); // Even before JSON overhead, the full Chinese chunk cannot fit.
            var accepted=new KnowledgeAnswerService(owner,tasks,history).submit(new KnowledgeAnswerRequest("琥珀项目的预算是多少？","十二"));
            assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
            KnowledgeLexicalIndexTest.ingest(store,ingestion,job.documentId(),"fixture.md","新版本的预算已变更。");
            provider.release.countDown();var done=terminal(manager,accepted);assertEquals(TaskStatus.SUCCEEDED,done.status());
            assertEquals(KnowledgeAnswerPrompt.VERSION,done.promptVersion());assertTrue(tasks.fitsInput("chat.balanced",provider.execution.input()));
            var visible=JSON.readTree(provider.execution.input()).path("evidence");assertEquals(1,visible.size());
            assertTrue(visible.get(0).path("excerpt").asBoolean());String text=visible.get(0).path("text").asString();
            assertTrue(text.contains("只适用于琥珀项目。预算上限为十二万元。其他项目不可沿用这个预算。"));
            var citation=((TaskResult.KnowledgeAnswer)done.result()).citations().getFirst();
            assertTrue(citation.startOffset()>0);assertTrue(citation.endOffset()<source.length());
            assertEquals(source.substring(citation.startOffset(),citation.endOffset()),text);
            assertEquals(1+source.substring(0,citation.startOffset()).chars().filter(c->c=='\n').count(),citation.startLine());
            assertEquals(citation.startLine()+text.chars().filter(c->c=='\n').count()-(text.endsWith("\n")?1:0),citation.endLine());
            assertEquals("1",citation.sourceRevision());assertEquals(original.citation().locator(),citation.locator());
            assertTrue(store.preview(job.documentId(),citation.sourceRevision(),citation.startOffset()).text().startsWith(text));
            assertFalse(provider.execution.input().contains(job.documentId()));
        } finally {manager.close();}
    }
    @Test void sentenceWindowsPreserveUnicodeAndRefuseMissingTermsOrNecessaryContext() {
        String source="外围说明。".repeat(15)+"\nBefore 😀 condition. ＢＵＤＧＥＴ cafe\u0301 is scoped. No global rule.\n"+"外围说明。".repeat(15);
        var excerpt=KnowledgeEvidenceExcerpt.select(item(1,source),LexicalAnalyzer.query("budget café").tokens(),e->e.text().length()<=120);
        assertNotNull(excerpt);assertTrue(excerpt.text().contains("Before 😀 condition. ＢＵＤＧＥＴ cafe\u0301 is scoped. No global rule."));
        assertEquals(source.substring(excerpt.citation().startOffset(),excerpt.citation().endOffset()),excerpt.text());
        assertTrue(excerpt.text().codePoints().noneMatch(cp->cp>=0xD800&&cp<=0xDFFF));
        assertEquals("internal",excerpt.representationDigest());assertEquals("S1",excerpt.label());
        assertNull(KnowledgeEvidenceExcerpt.select(item(1,"中".repeat(2048)),LexicalAnalyzer.query("中").tokens(),e->e.text().length()<1000));
        assertNull(KnowledgeEvidenceExcerpt.select(item(1,"Conditions apply. budget applies. Exclusions apply."),LexicalAnalyzer.query("budget").tokens(),e->e.text().length()<20));
        assertNull(KnowledgeEvidenceExcerpt.select(item(1,"alpha. "+"middle. ".repeat(100)+"omega."),LexicalAnalyzer.query("alpha omega").tokens(),e->e.text().length()<100));
        assertNull(KnowledgeEvidenceExcerpt.select(item(1,source),LexicalAnalyzer.query("missing").tokens(),e->true));
    }
}
