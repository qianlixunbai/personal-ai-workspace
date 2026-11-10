import io.github.qianlixunbai.workspace.knowledge.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.persistence.*;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import io.github.qianlixunbai.workspace.conversation.ConversationStore;

/** Real PostgreSQL combined-transaction acceptance; only new synthetic profiles and clusters. */
public final class CombinedMigrationProbe {
    public static void main(String[] args) throws Exception {
        var output = System.out; System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(System.out);
        byte[] input = System.in.readNBytes(16385); String stage = "INPUT";
        try {
            require(args.length == 0 && input.length < 16385);
            var config = JsonMapper.builder().build().readTree(input);
            int port = config.path("port").asInt(); require(port >= 49152 && port <= 65535);
            Path cluster = Path.of(config.path("data").asString()).toAbsolutePath().normalize();
            var pid = Files.readAllLines(cluster.resolve("postmaster.pid"));
            require(pid.get(1).replace('/', '\\').equalsIgnoreCase(cluster.toString()) && Integer.parseInt(pid.get(3)) == port
                    && pid.get(5).equals("127.0.0.1") && Long.parseLong(pid.getFirst()) == config.path("processId").asLong());
            Path root = WindowsNativeGate.profileRoot(), data = root.resolve("data"), token = root.resolve("credentials/client-token");
            require(!Files.exists(data.resolve("database.dpapi")) && !Files.exists(data.resolve("memory.db")) && !Files.exists(data.resolve("knowledge/knowledge.db")));
            var admin = source(port, "postgres", "offline_fixture_admin", config.path("password").asString());
            String applicationPassword = config.path("appPassword").asString();
            require(applicationPassword.matches("[a-f0-9]{64}"));
            stage = "FRESH_CLUSTER";
            try (var db = admin.getConnection()) {
                require(text(db, "SHOW data_directory").replace('/', '\\').equalsIgnoreCase(cluster.toString()));
                require(text(db, "SELECT system_identifier::text FROM pg_control_system()").equals(config.path("systemId").asString()));
                require(text(db, "SELECT host(inet_server_addr())").equals("127.0.0.1") && text(db, "SELECT inet_server_port()").equals(Integer.toString(port)));
                require(Long.parseLong(text(db, "SHOW server_version_num")) / 10000 == 17);
                require(text(db, "SELECT count(*) FROM pg_database").equals("3") && text(db, "SELECT count(*) FROM pg_roles WHERE rolname !~ '^pg_'").equals("1"));
                sql(db, "CREATE DATABASE cutover_fixture TEMPLATE template0 ENCODING 'UTF8'");
                sql(db, "CREATE ROLE cutover_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '" + applicationPassword + "'");
                sql(db, "REVOKE ALL ON DATABASE cutover_fixture FROM PUBLIC"); sql(db, "GRANT CONNECT ON DATABASE cutover_fixture TO cutover_app");
            }
            var owner = source(port, "cutover_fixture", admin.getUser(), admin.getPassword());
            var app = source(port, "cutover_fixture", "cutover_app", applicationPassword);
            try (var db = owner.getConnection()) {
                sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC"); sql(db, "CREATE SCHEMA workspace"); sql(db, "REVOKE ALL ON SCHEMA workspace FROM PUBLIC");
            }
            stage = "SCHEMA";
            var flyway = Flyway.configure().dataSource(owner).schemas("workspace").defaultSchema("workspace")
                    .createSchemas(false).locations("classpath:db/workspace/migration").cleanDisabled(true).load();
            require(flyway.migrate().migrationsExecuted == 2); flyway.validate();
            try (var db = owner.getConnection()) {
                sql(db, "GRANT USAGE ON SCHEMA workspace TO cutover_app");
                sql(db, "GRANT SELECT ON ALL TABLES IN SCHEMA workspace TO cutover_app");
                sql(db, "GRANT UPDATE ON workspace.write_lock,workspace.knowledge_control TO cutover_app");
                sql(db, "GRANT INSERT,UPDATE,DELETE ON workspace.memory_items,workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections,workspace.knowledge_documents,workspace.knowledge_revisions,workspace.knowledge_jobs,workspace.knowledge_deletes,workspace.knowledge_chunks,workspace.knowledge_index_metadata TO cutover_app");
                sql(db, "GRANT INSERT ON workspace.sqlite_import,workspace.knowledge_sqlite_import TO cutover_app");
                sql(db, "REVOKE ALL ON FUNCTION workspace.reject_immutable_update() FROM PUBLIC");
            }
            stage = "SYNTHETIC_SOURCE";
            UUID memoryId; UUID conversationId;
            try (var memory = new MemoryStore(data, token)) {
                memoryId = memory.create(MemoryItem.Type.PROJECT_NOTE, "Synthetic cutover", "Synthetic cutover 中文 😀").id();
                try (var conversations = new ConversationStore(memory.databaseFile())) {
                    var conversation = conversations.create("Combined synthetic conversation"); conversationId = conversation.id();
                    var turn = conversations.createTurnWithUserMessage(conversationId, "Synthetic question", null, List.of(new MemoryReference(memoryId, 1)));
                    conversations.completeTurnWithAssistantMessage(conversationId, turn.id(), "Synthetic answer");
                }
            }
            String documentId;
            try (var store = new KnowledgeStore(data, token); var ingestion = new KnowledgeIngestion(store)) {
                byte[] bytes = "# Cutover\nSynthetic preserved source 中文 😀\n".getBytes(StandardCharsets.UTF_8);
                var job = ingestion.upload(UUID.randomUUID().toString(), null, null, "cutover.md", bytes.length, new ByteArrayInputStream(bytes));
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (Set.of("PENDING", "PARSING").contains(store.job(job.requestId()).state()) && System.nanoTime() < deadline) Thread.sleep(10);
                require(store.job(job.requestId()).state().equals("READY")); documentId = job.documentId();
            }

            stage = "FROZEN_SYNTHETIC_BACKUPS";
            Path backups = root.resolve("evidence/combined-backup"); require(!Files.exists(backups));
            PrivateMemoryDirectory.directory(backups); PrivateMemoryDirectory.directory(backups.resolve("knowledge"));
            Path sourceMemory = data.resolve("memory.db"), sourceKnowledge = data.resolve("knowledge/knowledge.db");
            String memoryHash = hash(sourceMemory), knowledgeHash = hash(sourceKnowledge);
            Path memoryBackup = backups.resolve("memory.db"), knowledgeBackup = backups.resolve("knowledge/knowledge.db");
            // The fixture owns all writers; this is NOT a product maintenance authorization.
            try (var wm = writable(sourceMemory); var wk = writable(sourceKnowledge)) {
                sql(wm, "BEGIN IMMEDIATE"); sql(wk, "BEGIN IMMEDIATE");
                try {
                    backup(sourceMemory, memoryBackup); backup(sourceKnowledge, knowledgeBackup);
                    try (var files = Files.walk(data.resolve("knowledge/sources"))) {
                        for (Path item : files.toList()) {
                            Path dest = backups.resolve("knowledge/sources").resolve(data.resolve("knowledge/sources").relativize(item));
                            if (Files.isDirectory(item)) PrivateMemoryDirectory.directory(dest);
                            else { privateFile(dest); Files.write(dest, Files.readAllBytes(item)); }
                        }
                    }
                    String memoryBackupHash = hash(memoryBackup), knowledgeBackupHash = hash(knowledgeBackup);
                    try (var m = readonly(memoryBackup); var k = readonly(knowledgeBackup)) {
                        stage = "KNOWLEDGE_FAILURE_ROLLS_BACK_BOTH";
                        try (var db = owner.getConnection()) { sql(db, "ALTER TABLE workspace.knowledge_chunks ADD CONSTRAINT combined_failure CHECK(false)"); }
                        var failure = stopped(() -> SqliteCombinedMigration.importSnapshots(m, k, backups.resolve("knowledge"), app));
                        require(failure.outcome() == SqliteCombinedMigration.Outcome.NO_COMMIT_ATTEMPTED);
                        require(SqliteCombinedMigration.inspect(app, failure.expected()) == SqliteCombinedMigration.Inspection.EMPTY);
                        try (var db = owner.getConnection()) { sql(db, "ALTER TABLE workspace.knowledge_chunks DROP CONSTRAINT combined_failure"); }
                        output.println("COMBINED_KNOWLEDGE_FAILURE_BOTH_DOMAINS_EMPTY");
                        stage = "TARGET_STORE_EXCLUSION";
                        try (var busy = new KnowledgeStore(app)) {
                            var blocked = stopped(() -> SqliteCombinedMigration.importSnapshots(m, k, backups.resolve("knowledge"), app));
                            require(blocked.getMessage().contains("TARGET_EXCLUSION") && blocked.outcome() == SqliteCombinedMigration.Outcome.NO_COMMIT_ATTEMPTED);
                        }
                        require(SqliteCombinedMigration.inspect(app, failure.expected()) == SqliteCombinedMigration.Inspection.EMPTY);
                        output.println("COMBINED_ACTIVE_KNOWLEDGE_STORE_REJECTED");
                        stage = "COMMIT_ACK_LOST";
                        var unknown = stopped(() -> SqliteCombinedMigration.importSnapshots(m, k, backups.resolve("knowledge"), loseCommitAcknowledgement(app)));
                        require(unknown.outcome() == SqliteCombinedMigration.Outcome.COMMIT_UNCONFIRMED);
                        require(SqliteCombinedMigration.inspect(app, unknown.expected()) == SqliteCombinedMigration.Inspection.MATCHING_DATA_AND_RECEIPTS);
                        output.println("COMBINED_REAL_COMMIT_LOST_ACK_INSPECTED_WITHOUT_REPLAY");
                        stage = "REOPEN_AND_PRESERVE_NEW_DATA";
                        try (var memory = new MemoryStore(app, root.resolve("evidence")); var conversations = new ConversationStore(app);
                             var knowledge = new KnowledgeStore(app)) {
                            require(memory.get(memoryId).content().equals("Synthetic cutover 中文 😀"));
                            require(conversations.detail(conversationId, 0, 10).totalTurns() == 1);
                            require(knowledge.preview(documentId, "1", 0).text().equals("# Cutover\nSynthetic preserved source 中文 😀\n"));
                            var newer = memory.create(MemoryItem.Type.PROJECT_NOTE, "After migration", "Must never be overwritten");
                            require(SqliteCombinedMigration.inspect(app, unknown.expected()) == SqliteCombinedMigration.Inspection.DIFFERENT_OR_PARTIAL);
                            require(memory.get(newer.id()).content().equals("Must never be overwritten"));
                        }
                        var duplicate = stopped(() -> SqliteCombinedMigration.importSnapshots(m, k, backups.resolve("knowledge"), app));
                        require(duplicate.outcome() == SqliteCombinedMigration.Outcome.NO_COMMIT_ATTEMPTED);
                        try (var db = app.getConnection()) { require(text(db, "SELECT count(*) FROM workspace.memory_items").equals("2")); }
                        output.println("COMBINED_REOPEN_NEW_DATA_AND_DUPLICATE_PRESERVED");
                        // A separate fresh target verifies ordinary success, without clearing the first.
                        stage = "NORMAL_SUCCESS_TARGET";
                        try (var db = admin.getConnection()) { sql(db, "CREATE DATABASE combined_success TEMPLATE template0 ENCODING 'UTF8'"); sql(db, "REVOKE ALL ON DATABASE combined_success FROM PUBLIC"); sql(db, "GRANT CONNECT ON DATABASE combined_success TO cutover_app"); }
                        var successOwner = source(port, "combined_success", admin.getUser(), admin.getPassword());
                        var success = source(port, "combined_success", "cutover_app", applicationPassword);
                        prepareTarget(successOwner);
                        var completed = SqliteCombinedMigration.importSnapshots(m, k, backups.resolve("knowledge"), success);
                        require(SqliteCombinedMigration.inspect(success, completed) == SqliteCombinedMigration.Inspection.MATCHING_DATA_AND_RECEIPTS);
                        require(SqliteCombinedMigration.inspect(app, completed) == SqliteCombinedMigration.Inspection.TARGET_CHANGED);
                        output.println("COMBINED_SUCCESS_FRESH_CONNECTION_AND_TARGET_BINDING_PASS");
                    }
                    require(hash(memoryBackup).equals(memoryBackupHash) && hash(knowledgeBackup).equals(knowledgeBackupHash));
                    try (var files = Files.walk(data.resolve("knowledge/sources"))) {
                        for (Path item : files.filter(Files::isRegularFile).toList())
                            require(hash(item).equals(hash(backups.resolve("knowledge/sources")
                                    .resolve(data.resolve("knowledge/sources").relativize(item)))));
                    }
                } finally { sql(wk, "ROLLBACK"); sql(wm, "ROLLBACK"); }
            }
            require(hash(sourceMemory).equals(memoryHash) && hash(sourceKnowledge).equals(knowledgeHash));
            try (var m = writable(sourceMemory); var k = writable(sourceKnowledge)) {
                sql(m, "BEGIN IMMEDIATE"); sql(k, "BEGIN IMMEDIATE"); sql(k, "ROLLBACK"); sql(m, "ROLLBACK");
            }
            require(!Files.exists(data.resolve("database.dpapi")));
            output.println("COMBINED_SOURCE_BACKUPS_RETAINED_LOCKS_RELEASED_CONFIGURATION_ABSENT");
        } catch (Exception failure) {
            output.println("COMBINED_FIXTURE_STOP " + stage + " " + failure.getClass().getSimpleName()); System.exit(10);
        } finally { Arrays.fill(input, (byte)0); }
    }

