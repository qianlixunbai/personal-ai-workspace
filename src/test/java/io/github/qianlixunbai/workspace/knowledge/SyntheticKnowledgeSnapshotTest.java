package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.TestSettings;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.task.*;
import io.github.qianlixunbai.workspace.backup.OpenPgpStreams;
import org.bouncycastle.bcpg.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeHistorySnapshotRecord.*;

class SyntheticKnowledgeSnapshotTest {
    @TempDir Path temporary;
    private Path token(){return temporary.resolve("auth/token");}
    private SyntheticKnowledgeSnapshot fixture()throws Exception{
        Path parent=temporary.resolve("fixtures");PrivateKnowledgeDirectory.directory(parent);
        return SyntheticKnowledgeSnapshot.create(parent,token());
    }
    private KnowledgeDocument ingest(SyntheticKnowledgeSnapshot f,String doc,String version,String filename,String text)throws Exception{
        try(var in=new KnowledgeIngestion(f.source())){
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            var job=in.upload(UUID.randomUUID().toString(),doc,version,filename,bytes.length,new ByteArrayInputStream(bytes));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(Set.of("PENDING","PARSING").contains(job.state())){assertTrue(System.nanoTime()<deadline);Thread.sleep(5);job=f.source().job(job.requestId());}
            assertEquals("READY",job.state());return f.source().get(job.documentId());
        }
    }
    private UUID answer(SyntheticKnowledgeSnapshot f,KnowledgeDocument doc)throws Exception{
        String revision=doc.currentReadyRevision();var preview=f.source().preview(doc.documentId(),revision,0);var locator=preview.locators().getFirst();
        var citation=new TaskResult.KnowledgeCitation(doc.documentId(),doc.title(),revision,"TXT",locator.startOffset(),locator.endOffset(),locator.startLine(),locator.endLine(),null,
                new TaskResult.KnowledgeLocator(locator.type(),locator.startLine(),locator.endLine(),locator.startOffset(),locator.endOffset(),locator.section()));
        UUID id=begin(f);f.history().finish(id,TaskStatus.SUCCEEDED,new TaskResult.KnowledgeAnswer("合成答案 😀",List.of(citation)),null);return id;
    }
    private UUID begin(SyntheticKnowledgeSnapshot f){UUID id=UUID.randomUUID();f.history().begin(id,"合成问题 <script>fixture</script>","合成",TestSettings.ask().publicInfo(),"knowledge-answer-v2");return id;}
    private static void code(ErrorCode expected,org.junit.jupiter.api.function.Executable action){assertEquals(expected,assertThrows(WorkspaceException.class,action).error().code());}
    private Path copy(Path snapshot,SyntheticKnowledgeSnapshot receiver)throws Exception{
        Path target=receiver.root().resolve("snapshot-"+UUID.randomUUID());PrivateKnowledgeDirectory.directory(target);
        for(String name:List.of("knowledge.backup","answers.bin","manifest.json")){
            Path file=target.resolve(name);PrivateKnowledgeDirectory.file(file);Files.write(file,Files.readAllBytes(snapshot.resolve(name)));
        }
        return target;
    }
    private static void manifest(Path snapshot)throws Exception{
        var old=decode(Files.readAllBytes(snapshot.resolve("manifest.json")),SyntheticKnowledgeSnapshot.Manifest.class);
        Files.write(snapshot.resolve("manifest.json"),JSON.writeValueAsBytes(new SyntheticKnowledgeSnapshot.Manifest(old.format(),old.version(),old.protection(),old.answers(),
                Files.size(snapshot.resolve("knowledge.backup")),sha(Files.readAllBytes(snapshot.resolve("knowledge.backup"))),
                Files.size(snapshot.resolve("answers.bin")),sha(Files.readAllBytes(snapshot.resolve("answers.bin"))))));
    }
    private static void row(Path snapshot,UnaryOperator<String> change)throws Exception{
        byte[] all=Files.readAllBytes(snapshot.resolve("answers.bin"));int length=ByteBuffer.wrap(all).getInt(4);
        byte[] replacement=change.apply(new String(all,8,length,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        var out=new ByteArrayOutputStream();var data=new DataOutputStream(out);data.writeInt(ByteBuffer.wrap(all).getInt(0));data.writeInt(replacement.length);data.write(replacement);data.write(all,8+length,all.length-8-length);
        Files.write(snapshot.resolve("answers.bin"),out.toByteArray());manifest(snapshot);
    }
    @Test void jointSnapshotRestoresAllTerminalStatesOldRevisionsAndUnavailableReferencesWithoutOriginalSource()throws Exception{
        try(var original=fixture();var receiver=fixture()){
            var first=ingest(original,null,null,"first.txt","合成第一版 😀\nold source\n");UUID success=answer(original,first);
            var second=ingest(original,first.documentId(),first.metadataVersion(),"second.txt","合成第二版\nnew source\n");
            var archived=original.source().lifecycle(second.documentId(),second.metadataVersion(),"ARCHIVED");
            var removed=ingest(original,null,null,"removed.txt","合成已删除资料\n");UUID missing=answer(original,removed);original.source().delete(removed.documentId(),removed.metadataVersion());
            List<UUID> ids=new ArrayList<>(List.of(success,missing));
            for(var state:List.of(TaskStatus.FAILED,TaskStatus.CANCELLED,TaskStatus.TIMED_OUT)){
                UUID id=begin(original);ids.add(id);original.history().finish(id,state,null,ApiError.of(state==TaskStatus.FAILED?ErrorCode.KNOWLEDGE_INTERRUPTED:state==TaskStatus.CANCELLED?ErrorCode.TASK_CANCELLED:ErrorCode.TASK_TIMEOUT,"EXECUTION"));
            }
            var entries=ids.stream().map(original.history()::get).toList();var revisions=original.source().detail(first.documentId()).revisions();
            var oldPreview=original.source().preview(first.documentId(),"1",0);
            Path snapshot=copy(original.snapshot(),receiver);original.close();
            Files.move(original.root().resolve("source"),original.root().resolve("unavailable-original"));
            Path target=receiver.candidate();assertEquals(5,receiver.restore(snapshot,target).answers());
            try(var names=Files.list(target)){assertEquals(Set.of("knowledge","knowledge-answer-history"),names.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));}
            for(int reopen=0;reopen<2;reopen++)try(var source=new KnowledgeStore(target,token());var history=new KnowledgeAnswerHistory(target,token())){
                assertEquals(5,history.list(0).total());for(int i=0;i<ids.size();i++)assertEquals(entries.get(i),history.get(ids.get(i)));
                assertEquals(archived.status(),source.get(first.documentId()).status());assertEquals("2",source.get(first.documentId()).currentReadyRevision());
                assertEquals(revisions,source.detail(first.documentId()).revisions());assertEquals(oldPreview,source.preview(first.documentId(),"1",0));
                code(ErrorCode.KNOWLEDGE_NOT_FOUND,()->source.get(removed.documentId()));
                assertEquals(TaskStatus.SUCCEEDED,history.get(missing).task().status());
            }
            assertEquals(0,receiver.source().list("ACTIVE",0).total());assertEquals(0,receiver.history().list(0).total());
            code(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,()->receiver.restore(snapshot,target));
        }
    }
    @Test void bothStoreOperationsAndSeparateSqlWritersAreExcludedUntilSnapshotFinishes()throws Exception{
        try(var f=fixture();var pool=Executors.newFixedThreadPool(3)){
            var doc=ingest(f,null,null,"source.txt","合成稳定源\n");UUID id=answer(f,doc);
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(2);
            Future<Path> snapshot=pool.submit(()->f.snapshot(()->{held.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}));
            assertTrue(held.await(3,TimeUnit.SECONDS));
            try{
                for(String name:List.of("knowledge/knowledge.db","knowledge-answer-history/history.db")){
                    try(var db=DriverManager.getConnection("jdbc:sqlite:"+f.root().resolve("source").resolve(name));var sql=db.createStatement()){
                        sql.execute("PRAGMA busy_timeout=50");assertThrows(SQLException.class,()->sql.execute("BEGIN IMMEDIATE"));
                    }
                }
                Future<?> sourceWrite=pool.submit(()->{started.countDown();f.source().lifecycle(doc.documentId(),doc.metadataVersion(),"ARCHIVED");});
                Future<?> historyWrite=pool.submit(()->{started.countDown();f.history().delete(id);});
                assertTrue(started.await(2,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->sourceWrite.get(100,TimeUnit.MILLISECONDS));assertThrows(TimeoutException.class,()->historyWrite.get(100,TimeUnit.MILLISECONDS));
                release.countDown();Path archive=snapshot.get(5,TimeUnit.SECONDS);sourceWrite.get(3,TimeUnit.SECONDS);historyWrite.get(3,TimeUnit.SECONDS);
                assertEquals(0,f.history().list(0).total());assertEquals("ARCHIVED",f.source().get(doc.documentId()).status());
                Path target=f.candidate();f.restore(archive,target);
                try(var restored=new KnowledgeStore(target,token());var history=new KnowledgeAnswerHistory(target,token())){
                    assertEquals("ACTIVE",restored.get(doc.documentId()).status());assertEquals(TaskStatus.SUCCEEDED,history.get(id).task().status());
                }
            }finally{release.countDown();}
        }
    }
    @Test void pendingTasksAndImportsRejectSnapshotWithoutReconciliationAndFailureReleasesLocks()throws Exception{
        try(var f=fixture()){
            UUID id=begin(f);code(ErrorCode.KNOWLEDGE_BACKUP_CONFLICT,f::snapshot);assertEquals(TaskStatus.QUEUED,f.history().get(id).task().status());
            f.history().finish(id,TaskStatus.CANCELLED,null,ApiError.of(ErrorCode.TASK_CANCELLED,"TASK"));
            assertThrows(WorkspaceException.class,()->f.snapshot(()->{throw new IllegalStateException("synthetic output failure");}));
            f.history().delete(id);assertEquals(0,f.history().list(0).total());
            var job=f.source().admit(UUID.randomUUID().toString(),null,null,"pending.txt",1);
            code(ErrorCode.KNOWLEDGE_BACKUP_CONFLICT,f::snapshot);assertEquals("PENDING",f.source().job(job.requestId()).state());
            try(var dirs=Files.list(f.root())){assertFalse(dirs.anyMatch(p->p.getFileName().toString().matches("snapshot-[0-9a-f-]{36}")));}
        }
    }
    @Test void malformedAndInconsistentSnapshotsNeverPublishOrOverwriteEvenWithRecomputedDigests()throws Exception{
        try(var source=fixture();var receiver=fixture();var empty=fixture()){
            var doc=ingest(source,null,null,"source.txt","合成原始来源\n");answer(source,doc);Path valid=source.snapshot();
            for(var change:List.<UnaryOperator<String>>of(
                    s->s.replace("\"status\":\"SUCCEEDED\"","\"status\":\"QUEUED\""),
                    s->s.replace("\"status\":\"SUCCEEDED\"","\"status\":2"),
                    s->s.replace("\"sourceRevision\":\"1\"","\"sourceRevision\":\"2\""),
                    s->s.replace("\"sourceRevision\":\"1\"","\"sourceRevision\":1"),
                    s->s.replace("\"availability\":\"AVAILABLE\"","\"availability\":\"UNAVAILABLE_AT_SNAPSHOT\""),
                    s->s.replace("\"startOffset\":0","\"startOffset\":1"),
                    s->s.substring(0,s.length()-1)+",\"path\":\"../user-file\"}",
                    s->s.substring(0,s.length()-1)+",\"question\":\"duplicate\"}",
                    s->s+"{}")){
                Path archive=copy(valid,receiver),target=receiver.candidate();row(archive,change);
                code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->receiver.restore(archive,target));assertFalse(Files.exists(target));
            }
            Path swapped=copy(valid,receiver),emptySnapshot=empty.snapshot();Files.write(swapped.resolve("knowledge.backup"),Files.readAllBytes(emptySnapshot.resolve("knowledge.backup")));manifest(swapped);
            Path missingTarget=receiver.candidate();code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->receiver.restore(swapped,missingTarget));assertFalse(Files.exists(missingTarget));
            Path duplicate=copy(valid,receiver);byte[] original=Files.readAllBytes(duplicate.resolve("answers.bin"));var doubled=new ByteArrayOutputStream();var out=new DataOutputStream(doubled);out.writeInt(2);out.write(original,4,original.length-4);out.write(original,4,original.length-4);Files.write(duplicate.resolve("answers.bin"),doubled.toByteArray());manifest(duplicate);
            String text=Files.readString(duplicate.resolve("manifest.json"));Files.writeString(duplicate.resolve("manifest.json"),text.replace("\"answers\":1","\"answers\":2"));
            Path duplicateTarget=receiver.candidate();code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->receiver.restore(duplicate,duplicateTarget));assertFalse(Files.exists(duplicateTarget));
            Path huge=copy(valid,receiver);byte[] body=Files.readAllBytes(huge.resolve("answers.bin"));ByteBuffer.wrap(body).putInt(4,MAX_RECORD_BYTES+1);Files.write(huge.resolve("answers.bin"),body);manifest(huge);
            code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->receiver.restore(huge,receiver.candidate()));
            Path extra=copy(valid,receiver);PrivateKnowledgeDirectory.file(extra.resolve("history.db"));code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->receiver.restore(extra,receiver.candidate()));
            Path version=copy(valid,receiver);Files.writeString(version.resolve("manifest.json"),Files.readString(version.resolve("manifest.json")).replace("\"version\":1","\"version\":2"));code(ErrorCode.KNOWLEDGE_BACKUP_UNSUPPORTED,()->receiver.restore(version,receiver.candidate()));
            Path damaged=copy(valid,receiver);byte[] corrupt=Files.readAllBytes(damaged.resolve("answers.bin"));corrupt[corrupt.length-1]^=1;Files.write(damaged.resolve("answers.bin"),corrupt);code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->receiver.restore(damaged,receiver.candidate()));
            Path target=receiver.candidate();PrivateKnowledgeDirectory.directory(target);Path user=target.resolve("user-file");PrivateKnowledgeDirectory.file(user);Files.writeString(user,"preserve");
            Path archive=copy(valid,receiver);code(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,()->receiver.restore(archive,target));assertEquals("preserve",Files.readString(user));
            Path racing=receiver.candidate();
            code(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,()->receiver.restore(archive,racing,()->{
                try{PrivateKnowledgeDirectory.directory(racing);PrivateKnowledgeDirectory.file(racing.resolve("user-file"));Files.writeString(racing.resolve("user-file"),"late arrival");}
                catch(IOException e){throw new UncheckedIOException(e);}
            }));
            assertEquals("late arrival",Files.readString(racing.resolve("user-file")));
            try(var files=Files.list(racing)){assertEquals(1,files.count());}
            Path failed=receiver.candidate();code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->receiver.restore(archive,failed,()->{throw new IllegalStateException("synthetic publication failure");}));
            assertFalse(Files.exists(failed));
            code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->receiver.restore(archive,temporary.resolve("outside-owned-fixture")));
            assertEquals(0,receiver.history().list(0).total());assertEquals(0,receiver.source().list("ACTIVE",0).total());
        }
    }
    @Test void encryptedSnapshotRestoresAllTerminalStatesAndExactOldSourcesWithoutClearExport()throws Exception{
        try(var original=fixture();var receiver=fixture()){
            char[] secret=password();
            try{
                var first=ingest(original,null,null,"source.txt","合成加密第一版 😀\nold source\n");UUID id=answer(original,first);
                var expected=original.history().get(id);var old=original.source().preview(first.documentId(),"1",0);
                var second=ingest(original,first.documentId(),first.metadataVersion(),"second.txt","合成第二版\n");
                original.source().lifecycle(second.documentId(),second.metadataVersion(),"ARCHIVED");
                var deleted=ingest(original,null,null,"deleted.txt","合成删除来源\n");UUID missing=answer(original,deleted);
                original.source().delete(deleted.documentId(),deleted.metadataVersion());
                for(var status:List.of(TaskStatus.FAILED,TaskStatus.CANCELLED,TaskStatus.TIMED_OUT)){
                    UUID terminal=begin(original);original.history().finish(terminal,status,null,ApiError.of(status==TaskStatus.FAILED?ErrorCode.KNOWLEDGE_INTERRUPTED:status==TaskStatus.CANCELLED?ErrorCode.TASK_CANCELLED:ErrorCode.TASK_TIMEOUT,"EXECUTION"));
                }
                var all=original.history().list(0).items().stream().map(row->original.history().get(row.taskId())).toList();
                char[] encryptSecret=secret.clone();Path encrypted=original.encryptedSnapshot(encryptSecret);cleared(encryptSecret);
                try(var files=Files.list(encrypted)){assertEquals(Set.of("backup.pgp","complete.json"),files.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));}
                assertNoDecoded(original);
                String cipher=new String(Files.readAllBytes(encrypted.resolve("backup.pgp")),StandardCharsets.UTF_8);
                assertFalse(cipher.contains("合成加密第一版"));assertFalse(cipher.contains("合成答案"));assertFalse(cipher.contains("knowledge.backup"));
                Path copied=copyEncrypted(encrypted,receiver);original.close();Files.move(original.root().resolve("source"),original.root().resolve("unavailable-original"));
                Path target=receiver.candidate();char[] restoreSecret=secret.clone();assertEquals(5,receiver.restoreEncrypted(copied,target,restoreSecret).answers());cleared(restoreSecret);
                assertNoDecoded(receiver);
                for(int reopen=0;reopen<2;reopen++)try(var store=new KnowledgeStore(target,token());var history=new KnowledgeAnswerHistory(target,token())){
                    assertEquals(5,history.list(0).total());for(var row:all)assertEquals(row,history.get(row.task().taskId()));
                    assertEquals(expected,history.get(id));assertEquals(old,store.preview(first.documentId(),"1",0));
                    assertEquals("2",store.get(first.documentId()).currentReadyRevision());assertEquals("ARCHIVED",store.get(first.documentId()).status());
                    assertEquals(TaskStatus.SUCCEEDED,history.get(missing).task().status());code(ErrorCode.KNOWLEDGE_NOT_FOUND,()->store.get(deleted.documentId()));
                }
                assertEquals(0,receiver.history().list(0).total());assertEquals(0,receiver.source().list("ACTIVE",0).total());
            }finally{Arrays.fill(secret,'\0');}
        }
    }
    @Test void encryptedWrongPasswordTamperingAndHostileKdfRejectBeforeAnyPlaintextEvenWithNewChecksum()throws Exception{
        try(var f=fixture()){
            var doc=ingest(f,null,null,"source.txt","合成原文\n");UUID id=answer(f,doc);var expected=f.history().get(id);
            char[] secret=password();
            try{
                Path encrypted=f.encryptedSnapshot(secret.clone());char[] wrong=password();Path target=f.candidate();
                code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->f.restoreEncrypted(encrypted,target,wrong));cleared(wrong);assertFalse(Files.exists(target));assertNoDecoded(f);
                for(String mode:List.of("middle","final-tag","truncated","kdf-cost","trailing")){
                    Path altered=copyEncrypted(encrypted,f);Path payload=altered.resolve("backup.pgp");byte[] bytes=Files.readAllBytes(payload);
                    if(mode.equals("truncated"))bytes=Arrays.copyOf(bytes,bytes.length-1);
                    else if(mode.equals("trailing")){bytes=Arrays.copyOf(bytes,bytes.length+1);bytes[bytes.length-1]=42;}
                    else if(mode.equals("kdf-cost")){
                        var pgp=new BCPGInputStream(new ByteArrayInputStream(bytes));var key=(SymmetricKeyEncSessionPacket)pgp.readPacket();byte[] kdf=key.getS2K().getEncoded();int found=-1;
                        for(int i=0;i<=Math.min(256,bytes.length)-kdf.length;i++)if(Arrays.equals(Arrays.copyOfRange(bytes,i,i+kdf.length),kdf)){found=i;break;}
                        assertTrue(found>=0);bytes[found+kdf.length-1]=30;
                    }else bytes[mode.equals("middle")?bytes.length/2:bytes.length-1]^=1;
                    Files.write(payload,bytes);encryptedReceipt(altered);
                    char[] attempt=secret.clone();Path rejected=f.candidate();code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->f.restoreEncrypted(altered,rejected,attempt));
                    cleared(attempt);assertFalse(Files.exists(rejected));assertNoDecoded(f);
                }
                assertEquals(expected,f.history().get(id));assertEquals("ACTIVE",f.source().get(doc.documentId()).status());
            }finally{Arrays.fill(secret,'\0');}
        }
    }
    @Test void authenticatedZipStillRejectsForeignPathsExpansionMissingFilesAndWrongContentContract()throws Exception{
        try(var f=fixture()){
            char[] secret=password();
            try{
                for(String mode:List.of("traversal","extra","missing","expansion","legacy-literal","invalid-logical")){
                    Path encrypted=f.root().resolve("encrypted-"+UUID.randomUUID());PrivateKnowledgeDirectory.directory(encrypted);
                    SyntheticKnowledgeSnapshot.write(encrypted.resolve("backup.pgp"),SyntheticEncryptedKnowledgeSnapshot.CIPHER_BYTES,out->
                        OpenPgpStreams.encryptZip(out,secret,mode.equals("legacy-literal")?"workspace-v3.zip":SyntheticEncryptedKnowledgeSnapshot.LITERAL,
                                SyntheticEncryptedKnowledgeSnapshot.CLEAR_BYTES,zip->{
                            if(mode.equals("invalid-logical")){
                                f.writeEntries((name,maximum,writer)->{
                                    zip.putNextEntry(new ZipEntry(name));
                                    if(name.equals("answers.bin"))zip.write(new byte[]{0,0,0,1});else writer.write(zip);zip.closeEntry();
                                },()->{});
                            }else{
                                zip.putNextEntry(new ZipEntry(mode.equals("traversal")?"../escape":mode.equals("extra")?"history.db":"manifest.json"));
                                zip.write(new byte[mode.equals("expansion")?4097:2]);zip.closeEntry();
                            }
                        }));
                    encryptedReceipt(encrypted);Path target=f.candidate();char[] attempt=secret.clone();
                    code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->f.restoreEncrypted(encrypted,target,attempt));cleared(attempt);
                    assertFalse(Files.exists(target));assertFalse(Files.exists(f.root().resolve("escape")));assertNoDecoded(f);
                }
            }finally{Arrays.fill(secret,'\0');}
        }
    }
    @Test void encryptedIncompleteWritersAndInterruptedOutputNeverPublishAndAlwaysClearPassword()throws Exception{
        try(var f=fixture()){
            UUID id=begin(f);char[] pending=password();code(ErrorCode.KNOWLEDGE_BACKUP_CONFLICT,()->f.encryptedSnapshot(pending));cleared(pending);
            assertEquals(TaskStatus.QUEUED,f.history().get(id).task().status());
            f.history().finish(id,TaskStatus.CANCELLED,null,ApiError.of(ErrorCode.TASK_CANCELLED,"TASK"));
            char[] failed=password();assertThrows(WorkspaceException.class,()->new SyntheticEncryptedKnowledgeSnapshot(f).snapshot(failed,()->{throw new IllegalStateException();}));cleared(failed);
            char[] interrupted=password();
            try{assertThrows(WorkspaceException.class,()->new SyntheticEncryptedKnowledgeSnapshot(f).snapshot(interrupted,()->Thread.currentThread().interrupt()));}
            finally{assertTrue(Thread.interrupted());}cleared(interrupted);
            try(var files=Files.list(f.root())){assertFalse(files.anyMatch(p->p.getFileName().toString().matches("encrypted-[0-9a-f-]{36}")));}
            assertNoDecoded(f);f.history().delete(id);assertEquals(0,f.history().list(0).total());
            char[] success=password();assertTrue(Files.isDirectory(f.encryptedSnapshot(success)));cleared(success);
        }
    }
    @Test void encryptedRestoreRejectsExistingTargetForeignEnvelopeAndExtraFilesWithoutChangingCurrentSources()throws Exception{
        try(var f=fixture()){
            char[] secret=password();
            try{
                Path encrypted=f.encryptedSnapshot(secret.clone()),target=f.candidate();PrivateKnowledgeDirectory.directory(target);
                Path user=target.resolve("user-file");PrivateKnowledgeDirectory.file(user);Files.writeString(user,"preserve");
                char[] attempt=secret.clone();code(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,()->f.restoreEncrypted(encrypted,target,attempt));cleared(attempt);
                assertEquals("preserve",Files.readString(user));assertNoDecoded(f);
                for(String mode:List.of("version","type","duplicate-field","extra-file")){
                    Path changed=copyEncrypted(encrypted,f);Path receipt=changed.resolve("complete.json");String text=Files.readString(receipt);
                    if(mode.equals("version"))Files.writeString(receipt,text.replace("\"version\":1","\"version\":2"));
                    if(mode.equals("type"))Files.writeString(receipt,text.replace("\"version\":1","\"version\":\"1\""));
                    if(mode.equals("duplicate-field"))Files.writeString(receipt,text.substring(0,text.length()-1)+",\"version\":1}");
                    if(mode.equals("extra-file"))PrivateKnowledgeDirectory.file(changed.resolve("unexpected"));
                    Path rejected=f.candidate();code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->f.restoreEncrypted(changed,rejected,secret.clone()));assertFalse(Files.exists(rejected));assertNoDecoded(f);
                }
            }finally{Arrays.fill(secret,'\0');}
        }
    }
    private static char[] password(){return (UUID.randomUUID().toString()+UUID.randomUUID()).toCharArray();}
    private static void cleared(char[] value){for(char c:value)assertEquals('\0',c);}
    private static void assertNoDecoded(SyntheticKnowledgeSnapshot f)throws Exception{
        try(var files=Files.list(f.root())){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith("snapshot-")));}
    }
    private static Path copyEncrypted(Path encrypted,SyntheticKnowledgeSnapshot receiver)throws Exception{
        Path copy=receiver.root().resolve("encrypted-"+UUID.randomUUID());PrivateKnowledgeDirectory.directory(copy);
        for(String name:List.of("backup.pgp","complete.json")){PrivateKnowledgeDirectory.file(copy.resolve(name));Files.write(copy.resolve(name),Files.readAllBytes(encrypted.resolve(name)));}return copy;
    }
    private static void encryptedReceipt(Path encrypted)throws Exception{
        Path receipt=encrypted.resolve("complete.json"),payload=encrypted.resolve("backup.pgp");
        if(!Files.exists(receipt))PrivateKnowledgeDirectory.file(receipt);
        Files.write(receipt,JSON.writeValueAsBytes(new SyntheticEncryptedKnowledgeSnapshot.Completion(SyntheticEncryptedKnowledgeSnapshot.ENCRYPTED_FORMAT,1,
                SyntheticEncryptedKnowledgeSnapshot.PROFILE,Files.size(payload),sha(Files.readAllBytes(payload)))));
    }
}
