import java.io.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PasswordUtil;
import tools.jackson.databind.json.JsonMapper;

/** F-next-1 only: a NEW private test cluster and NEW synthetic databases. */
public final class FinanceNextPreparation {
    private static int port;
    private static String password;
    private static PGSimpleDataSource source(String database) {
        var ds = new PGSimpleDataSource(); ds.setServerNames(new String[]{"127.0.0.1"}); ds.setPortNumbers(new int[]{port});
        ds.setDatabaseName(database); ds.setUser("fnext_admin"); ds.setPassword(password); ds.setConnectTimeout(5); return ds;
    }
    private static void sql(Connection db, String text) throws SQLException { try (var statement = db.createStatement()) { statement.execute(text); } }
    private static void require(boolean condition) throws IOException { if (!condition) throw new IOException(); }
    public static void main(String[] args) {
        PrintStream output = System.out; System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(System.out);
        byte[] input = null; String stage = "INPUT";
        try {
            require(args.length == 1 && Set.of("prepare", "identity-change", "identity-restore", "swap", "unswap", "empty", "accounts-restore", "offline", "online").contains(args[0]));
            input = System.in.readNBytes(16385); require(input.length <= 16384);
            var config = JsonMapper.builder().build().readTree(input);
            port = config.path("port").asInt(); require(port >= 55000 && port <= 55999);
            password = config.path("adminPassword").asString();
            String suffix = config.path("suffix").asString(); require(suffix.matches("[a-f0-9]{12}"));
            String finance = "fnext1_finance_" + suffix, workspace = "fnext1_workspace_" + suffix;
            String reader = "fnext1_reader_" + suffix, writer = "fnext1_writer_" + suffix;
            stage = "LOCAL_CLUSTER";
            try (var db = source("postgres").getConnection()) {
                try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT current_user, inet_server_port(), current_setting('data_directory')")) {
                    require(result.next() && result.getString(1).equals("fnext_admin") && result.getInt(2) == port
                        && result.getString(3).replace('\\', '/').endsWith("/PersonalAiWorkspace-Fnext1/" + suffix + "/pgdata"));
                }
                if (!args[0].equals("prepare")) {
                    stage = args[0];
                    try (var statement = db.prepareStatement("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=?")) {
                        statement.setString(1, finance);
                        try (var result = statement.executeQuery()) { require(result.next() && ("FNEXT1_SYNTHETIC_" + suffix).equals(result.getString(1))); }
                    }
                    if (args[0].equals("swap")) {
                        sql(db, "CREATE DATABASE fnext1_clone_" + suffix + " TEMPLATE " + finance);
                        sql(db, "COMMENT ON DATABASE fnext1_clone_" + suffix + " IS 'FNEXT1_SYNTHETIC_" + suffix + "'");
                        sql(db, "ALTER DATABASE " + finance + " RENAME TO fnext1_original_" + suffix);
                        sql(db, "ALTER DATABASE fnext1_clone_" + suffix + " RENAME TO " + finance);
                    } else if (args[0].equals("unswap")) {
                        sql(db, "ALTER DATABASE " + finance + " RENAME TO fnext1_swapped_" + suffix);
                        sql(db, "ALTER DATABASE fnext1_original_" + suffix + " RENAME TO " + finance);
                    } else if (args[0].equals("offline") || args[0].equals("online")) {
                        sql(db, "ALTER ROLE " + reader + (args[0].equals("offline") ? " NOLOGIN" : " LOGIN"));
                    } else try (var data = source(finance).getConnection()) {
                        sql(data, switch (args[0]) {
                            case "identity-change" -> "UPDATE users SET email='changed@example.invalid' WHERE id=1";
                            case "identity-restore" -> "UPDATE users SET email='alice@example.invalid' WHERE id=1";
                            case "empty" -> "UPDATE accounts SET currency='USD' WHERE user_id=1";
                            case "accounts-restore" -> "UPDATE accounts SET currency='CNY' WHERE user_id=1 AND id IN (1,2)";
                            default -> throw new IOException();
                        });
                    }
                    output.println("FNEXT_FIXTURE_PASS_" + stage); return;
                }
                for (String name : List.of(finance, workspace, reader, writer)) {
                    try (var statement = db.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=?) OR EXISTS(SELECT 1 FROM pg_roles WHERE rolname=?)")) {
                        statement.setString(1, name); statement.setString(2, name);
                        try (var result = statement.executeQuery()) { require(result.next() && !result.getBoolean(1)); }
                    }
                }
                for (String role : List.of(reader, writer)) {
                    char[] secret = config.path(role.equals(reader) ? "readerPassword" : "writerPassword").asString().toCharArray();
                    try { sql(db, "CREATE ROLE " + role + " LOGIN PASSWORD '" + PasswordUtil.encodeScramSha256(secret) + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"); }
                    finally { Arrays.fill(secret, '\0'); }
                }
                sql(db, "CREATE DATABASE " + finance); sql(db, "CREATE DATABASE " + workspace);
                for (String database : List.of(finance, workspace)) sql(db, "COMMENT ON DATABASE " + database + " IS 'FNEXT1_SYNTHETIC_" + suffix + "'");
                for (String database : List.of(finance, workspace)) sql(db, "REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
                sql(db, "GRANT CONNECT ON DATABASE " + finance + " TO " + reader);
                sql(db, "GRANT CONNECT ON DATABASE " + workspace + " TO " + writer);
                sql(db, "ALTER ROLE " + reader + " SET default_transaction_read_only=on");
            }
            stage = "FINANCE_V1_V21";
            var migrations = Flyway.configure().dataSource(source(finance)).locations("classpath:db/finance/migration").cleanDisabled(true).load();
            require(migrations.migrate().migrationsExecuted == 21); migrations.validate();
            try (var db = source(finance).getConnection()) {
                sql(db, "INSERT INTO users(id,username,email,password_hash) VALUES(1,'Synthetic Alice','alice@example.invalid','not-a-login'),(2,'Synthetic Bob','bob@example.invalid','not-a-login')");
                sql(db, "INSERT INTO accounts(id,user_id,name,type,currency,balance,status) VALUES(1,1,'合成 CNY 账户','BANK','CNY',9007199254740993.10,'ACTIVE'),(2,1,'合成零钱','CASH','CNY',0.20,'INACTIVE'),(3,2,'Bob private','BANK','CNY',88.88,'ACTIVE'),(4,1,'USD excluded','BANK','USD',999.99,'ACTIVE')");
                sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC"); sql(db, "GRANT USAGE ON SCHEMA public TO " + reader);
                sql(db, "GRANT SELECT ON public.users,public.accounts,public.flyway_schema_history TO " + reader);
                sql(db, "GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO " + reader);
            }
            stage = "WORKSPACE_V1_V2";
            try (var db = source(workspace).getConnection()) {
                sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC"); sql(db, "CREATE SCHEMA workspace"); sql(db, "REVOKE ALL ON SCHEMA workspace FROM PUBLIC");
            }
            var memory = Flyway.configure().dataSource(source(workspace)).schemas("workspace").defaultSchema("workspace")
                .createSchemas(false).locations("classpath:db/workspace/migration").cleanDisabled(true).load();
            require(memory.migrate().migrationsExecuted == 2); memory.validate();
            try (var db = source(workspace).getConnection()) {
                sql(db, "GRANT USAGE ON SCHEMA workspace TO " + writer);
                sql(db, "GRANT SELECT ON ALL TABLES IN SCHEMA workspace TO " + writer);
                sql(db, "GRANT UPDATE ON workspace.write_lock,workspace.knowledge_control TO " + writer);
                sql(db, "GRANT INSERT,UPDATE,DELETE ON workspace.memory_items,workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections,workspace.knowledge_documents,workspace.knowledge_revisions,workspace.knowledge_jobs,workspace.knowledge_deletes,workspace.knowledge_index_metadata,workspace.knowledge_chunks TO " + writer);
                sql(db, "REVOKE ALL ON FUNCTION workspace.reject_immutable_update() FROM PUBLIC");
            }
            output.println("FNEXT_FIXTURE_PASS_prepare");
        } catch (Exception | LinkageError rejected) {
            output.println("FNEXT_FIXTURE_STOP_" + stage + "_" + (rejected instanceof SQLException sql ? sql.getSQLState() : rejected.getClass().getSimpleName())); System.exit(10);
        } finally { if (input != null) Arrays.fill(input, (byte) 0); password = null; }
    }
}
