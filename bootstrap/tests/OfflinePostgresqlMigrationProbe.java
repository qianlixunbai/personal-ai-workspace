import io.github.qianlixunbai.workspace.conversation.ConversationStore;
import io.github.qianlixunbai.workspace.knowledge.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.persistence.*;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlite.SQLiteConfig;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Explicit synthetic-only acceptance of the real Windows file migration APIs and PostgreSQL.
 * The guardian supplies a newly initialized cluster over stdin, never a user's database/credential. */
public final class OfflinePostgresqlMigrationProbe {
    private static final List<String> WORKSPACE = List.of("memory_items", "conversations", "conversation_turns",
            "conversation_messages", "conversation_memory_selections");
    private static final List<String> KNOWLEDGE = List.of("documents", "revisions", "jobs", "deletes");
    private static final String ADMIN = "offline_fixture_admin", APP = "offline_fixture_app", DATABASE = "offline_migration";
    private static final String CONTENT = "保留 exact # %23 & 😀\nrevision two";
    private static final String DOCUMENT = "# Synthetic migration\n保留 source # %23 & 😀\n";
    private static String stage = "INPUT";

    public static void main(String[] args) throws Exception {
        var output = System.out;
        // Third-party diagnostics can include SQL. Only the bounded receipt is emitted.
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(System.out);
        byte[] input = System.in.readNBytes(16385);
        try {
            check(args.length == 0 && input.length < 16385, "bounded input");
            var config = JsonMapper.builder().build().readTree(input);
            int port = config.path("port").asInt();
            check(port >= 49152 && port <= 65535, "new ephemeral port");
            Path cluster = Path.of(config.path("data").asString()).toAbsolutePath().normalize();
            var pid = Files.readAllLines(cluster.resolve("postmaster.pid"));
            check(pid.get(1).replace('/', '\\').equalsIgnoreCase(cluster.toString())
                    && Integer.parseInt(pid.get(3)) == port && pid.get(5).equals("127.0.0.1")
                    && Long.parseLong(pid.getFirst()) == config.path("processId").asLong(), "owned cluster file");
            Path root = WindowsNativeGate.profileRoot(), token = root.resolve("credentials/client-token");
            Path evidence = root.resolve("evidence/offline-postgresql");
            check(!Files.exists(evidence), "fresh source evidence");
            PrivateMemoryDirectory.directory(evidence);
            stage = "FRESH_CLUSTER_IDENTITY";
            var admin = data(port, "postgres", ADMIN, config.path("password").asString());
            String appPassword = UUID.randomUUID().toString() + UUID.randomUUID();
            try (var db = admin.getConnection()) {
                check(text(db, "SHOW data_directory").replace('/', '\\').equalsIgnoreCase(cluster.toString()), "data root");
                check(text(db, "SELECT system_identifier::text FROM pg_control_system()").equals(config.path("systemId").asString()), "cluster ID");
                check(text(db, "SELECT host(inet_server_addr())").equals("127.0.0.1") && number(db, "SELECT inet_server_port()") == port, "loopback identity");
                check(number(db, "SHOW server_version_num") / 10000 == 17 && number(db, "SELECT count(*) FROM pg_database") == 3
                        && number(db, "SELECT count(*) FROM pg_roles WHERE rolname !~ '^pg_'") == 1, "fresh PG17 catalog");
                sql(db, "CREATE DATABASE " + DATABASE + " TEMPLATE template0 ENCODING 'UTF8'");
                sql(db, "CREATE ROLE " + APP + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + appPassword + "'");
                sql(db, "REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC");
                sql(db, "GRANT CONNECT ON DATABASE " + DATABASE + " TO " + APP);
            }
            var owner = data(port, DATABASE, ADMIN, admin.getPassword());
            var app = data(port, DATABASE, APP, appPassword);
            stage = "SCHEMA_AND_APP_ROLE";
            try (var db = owner.getConnection()) {
                sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC"); sql(db, "CREATE SCHEMA workspace");
                sql(db, "REVOKE ALL ON SCHEMA workspace FROM PUBLIC");
            }
            var flyway = Flyway.configure().dataSource(owner).schemas("workspace").defaultSchema("workspace")
                    .createSchemas(false).locations("classpath:db/workspace/migration").cleanDisabled(true).load();
            check(flyway.migrate().migrationsExecuted == 2, "V1/V2 executed"); flyway.validate();
            try (var db = owner.getConnection()) {
                sql(db, "GRANT USAGE ON SCHEMA workspace TO " + APP);
                sql(db, "GRANT SELECT ON ALL TABLES IN SCHEMA workspace TO " + APP);
                sql(db, "GRANT UPDATE ON workspace.write_lock,workspace.knowledge_control TO " + APP);
                sql(db, "GRANT INSERT,UPDATE,DELETE ON workspace.memory_items,workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections,workspace.knowledge_documents,workspace.knowledge_revisions,workspace.knowledge_jobs,workspace.knowledge_deletes,workspace.knowledge_chunks,workspace.knowledge_index_metadata TO " + APP);
                sql(db, "GRANT INSERT ON workspace.sqlite_import,workspace.knowledge_sqlite_import TO " + APP);
                sql(db, "REVOKE ALL ON FUNCTION workspace.reject_immutable_update() FROM PUBLIC");
            }
            try (var db = app.getConnection()) { PostgresqlWorkspace.verify(db); }
            var receipt = new LinkedHashMap<String,Object>();
            for (String domain : List.of("workspace", "knowledge")) {
                stage = domain + "_SOURCE";
                Path sourceRoot = evidence.resolve(domain);
                // Both source and backup exceed MAX_PATH; previous acceptance separated these cases.
                for (int i = 0; i < 3; i++) sourceRoot = sourceRoot.resolve("synthetic-" + "x".repeat(60));
                sourceRoot = sourceRoot.resolve("data %23#&中文");
                Path source; UUID memoryId = null, conversationId = null; String documentId = null;
                if (domain.equals("workspace")) {
                    try (var memory = new MemoryStore(sourceRoot, token); var conversations = new ConversationStore(memory.databaseFile())) {
                        var item = memory.create(MemoryItem.Type.PROJECT_NOTE, "Synthetic migration", "initial");
                        item = memory.update(item.id(), item.revision(), item.type(), item.title(), CONTENT); memoryId = item.id();
                        var archived = memory.create(MemoryItem.Type.PREFERENCE, "Archived", "synthetic archived");
                        memory.archive(archived.id(), archived.revision());
                        var conversation = conversations.create("Synthetic migration conversation"); conversationId = conversation.id();
                        var turn = conversations.createTurnWithUserMessage(conversation.id(), "Synthetic user 😀", null, List.of(new MemoryReference(item.id(), item.revision())));
                        conversations.completeTurnWithAssistantMessage(conversation.id(), turn.id(), "Synthetic answer 中文");
                    }
                    source = sourceRoot.resolve("memory.db");
                } else {
                    try (var store = new KnowledgeStore(sourceRoot, token); var ingestion = new KnowledgeIngestion(store)) {
                        byte[] bytes = DOCUMENT.getBytes(StandardCharsets.UTF_8);
                        var job = ingestion.upload(UUID.randomUUID().toString(), null, null, "synthetic.md", bytes.length, new ByteArrayInputStream(bytes));
                        long deadline = System.nanoTime() + 10_000_000_000L;
                        while (Set.of("PENDING", "PARSING").contains(store.job(job.requestId()).state()) && System.nanoTime() < deadline) Thread.sleep(10);
                        check(store.job(job.requestId()).state().equals("READY"), "source ready"); documentId = job.documentId();
                    }
                    source = sourceRoot.resolve("knowledge/knowledge.db");
                }
                var before = digest(source); var allSourceRows = snapshot(source);
                Path backups = sourceRoot.getParent().resolve("backups"); PrivateMemoryDirectory.directory(backups);
                String failureTable = domain.equals("workspace") ? "conversation_messages" : "knowledge_chunks";
                stage = domain + "_REAL_TRANSACTION_FAILURE";
                try (var db = owner.getConnection()) { sql(db, "ALTER TABLE workspace." + failureTable + " ADD CONSTRAINT synthetic_failure CHECK (false)"); }
                Path failedBackup = backup(backups, domain, "failed");
                stopped(() -> migrate(domain, source, failedBackup, app));
                try (var db = app.getConnection()) {
                    for (String table : targetTables(domain)) check(number(db, "SELECT count(*) FROM workspace." + table) == 0, "transaction rolled back " + table);
                }
                preserved(source, failedBackup, before, allSourceRows, domain);
                // Explicit recovery only after a fresh-connection inspection proved no receipt/data.
                try (var db = owner.getConnection()) { sql(db, "ALTER TABLE workspace." + failureTable + " DROP CONSTRAINT synthetic_failure"); }
                stage = domain + "_SUCCESS";
                Path goodBackup = backup(backups, domain, "success");
                Object result = migrate(domain, source, goodBackup, app);
                preserved(source, goodBackup, before, allSourceRows, domain);
                var counts = new LinkedHashMap<String,Long>();
                try (var sqlite = readOnly(source); var db = app.getConnection()) {
                    for (String table : domain.equals("workspace") ? WORKSPACE : KNOWLEDGE) {
                        String target = "workspace." + (domain.equals("workspace") ? "" : "knowledge_") + table;
                        check(tableRows(sqlite, table).equals(tableRows(db, target)), "all migrated columns " + table);
                        counts.put(table, number(db, "SELECT count(*) FROM " + target));
                    }
                    String digest = domain.equals("workspace") ? ((SqliteWorkspaceMigration.Result)result).contentDigest() : ((SqliteKnowledgeMigration.Result)result).contentDigest();
                    check(text(db, domain.equals("workspace") ? "SELECT source_digest FROM workspace.sqlite_import" : "SELECT content_digest FROM workspace.knowledge_sqlite_import").equals(digest), "committed receipt");
                    if (domain.equals("knowledge")) check(number(db, "SELECT count(*) FROM workspace.knowledge_chunks") > 0, "rebuilt index");
                }
                stage = domain + "_REPEAT_REJECTED";
                Map<String,List<List<String>>> targetBefore = targetSnapshot(app, domain);
                byte[] backupBefore = digest(goodBackup);
                stopped(() -> migrate(domain, source, goodBackup, app));
                check(Arrays.equals(backupBefore, digest(goodBackup)), "backup never overwritten");
                Path duplicateBackup = backup(backups, domain, "duplicate");
                stopped(() -> migrate(domain, source, duplicateBackup, app));
                check(targetBefore.equals(targetSnapshot(app, domain)), "repeat target and receipt unchanged");
                preserved(source, duplicateBackup, before, allSourceRows, domain);
                stage = domain + "_STORE_REOPEN";
                if (domain.equals("workspace")) {
                    try (var memory = new MemoryStore(app, evidence); var conversations = new ConversationStore(app)) {
                        check(memory.get(memoryId).content().equals(CONTENT) && memory.get(memoryId).revision() == 2, "Memory reopen");
                        check(conversations.detail(conversationId, 0, 10).totalTurns() == 1, "Conversation reopen");
                    }
                } else {
                    try (var store = new KnowledgeStore(app); var index = new KnowledgeLexicalIndex(store)) {
                        check(store.preview(documentId, "1", 0).text().contains("保留 source"), "Knowledge preview");
                        long deadline = System.nanoTime() + 10_000_000_000L;
                        while (!index.status().state().equals("READY") && System.nanoTime() < deadline) Thread.sleep(10);
                        var hits = index.search("synthetic", 10).hits();
                        check(hits.size() == 1 && hits.getFirst().documentId().equals(documentId), "search rebuilt index");
                    }
                }
                receipt.put(domain, Map.of("rows", counts, "sourceLength", source.toString().length(), "backupLength", goodBackup.toString().length(),
                        "sourceSha256", HexFormat.of().formatHex(before), "rollback", true, "duplicateRejected", true, "storeReopened", true));
                output.println("OFFLINE_PG_PASS " + domain + " rows=" + counts);
            }
            receipt.put("realPostgresql", true); receipt.put("modelCalls", 0); receipt.put("defaultChanged", false);
            Files.writeString(evidence.resolve("result.json"), JsonMapper.builder().build().writeValueAsString(receipt), StandardOpenOption.CREATE_NEW);
            output.println("OFFLINE_POSTGRESQL_MIGRATION_PASS");
        } catch (Exception | AssertionError failure) {
            output.println("OFFLINE_PG_STOP stage=" + stage + " kind=" + failure.getClass().getSimpleName()
                    + (failure instanceof SQLException sql ? " sqlstate=" + sql.getSQLState() : "")
                    + (failure instanceof AssertionError ? " check=" + failure.getMessage() : ""));
            System.exit(10);
        } finally { Arrays.fill(input, (byte)0); }
    }
    private static PGSimpleDataSource data(int port, String database, String user, String password) {
        var ds = new PGSimpleDataSource(); ds.setServerNames(new String[]{"127.0.0.1"}); ds.setPortNumbers(new int[]{port});
        ds.setDatabaseName(database); ds.setUser(user); ds.setPassword(password); ds.setCurrentSchema("workspace,pg_catalog");
        ds.setConnectTimeout(5); ds.setSocketTimeout(30); return ds;
    }
    private static Path backup(Path parent, String domain, String name) { return domain.equals("workspace") ? parent.resolve(name + ".db") : parent.resolve(name).resolve("knowledge.db"); }
    private static Object migrate(String domain, Path source, Path backup, PGSimpleDataSource target) throws IOException {
        return domain.equals("workspace") ? SqliteWorkspaceMigration.migrate(source, backup, target) : SqliteKnowledgeMigration.migrate(source.getParent(), backup.getParent(), target);
    }
    private static List<String> targetTables(String domain) {
        var tables = new ArrayList<>(domain.equals("workspace") ? WORKSPACE : KNOWLEDGE.stream().map(s -> "knowledge_" + s).toList());
        tables.add(domain.equals("workspace") ? "sqlite_import" : "knowledge_sqlite_import");
        if (domain.equals("knowledge")) tables.addAll(List.of("knowledge_chunks", "knowledge_index_metadata")); return tables;
    }
    private static Map<String,List<List<String>>> targetSnapshot(PGSimpleDataSource ds, String domain) throws Exception {
        var result = new TreeMap<String,List<List<String>>>();
        try (var db = ds.getConnection()) { for (String table : targetTables(domain)) result.put(table, tableRows(db, "workspace." + table)); } return result;
    }
    private static List<List<String>> tableRows(Connection db, String table) throws SQLException {
        int columns; try (var s = db.createStatement(); var r = s.executeQuery("SELECT * FROM " + table + " LIMIT 0")) { columns = r.getMetaData().getColumnCount(); }
        String order = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
        var result = new ArrayList<List<String>>();
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT * FROM " + table + " ORDER BY " + order)) {
            while (r.next()) { var row = new ArrayList<String>(); for (int i = 1; i <= columns; i++) row.add(r.getString(i)); result.add(row); }
        } return result;
    }
    private static Map<String,List<List<String>>> snapshot(Path file) throws Exception {
        var result = new TreeMap<String,List<List<String>>>();
        try (var db = readOnly(file)) {
            check(text(db, "PRAGMA integrity_check").equals("ok"), "SQLite integrity");
            var tables = new ArrayList<String>();
            try (var s = db.createStatement(); var r = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) { while (r.next()) tables.add(r.getString(1)); }
            for (String table : tables) { check(table.matches("[a-z_]+"), "fixture table"); result.put(table, tableRows(db, table)); }
        } return result;
    }
    private static void preserved(Path source, Path backup, byte[] before, Map<String,List<List<String>>> rows, String domain) throws Exception {
        check(Arrays.equals(before, digest(source)) && rows.equals(snapshot(source)) && rows.equals(snapshot(backup)), "source and backup truth");
        if (domain.equals("knowledge")) try (var paths = Files.walk(source.getParent().resolve("sources"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) check(Arrays.equals(digest(file), digest(backup.getParent().resolve(source.getParent().relativize(file)))), "source artifact backup");
        }
        try (var db = DriverManager.getConnection(SqliteFileUrl.forFile(source))) { sql(db, "BEGIN IMMEDIATE"); sql(db, "ROLLBACK"); }
        check(Arrays.equals(before, digest(source)), "released source write lock");
    }
    private static Connection readOnly(Path file) throws SQLException { var config = new SQLiteConfig(); config.setReadOnly(true); return DriverManager.getConnection(SqliteFileUrl.forFile(file), config.toProperties()); }
    private static byte[] digest(Path file) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)); }
    private static void sql(Connection db, String query) throws SQLException { try (var s = db.createStatement()) { s.execute(query); } }
    private static String text(Connection db, String query) throws SQLException { try (var s = db.createStatement(); var r = s.executeQuery(query)) { if (!r.next()) throw new SQLException(); return r.getString(1); } }
    private static long number(Connection db, String query) throws SQLException { return Long.parseLong(text(db, query)); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void stopped(Work action) throws Exception { try { action.run(); throw new AssertionError("failure required"); } catch (IOException expected) {} }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
