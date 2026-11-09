package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.task.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeHistorySnapshotRecord.*;

/** Explicit desktop-test fixture only. A native host creates a new Profile; data stays in
 * its evidence domain. This does not install/activate a recovered Profile or change data roots
 * accepted by the product launcher. The persisted JSON is an expected-value oracle, not trust. */
public final class KnowledgeRecoveryDesktopFixture {
    private static final String PHASE=System.getProperty("knowledge.recovery.desktop.fixture","");
    private static final Path ROOT=initializeRoot();
    private static final State STATE=initialize();
    record State(String candidate,long producerPid,String documentId,String oldText,String currentText,List<Saved> answers){}
    private KnowledgeRecoveryDesktopFixture(){}
    public static Path root(){return ROOT;}
    public static Path data(){
        Path data=ROOT.resolve(STATE.candidate()).normalize();
        assertTrue(STATE.candidate().matches("fixtures[/\\\\]ks-[0-9a-f]{16}[/\\\\]candidate-[0-9a-f-]{36}"));
        assertTrue(data.startsWith(ROOT.resolve("fixtures")));return data;
    }
    private static Path initializeRoot(){
        assertTrue(Set.of("prepare","reopen").contains(PHASE));
        try{
            Path root=WindowsNativeGate.profileRoot().resolve("evidence/kd");
            if(PHASE.equals("prepare")){assertFalse(Files.exists(root));PrivateKnowledgeDirectory.directory(root);}
            else{PrivateKnowledgeDirectory.noLinks(root);PrivateKnowledgeDirectory.protect(root,true);assertTrue(Files.isRegularFile(root.resolve("fixture.json")));}
            return root;
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static State initialize(){
        try{
            Path file=ROOT.resolve("fixture.json");
            if(PHASE.equals("reopen")){
                PrivateKnowledgeDirectory.regular(file);PrivateKnowledgeDirectory.protect(file,false);
                var state=decode(Files.readAllBytes(file),State.class);assertNotEquals(ProcessHandle.current().pid(),state.producerPid());return state;
            }
            Path parent=ROOT.resolve("fixtures"),token=ROOT.resolve("credentials/client-token");PrivateKnowledgeDirectory.directory(parent);
            char[] password=(UUID.randomUUID().toString()+UUID.randomUUID()).toCharArray();
            try(var original=SyntheticKnowledgeSnapshot.create(parent,token);var receiver=SyntheticKnowledgeSnapshot.create(parent,token)){
                var first=ingest(original,null,"old.txt","合成恢复第一版 😀\n旧版约定：周三提交报告。\n<script>仅是原文</script>\n");
                answer(original,first,"合成：旧版报告在哪天提交？");String oldText=original.source().preview(first.documentId(),"1",0).text();
                var current=ingest(original,first,"new.txt","合成恢复第二版\n新版约定：周五提交报告。\n");
                String currentText=original.source().preview(current.documentId(),"2",0).text();
                var deleted=ingest(original,null,"deleted.txt","合成已删除资料\n该答案保留，来源已删除。\n");
                answer(original,deleted,"合成：已删除来源的原回答是什么？");original.source().delete(deleted.documentId(),deleted.metadataVersion());
                for(var status:List.of(TaskStatus.FAILED,TaskStatus.CANCELLED,TaskStatus.TIMED_OUT)){
                    UUID id=begin(original,"合成：终态记录 "+status);
                    original.history().finish(id,status,null,ApiError.of(status==TaskStatus.FAILED?ErrorCode.KNOWLEDGE_INTERRUPTED:status==TaskStatus.CANCELLED?ErrorCode.TASK_CANCELLED:ErrorCode.TASK_TIMEOUT,"EXECUTION"));
                }
                var answers=new ArrayList<Saved>();
                for(var row:original.history().list(0).items())answers.add(capture(original.history().get(row.taskId()),original.source()));
                Path archive=original.encryptedSnapshot(password.clone()),copy=receiver.root().resolve("encrypted-"+UUID.randomUUID());
                PrivateKnowledgeDirectory.directory(copy);
                for(String name:List.of("backup.pgp","complete.json")){PrivateKnowledgeDirectory.file(copy.resolve(name));Files.write(copy.resolve(name),Files.readAllBytes(archive.resolve(name)));}
                original.close();Files.move(original.root().resolve("source"),original.root().resolve("unavailable-original"));
                Path candidate=receiver.candidate();assertEquals(5,receiver.restoreEncrypted(copy,candidate,password.clone()).answers());
                var state=new State(ROOT.relativize(candidate).toString(),ProcessHandle.current().pid(),first.documentId(),oldText,currentText,answers);
                SyntheticKnowledgeSnapshot.write(file,131072,out->out.write(JSON.writeValueAsBytes(state)));
                System.out.println("DESKTOP_SYNTHETIC_ENCRYPTED_RESTORE_PREPARED");return state;
            }finally{Arrays.fill(password,'\0');}
        }catch(Exception e){throw new IllegalStateException("Synthetic desktop fixture preparation failed",e);}
    }
    private static KnowledgeDocument ingest(SyntheticKnowledgeSnapshot f,KnowledgeDocument before,String filename,String text)throws Exception{
        try(var ingestion=new KnowledgeIngestion(f.source())){
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            var job=ingestion.upload(UUID.randomUUID().toString(),before==null?null:before.documentId(),before==null?null:before.metadataVersion(),filename,bytes.length,new ByteArrayInputStream(bytes));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(Set.of("PENDING","PARSING").contains(job.state())){assertTrue(System.nanoTime()<deadline);Thread.sleep(10);job=f.source().job(job.requestId());}
            assertEquals("READY",job.state());return f.source().get(job.documentId());
        }
    }
    private static UUID begin(SyntheticKnowledgeSnapshot f,String question){
        UUID id=UUID.randomUUID();f.history().begin(id,question,"合成",TestSettings.ask().publicInfo(),"knowledge-answer-v2");return id;
    }
    private static void answer(SyntheticKnowledgeSnapshot f,KnowledgeDocument doc,String question){
        var p=f.source().preview(doc.documentId(),doc.currentReadyRevision(),0);var l=p.locators().getFirst();
        var citation=new TaskResult.KnowledgeCitation(doc.documentId(),doc.title(),doc.currentReadyRevision(),"TXT",l.startOffset(),l.endOffset(),l.startLine(),l.endLine(),null,
                new TaskResult.KnowledgeLocator(l.type(),l.startLine(),l.endLine(),l.startOffset(),l.endOffset(),l.section()));
        UUID id=begin(f,question);f.history().finish(id,TaskStatus.SUCCEEDED,new TaskResult.KnowledgeAnswer("合成保存的回答："+question+"\n内容按原记录恢复。",List.of(citation)),null);
    }
    public static void verify(KnowledgeStore source,KnowledgeAnswerHistory history,TaskManager tasks)throws Exception{
        assertEquals(5,history.list(0).total());assertEquals(1,source.list("ACTIVE",0).total());
        assertEquals("2",source.get(STATE.documentId()).currentReadyRevision());
        assertEquals(STATE.oldText(),source.preview(STATE.documentId(),"1",0).text());assertEquals(STATE.currentText(),source.preview(STATE.documentId(),"2",0).text());
        for(var saved:STATE.answers()){
            var entry=KnowledgeHistorySnapshotRecord.verify(saved,source);assertEquals(entry,history.get(entry.task().taskId()));
            assertEquals(ErrorCode.TASK_NOT_FOUND,assertThrows(WorkspaceException.class,()->tasks.get(entry.task().taskId())).error().code());
        }
    }
}
