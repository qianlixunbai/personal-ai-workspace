package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.conversation.ConversationStore;
import io.github.qianlixunbai.workspace.conversation.ConversationLimits;
import io.github.qianlixunbai.workspace.conversation.Conversation;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.memory.MemoryItem.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, newly owned PostgreSQL cluster only. Never reads the existing database configuration.
 * Reuses the synthetic maintenance file validator, real Flyway migration and normal Store APIs. */
@EnabledIfSystemProperty(named = "memory.postgresql.fixture", matches = ".+")
class MemoryPostgresqlLifecycleTest {
    @TempDir Path temporary;
    private static final String DATABASE = "memory_acceptance", APP = "memory_fixture_app";

    @Test void freshPostgresqlPreservesReviewedMemoryLifecycleAndConversationReferences() throws Exception {
        assertTrue(temporary.startsWith(WindowsNativeGate.profileRoot().resolve("evidence")));
        Path file = Path.of(System.getProperty("memory.postgresql.fixture")).toAbsolutePath().normalize();
        privatePath(file, false); privatePath(file.getParent(), true);
        assertEquals("fixture.json", file.getFileName().toString());
        assertTrue(file.getParent().getFileName().toString().matches("paw-memory-postgresql-[0-9a-f]{32}"));
        assertTrue(Files.size(file) < 16384);
        var config = JsonMapper.builder().build().readTree(Files.readAllBytes(file));
        Path root = Path.of(config.path("root").asString());
        assertEquals(file.getParent(), root);
        int port = config.path("port").asInt(); assertTrue(port > 1024 && port < 65536 && port != 5432);
        assertEquals("memory_fixture_admin", config.path("adminUsername").asString());
        var pid = Files.readAllLines(root.resolve("pgdata/postmaster.pid"));
        assertEquals(root.resolve("pgdata").toString().replace('\\', '/'), pid.get(1));
        assertEquals(Integer.toString(port), pid.get(3)); assertEquals("127.0.0.1", pid.get(5));
        assertEquals(config.path("processId").asLong(), Long.parseLong(pid.getFirst()));
        var process = ProcessHandle.of(Long.parseLong(pid.getFirst())).orElseThrow();
        assertTrue(process.isAlive());
        assertEquals(Path.of(config.path("postgresExecutable").asString()), Path.of(process.info().command().orElseThrow()));
        assertTrue(Math.abs(process.info().startInstant().orElseThrow().toEpochMilli() - config.path("startEpochMillis").asLong()) < 1000);
        write(root.resolve("MEMORY_TEST_STARTED"), new byte[]{1}); // A failed or completed fixture is never replayed.
        var admin = dataSource(port, "postgres", "memory_fixture_admin", config.path("adminPassword").asString());
        String appPassword = UUID.randomUUID().toString() + UUID.randomUUID();
        try (var db = admin.getConnection()) {
            assertEquals(root.resolve("pgdata").toString().replace('\\', '/'), text(db, "SHOW data_directory").replace('\\', '/'));
            assertEquals(config.path("systemId").asString(), text(db, "SELECT system_identifier::text FROM pg_control_system()"));
            assertEquals(port, number(db, "SELECT inet_server_port()"));
            assertEquals(3, number(db, "SELECT count(*) FROM pg_database"));
            assertEquals(1, number(db, "SELECT count(*) FROM pg_roles WHERE rolname !~ '^pg_'"));
            sql(db, "CREATE DATABASE " + DATABASE + " TEMPLATE template0 ENCODING 'UTF8'");
            sql(db, "CREATE ROLE " + APP + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + appPassword + "'");
            sql(db, "REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC");
            sql(db, "GRANT CONNECT ON DATABASE " + DATABASE + " TO " + APP);
        }
        var owner = dataSource(port, DATABASE, admin.getUser(), admin.getPassword());
        var app = dataSource(port, DATABASE, APP, appPassword); appPassword = null;
        try (var db = owner.getConnection()) {
            sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC"); sql(db, "CREATE SCHEMA workspace");
            sql(db, "CREATE TABLE public.fixture_sentinel(value text)");
            sql(db, "INSERT INTO public.fixture_sentinel VALUES('UNCHANGED_SYNTHETIC')");
        }
        Flyway.configure().dataSource(owner).schemas("workspace").defaultSchema("workspace").createSchemas(false)
                .locations("classpath:db/workspace/migration").cleanDisabled(true).load().migrate();
        try (var db = owner.getConnection()) {
            sql(db, "GRANT USAGE ON SCHEMA workspace TO " + APP);
            sql(db, "GRANT SELECT ON workspace.storage_metadata,workspace.write_lock TO " + APP);
            sql(db, "GRANT UPDATE ON workspace.write_lock TO " + APP);
            sql(db, "GRANT SELECT,INSERT,UPDATE,DELETE ON workspace.memory_items,workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections TO " + APP);
            sql(db, "REVOKE ALL ON FUNCTION workspace.reject_immutable_update() FROM PUBLIC");
        }
        try (var db = app.getConnection()) {
            assertEquals(APP, text(db, "SELECT current_user"));
            assertEquals("false", text(db, "SELECT has_schema_privilege(current_user,'workspace','CREATE')::text"));
            assertThrows(SQLException.class, () -> sql(db, "INSERT INTO public.fixture_sentinel VALUES('DENIED')"));
        }

        UUID retained, conversation, turn;
        List<String> beforeReopen;
        try (var pg = new MemoryStore(app, temporary.resolve("pg"));
             var sqlite = new MemoryStore(temporary.resolve("sqlite-data"), temporary.resolve("credentials/client-token"));
             var conversations = new ConversationStore(app)) {
            assertTrue(pg.postgresql()); assertFalse(sqlite.postgresql());
            var p = seed(pg); var s = seed(sqlite);
            assertEquals(summary(sqlite.recall("ＪＡＶＡ 项目计划", null)), summary(pg.recall("ＪＡＶＡ 项目计划", null)));
            assertEquals(List.of(p[0].id(), p[1].id()), pg.recall("java 项目", null).hits().stream().map(h -> h.item().id()).toList());
            assertEquals(1, pg.recall("java", Type.PREFERENCE).total());
            assertEquals(0, pg.recall("unrelated %' OR 1=1 --", null).total());
            assertEquals(0, pg.recall("😀 %_", null).total());
            var selected = List.of(new MemoryReference(p[0].id(), p[0].revision()));
            assertEquals(p[0].content(), pg.snapshotForAsk(selected).getFirst().content());
            conversation = conversations.create("Synthetic memory lifecycle").id();
            var accepted = conversations.createTurnWithUserMessage(conversation, "synthetic question", UUID.randomUUID(), selected);
            turn = accepted.id();
            conversations.completeTurnWithAssistantMessage(conversation, turn, "synthetic answer; no model called");

            // The ordinary PostgreSQL read snapshot remains repeatable across another Store's write.
            try (var snapshot = pg.openReadSnapshot(); var writer = new MemoryStore(app, temporary.resolve("pg"))) {
                assertEquals("on", text(snapshot, "SHOW transaction_read_only"));
                assertEquals("repeatable read", text(snapshot, "SHOW transaction_isolation"));
                long originalCount = number(snapshot, "SELECT count(*) FROM memory_items");
                var extra = writer.create(Type.PROJECT_NOTE, "temporary", "synthetic");
                assertEquals(originalCount, number(snapshot, "SELECT count(*) FROM memory_items"));
                writer.delete(extra.id(), extra.revision());
            }
            pg.update(p[0].id(), 1, Type.PROJECT_NOTE, "Updated", "new project preference");
            sqlite.update(s[0].id(), 1, Type.PROJECT_NOTE, "Updated", "new project preference");
            code(ErrorCode.MEMORY_SELECTION_STALE, () -> pg.snapshotForAsk(selected));
            code(ErrorCode.MEMORY_REVISION_CONFLICT, () -> pg.update(p[0].id(), 1, Type.PROJECT_NOTE, "obsolete", "must not replace"));
            assertEquals(summary(sqlite.recall("java", null)), summary(pg.recall("java", null)));
            var archived = pg.archive(p[1].id(), 1); assertEquals(0, pg.recall("java", null).total());
            var restored = pg.restore(archived.id(), archived.revision()); assertEquals(1, pg.recall("java", null).total());
            pg.delete(restored.id(), restored.revision()); assertEquals(0, pg.recall("java", null).total());
            assertEquals(List.of(new Conversation.Selection(p[0].id(), 1, 0)),
                    conversations.detail(conversation, 0, ConversationLimits.DEFAULT_LIMIT).turns().getFirst().memories());
            retained = p[0].id(); beforeReopen = summary(pg.recall("preference", null));
            var unchanged = pg.backupSnapshot(); pg.recall("preference", null); assertEquals(unchanged, pg.backupSnapshot());
        }
        try (var reopened = new MemoryStore(app, temporary.resolve("pg")); var conversations = new ConversationStore(app)) {
            assertEquals(beforeReopen, summary(reopened.recall("preference", null)));
            assertEquals(2, reopened.get(retained).revision());
            assertEquals(turn, conversations.detail(conversation, 0, ConversationLimits.DEFAULT_LIMIT).turns().getFirst().id());
            assertEquals(1, conversations.detail(conversation, 0, ConversationLimits.DEFAULT_LIMIT).turns().getFirst().memories().getFirst().revision());
            boundedRanking(owner, reopened);
        }
        try (var db = owner.getConnection()) { assertEquals("UNCHANGED_SYNTHETIC", text(db, "SELECT value FROM public.fixture_sentinel")); }
        write(root.resolve("memory-postgresql-evidence.json"), JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "status", "POSTGRESQL_MEMORY_LIFECYCLE_VERIFIED", "sqliteRankingParity", true,
                "leastPrivilegeStore", true, "currentRevisionAndReopen", true, "conversationReferencesRetained", true,
                "readOnlyRepeatableSnapshot", true, "boundedRanking", true, "realModelCalled", false)));
    }
    private MemoryItem[] seed(MemoryStore store) {
        var title = store.create(Type.PROJECT_NOTE, "Java 项目", "  编程习惯\r\n<script>literal</script>  ");
        var body = store.create(Type.PREFERENCE, "工作偏好", "java 项目");
        var archived = store.create(Type.PROJECT_NOTE, "Java 项目", "java 项目"); store.archive(archived.id(), 1);
        return new MemoryItem[]{title, body};
    }
    private List<String> summary(MemoryRecall.Result result) {
        return result.hits().stream().map(h -> h.item().title() + "|" + h.item().content() + "|" + h.item().revision() + "|" + h.score() + "|" + h.matchedTerms()).toList();
    }
    private void boundedRanking(PGSimpleDataSource owner, MemoryStore store) throws Exception {
        try (var db = owner.getConnection()) {
            sql(db, "DELETE FROM workspace.memory_items"); // This fresh owned fixture only; historical selections intentionally survive.
            try (var insert = db.prepareStatement("INSERT INTO workspace.memory_items VALUES(?,'PROJECT_NOTE','bounded','bounded bounded','ACTIVE',9007199254740993,'MANUAL',0,0)")) {
                for (int i = 1000; i >= 1; i--) { insert.setString(1, new UUID(0, i).toString()); insert.addBatch(); } insert.executeBatch();
            }
        }
        var top = store.recall("bounded bounded", null);
        assertEquals(1000, top.total()); assertEquals(20, top.hits().size());
        assertEquals(new UUID(0, 1), top.hits().getFirst().item().id()); assertEquals(new UUID(0, 20), top.hits().getLast().item().id());
        assertTrue(top.hits().stream().allMatch(h -> h.score() == 4 && h.item().revision() == 9007199254740993L));
        for (String invalid : Arrays.asList(null, "", " ", "x".repeat(161), "\uD800", "x\0y"))
            code(ErrorCode.MEMORY_INVALID, () -> store.recall(invalid, null));
        try (var db = owner.getConnection()) {
            sql(db, "INSERT INTO workspace.memory_items VALUES('" + new UUID(0, 1001) + "','PROJECT_NOTE','bounded','content','ACTIVE',1,'MANUAL',0,0)");
        }
        code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, () -> store.recall("bounded", null));
    }
    private PGSimpleDataSource dataSource(int port, String database, String user, String password) {
        var source = new PGSimpleDataSource(); source.setServerNames(new String[]{"127.0.0.1"}); source.setPortNumbers(new int[]{port});
        source.setDatabaseName(database); source.setUser(user); source.setPassword(password); source.setCurrentSchema("workspace,pg_catalog");
        source.setConnectTimeout(5); source.setSocketTimeout(15); return source;
    }
    private String text(Connection db, String query) throws SQLException { try (var s = db.createStatement(); var r = s.executeQuery(query)) { assertTrue(r.next()); return r.getString(1); } }
    private long number(Connection db, String query) throws SQLException { return Long.parseLong(text(db, query)); }
    private void sql(Connection db, String query) throws SQLException { try (var s = db.createStatement()) { s.execute(query); } }
    private void code(ErrorCode expected, org.junit.jupiter.api.function.Executable action) { assertEquals(expected, assertThrows(WorkspaceException.class, action).error().code()); }
}
