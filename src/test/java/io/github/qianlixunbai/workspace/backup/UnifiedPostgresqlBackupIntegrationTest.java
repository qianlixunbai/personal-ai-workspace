package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.knowledge.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.conversation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;

/** One real archive/restore acceptance flow. Source is read-only; all fault injection is isolated. */
@EnabledIfSystemProperty(named="unified.backup.test",matches="true")
class UnifiedPostgresqlBackupIntegrationTest {
    @Test void completionMarkerRequiresDurableStagingAndNeverOverwrites() throws Exception {
        Path root=Path.of(".runtime/workspace/b3-publication-"+UUID.randomUUID()).toAbsolutePath();directory(root);
        Path finalMarker=root.resolve("BACKUP_COMPLETE"),pending=root.resolve("BACKUP_COMPLETE.pending");
        directory(pending); // Real staging failure, without a fake filesystem or production hook.
        assertThrows(IOException.class,()->publish(finalMarker,new byte[]{1}));assertFalse(Files.exists(finalMarker));
        Files.delete(pending); // This test's own empty directory only.
        publish(finalMarker,new byte[]{1,2,3});assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(finalMarker));assertFalse(Files.exists(pending));
        assertThrows(IOException.class,()->publish(finalMarker,new byte[]{4}));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(finalMarker));
    }
    @Test void archiveRestoreStoresCorruptionAndInterruptedCandidate() throws Exception {
        Path credentials=Path.of(".runtime/workspace/b3-test-credentials.json");privatePath(credentials,false);require(Files.size(credentials)<16384);
        var c=JSON.readTree(Files.readAllBytes(credentials));
        var baseSource=new Endpoint(c.path("sourceDatabase").asString(),c.path("sourceUsername").asString(),c.path("sourcePassword").asString());
        var owner=new Endpoint(c.path("targetDatabase").asString(),c.path("ownerUsername").asString(),c.path("ownerPassword").asString());
        var app=new Endpoint(owner.database(),c.path("appUsername").asString(),c.path("appPassword").asString());
        var interrupted=new Endpoint(c.path("interruptedDatabase").asString(),owner.username(),owner.password());
        var interruptedApp=new Endpoint(interrupted.database(),app.username(),app.password());
        Path root=Path.of(c.path("maintenanceRoot").asString()),baseKnowledge=Path.of(c.path("knowledgeRoot").asString());
        String receipt=c.path("ownershipReceipt").asString();var maintenance=new UnifiedPostgresqlBackup(Path.of(c.path("tools").asString()));
        Snapshot baseBefore;try(var db=baseSource.dataSource().getConnection()){baseBefore=snapshot(db);}
        var prepared=c.has("seedDatabase")?seedSource(c,maintenance,baseSource,owner,app,receipt,root):new Prepared(baseSource,baseKnowledge);
        var source=prepared.source();Path knowledge=prepared.knowledge();c=null;
        Snapshot sourceBefore;try(var db=source.dataSource().getConnection()){sourceBefore=snapshot(db);}
        Path backup=root.resolve("backup"),candidate=root.resolve("restored-"+owner.database()),failed=root.resolve("interrupted-"+interrupted.database());
        boolean backupExecutedThisRun=!Files.exists(backup),restoreExecutedThisRun=!Files.exists(candidate);
        assertThrows(IOException.class,()->maintenance.backup(source,knowledge,backup,false));
        var manifest=backupExecutedThisRun?maintenance.backup(source,knowledge,backup,true):maintenance.validate(backup);
        assertEquals("BACKUP_COMPLETE",manifest.status());assertEquals(sourceBefore,manifest.snapshot());
        assertTrue(manifest.archive().bytes()>0);assertFalse(manifest.knowledgeFiles().isEmpty());assertEquals(manifest,maintenance.validate(backup));
        assertThrows(IOException.class,()->maintenance.backup(source,knowledge,backup,true));
        checkDamagedBackups(maintenance,backup,manifest,owner,app,receipt,root);
        var restoredResult=restoreExecutedThisRun?maintenance.restore(backup,owner,app,receipt,candidate):
            JSON.readValue(Files.readAllBytes(candidate.resolve("RESTORE_VERIFIED.json")),Result.class);
        assertEquals(new Result("RESTORE_VERIFIED","IDENTITY_REBIND_REQUIRED","WINDOWS_ADMISSION_PENDING"),restoredResult);
        Snapshot restored;try(var db=owner.dataSource().getConnection()){
            restored=snapshot(db);
            // Canonicalization must still distinguish a materially changed constraint expression.
            db.setAutoCommit(false);
            try {
                sql(db,"ALTER TABLE public.accounts DROP CONSTRAINT accounts_type_check");
                sql(db,"ALTER TABLE public.accounts ADD CONSTRAINT accounts_type_check CHECK (type <> 'FORBIDDEN_B3')");
                assertNotEquals(restored.catalogSha256(),snapshot(db).catalogSha256());
            }finally{db.rollback();db.setAutoCommit(true);}
        }
        assertThrows(IOException.class,()->maintenance.restore(backup,owner,app,receipt,root.resolve("duplicate")));
        assertFalse(Files.exists(root.resolve("duplicate")));
        try(var db=owner.dataSource().getConnection()){assertEquals(restored,snapshot(db));}
        try(var db=app.dataSource().getConnection()) {
            assertEquals(0,scalar(db,"SELECT has_schema_privilege(current_user,'workspace','CREATE')::int"));
            var denied=assertThrows(SQLException.class,()->scalar(db,"SELECT has_table_privilege(current_user,'public.users','SELECT')::int"));
            assertEquals("42501",denied.getSQLState()); // public schema itself is inaccessible.
            assertEquals(0,scalar(db,"SELECT has_table_privilege(current_user,'workspace.sqlite_import','INSERT')::int"));
            assertEquals(0,scalar(db,"SELECT has_table_privilege(current_user,'workspace.memory_items','UPDATE')::int"));
            assertEquals(1,scalar(db,"SELECT count(*) FROM workspace.sqlite_import"));
            assertEquals(1,scalar(db,"SELECT count(*) FROM workspace.knowledge_sqlite_import"));
        }
        try(var db=owner.dataSource().getConnection()) {
            assertTrue(scalar(db,"SELECT count(*) FROM public.users")>=2);
            assertEquals("90071992547409.93",value(db,"SELECT balance::text FROM public.accounts WHERE name='B3 precision'"));
            assertEquals("12.34",value(db,"SELECT amount::text FROM public.transactions WHERE description='B3 synthetic transaction'"));
            assertEquals(1,scalar(db,"SELECT count(*) FROM public.transactions t JOIN public.accounts a ON a.id=t.account_id JOIN public.users u ON u.id=t.user_id WHERE a.user_id=u.id AND t.description='B3 synthetic transaction'"));
        }
        try(var memory=new MemoryStore(app.dataSource(),candidate);var conversations=new ConversationStore(app.dataSource())) {
            var reference=memory.backupSnapshot().getFirst();var page=conversations.list(Conversation.Status.ACTIVE,0,ConversationLimits.MAX_LIMIT);assertFalse(page.items().isEmpty());
            var detail=conversations.detail(page.items().getFirst().id(),0,ConversationLimits.MAX_LIMIT);assertEquals(1,detail.totalTurns());
            var turn=detail.turns().getFirst();assertEquals("B3 synthetic question",turn.userMessage().content());assertEquals("B3 synthetic answer",turn.assistantMessage().content());
            assertEquals(reference.id(),turn.memories().getFirst().memoryId());assertEquals(reference.revision(),turn.memories().getFirst().revision());
        }
        // Exercise actual retrieval and stale-index rejection after successful file/Store verification.
        try(var store=new KnowledgeStore(app.dataSource());var index=new KnowledgeLexicalIndex(store)) {
            ready(index);var hits=index.search("预算",10).hits();assertFalse(hits.isEmpty());
            var hit=hits.getFirst();assertTrue(store.preview(hit.documentId(),hit.sourceRevision(),0).text().contains("预算"));
            assertFalse(store.preview(hit.documentId(),hit.sourceRevision(),0).locators().isEmpty());
            try(var db=owner.dataSource().getConnection()){sql(db,"UPDATE workspace.knowledge_index_metadata SET v='damaged' WHERE k='fingerprint'");}
            assertThrows(io.github.qianlixunbai.workspace.common.WorkspaceException.class,()->index.search("预算",10));
            index.rebuild();ready(index);assertEquals(hit.documentId(),index.search("预算",10).hits().getFirst().documentId());
        }
        // A real filesystem collision after target admission leaves a restored DB but no verified root.
        // No production test hook: another actor creates a conflicting regular file in our private candidate.
        var executor=Executors.newSingleThreadExecutor();
        try {
            var collision=executor.submit(()->{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!Files.exists(failed.resolve("RESTORE_INCOMPLETE"))&&System.nanoTime()<deadline)Thread.sleep(2);
                write(failed.resolve("knowledge"),"synthetic interruption".getBytes(StandardCharsets.UTF_8));return true;
            });
            var failure=assertThrows(IOException.class,()->maintenance.restore(backup,interrupted,interruptedApp,receipt,failed));
            assertTrue(collision.get(35,TimeUnit.SECONDS));assertTrue(failure.getMessage().contains("KNOWLEDGE_RESTORE"),failure::getMessage);
            assertTrue(Files.exists(failed.resolve("RESTORE_INCOMPLETE")));assertFalse(Files.exists(failed.resolve("RESTORE_VERIFIED.json")));
            try(var db=interrupted.dataSource().getConnection()){histories(db);assertEquals(manifest.snapshot().tables(),snapshot(db).tables());}
            assertThrows(IOException.class,()->maintenance.restore(backup,interrupted,interruptedApp,receipt,root.resolve("retry")));
        }finally{executor.shutdownNow();}
        try(var db=source.dataSource().getConnection()){assertEquals(sourceBefore,snapshot(db));}
        try(var db=baseSource.dataSource().getConnection()){assertEquals(baseBefore,snapshot(db));}
        // All source file digests/metadata still match. No source index rebuilding or truth writes occurred.
        try(var db=source.dataSource().getConnection()){verifyKnowledge(db,knowledge,manifest.knowledgeFiles());}
        Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("status","DATABASE AND SYNTHETIC FILE RESTORE VERIFIED; OVERALL PARTIAL");
        evidence.put("backupExecutedThisRun",backupExecutedThisRun);evidence.put("successfulRestoreExecutedThisRun",restoreExecutedThisRun);
        evidence.put("sourceDatabase",source.database());evidence.put("targetDatabase",owner.database());evidence.put("interruptedDatabase",interrupted.database());
        evidence.put("originalTestDatabaseUnchanged",true);evidence.put("nonemptyFinancePrecisionTransactionAndConversationRelations",true);
        evidence.put("postgresqlVersion",manifest.serverVersion());evidence.put("pgDump",manifest.dumpVersion());evidence.put("pgRestore",manifest.restoreVersion());
        evidence.put("tables",manifest.snapshot().tables());evidence.put("financeHistory",manifest.financeVersions());evidence.put("workspaceHistory",manifest.workspaceVersions());
        evidence.put("knowledgeFiles",manifest.knowledgeFiles().size());evidence.put("sourceRowsCatalogSequencesAndFilesUnchanged",true);
        evidence.put("realStoresPreviewQueryIndexRebuild",true);evidence.put("corruptionMissingDigestDuplicateTraversalIncompleteRejected",true);
        evidence.put("duplicateRestoreRefused",true);evidence.put("interruptedRestoreNeverPublished",true);
        evidence.put("identity","IDENTITY_REBIND_REQUIRED");evidence.put("windows","WINDOWS_ADMISSION_PENDING");evidence.put("encryption","NOT IMPLEMENTED; SYNTHETIC ONLY");
        write(root.resolve("evidence.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
        System.out.println("B3: real PostgreSQL archive/isolated restore + synthetic Knowledge files + real Stores/index VERIFIED; encryption/Windows/identity PENDING");
    }
    private record Prepared(Endpoint source,Path knowledge) {}
    private static Prepared seedSource(tools.jackson.databind.JsonNode config,UnifiedPostgresqlBackup maintenance,Endpoint base,Endpoint owner,Endpoint app,String receipt,Path root) throws Exception {
        String database=config.path("seedDatabase").asString();var seedOwner=new Endpoint(database,owner.username(),owner.password());var seedApp=new Endpoint(database,app.username(),app.password());
        Path candidate=root.resolve("seed"),marker=root.resolve("SYNTHETIC_SEED_COMPLETE");
        if(!Files.exists(marker)) {
            maintenance.restore(Path.of(config.path("bootstrapBackup").asString()),seedOwner,seedApp,receipt,candidate);
            Files.move(candidate.resolve("RESTORE_VERIFIED.json"),candidate.resolve("BASE_RESTORE_VERIFIED.json"));
            try(var db=seedOwner.dataSource().getConnection()) {
                sql(db,"GRANT INSERT,UPDATE ON workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections,workspace.write_lock TO "+app.username());
                db.setAutoCommit(false);
                try {
                    long first=scalar(db,"INSERT INTO public.users(username,email,password_hash) VALUES('b3_first','b3_first@example.invalid','!not-a-login-hash!') RETURNING id");
                    long second=scalar(db,"INSERT INTO public.users(username,email,password_hash) VALUES('b3_second','b3_second@example.invalid','!not-a-login-hash!') RETURNING id");
                    long account=scalar(db,"INSERT INTO public.accounts(user_id,name,type,currency,balance) VALUES("+first+",'B3 precision','CASH','CNY',90071992547409.93) RETURNING id");
                    sql(db,"INSERT INTO public.accounts(user_id,name,type,currency,balance) VALUES("+second+",'B3 second user','BANK','CNY',-456.78)");
                    long category=scalar(db,"INSERT INTO public.categories(user_id,name,type) VALUES("+first+",'B3 income','INCOME') RETURNING id");
                    sql(db,"INSERT INTO public.transactions(user_id,account_id,category_id,type,amount,currency,description,transacted_at) VALUES("+first+","+account+","+category+",'INCOME',12.34,'CNY','B3 synthetic transaction','2026-10-17 10:00:00')");
                    db.commit();
                }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
            }
            try(var memory=new MemoryStore(seedApp.dataSource(),candidate);var conversations=new ConversationStore(seedApp.dataSource())) {
                var reference=memory.backupSnapshot().getFirst();var conversation=conversations.create("B3 synthetic conversation");
                var turn=conversations.createTurnWithUserMessage(conversation.id(),"B3 synthetic question",null,List.of(new MemoryReference(reference.id(),reference.revision())));
                conversations.completeTurnWithAssistantMessage(conversation.id(),turn.id(),"B3 synthetic answer");
            }
            try(var db=seedOwner.dataSource().getConnection()) {
                sql(db,"REVOKE INSERT,UPDATE ON workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections,workspace.write_lock FROM "+app.username());
                sql(db,"GRANT USAGE ON SCHEMA public,workspace TO "+base.username());
                sql(db,"GRANT SELECT,UPDATE ON ALL TABLES IN SCHEMA public,workspace TO "+base.username());
                sql(db,"GRANT SELECT ON ALL SEQUENCES IN SCHEMA public,workspace TO "+base.username());
                sql(db,"GRANT CONNECT ON DATABASE "+database+" TO "+base.username());
            }
            // The initial restore marker describes the unseeded clone; never leave it as a candidate.
            write(marker,"Synthetic source only; never activate as Runtime data".getBytes(StandardCharsets.UTF_8));
        }
        return new Prepared(new Endpoint(database,base.username(),base.password()),candidate.resolve("knowledge"));
    }
    private static void checkDamagedBackups(UnifiedPostgresqlBackup maintenance,Path backup,Manifest manifest,Endpoint owner,Endpoint app,String receipt,Path root) throws Exception {
        Path first=backup.resolve("knowledge").resolve(manifest.knowledgeFiles().getFirst().path());byte[] original=Files.readAllBytes(first);
        Files.delete(first);
        assertThrows(IOException.class,()->maintenance.restore(backup,owner,app,receipt,root.resolve("missing")));assertFalse(Files.exists(root.resolve("missing")));
        write(first,original);
        byte[] corrupt=original.clone();corrupt[0]^=1;Files.write(first,corrupt);
        assertThrows(IOException.class,()->maintenance.validate(backup));Files.write(first,original);
        Path archive=backup.resolve("database.dump");byte[] bytes=Files.readAllBytes(archive);byte[] changed=bytes.clone();changed[changed.length/2]^=1;
        Files.write(archive,changed);assertThrows(IOException.class,()->maintenance.validate(backup));Files.write(archive,bytes);
        Path metadata=backup.resolve("manifest.json"),complete=backup.resolve("BACKUP_COMPLETE");byte[] originalManifest=Files.readAllBytes(metadata),originalMarker=Files.readAllBytes(complete);
        try {
            var node=(ObjectNode)JSON.readTree(originalManifest);var list=node.withArray("knowledgeFiles");list.add(list.get(0).deepCopy());rewrite(backup,node);
            assertThrows(IOException.class,()->maintenance.validate(backup));
            node=(ObjectNode)JSON.readTree(originalManifest);((ObjectNode)node.withArray("knowledgeFiles").get(0)).put("path","sources/../../outside.source");rewrite(backup,node);
            assertThrows(IOException.class,()->maintenance.validate(backup));
            node=(ObjectNode)JSON.readTree(originalManifest);((ObjectNode)node.withArray("knowledgeFiles").get(0)).put("sha256","0".repeat(64));rewrite(backup,node);
            assertThrows(IOException.class,()->maintenance.validate(backup));
            Files.write(metadata,originalManifest);Files.delete(complete);assertThrows(IOException.class,()->maintenance.validate(backup));
        }finally{Files.write(metadata,originalManifest);Files.write(complete,originalMarker,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);}
        assertEquals(manifest,maintenance.validate(backup));
    }
    private static void rewrite(Path backup,ObjectNode node) throws Exception {
        Files.write(backup.resolve("manifest.json"),JSON.writeValueAsBytes(node));
        Files.writeString(backup.resolve("BACKUP_COMPLETE"),digest(backup.resolve("manifest.json")));
    }
    private static void ready(KnowledgeLexicalIndex index) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!index.status().state().equals("READY")&&System.nanoTime()<deadline)Thread.sleep(20);
        assertEquals("READY",index.status().state());
    }
}
