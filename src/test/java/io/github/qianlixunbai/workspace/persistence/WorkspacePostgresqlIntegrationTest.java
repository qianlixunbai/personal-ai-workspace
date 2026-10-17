package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.backup.WorkspaceBackupService;
import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.conversation.*;
import io.github.qianlixunbai.workspace.memory.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlite.SQLiteConnection;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** One explicit test-database flow. Never starts application main or substitutes Windows admission. */
@EnabledIfSystemProperty(named = "workspace.postgresql.test", matches = "true")
class WorkspacePostgresqlIntegrationTest {
    @TempDir Path temporary;
    private static final String APP = "personal_ai_workspace_b1_app", MIGRATOR = "personal_ai_workspace_b1_migrator";
    private static final UUID MEMORY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TURN = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Test void migrateSyntheticSnapshotThenUseNormalStoresAndProveRollbackAndIsolation() throws Exception {
        Path credentials = Path.of(".runtime/workspace/b1-test-credentials.json");
        verifyCredentials(credentials);
        var json = JsonMapper.builder().build();
        var config = json.readTree(Files.readAllBytes(credentials));
        assertEquals("jdbc:postgresql://127.0.0.1:5432/personal_ai_workspace_test", config.path("url").asString());
        assertEquals(APP, config.path("appUsername").asString()); assertEquals(MIGRATOR, config.path("migrationUsername").asString());
        var owner = dataSource(MIGRATOR, config.path("migrationPassword").asString());
        var app = dataSource(APP, config.path("appPassword").asString()); config = null;
        String before;
        try (var db = owner.getConnection()) { verifyTarget(db, MIGRATOR); before = financeCatalog(db); }
        try (var db = app.getConnection()) { verifyTarget(db, APP); }
        var flyway = Flyway.configure().dataSource(owner).schemas("workspace").defaultSchema("workspace")
                .createSchemas(false).locations("classpath:db/workspace/migration").cleanDisabled(true).load();
        flyway.migrate(); flyway.validate();
        try (var db = owner.getConnection()) {
            sql(db, "GRANT SELECT ON workspace.storage_metadata,workspace.write_lock TO " + APP);
            sql(db, "GRANT UPDATE ON workspace.write_lock TO " + APP);
            sql(db, "GRANT SELECT,INSERT,UPDATE,DELETE ON workspace.memory_items,workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections TO " + APP);
            sql(db, "GRANT SELECT,INSERT ON workspace.sqlite_import TO " + APP);
            sql(db, "REVOKE ALL ON FUNCTION workspace.reject_immutable_update() FROM PUBLIC");
            assertEquals(before, financeCatalog(db));
        }
        // Existing target data/receipt is a stop, never a cleanup/retry instruction.
        try (var db = app.getConnection()) {
            assertEquals(0, count(db, "SELECT count(*) FROM workspace.sqlite_import"));
            assertEquals(0, count(db, "SELECT count(*) FROM workspace.memory_items"));
            assertEquals(0, count(db, "SELECT count(*) FROM workspace.conversations"));
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        Path source = temporary.resolve("synthetic.db"), backup = temporary.resolve("synthetic-backup.db");
        try (var sqlite = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            source(sqlite);
            // Real SQLite online backup primitive, with a separate writer exclusion connection.
            try (var freeze = DriverManager.getConnection("jdbc:sqlite:" + source)) {
                sql(freeze, "BEGIN IMMEDIATE");
                assertEquals(0, sqlite.unwrap(SQLiteConnection.class).getDatabase().backup("main", backup.toString(), null));
                sql(freeze, "ROLLBACK");
            }
        }
        byte[] backupBefore = Files.readAllBytes(backup);
        try (var snapshot = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            // A late trigger failure proves all earlier copied tables roll back with no receipt.
            try (var db = owner.getConnection()) {
                sql(db, "CREATE TRIGGER b1_injected_failure BEFORE INSERT ON workspace.conversation_messages FOR EACH ROW EXECUTE FUNCTION workspace.reject_immutable_update()");
            }
            try { assertThrows(IOException.class, () -> SqliteWorkspaceMigration.importSnapshot(snapshot, app)); }
            finally { try (var db = owner.getConnection()) { sql(db, "DROP TRIGGER b1_injected_failure ON workspace.conversation_messages"); } }
            try (var db = app.getConnection()) {
                assertEquals(0, count(db, "SELECT count(*) FROM memory_items"));
                assertEquals(0, count(db, "SELECT count(*) FROM conversations"));
                assertEquals(0, count(db, "SELECT count(*) FROM sqlite_import"));
            }
            var result = SqliteWorkspaceMigration.importSnapshot(snapshot, app);
            assertEquals(1L, result.rows().get("memory_items")); assertEquals(2L, result.rows().get("conversation_messages"));
            assertThrows(IOException.class, () -> SqliteWorkspaceMigration.importSnapshot(snapshot, app));
            evidence.put("snapshotImportCountsAllFieldsDigestAndCommitVisibility", true);
            evidence.put("lateFailureRolledBackAllRowsAndReceipt", true);
            evidence.put("duplicateImportRejectedWithoutOverwrite", true);
        }
        assertArrayEquals(backupBefore, Files.readAllBytes(backup)); assertTrue(Files.isRegularFile(source));
        try (var memory = new MemoryStore(app, temporary.resolve("unused-profile-data"));
             var conversations = new ConversationStore(app)) {
            var migrated = memory.get(MEMORY);
            assertEquals(7, migrated.revision()); assertEquals(1234567890123L, migrated.createdAt().toEpochMilli());
            assertEquals("中文 😀 ' \" \\ % _ \r\n", migrated.content());
            assertEquals(1, memory.list(null, null, "pha", 0, 20).total());
            assertEquals(0, memory.list(null, null, "alphabet", 0, 20).total());
            assertEquals(1, memory.list(null, null, "% _", 0, 20).total());
            assertEquals(1, memory.list(null, null, "😀", 0, 20).total());
            assertEquals(7, memory.snapshotForAsk(List.of(new MemoryReference(MEMORY, 7))).getFirst().revision());
            var updated = memory.update(MEMORY, 7, MemoryItem.Type.PROJECT_NOTE, "Alphabet", "changed");
            assertEquals(8, updated.revision());
            code(ErrorCode.MEMORY_REVISION_CONFLICT, () -> memory.update(MEMORY, 7, MemoryItem.Type.PREFERENCE, "stale", "stale"));
            var detail = conversations.detail(CONVERSATION, 0, 10);
            assertEquals(TURN, detail.turns().getFirst().id()); assertEquals(7, detail.turns().getFirst().memories().getFirst().revision());
            assertEquals("synthetic assistant", detail.turns().getFirst().assistantMessage().content());
            try (var db = app.getConnection()) {
                assertThrows(SQLException.class, () -> sql(db, "UPDATE conversation_messages SET content='changed'"));
                assertThrows(SQLException.class, () -> sql(db, "UPDATE conversation_turns SET status='FAILED'"));
                assertThrows(SQLException.class, () -> sql(db, "UPDATE conversation_memory_selections SET revision=8"));
                assertFalse(bool(db, "SELECT has_schema_privilege(current_user,'workspace','CREATE')"));
                assertFalse(bool(db, "SELECT has_table_privilege(current_user,'workspace.storage_metadata','UPDATE')"));
            }
            var next = conversations.createTurnWithUserMessage(CONVERSATION, "new question", UUID.randomUUID(), List.of());
            code(ErrorCode.CONVERSATION_CONFLICT, () -> conversations.delete(CONVERSATION));
            assertEquals(1, conversations.reconcilePending());
            assertEquals(Conversation.FailureCode.EXECUTION_INTERRUPTED, conversations.detail(CONVERSATION, 0, 10).turns().getLast().failureCode());
            code(ErrorCode.CONVERSATION_CONFLICT, () -> conversations.completeTurnWithAssistantMessage(CONVERSATION, next.id(), "late"));
            var output = new ByteArrayOutputStream();
            new WorkspaceBackupService(memory, temporary.resolve("unused-token")).export(output);
            assertTrue(json.readTree(output.toByteArray()).path("memory").path("itemCount").asLong() >= 1);
            concurrentRevision(app);
            evidence.put("normalMemoryConversationReadWriteSearchStaleStateRecoveryAndBackupExport", true);
            evidence.put("crossConnectionConcurrentRevisionProtected", true);
            conversations.archive(CONVERSATION); conversations.unarchive(CONVERSATION); conversations.delete(CONVERSATION);
            try (var db = app.getConnection()) { assertEquals(0, count(db, "SELECT count(*) FROM conversation_messages")); }
        }
        try (var reopened = new MemoryStore(app, temporary)) { assertEquals(9, reopened.get(MEMORY).revision()); }
        try (var db = owner.getConnection()) { assertEquals(before, financeCatalog(db)); }
        evidence.put("financeCatalogUnchangedAndNoPublicWriteAuthority", true);
        evidence.put("sqliteOnlineBackupRetainedAndUnmodified", true);
        evidence.put("privateWindowsFileFacade", "NOT EXECUTED; JDBC snapshot import is not Windows Profile acceptance");
        Path directory = Path.of(".verification/f2.3-b1-workspace-postgresql"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("database-evidence.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println("B1: PostgreSQL Memory/Conversation migration and normal store reads/writes verified; no Windows acceptance claim");
        // Keep the synthetic receipt/data for inspection. No public DML, schema deletion or database reset.
    }

    private void concurrentRevision(PGSimpleDataSource app) throws Exception {
        try (var first = new MemoryStore(app, temporary); var second = new MemoryStore(app, temporary);
             var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var one = executor.submit(() -> edit(first, start)); var two = executor.submit(() -> edit(second, start)); start.countDown();
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
    }
    private static boolean edit(MemoryStore store, CountDownLatch start) throws Exception {
        start.await();
        try { store.update(MEMORY, 8, MemoryItem.Type.PROJECT_NOTE, "Alphabet", "concurrent"); return true; }
        catch (WorkspaceException denied) { assertEquals(ErrorCode.MEMORY_REVISION_CONFLICT, denied.error().code()); return false; }
    }
    static void source(Connection db) throws SQLException {
        sql(db, "PRAGMA foreign_keys=ON");
        sql(db, "CREATE TABLE memory_items(id TEXT NOT NULL UNIQUE,type TEXT NOT NULL,title TEXT NOT NULL,content TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,source TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        WorkspaceSchema.upgrade(db, 1);
        try (var s = db.prepareStatement("INSERT INTO memory_items VALUES(?, 'PROJECT_NOTE', 'Alphabet', ?, 'ACTIVE', 7, 'MANUAL', 1234567890123, 1234567890999)")) {
            s.setString(1, MEMORY.toString()); s.setString(2, "中文 😀 ' \" \\ % _ \r\n"); s.executeUpdate();
        }
        sql(db, "INSERT INTO conversations VALUES('" + CONVERSATION + "','synthetic','ACTIVE',1234567890123,1234567890999)");
        sql(db, "INSERT INTO conversation_turns VALUES('" + TURN + "','" + CONVERSATION + "',1,'SUCCEEDED',1234567890123,1234567890999,NULL,NULL)");
        sql(db, "INSERT INTO conversation_messages VALUES('40000000-0000-0000-0000-000000000001','" + TURN + "','USER','synthetic user',1234567890123)");
        sql(db, "INSERT INTO conversation_messages VALUES('40000000-0000-0000-0000-000000000002','" + TURN + "','ASSISTANT','synthetic assistant',1234567890999)");
        sql(db, "INSERT INTO conversation_memory_selections VALUES('" + TURN + "',0,'" + MEMORY + "',7)");
    }
    private static PGSimpleDataSource dataSource(String role, String password) {
        var data = new PGSimpleDataSource(); data.setServerNames(new String[]{"127.0.0.1"}); data.setPortNumbers(new int[]{5432});
        data.setDatabaseName("personal_ai_workspace_test"); data.setUser(role); data.setPassword(password);
        data.setCurrentSchema("workspace,pg_catalog"); data.setConnectTimeout(5); data.setSocketTimeout(30); return data;
    }
    private static void verifyCredentials(Path file) throws Exception {
        var user = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        for (Path part = file.toAbsolutePath(); part != null; part = part.getParent())
            assertFalse(Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther());
        assertEquals(user, Files.getOwner(file)); assertTrue(Files.size(file) < 16384);
        for (var rule : Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl()) {
            assertEquals(user, rule.principal()); assertEquals(AclEntryType.ALLOW, rule.type());
        }
    }
    private static void verifyTarget(Connection db, String role) throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT current_database(),current_user,host(inet_server_addr()),inet_server_port(),current_setting('server_version_num')::int/10000")) {
            assertTrue(r.next()); assertEquals("personal_ai_workspace_test", r.getString(1)); assertEquals(role, r.getString(2));
            assertEquals("127.0.0.1", r.getString(3)); assertEquals(5432, r.getInt(4)); assertEquals(17, r.getInt(5));
        }
        assertFalse(bool(db, "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user"));
        assertEquals(0, count(db, "SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)"));
        assertFalse(bool(db, "SELECT has_schema_privilege(current_user,'public','CREATE')"));
        assertEquals(0, count(db, "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'INSERT,UPDATE,DELETE,TRUNCATE')"));
    }
    private static String financeCatalog(Connection db) throws SQLException {
        StringBuilder result = new StringBuilder();
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT c.relname,c.relkind,a.attname,a.atttypid,a.attnotnull FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 WHERE n.nspname='public' ORDER BY c.relname,a.attnum")) {
            while (r.next()) for (int i=1;i<=5;i++) result.append(r.getString(i)).append('|');
        }
        return result.toString();
    }
    private static boolean bool(Connection db, String query) throws SQLException { try (var s=db.createStatement(); var r=s.executeQuery(query)) { r.next(); return r.getBoolean(1); } }
    private static long count(Connection db, String query) throws SQLException { try (var s=db.createStatement(); var r=s.executeQuery(query)) { r.next(); return r.getLong(1); } }
    private static void sql(Connection db, String query) throws SQLException { try (var s=db.createStatement()) { s.execute(query); } }
    private static void code(ErrorCode code, org.junit.jupiter.api.function.Executable work) { assertEquals(code, assertThrows(WorkspaceException.class, work).error().code()); }
}
