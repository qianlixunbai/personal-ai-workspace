package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.conversation.*;
import io.github.qianlixunbai.workspace.knowledge.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;
import static io.github.qianlixunbai.workspace.backup.PortableEncryptedBackup.*;
import static io.github.qianlixunbai.workspace.backup.PortableEncryptedBackupIntegrationTest.*;

/** One real synthetic source -> direct encrypted archive -> new isolated target acceptance flow. */
@EnabledIfSystemProperty(named="secure.backup.test",matches="true")
class SecureBackupPipelineIntegrationTest {
    @Test void directSnapshotAuthenticatedRestoreAndPlaintextLifecycle() throws Exception {
        Path config=Path.of(".runtime/workspace/b3f-test-credentials.json");privatePath(config,false);
        var c=JSON.readTree(Files.readAllBytes(config));Path parent=Path.of(c.path("maintenanceRoot").asString());
        Path oldBackup=Path.of(c.path("backupRoot").asString()),tools=Path.of(c.path("tools").asString());
        var plain=new UnifiedPostgresqlBackup(tools);var original=plain.validate(oldBackup);
        var owner=new Endpoint(c.path("targetDatabase").asString(),c.path("ownerUsername").asString(),c.path("ownerPassword").asString());
        var app=new Endpoint(owner.database(),c.path("appUsername").asString(),c.path("appPassword").asString());
        var source=new Endpoint(c.path("sourceDatabase").asString(),c.path("sourceUsername").asString(),c.path("sourcePassword").asString());
        var sourceOwner=new Endpoint(source.database(),owner.username(),owner.password());
        var sourceApp=new Endpoint(source.database(),app.username(),app.password());String receipt=c.path("ownershipReceipt").asString();c=null;
        // Bootstrap only our fresh source, using the preserved B3 synthetic collection read-only.
        Path seed=parent.resolve("seed");
        if(!Files.exists(seed.resolve("RESTORE_VERIFIED.json")))plain.restore(oldBackup,sourceOwner,sourceApp,receipt,seed);
        try(var db=sourceOwner.dataSource().getConnection()) {
            sql(db,"GRANT USAGE ON SCHEMA public,workspace TO "+source.username());
            sql(db,"GRANT SELECT,UPDATE ON ALL TABLES IN SCHEMA public,workspace TO "+source.username());
            sql(db,"GRANT SELECT ON ALL SEQUENCES IN SCHEMA public,workspace TO "+source.username());
        }
        Path root=parent.resolve("attempt-"+UUID.randomUUID());directory(root);
        var secure=new PortableEncryptedBackup(tools,StagingProtection.SYNTHETIC_ONLY);
        Path knowledge=seed.resolve("knowledge"),encrypted=root.resolve("encrypted");char[] password=randomPassword();
        Snapshot sourceBefore;try(var db=source.dataSource().getConnection()){sourceBefore=snapshot(db);}
        try {
            MaintenanceStaging.admit(root.resolve("admission-probe"),true);
            // Repository ancestry, public TEMP, sync names and an unconfirmed protected volume fail closed.
            assertThrows(Exception.class,()->MaintenanceStaging.admit(oldBackup.getParent().resolve("unsafe-ancestor"),true));
            assertThrows(IOException.class,()->PortableEncryptedBackup.stagingParent(Path.of(System.getProperty("java.io.tmpdir"),"reject-b3f")));
            Path sync=root.resolve("Dropbox");directory(sync);
            assertThrows(IOException.class,()->PortableEncryptedBackup.stagingParent(sync.resolve("reject")));
            assertThrows(Exception.class,()->MaintenanceStaging.admit(root.resolve("unverified-volume"),false));
            assertThrows(IOException.class,()->secure.backup(source,knowledge,root.resolve("writers-running"),false,password.clone()));
            var complete=secure.backup(source,knowledge,encrypted,true,password.clone());assertEquals(2,complete.version());
            assertEquals(complete,secure.completion(encrypted));
            try(var walk=Files.walk(root)){assertTrue(walk.noneMatch(p->p.toString().endsWith("database.dump")||p.toString().endsWith(".zip")||p.toString().endsWith(".source")));}
            Path export=root.resolve("explicit-export");var decoded=secure.decrypt(encrypted,export,password.clone());
            var m=decoded.manifest();assertEquals(2,m.formatVersion());assertEquals(STREAM_FORMAT,m.format());
            assertEquals(sourceBefore,m.snapshot());assertEquals(original.knowledgeFiles(),m.knowledgeFiles());
            assertEquals(m,plain.validate(decoded.backup(),true));assertThrows(IOException.class,()->plain.validate(decoded.backup()));
            assertFalse(Files.exists(export.resolve("bundle.zip")));assertFalse(Files.exists(export.resolve("bundle.zip.pending")));
            MaintenanceStaging.clean(export);assertClean(export);
            // Source collection v1 remains readable, without creating a plaintext ZIP.
            Path legacy=root.resolve("legacy");secure.encrypt(oldBackup,legacy,password.clone());
            Path legacyExport=root.resolve("legacy-export");assertEquals(original,secure.decrypt(legacy,legacyExport,password.clone()).manifest());
            MaintenanceStaging.clean(legacyExport);assertClean(legacyExport);
            Path missingConfig=root.resolve("must-not-be-read.json");
            reject(secure,encrypted,root.resolve("wrong-password"),randomPassword(),missingConfig,"AUTHENTICATED_DECRYPTION");
            for(String mode:List.of("middle","final-tag","truncated")) {
                Path altered=root.resolve(mode);cloneEncrypted(encrypted,altered);Path payload=altered.resolve(PAYLOAD);
                try(var channel=FileChannel.open(payload,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
                    if(mode.equals("truncated"))channel.truncate(channel.size()-1);
                    else {long offset=mode.equals("middle")?channel.size()/2:channel.size()-1;var b=ByteBuffer.allocate(1);channel.position(offset);channel.read(b);b.flip();b.put(0,(byte)(b.get(0)^1));channel.position(offset);channel.write(b);}
                    channel.force(true);
                }
                Files.write(altered.resolve(COMPLETE),JSON.writeValueAsBytes(new Completion(FORMAT,2,complete.status(),complete.profile(),PAYLOAD,Files.size(payload),digest(payload))));
                reject(secure,altered,root.resolve("reject-"+mode),password.clone(),missingConfig,"AUTHENTICATED_DECRYPTION");
            }
            for(String mode:List.of("traversal","expansion")) {
                Path hostile=root.resolve(mode);hostileAuthenticatedZip(hostile,password,mode.equals("expansion"));
                reject(secure,hostile,root.resolve("reject-"+mode),password.clone(),missingConfig,"INTERNAL_VALIDATION");
            }
            Path unknown=root.resolve("unknown-payload");cloneEncrypted(encrypted,unknown);directory(unknown.resolve("extra"));
            reject(secure,unknown,root.resolve("reject-unknown"),password.clone(),missingConfig,"CONTAINER_VALIDATION");
            var noTools=new PortableEncryptedBackup(root.resolve("absent-tools"),StagingProtection.SYNTHETIC_ONLY);
            assertThrows(IOException.class,()->noTools.backup(source,knowledge,root.resolve("bad-tools"),true,password.clone()));
            assertFalse(Files.exists(root.resolve("bad-tools/"+COMPLETE)));
            Path configFailure=root.resolve("config-failure");
            assertThrows(IOException.class,()->secure.restore(encrypted,configFailure,password.clone(),missingConfig,true));assertClean(configFailure);
            assertFalse(Files.exists(configFailure.resolve("candidate")));
            interrupted(secure,source,knowledge,root,password);
            Path restored=root.resolve("restore");
            var result=secure.restore(encrypted,restored,password.clone(),config,true);
            assertEquals(new Result("RESTORE_VERIFIED","IDENTITY_REBIND_REQUIRED","WINDOWS_ADMISSION_PENDING"),result);assertClean(restored);
            try(var db=owner.dataSource().getConnection()) {
                histories(db);
                assertEquals(sourceBefore.catalogSha256(),snapshot(db).catalogSha256());
                assertEquals("90071992547409.93",value(db,"SELECT balance::text FROM public.accounts WHERE name='B3 precision'"));
                assertEquals("12.34",value(db,"SELECT amount::text FROM public.transactions WHERE description='B3 synthetic transaction'"));
                assertEquals(2,scalar(db,"SELECT count(*) FROM public.users"));assertEquals(2,scalar(db,"SELECT count(*) FROM public.accounts"));
                assertEquals(1,scalar(db,"SELECT count(*) FROM workspace.sqlite_import"));assertEquals(1,scalar(db,"SELECT count(*) FROM workspace.knowledge_sqlite_import"));
            }
            try(var memories=new MemoryStore(app.dataSource(),restored.resolve("candidate"));var conversations=new ConversationStore(app.dataSource());
                var store=new KnowledgeStore(app.dataSource());var index=new KnowledgeLexicalIndex(store)) {
                var memory=memories.backupSnapshot().getFirst();assertNotNull(memories.get(memory.id()));
                var turn=conversations.detail(conversations.list(Conversation.Status.ACTIVE,0,10).items().getFirst().id(),0,10).turns().getFirst();
                assertEquals("B3 synthetic question",turn.userMessage().content());assertEquals("B3 synthetic answer",turn.assistantMessage().content());
                assertEquals(memory.id(),turn.memories().getFirst().memoryId());assertEquals(memory.revision(),turn.memories().getFirst().revision());
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!index.status().state().equals("READY")&&System.nanoTime()<deadline)Thread.sleep(20);
                assertEquals("READY",index.status().state());var hit=index.search("预算",10).hits().getFirst();
                assertTrue(store.preview(hit.documentId(),hit.sourceRevision(),0).text().contains("预算"));assertFalse(store.preview(hit.documentId(),hit.sourceRevision(),0).locators().isEmpty());
            }
            try(var walk=Files.walk(restored.resolve("candidate/knowledge"))){assertEquals(m.knowledgeFiles().size(),walk.filter(Files::isRegularFile).count());}
            Snapshot targetAfter;try(var db=owner.dataSource().getConnection()){targetAfter=snapshot(db);}
            Path duplicate=root.resolve("duplicate");assertThrows(IOException.class,()->secure.restore(encrypted,duplicate,password.clone(),config,true));assertClean(duplicate);
            assertFalse(Files.exists(duplicate.resolve("candidate")));try(var db=owner.dataSource().getConnection()){assertEquals(targetAfter,snapshot(db));}
            // Real pg_dump-only permission failure: large objects are outside application-table SELECT.
            // Created only in this run's synthetic source; retain it as failure evidence, no cleanup DDL.
            try(var db=sourceOwner.dataSource().getConnection()){sql(db,"SELECT lo_from_bytea(0,decode('423346','hex'))");}
            Path failedDump=root.resolve("pg-dump-failure");
            assertThrows(IOException.class,()->secure.backup(source,knowledge,failedDump,true,password.clone()));
            assertFalse(Files.exists(failedDump.resolve(COMPLETE)));assertTrue(Files.exists(failedDump.resolve("ENCRYPTED_BACKUP_INCOMPLETE")));
            try(var db=sourceOwner.dataSource().getConnection()){assertEquals(0,scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'"));}
            assertEquals(original,plain.validate(oldBackup));
            write(root.resolve("evidence.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                "directEncryptedBackup","VERIFIED_SYNTHETIC","authenticatedDecryption","VERIFIED","isolatedRestore",result,
                "sourceDatabase",source.database(),"targetDatabase",owner.database(),"knowledgeFiles",m.knowledgeFiles().size(),
                "plaintextZipCreated",false,"temporaryPlaintextCleaned",true,"volumeProtection","UNVERIFIED; synthetic only; default rejects",
                "productReadiness","PARTIAL; Windows/identity/B4 pending")));
            System.out.println("B3F acceptance evidence: "+root.resolve("evidence.json"));
        }finally{Arrays.fill(password,'\0');}
    }
    private static void assertClean(Path staging) {
        assertFalse(Files.exists(staging.resolve("backup")));assertFalse(Files.exists(staging.resolve("bundle.zip")));
        assertFalse(Files.exists(staging.resolve("bundle.zip.pending")));assertTrue(Files.exists(staging.resolve("PLAINTEXT_CLEANED")));
    }
    private static void reject(PortableEncryptedBackup secure,Path encrypted,Path staging,char[] password,Path config,String expected) throws Exception {
        var failure=assertThrows(IOException.class,()->secure.restore(encrypted,staging,password,config,true));assertTrue(failure.getMessage().contains(expected),failure::getMessage);
        assertFalse(Files.exists(staging.resolve("candidate")));assertFalse(Files.exists(staging.resolve("DECRYPTION_VERIFIED")));
        if(Files.exists(staging))assertClean(staging);
    }
    private static void interrupted(PortableEncryptedBackup secure,Endpoint source,Path knowledge,Path root,char[] password) throws Exception {
        Path output=root.resolve("interrupted");var failure=new AtomicReference<Throwable>();char[] secret=password.clone();
        var thread=new Thread(()->{try{secure.backup(source,knowledge,output,true,secret);}catch(Throwable e){failure.set(e);}},"b3f-cancellation");
        thread.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!Files.exists(output.resolve("backup.pgp.pending"))&&thread.isAlive()&&System.nanoTime()<deadline)Thread.sleep(1);
        thread.interrupt();thread.join(15000);assertFalse(thread.isAlive());assertNotNull(failure.get());
        for(char value:secret)assertEquals('\0',value);assertFalse(Files.exists(output.resolve(COMPLETE)));
    }
}
