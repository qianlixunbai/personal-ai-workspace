package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlite.SQLiteConnection;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Knowledge only. V1 source/data and Finance catalog are compared, never reset or initialized. */
@EnabledIfSystemProperty(named="knowledge.postgresql.test",matches="true")
class KnowledgePostgresqlIntegrationTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Path OUTPUT=Path.of(".verification/f2.3-b2-knowledge-postgresql");
    private static final String APP="personal_ai_workspace_b1_app",MIG="personal_ai_workspace_b1_migrator";
    @Test void migrateTruthAndFilesThenRebuildQueryAndExerciseStoreAndConcurrency()throws Exception{
        Path credentials=Path.of(".runtime/workspace/b1-test-credentials.json");verifyCredential(credentials);
        var config=JSON.readTree(Files.readAllBytes(credentials));
        assertEquals("jdbc:postgresql://127.0.0.1:5432/personal_ai_workspace_test",config.path("url").asString());
        assertEquals(APP,config.path("appUsername").asString());assertEquals(MIG,config.path("migrationUsername").asString());
        var owner=data(MIG,config.path("migrationPassword").asString());var app=data(APP,config.path("appPassword").asString());config=null;
        String protectedBefore;try(var db=app.getConnection()){verifyTarget(db,APP);}
        try(var db=owner.getConnection()){verifyTarget(db,MIG);protectedBefore=protectedState(db);}
        var flyway=Flyway.configure().dataSource(owner).schemas("workspace").defaultSchema("workspace").createSchemas(false)
            .locations("classpath:db/workspace/migration").cleanDisabled(true).load();
        var migrated=flyway.migrate();assertTrue(migrated.migrationsExecuted<=1);flyway.validate();
        try(var db=owner.getConnection()){
            assertEquals(2,count(db,"SELECT count(*) FROM workspace.flyway_schema_history WHERE success"));
            sql(db,"GRANT SELECT,UPDATE ON workspace.knowledge_control TO "+APP);
            sql(db,"GRANT SELECT,INSERT,UPDATE,DELETE ON workspace.knowledge_documents,workspace.knowledge_revisions,workspace.knowledge_jobs,workspace.knowledge_deletes,workspace.knowledge_index_metadata,workspace.knowledge_chunks TO "+APP);
            sql(db,"GRANT SELECT,INSERT ON workspace.knowledge_sqlite_import TO "+APP);
        }
        Files.createDirectories(OUTPUT);Path root=OUTPUT.resolve("synthetic-knowledge"),source=root.resolve("knowledge.db"),backup=root.resolve("knowledge-backup.db");
        List<String> ids;
        if(!Files.exists(source)){
            Files.createDirectory(root);
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+source)){ids=KnowledgePostgresqlFixture.create(db,root);}
            Files.writeString(root.resolve("fixture-ids.json"),JSON.writeValueAsString(ids),StandardOpenOption.CREATE_NEW);
            try(var freeze=DriverManager.getConnection("jdbc:sqlite:"+source);var reader=DriverManager.getConnection("jdbc:sqlite:"+source)){
                sql(freeze,"BEGIN IMMEDIATE");assertEquals(0,reader.unwrap(SQLiteConnection.class).getDatabase().backup("main",backup.toString(),null));sql(freeze,"ROLLBACK");
            }
        }else{ids=new ArrayList<>();for(var node:JSON.readTree(Files.readAllBytes(root.resolve("fixture-ids.json"))))ids.add(node.asString());assertEquals(7,ids.size());assertTrue(Files.isRegularFile(backup));}
        byte[] sourceBefore=Files.readAllBytes(source),backupBefore=Files.readAllBytes(backup);
        Map<String,Object> evidence=new LinkedHashMap<>();
        try(var snapshot=DriverManager.getConnection("jdbc:sqlite:"+backup)){
            boolean receipt;try(var db=app.getConnection()){receipt=count(db,"SELECT count(*) FROM workspace.knowledge_sqlite_import")>0;}
            if(!receipt){
                try(var db=owner.getConnection()){sql(db,"CREATE TRIGGER b2_late_failure BEFORE INSERT ON workspace.knowledge_chunks FOR EACH ROW EXECUTE FUNCTION workspace.reject_immutable_update()");}
                try{var failure=assertThrows(IOException.class,()->SqliteKnowledgeMigration.importSnapshot(snapshot,root,app));assertTrue(failure.getMessage().contains("REBUILD_INDEX"));}
                finally{try(var db=owner.getConnection()){sql(db,"DROP TRIGGER b2_late_failure ON workspace.knowledge_chunks");}}
                try(var db=app.getConnection()){
                    for(String table:List.of("knowledge_documents","knowledge_revisions","knowledge_jobs","knowledge_chunks","knowledge_index_metadata","knowledge_sqlite_import"))assertEquals(0,count(db,"SELECT count(*) FROM workspace."+table));
                }
                var result=SqliteKnowledgeMigration.importSnapshot(snapshot,root,app);assertEquals(7L,result.rows().get("documents"));assertEquals(7L,result.rows().get("revisions"));
                evidence.put("importAndLateIndexFailureRollbackExecutedThisRun",true);
            }else{
                try(var db=app.getConnection()){for(String id:ids)assertEquals(1,count(db,"SELECT count(*) FROM workspace.knowledge_documents WHERE id='"+KnowledgeStore.id(id)+"'"));}
                evidence.put("importAndLateIndexFailureRollbackExecutedThisRun",false); // Resume preserved fixture after a later assertion failure; never clear rows.
            }
            assertThrows(IOException.class,()->SqliteKnowledgeMigration.importSnapshot(snapshot,root,app));
        }
        try(var store=new KnowledgeStore(app);var index=new KnowledgeLexicalIndex(store)){
            ready(index);var first=store.get(ids.getFirst());assertEquals(KnowledgePostgresqlFixture.TIME,first.createdAt());
            assertEquals("1",first.currentReadyRevision());assertEquals(1,store.detail(first.documentId()).revisions().size());
            assertEquals("READY",store.job(first.requestId()).state());
            String cjk=ids.get(3);var preview=store.preview(cjk,"1",0);assertFalse(preview.text().contains("\r"));assertTrue(preview.text().contains("😀"));assertEquals(1,preview.locators().getFirst().startLine());
            compareFts5(store,index);
            index.rebuild();ready(index);assertEquals(cjk,index.search("预算",10).hits().getFirst().documentId());
            try(var db=app.getConnection()){
                assertThrows(SQLException.class,()->sql(db,"UPDATE workspace.knowledge_revisions SET text='changed' WHERE doc_id='"+cjk+"'"));
                db.setAutoCommit(false);
                try{
                    String id=UUID.randomUUID().toString();
                    try(var s=db.prepareStatement("INSERT INTO workspace.knowledge_documents VALUES(?,'deferred.txt','ACTIVE',1,1,?,?,NULL)")){
                        s.setString(1,id);s.setString(2,KnowledgePostgresqlFixture.TIME);s.setString(3,KnowledgePostgresqlFixture.TIME);s.executeUpdate();}
                    // The circular pointer is accepted until commit; missing revision is rejected at commit.
                    assertThrows(SQLException.class,db::commit);
                }finally{db.rollback();db.setAutoCommit(true);}
                db.setAutoCommit(false);
                try{
                    String columns="doc_id,revision,digest,filename,type,bytes,imported,parser,normalizer,representation_digest,text,locators,lines,artifact_bytes";
                    assertThrows(SQLException.class,()->sql(db,"INSERT INTO workspace.knowledge_revisions("+columns+") SELECT doc_id,2,digest,filename,type,bytes,imported,parser,normalizer,representation_digest,text,locators,lines,artifact_bytes FROM workspace.knowledge_revisions WHERE doc_id='"+cjk+"' AND revision=1"));
                }finally{db.rollback();db.setAutoCommit(true);}
                // Delete intent and cascading truth changes roll back together; no source file is deleted here.
                db.setAutoCommit(false);
                try{sql(db,"INSERT INTO workspace.knowledge_deletes VALUES('"+cjk+"','"+UUID.randomUUID()+"','1',2)");sql(db,"DELETE FROM workspace.knowledge_documents WHERE id='"+cjk+"'");assertEquals(0,count(db,"SELECT count(*) FROM workspace.knowledge_revisions WHERE doc_id='"+cjk+"'"));}
                finally{db.rollback();db.setAutoCommit(true);}
                assertEquals(1,count(db,"SELECT count(*) FROM workspace.knowledge_revisions WHERE doc_id='"+cjk+"'"));
            }
            long initial=KnowledgeStore.version(store.get(ids.get(2)).metadataVersion());concurrentLifecycle(app,ids.get(2),initial);
            var current=store.get(ids.get(2));assertEquals(initial+1,KnowledgeStore.version(current.metadataVersion()));store.lifecycle(current.documentId(),current.metadataVersion(),"ACTIVE");ready(index);
            String request=UUID.randomUUID().toString();var admitted=store.admit(request,null,null,"pending.txt",8);store.parsing(request);assertEquals("PARSING",store.job(request).state());
            try(var restarted=new KnowledgeStore(app)){restarted.recoverInterruptedJobs();assertEquals("INTERRUPTED",restarted.job(request).state());}
            String cancelled=UUID.randomUUID().toString();var next=store.admit(cancelled,null,null,"cancelled.txt",8);store.cancel(cancelled,next.documentId());assertEquals("CANCELLED",store.job(cancelled).state());
            var missingFiles=assertThrows(WorkspaceException.class,()->store.delete(admitted.documentId(),"1"));assertEquals(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE,missingFiles.error().code());
            // Corrupt persisted identity is rejected by actual search; rebuilt metadata restores the derived path.
            try(var db=app.getConnection()){sql(db,"UPDATE workspace.knowledge_index_metadata SET v='invalid' WHERE k='fingerprint'");}
            assertThrows(WorkspaceException.class,()->index.search("预算",10));index.rebuild();ready(index);assertFalse(index.search("预算",10).hits().isEmpty());
            evidence.put("storeMetadataRevisionPreviewJobsAndConcurrentLifecycle",true);
            evidence.put("deferredForeignKeyDigestUniquenessImmutableRevisionAndDeleteRollback",true);
            evidence.put("postgresqlIndexRebuildFts5OracleAndStaleFingerprintRejection",true);
            evidence.put("filePublicationAndDeleteRecovery", "NOT VERIFIED: database maintenance session rejects file publication/deletion; trusted Windows root still required");
        }
        try(var reopened=new KnowledgeStore(app);var index=new KnowledgeLexicalIndex(reopened)){ready(index);assertEquals(KnowledgePostgresqlFixture.TIME,reopened.get(ids.getFirst()).createdAt());assertFalse(index.search("预算",10).hits().isEmpty());}
        assertArrayEquals(sourceBefore,Files.readAllBytes(source));assertArrayEquals(backupBefore,Files.readAllBytes(backup));
        try(var db=owner.getConnection()){assertEquals(protectedBefore,protectedState(db));}
        evidence.put("financeCatalogAndB1RowsReceiptAndHistoryUnchanged",true);evidence.put("syntheticSourceAndBackupRetainedUnchanged",true);
        Files.writeString(OUTPUT.resolve("database-evidence.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println("B2: Knowledge JDBC truth/migration and PostgreSQL lexical query verified; trusted Windows file lifecycle remains separate");
    }
    private static void compareFts5(KnowledgeStore store,KnowledgeLexicalIndex index)throws Exception{
        try(var oracle=DriverManager.getConnection("jdbc:sqlite::memory:")){
            sql(oracle,"CREATE VIRTUAL TABLE lexical USING fts5(title,heading,body,tokenize='ascii')");sql(oracle,"CREATE TABLE refs(id INTEGER PRIMARY KEY,document TEXT,revision INTEGER,ordinal INTEGER)");
            int[] id={0};for(var ref:store.searchCorpus()){
                var source=store.searchSource(ref);LexicalChunker.chunks(source,chunk->{try{
                    int row=++id[0];try(var s=oracle.prepareStatement("INSERT INTO lexical(rowid,title,heading,body) VALUES(?,?,?,?)")){s.setInt(1,row);s.setString(2,LexicalAnalyzer.stream(ref.title()));s.setString(3,LexicalAnalyzer.stream(chunk.heading()==null?"":chunk.heading()));s.setString(4,LexicalAnalyzer.stream(source.text().substring(chunk.startOffset(),chunk.endOffset())));s.executeUpdate();}
                    try(var s=oracle.prepareStatement("INSERT INTO refs VALUES(?,?,?,?)")){s.setInt(1,row);s.setString(2,ref.documentId());s.setLong(3,KnowledgeStore.version(ref.sourceRevision()));s.setInt(4,chunk.ordinal());s.executeUpdate();}
                }catch(SQLException e){throw new RuntimeException(e);}});
            }
            for(String raw:List.of("budget","ＢＵＤＧＥＴ","预算","Café","cafe\u0301","punctuation-boundary","tie exact","alpha beta","missing")){
                List<String> expected=new ArrayList<>();try(var s=oracle.prepareStatement("SELECT r.document FROM lexical JOIN refs r ON r.id=lexical.rowid WHERE lexical MATCH ? ORDER BY bm25(lexical,4.0,2.0,1.0),r.document COLLATE BINARY,r.revision,r.ordinal LIMIT 10")){
                    s.setString(1,LexicalAnalyzer.query(raw).expression());try(var r=s.executeQuery()){while(r.next())expected.add(r.getString(1));}}
                var actual=index.search(raw,10).hits();assertEquals(expected,actual.stream().map(KnowledgeLexicalIndex.Hit::documentId).toList());
                for(var hit:actual){var preview=store.preview(hit.documentId(),hit.sourceRevision(),hit.startOffset());assertEquals(hit.startOffset(),preview.offset());assertTrue(hit.endOffset()>hit.startOffset());assertTrue(hit.endLine()>=hit.startLine());}
            }
        }
    }
    private static void concurrentLifecycle(PGSimpleDataSource app,String id,long expected)throws Exception{
        try(var one=new KnowledgeStore(app);var two=new KnowledgeStore(app);var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var a=executor.submit(()->edit(one,id,expected,start));var b=executor.submit(()->edit(two,id,expected,start));start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}
    }
    private static boolean edit(KnowledgeStore store,String id,long version,CountDownLatch start)throws Exception{
        start.await();try{store.lifecycle(id,Long.toString(version),"ARCHIVED");return true;}catch(WorkspaceException e){assertEquals(ErrorCode.KNOWLEDGE_REVISION_CONFLICT,e.error().code());return false;}
    }
    private static void ready(KnowledgeLexicalIndex index)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!index.status().state().equals("READY")&&System.nanoTime()<end)Thread.sleep(10);assertEquals("READY",index.status().state());}
    private static PGSimpleDataSource data(String user,String password){var ds=new PGSimpleDataSource();ds.setServerNames(new String[]{"127.0.0.1"});ds.setPortNumbers(new int[]{5432});ds.setDatabaseName("personal_ai_workspace_test");ds.setCurrentSchema("workspace,pg_catalog");ds.setUser(user);ds.setPassword(password);ds.setConnectTimeout(5);ds.setSocketTimeout(30);return ds;}
    private static void verifyCredential(Path file)throws Exception{
        for(Path p=file.toAbsolutePath();p!=null;p=p.getParent()){var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);assertFalse(a.isSymbolicLink()||a.isOther());}
        var user=file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));assertEquals(user,Files.getOwner(file));assertTrue(Files.size(file)<16384);
        for(var rule:Files.getFileAttributeView(file,AclFileAttributeView.class).getAcl()){assertEquals(user,rule.principal());assertEquals(AclEntryType.ALLOW,rule.type());}
    }
    private static void verifyTarget(Connection db,String role)throws SQLException{
        try(var s=db.createStatement();var r=s.executeQuery("SELECT current_database(),current_user,host(inet_server_addr()),inet_server_port(),current_setting('server_version_num')::int/10000")){assertTrue(r.next());assertEquals("personal_ai_workspace_test",r.getString(1));assertEquals(role,r.getString(2));assertEquals("127.0.0.1",r.getString(3));assertEquals(5432,r.getInt(4));assertEquals(17,r.getInt(5));}
        assertEquals(0,count(db,"SELECT count(*) FROM pg_tables WHERE schemaname='public' AND has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'INSERT,UPDATE,DELETE,TRUNCATE')"));
    }
    private static String protectedState(Connection db)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        for(String table:List.of("memory_items","conversations","conversation_turns","conversation_messages","conversation_memory_selections","sqlite_import")){
            try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM workspace."+table+" ORDER BY 1,2")){
                while(r.next())for(int i=1;i<=r.getMetaData().getColumnCount();i++){String value=r.getString(i);byte[] bytes=Objects.toString(value,"<NULL>").getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}}
        }
        try(var s=db.createStatement();var r=s.executeQuery("SELECT c.relname,c.relkind,a.attname,a.atttypid,a.attnotnull FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 WHERE n.nspname='public' ORDER BY c.relname,a.attnum")){
            while(r.next())for(int i=1;i<=5;i++)digest.update(Objects.toString(r.getString(i),"<NULL>").getBytes(StandardCharsets.UTF_8));}
        try(var s=db.createStatement();var r=s.executeQuery("SELECT version,checksum,success FROM workspace.flyway_schema_history WHERE version='1'")){assertTrue(r.next());for(int i=1;i<=3;i++)digest.update(r.getString(i).getBytes(StandardCharsets.UTF_8));}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static long count(Connection db,String query)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getLong(1);}}
    private static void sql(Connection db,String query)throws SQLException{try(var s=db.createStatement()){s.execute(query);}}
}
