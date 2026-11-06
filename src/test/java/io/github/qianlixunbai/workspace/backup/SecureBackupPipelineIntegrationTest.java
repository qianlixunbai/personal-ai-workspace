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
    /** Explicit fresh fixture only: never reads the retained B3/B3F credentials or existing service. */
    @Test void workspaceV3ExcludesFinanceAndRebuildsFromImmutableHistory() throws Exception {
        Path config=Path.of(System.getProperty("recovery.v3.fixture"));privatePath(config,false);
        var c=JSON.readTree(Files.readAllBytes(config));Path root=Path.of(c.path("root").asString());privatePath(root,true);
        int port=c.path("port").asInt();assertNotEquals(5432,port);
        var source=new Endpoint(c.path("sourceDatabase").asString(),c.path("sourceUsername").asString(),c.path("sourcePassword").asString(),port);
        var owner=new Endpoint(c.path("targetDatabase").asString(),c.path("ownerUsername").asString(),c.path("ownerPassword").asString(),port);
        var sourceOwner=new Endpoint(source.database(),owner.username(),owner.password(),port);
        var app=new Endpoint(owner.database(),c.path("appUsername").asString(),c.path("appPassword").asString(),port);
        var failedOwner=new Endpoint(c.path("failedDatabase").asString(),owner.username(),owner.password(),port);
        var failedApp=new Endpoint(failedOwner.database(),app.username(),app.password(),port);String receipt=c.path("ownershipReceipt").asString();
        Path pid=root.resolve("pgdata/postmaster.pid");privatePath(pid,false);
        var lines=Files.readAllLines(pid);assertEquals(root.resolve("pgdata").toString().replace('\\','/'),lines.get(1));assertEquals(Integer.toString(port),lines.get(3));
        try(var db=sourceOwner.dataSource().getConnection()){
            assertEquals(c.path("ownershipReceipt").asString(),value(db,"SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()"));
            WorkspaceV3Catalog.verify(db);assertEquals(0,scalar(db,"SELECT count(*) FROM workspace.memory_items"));assertEquals(0,scalar(db,"SELECT count(*) FROM workspace.knowledge_documents"));
        }
        var secure=new PortableEncryptedBackup(Path.of(c.path("tools").asString()),StagingProtection.SYNTHETIC_ONLY);c=null;
        Path attempt=root.resolve("v3-"+UUID.randomUUID());directory(attempt);MaintenanceStaging.admit(attempt.resolve("admission"),true);
        Path knowledge=attempt.resolve("knowledge");directory(knowledge);directory(knowledge.resolve("sources"));
        String memory=UUID.randomUUID().toString(),conversation=UUID.randomUUID().toString(),doc=UUID.randomUUID().toString();
        seedWorkspaceV3(sourceOwner,knowledge,memory,conversation,doc);
        Snapshot before;try(var db=source.dataSource().getConnection()){before=snapshot(db,true);assertEquals(0,scalar(db,"SELECT has_schema_privilege(current_user,'public','USAGE')::int"));}
        char[] password=randomPassword();Path encrypted=attempt.resolve("encrypted");
        try {
            var origin=secure.backupWorkspace(source,knowledge,encrypted,true,password.clone());assertEquals(3,origin.completion().version());
            var manifest=origin.manifest();assertEquals(WorkspaceV3Catalog.SCOPE,manifest.scope());assertEquals(List.of("workspace"),manifest.schemas());assertTrue(manifest.financeVersions().isEmpty());
            assertEquals(WorkspaceV3Catalog.EXCLUDED,manifest.dataExcludedTables());assertEquals(before,manifest.snapshot());assertEquals(10,manifest.knowledgeFiles().size());
            try(var walk=Files.walk(encrypted)){assertTrue(walk.noneMatch(p->p.toString().endsWith(".dump")||p.toString().endsWith(".source")||p.toString().endsWith(".zip")));}
            Path decoded=attempt.resolve("decoded");var clear=secure.decryptWorkspace(origin,decoded,password.clone());assertEquals(manifest,clear.manifest());
            // Decryption validated real pg_restore --list against the frozen trusted TOC.
            assertThrows(IOException.class,()->new UnifiedPostgresqlBackup(Path.of("D:/PostgreSQL/bin")).validate(clear.backup()));
            var plain=new UnifiedPostgresqlBackup(Path.of("D:/PostgreSQL/bin"));
            try(var db=failedOwner.dataSource().getConnection()){sql(db,"CREATE TYPE public.recovery_leftover AS ENUM ('fixture')");}
            assertThrows(IOException.class,()->plain.restoreWorkspace(clear.backup(),failedOwner,failedApp,receipt,attempt.resolve("enum-target"),origin));
            assertFalse(Files.exists(attempt.resolve("enum-target")));
            try(var db=failedOwner.dataSource().getConnection()){
                assertEquals(receipt,value(db,"SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()"));
                sql(db,"DROP TYPE public.recovery_leftover"); // Only the exact enum created by this fixture.
            }
            // Deterministic filesystem failure AFTER durable claim, BEFORE SQL: target remains empty.
            assertThrows(IOException.class,()->plain.restoreWorkspace(clear.backup(),failedOwner,failedApp,receipt,attempt.resolve("x".repeat(300)),origin));
            try(var db=failedOwner.dataSource().getConnection()){
                assertEquals(0,scalar(db,"SELECT count(*) FROM pg_namespace WHERE nspname='workspace'"));
                assertTrue(value(db,"SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()").startsWith(receipt+":restore-attempted:"));
            }
            assertThrows(IOException.class,()->plain.restoreWorkspace(clear.backup(),failedOwner,failedApp,receipt,attempt.resolve("unknown-retry"),origin));
            assertFalse(Files.exists(attempt.resolve("unknown-retry")));
            MaintenanceStaging.clean(decoded);assertClean(decoded);
            Path missingConfig=attempt.resolve("must-not-read.json");
            assertThrows(IOException.class,()->secure.restoreWorkspace(null,attempt.resolve("no-origin"),password.clone(),missingConfig));
            reject(secure,encrypted,attempt.resolve("public-v3"),password.clone(),missingConfig,"SQL_SOURCE_TRUST");
            var wrong=assertThrows(IOException.class,()->secure.restoreWorkspace(origin,attempt.resolve("wrong-pass"),randomPassword(),missingConfig));assertTrue(wrong.getMessage().contains("AUTHENTICATED_DECRYPTION"));
            // Outer/literal version mismatch and unknown versions remain fail closed before target config.
            Path mismatch=attempt.resolve("version-mismatch");cloneEncrypted(encrypted,mismatch);
            Files.write(mismatch.resolve(COMPLETE),JSON.writeValueAsBytes(new Completion(FORMAT,2,origin.completion().status(),origin.completion().profile(),PAYLOAD,origin.completion().bytes(),origin.completion().sha256())));
            reject(secure,mismatch,attempt.resolve("reject-mismatch"),password.clone(),missingConfig,"AUTHENTICATED_DECRYPTION");
            Files.write(mismatch.resolve(COMPLETE),JSON.writeValueAsBytes(new Completion(FORMAT,4,origin.completion().status(),origin.completion().profile(),PAYLOAD,origin.completion().bytes(),origin.completion().sha256())));
            reject(secure,mismatch,attempt.resolve("reject-version"),password.clone(),missingConfig,"CONTAINER_VALIDATION");
            // Reuse existing v1 authenticated hostile-input regression without any old database fixture.
            Path hostile=attempt.resolve("legacy-hostile");hostileAuthenticatedZip(hostile,password,false);
            reject(secure,hostile,attempt.resolve("reject-legacy"),password.clone(),missingConfig,"INTERNAL_VALIDATION");
            try(var db=sourceOwner.dataSource().getConnection()){
                db.setAutoCommit(false);
                try{sql(db,"CREATE TABLE workspace.unreviewed(value text)");assertThrows(Exception.class,()->WorkspaceV3Catalog.verify(db));}finally{db.rollback();}
                try{sql(db,"INSERT INTO workspace.conversation_turns VALUES('"+UUID.randomUUID()+"','"+conversation+"',5,'PENDING',1000,1000,NULL,NULL)");assertThrows(Exception.class,()->safeJobs(db));}finally{db.rollback();}
                try{sql(db,"INSERT INTO workspace.knowledge_jobs VALUES('"+UUID.randomUUID()+"','"+doc+"','PARSING',NULL,3,'pending.txt','TXT',4,4,'2026-01-01T00:00:00Z')");assertThrows(Exception.class,()->safeJobs(db));}finally{db.rollback();}
                try{sql(db,"INSERT INTO workspace.knowledge_deletes VALUES('"+doc+"','"+UUID.randomUUID()+"','1,2',4)");assertThrows(Exception.class,()->safeJobs(db));}finally{db.rollback();}
            }
            Path oldSource=knowledge.resolve("sources/"+doc+"/1.source");byte[] original=Files.readAllBytes(oldSource);
            try{Files.writeString(oldSource,"bad source");assertThrows(IOException.class,()->secure.backupWorkspace(source,knowledge,attempt.resolve("bad-source"),true,password.clone()));assertFalse(Files.exists(attempt.resolve("bad-source/"+COMPLETE)));}
            finally{Files.write(oldSource,original);}
            Path restored=attempt.resolve("restore");assertEquals(new Result("RESTORE_VERIFIED","IDENTITY_REBIND_REQUIRED","WINDOWS_ADMISSION_PENDING"),secure.restoreWorkspace(origin,restored,password.clone(),config));assertClean(restored);
            try(var db=owner.dataSource().getConnection()){
                assertEquals(before,snapshot(db,true));WorkspaceV3Catalog.emptyExcluded(db,true);
                assertEquals(0,scalar(db,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'"));
                assertEquals(4,scalar(db,"SELECT count(*) FROM workspace.conversation_turns WHERE task_id IS NOT NULL"));assertEquals(0,scalar(db,"SELECT count(*) FROM workspace.conversation_turns WHERE status='PENDING'"));
                assertEquals(3,scalar(db,"SELECT count(*) FROM workspace.knowledge_jobs WHERE state='READY'"));assertEquals(1,scalar(db,"SELECT count(*) FROM workspace.knowledge_jobs WHERE state='FAILED' AND revision=11"));assertTrue(scalar(db,"SELECT count(*) FROM workspace.knowledge_chunks")>0);
            }
            try(var memories=new MemoryStore(app.dataSource(),restored.resolve("candidate"));var conversations=new ConversationStore(app.dataSource());var store=new KnowledgeStore(app.dataSource());var index=new KnowledgeLexicalIndex(store)){
                assertEquals(2,memories.get(UUID.fromString(memory)).revision());var turns=conversations.detail(UUID.fromString(conversation),0,10).turns();assertEquals(4,turns.size());
                assertEquals(1,turns.getFirst().memories().getFirst().revision());assertEquals(2,turns.getFirst().memories().size());
                assertEquals("historical budget\n",store.preview(doc,"1",0).text());assertEquals("current budget\n",store.preview(doc,"10",0).text());assertEquals("FAILED",store.detail(doc).job().state());
                assertEquals("READY",index.status().state());assertEquals("10",index.search("budget",10).hits().getFirst().sourceRevision());
            }
            for(var file:manifest.knowledgeFiles())assertEquals(file.sha256(),digest(restored.resolve("candidate/knowledge").resolve(file.path())));
            assertThrows(IOException.class,()->secure.restoreWorkspace(origin,attempt.resolve("duplicate"),password.clone(),config));assertFalse(Files.exists(attempt.resolve("duplicate/candidate/RESTORE_VERIFIED.json")));
            try(var db=source.dataSource().getConnection()){assertEquals(before,snapshot(db,true));verifyKnowledge(db,knowledge,manifest.knowledgeFiles());}
            try(var db=sourceOwner.dataSource().getConnection()){assertEquals("OUTSIDE_WORKSPACE",value(db,"SELECT secret FROM public.finance_sentinel"));}
            System.out.println("Recovery v3: synthetic PG17 nondefault-port archive/AEAD/restore, Finance exclusion, immutable history, index rebuild, no-overwrite and source preservation verified; personal recovery unavailable");
        }finally{Arrays.fill(password,'\0');}
    }
    private static void seedWorkspaceV3(Endpoint owner,Path knowledge,String memory,String conversation,String doc)throws Exception {
        directory(knowledge.resolve("sources/"+doc));String job1=UUID.randomUUID().toString(),job2=UUID.randomUUID().toString(),overflow=UUID.randomUUID().toString();
        try(var db=owner.dataSource().getConnection()){
            sql(db,"CREATE TABLE public.finance_sentinel(secret text NOT NULL)");sql(db,"INSERT INTO public.finance_sentinel VALUES('OUTSIDE_WORKSPACE')");
            sql(db,"INSERT INTO workspace.memory_items VALUES('"+memory+"','PROJECT_NOTE','Synthetic','Current memory','ACTIVE',2,'MANUAL',1000,2000)");
            sql(db,"INSERT INTO workspace.conversations VALUES('"+conversation+"','Synthetic conversation','ACTIVE',1000,2000)");
            int sequence=0;for(String state:List.of("SUCCEEDED","FAILED","CANCELLED","TIMED_OUT")){
                String turn=UUID.randomUUID().toString();sequence++;
                sql(db,"INSERT INTO workspace.conversation_turns VALUES('"+turn+"','"+conversation+"',"+sequence+",'"+state+"',1000,2000,'"+UUID.randomUUID()+"',"+(state.equals("FAILED")?"'EXECUTION_FAILED'":"NULL")+")");
                sql(db,"INSERT INTO workspace.conversation_messages VALUES('"+UUID.randomUUID()+"','"+turn+"','USER','synthetic question',1000)");
                if(state.equals("SUCCEEDED"))sql(db,"INSERT INTO workspace.conversation_messages VALUES('"+UUID.randomUUID()+"','"+turn+"','ASSISTANT','synthetic answer',2000)");
                sql(db,"INSERT INTO workspace.conversation_memory_selections VALUES('"+turn+"',0,'"+memory+"',1),('"+turn+"',1,'"+UUID.randomUUID()+"',3)");
            }
            sql(db,"INSERT INTO workspace.sqlite_import VALUES(1,'"+"a".repeat(64)+"',1000)");sql(db,"INSERT INTO workspace.knowledge_sqlite_import VALUES(1,'"+"b".repeat(64)+"',1000)");
            sql(db,"INSERT INTO workspace.knowledge_index_metadata VALUES('fingerprint','untrusted-old-ready')");
            db.setAutoCommit(false);
            sql(db,"INSERT INTO workspace.knowledge_documents VALUES('"+doc+"','budget.txt','ACTIVE',11,10,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','"+overflow+"')");
            for(int revision=1;revision<=10;revision++){
                Path file=knowledge.resolve("sources/"+doc+"/"+revision+".source");write(file,((revision==1?"historical":revision==10?"current":"intermediate "+revision)+" budget\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var parsed=KnowledgeParser.parse(file,"TXT",()->false);
                try(var statement=db.prepareStatement("INSERT INTO workspace.knowledge_revisions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
                    Object[] values={doc,revision,digest(file),"budget.txt","TXT",Files.size(file),"2026-01-01T00:00:00Z",KnowledgeParser.PARSER_VERSION,KnowledgeParser.NORMALIZATION_VERSION,parsed.digest(),parsed.text(),parsed.locatorJson(),parsed.lineCount(),(long)parsed.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+parsed.locatorJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length};
                    // Fourteen reviewed columns; parameter count follows the V2 DDL.
                    for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);statement.executeUpdate();
                }
                if(revision==1||revision==10)sql(db,"INSERT INTO workspace.knowledge_jobs VALUES('"+(revision==1?job1:job2)+"','"+doc+"','READY',NULL,"+revision+",'budget.txt','TXT',"+Files.size(file)+","+revision+",'2026-01-01T00:00:00Z')");
            }
            sql(db,"INSERT INTO workspace.knowledge_jobs VALUES('"+UUID.randomUUID()+"','"+doc+"','READY',NULL,1,'renamed.md','MARKDOWN',"+Files.size(knowledge.resolve("sources/"+doc+"/1.source"))+",11,'2026-01-01T00:00:00Z')");
            sql(db,"INSERT INTO workspace.knowledge_jobs VALUES('"+overflow+"','"+doc+"','FAILED','KNOWLEDGE_LIMIT_EXCEEDED',11,'overflow.txt','TXT',4,11,'2026-01-01T00:00:00Z')");
            db.commit();
        }
    }
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