    private interface Work { void run() throws Exception; }
    private static SqliteCombinedMigration.Failure stopped(Work work) throws Exception {
        try { work.run(); } catch (SqliteCombinedMigration.Failure failure) { return failure; }
        throw new IllegalStateException("Expected controlled stop");
    }
    private static DataSource loseCommitAcknowledgement(DataSource real) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (p, method, args) -> {
            try {
                Object value = method.invoke(real, args);
                if (!method.getName().equals("getConnection")) return value;
                Connection connection = (Connection)value;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (q, call, parameters) -> {
                    try {
                        Object result = call.invoke(connection, parameters);
                        if (call.getName().equals("commit")) throw new SQLTransientConnectionException("Synthetic lost acknowledgement", "08006");
                        return result;
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private static void prepareTarget(PGSimpleDataSource owner) throws Exception {
        try (var db = owner.getConnection()) { sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC"); sql(db, "CREATE SCHEMA workspace"); sql(db, "REVOKE ALL ON SCHEMA workspace FROM PUBLIC"); }
        var flyway = Flyway.configure().dataSource(owner).schemas("workspace").defaultSchema("workspace").createSchemas(false).locations("classpath:db/workspace/migration").cleanDisabled(true).load();
        require(flyway.migrate().migrationsExecuted == 2);
        try (var db = owner.getConnection()) {
            sql(db, "GRANT USAGE ON SCHEMA workspace TO cutover_app");
            sql(db, "GRANT SELECT ON ALL TABLES IN SCHEMA workspace TO cutover_app");
            sql(db, "GRANT UPDATE ON workspace.write_lock,workspace.knowledge_control TO cutover_app");
            sql(db, "GRANT INSERT,UPDATE,DELETE ON workspace.memory_items,workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections,workspace.knowledge_documents,workspace.knowledge_revisions,workspace.knowledge_jobs,workspace.knowledge_deletes,workspace.knowledge_chunks,workspace.knowledge_index_metadata TO cutover_app");
            sql(db, "GRANT INSERT ON workspace.sqlite_import,workspace.knowledge_sqlite_import TO cutover_app");
            sql(db, "REVOKE ALL ON FUNCTION workspace.reject_immutable_update() FROM PUBLIC");
        }
    }
    private static Connection readonly(Path path) throws SQLException {
        var config = new SQLiteConfig(); config.setReadOnly(true);
        return DriverManager.getConnection(SqliteFileUrl.forFile(path), config.toProperties());
    }
    private static Connection writable(Path path) throws SQLException {
        var config = new SQLiteConfig(); config.resetOpenMode(org.sqlite.SQLiteOpenMode.CREATE); config.setBusyTimeout(1000);
        return DriverManager.getConnection(SqliteFileUrl.forFile(path), config.toProperties());
    }
    private static void backup(Path source, Path destination) throws Exception {
        privateFile(destination);
        try (var db = readonly(source)) { require(db.unwrap(SQLiteConnection.class).getDatabase().backup("main", SqliteFileUrl.forNativeFile(destination), null) == 0); }
        try (var db = readonly(destination)) { require(text(db, "PRAGMA integrity_check").equals("ok")); }
    }
    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static void privateFile(Path file) throws IOException {
        io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.createFile(file);
        PrivateMemoryDirectory.protect(file, false);
    }
    private static PGSimpleDataSource source(int port, String database, String user, String password) {
        var ds = new PGSimpleDataSource(); ds.setServerNames(new String[]{"127.0.0.1"}); ds.setPortNumbers(new int[]{port});
        ds.setDatabaseName(database); ds.setUser(user); ds.setPassword(password); ds.setCurrentSchema("workspace,pg_catalog");
        ds.setConnectTimeout(5); ds.setSocketTimeout(30); return ds;
    }
    private static String text(Connection db, String query) throws SQLException { try (var s = db.createStatement(); var r = s.executeQuery(query)) { require(r.next()); return r.getString(1); } }
    private static void sql(Connection db, String query) throws SQLException { try (var s = db.createStatement()) { s.execute(query); } }
    private static void require(boolean value) { if (!value) throw new IllegalStateException(); }
}
