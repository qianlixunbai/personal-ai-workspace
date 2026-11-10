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

/** New synthetic target preparation for the real settings cutover only; no rollback matrix. */
public final class PostgresqlCutoverFixture {
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
            UUID memoryId;
            try (var memory = new MemoryStore(data, token)) {
                memoryId = memory.create(MemoryItem.Type.PROJECT_NOTE, "Synthetic cutover", "Synthetic cutover 中文 😀").id();
            }
            String documentId;
            try (var store = new KnowledgeStore(data, token); var ingestion = new KnowledgeIngestion(store)) {
                byte[] bytes = "# Cutover\nSynthetic preserved source 中文 😀\n".getBytes(StandardCharsets.UTF_8);
                var job = ingestion.upload(UUID.randomUUID().toString(), null, null, "cutover.md", bytes.length, new ByteArrayInputStream(bytes));
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (Set.of("PENDING", "PARSING").contains(store.job(job.requestId()).state()) && System.nanoTime() < deadline) Thread.sleep(10);
                require(store.job(job.requestId()).state().equals("READY")); documentId = job.documentId();
            }
            stage = "EXISTING_IMPORT_APIS";
            Path backups = root.resolve("evidence/cutover-backup"); require(!Files.exists(backups)); PrivateMemoryDirectory.directory(backups);
            SqliteWorkspaceMigration.migrate(data.resolve("memory.db"), backups.resolve("memory.db"), app);
            SqliteKnowledgeMigration.migrate(data.resolve("knowledge"), backups.resolve("knowledge"), app);
            require(!Files.exists(data.resolve("database.dpapi")));
            Files.writeString(root.resolve("evidence/cutover-data.json"), JsonMapper.builder().build().writeValueAsString(
                    Map.of("memoryId", memoryId.toString(), "documentId", documentId, "synthetic", true)), StandardOpenOption.CREATE_NEW);
            output.println("CUTOVER_TARGET_PREPARED_CONFIGURATION_ABSENT");
        } catch (Exception failure) {
            output.println("CUTOVER_FIXTURE_STOP " + stage + " " + failure.getClass().getSimpleName()); System.exit(10);
        } finally { Arrays.fill(input, (byte)0); }
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
