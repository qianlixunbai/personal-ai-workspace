package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBackupTest {
    @TempDir Path temporary;
    Path auth(){return temporary.resolve("auth/token");}
    KnowledgeStore store(String name){return new KnowledgeStore(temporary.resolve(name),auth());}
    static void code(ErrorCode expected,org.junit.jupiter.api.function.Executable f){assertEquals(expected,assertThrows(WorkspaceException.class,f).error().code());}
    KnowledgeStore.Job ingest(KnowledgeStore store,KnowledgeIngestion in,String doc,String version,String filename,String text)throws Exception{
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);var job=in.upload(UUID.randomUUID().toString(),doc,version,filename,bytes.length,new ByteArrayInputStream(bytes));
        for(int i=0;i<1000;i++){job=store.job(job.requestId());if(!Set.of("PENDING","PARSING").contains(job.state())){assertEquals("READY",job.state());return job;}Thread.sleep(5);}throw new AssertionError("Ingestion timeout");
    }
    byte[] fixture()throws Exception{try(var store=store("fixture");var in=new KnowledgeIngestion(store)){ingest(store,in,null,null,"fixture.txt","fixture source bytes");var output=new ByteArrayOutputStream();new KnowledgeBackup(store,auth()).export(output);return output.toByteArray();}}
    @Test void independentStreamingContainerRecoversExactSourcesAndRevisionsWithoutOriginalWorkspace()throws Exception {
        List<KnowledgeDocumentRevision> expected;List<KnowledgeStore.Preview> previews=new ArrayList<>();List<byte[]> sources=new ArrayList<>();KnowledgeDocument original;Path file=temporary.resolve("knowledge-backup");KnowledgeBackup.Metadata exported;
        try(var store=store("source");var in=new KnowledgeIngestion(store)){
            var first=ingest(store,in,null,null,"source.txt","first\r\n中文 source");var d=store.get(first.documentId());
            ingest(store,in,d.documentId(),d.metadataVersion(),"source.md","# First\nmarkdown body\n## Next\nnext body\n");d=store.get(d.documentId());original=store.lifecycle(d.documentId(),d.metadataVersion(),"ARCHIVED");
            expected=store.detail(d.documentId()).revisions();for(var r:expected){sources.add(Files.readAllBytes(store.source(d.documentId(),r.sourceRevision())));previews.add(store.preview(d.documentId(),r.sourceRevision(),0));}
            try(var output=Files.newOutputStream(file)){exported=new KnowledgeBackup(store,auth()).export(output);}
        }
        Files.move(temporary.resolve("source"),temporary.resolve("unavailable-original"));
        try(var maintenance=store("maintenance")){
            var backup=new KnowledgeBackup(maintenance,auth());try(var input=Files.newInputStream(file)){assertEquals(exported,backup.validate(input));}
            assertEquals(0,maintenance.list("ACTIVE",0).total());Path target=temporary.resolve("restored");
            try(var input=Files.newInputStream(file)){assertEquals(exported,backup.restore(input,target.toString()));}
            assertFalse(Files.exists(target.resolve("memory.db")));try(var contents=Files.list(target)){assertEquals(1,contents.count());}
            try(var restored=store("restored")){
                var detail=restored.detail(original.documentId());assertEquals(expected,detail.revisions());assertEquals(original.metadataVersion(),detail.document().metadataVersion());assertEquals("ARCHIVED",detail.document().status());assertEquals("2",detail.document().currentReadyRevision());
                for(int i=0;i<expected.size();i++){assertArrayEquals(sources.get(i),Files.readAllBytes(restored.source(original.documentId(),expected.get(i).sourceRevision())));assertEquals(previews.get(i),restored.preview(original.documentId(),expected.get(i).sourceRevision(),0));}
            }
            try(var input=Files.newInputStream(file)){code(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,()->backup.restore(input,target.toString()));}
            try(var stages=Files.list(maintenance.root().resolve("staging"))){assertEquals(0,stages.count());}
        }
    }
    static byte[] checksum(byte[] payload)throws Exception {byte[] hash=MessageDigest.getInstance("SHA-256").digest(payload);var output=new ByteArrayOutputStream();output.write(payload);output.write(hash);return output.toByteArray();}
    static byte[] document(byte[] backup,UnaryOperator<String> mutate)throws Exception {
        int offset=KnowledgeBackup.MAGIC.length+40;var input=new DataInputStream(new ByteArrayInputStream(backup,offset+1,backup.length-offset-1));int size=input.readInt();String json=new String(input.readNBytes(size),StandardCharsets.UTF_8);
        byte[] replacement=mutate.apply(json).getBytes(StandardCharsets.UTF_8);var output=new ByteArrayOutputStream();output.write(backup,0,offset+1);new DataOutputStream(output).writeInt(replacement.length);output.write(replacement);
        int rest=offset+1+4+size;output.write(backup,rest,backup.length-32-rest);return checksum(output.toByteArray());
    }
    @Test void pathsUnknownFieldsDuplicateKeysAndMalformedMetadataCannotBecomeExtractionAuthority()throws Exception {
        byte[] fixture=fixture();try(var store=store("maintenance")){
            var backup=new KnowledgeBackup(store,auth());for(var change:List.<UnaryOperator<String>>of(
                x->x.replace("fixture.txt","../file.txt"),x->x.replace("fixture.txt","C:\\\\private.txt"),
                x->x.substring(0,x.length()-1)+",\"path\":\"/absolute/file\"}",
                x->x.substring(0,x.length()-1)+",\"documentId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"}",
                x->x.replace("\"metadataVersion\":\"2\"","\"metadataVersion\":2"),x->x+"{}")){
                byte[] invalid=document(fixture,change);code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->backup.validate(new ByteArrayInputStream(invalid)));
            }
            assertFalse(Files.exists(temporary.resolve("file.txt")));try(var files=Files.list(store.root().resolve("staging"))){assertEquals(0,files.count());}
        }
    }
    @Test void digestMismatchTruncationTrailingDataUnsupportedVersionAndDecodedCeilingsFailClosed()throws Exception {
        byte[] fixture=fixture();try(var store=store("maintenance")){
            var backup=new KnowledgeBackup(store,auth());byte[] corrupt=fixture.clone();corrupt[corrupt.length-1]^=1;
            code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->backup.validate(new ByteArrayInputStream(corrupt)));
            byte[] sourceCorrupt=Arrays.copyOf(fixture,fixture.length-32);byte[] marker="fixture source bytes".getBytes(StandardCharsets.UTF_8);
            for(int i=0;i<sourceCorrupt.length-marker.length;i++){if(Arrays.equals(Arrays.copyOfRange(sourceCorrupt,i,i+marker.length),marker)){sourceCorrupt[i]^=1;break;}}
            byte[] sourceMismatch=checksum(sourceCorrupt);code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->backup.validate(new ByteArrayInputStream(sourceMismatch)));
            code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->backup.validate(new ByteArrayInputStream(Arrays.copyOf(fixture,fixture.length-10))));
            byte[] trailing=Arrays.copyOf(fixture,fixture.length+1);code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->backup.validate(new ByteArrayInputStream(trailing)));
            byte[] version=fixture.clone();ByteBuffer.wrap(version).putInt(KnowledgeBackup.MAGIC.length,2);code(ErrorCode.KNOWLEDGE_BACKUP_UNSUPPORTED,()->backup.validate(new ByteArrayInputStream(version)));
            byte[] count=fixture.clone();ByteBuffer.wrap(count).putInt(KnowledgeBackup.MAGIC.length+16,501);code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->backup.validate(new ByteArrayInputStream(count)));
            byte[] limit=fixture.clone();ByteBuffer.wrap(limit).putLong(KnowledgeBackup.MAGIC.length+24,KnowledgeLimits.CORPUS_BYTES+1);code(ErrorCode.KNOWLEDGE_BACKUP_TOO_LARGE,()->backup.validate(new ByteArrayInputStream(limit)));
            assertEquals(0,store.scalar("SELECT count(*) FROM documents"));
        }
    }
    @Test void duplicateDocumentIdentityAndRecordExplosionAreRejectedEvenWithValidChecksum()throws Exception {
        byte[] fixture=fixture();int prefix=KnowledgeBackup.MAGIC.length+40;byte[] record=Arrays.copyOfRange(fixture,prefix,fixture.length-33);
        byte[] header=Arrays.copyOf(fixture,prefix);ByteBuffer.wrap(header).putInt(KnowledgeBackup.MAGIC.length+16,2).putInt(KnowledgeBackup.MAGIC.length+20,2);
        var output=new ByteArrayOutputStream();output.write(header);output.write(record);output.write(record);output.write('E');byte[] duplicate=checksum(output.toByteArray());
        try(var store=store("maintenance")){code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->new KnowledgeBackup(store,auth()).validate(new ByteArrayInputStream(duplicate)));}
    }
    @Test void invalidRestoreNeverPublishesAndTargetContentsRemainUntouched()throws Exception {
        byte[] fixture=fixture();byte[] corrupted=fixture.clone();corrupted[corrupted.length-1]^=1;
        try(var store=store("maintenance")){var backup=new KnowledgeBackup(store,auth());Path empty=temporary.resolve("empty");Files.createDirectory(empty);
            code(ErrorCode.KNOWLEDGE_BACKUP_INVALID,()->backup.restore(new ByteArrayInputStream(corrupted),empty.toString()));try(var entries=Files.list(empty)){assertEquals(0,entries.count());}
            Files.writeString(empty.resolve("user-file"),"preserve");code(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,()->backup.restore(new ByteArrayInputStream(fixture),empty.toString()));assertEquals("preserve",Files.readString(empty.resolve("user-file")));
        }
    }
    @Test void pendingJobBlocksExportWithoutCreatingIncompleteBackup()throws Exception {
        try(var store=store("source")){store.admit(UUID.randomUUID().toString(),null,null,"pending.txt",1);var output=new ByteArrayOutputStream();
            code(ErrorCode.KNOWLEDGE_BACKUP_CONFLICT,()->new KnowledgeBackup(store,auth()).export(output));assertEquals(0,output.size());}
    }
}
