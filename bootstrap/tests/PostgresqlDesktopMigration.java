import java.io.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PasswordUtil;
import tools.jackson.databind.json.JsonMapper;

/** Explicit fresh synthetic database maintenance; never receives an administrator password. */
public final class PostgresqlDesktopMigration {
    private static void require(boolean value) throws IOException { if (!value) throw new IOException(); }
    private static void sql(Connection db, String query) throws SQLException {
        try (var s = db.createStatement()) { s.execute(query); }
    }
    private static long number(Connection db, String query) throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery(query)) {
            if (!r.next()) throw new SQLException();
            return r.getLong(1);
        }
    }
    private static PGSimpleDataSource source(String database, String user, String password) {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[]{"127.0.0.1"}); source.setPortNumbers(new int[]{5432});
        source.setDatabaseName(database); source.setUser(user); source.setPassword(password);
        source.setConnectTimeout(5); source.setSocketTimeout(30);
        return source;
    }
    public static void main(String[] args) {
        PrintStream output = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(System.out);
        String stage = "INPUT"; byte[] input = null;
        try {
            require(args.length == 1 && Set.of("--verifiers", "--migrate").contains(args[0]));
            input = System.in.readNBytes(16385); require(input.length < 16385);
            var config = JsonMapper.builder().build().readTree(input);
            String suffix = config.path("suffix").asString(); require(suffix.matches("[a-f0-9]{12}"));
            String database = "paw_pgdesktop_" + suffix, owner = "paw_pgd_owner_" + suffix, app = "paw_pgd_app_" + suffix;
            String ownerPassword = config.path("ownerPassword").asString(), appPassword = config.path("appPassword").asString();
            require(ownerPassword.length() >= 32 && appPassword.length() >= 32 && !ownerPassword.equals(appPassword));
            if (args[0].equals("--verifiers")) {
                for (String password : List.of(ownerPassword, appPassword)) {
                    char[] characters = password.toCharArray();
                    try { output.println(PasswordUtil.encodeScramSha256(characters)); }
                    finally { Arrays.fill(characters, '\0'); }
                }
                return;
            }
            var migrator = source(database, owner, ownerPassword);
            stage = "FRESH_TARGET_IDENTITY";
            try (var db = migrator.getConnection()) {
                try (var s = db.createStatement(); var r = s.executeQuery(
                        "SELECT d.datname,pg_get_userbyid(d.datdba),shobj_description(d.oid,'pg_database'),"
                        + "host(inet_server_addr()),inet_server_port(),current_user FROM pg_database d WHERE datname=current_database()")) {
                    require(r.next() && database.equals(r.getString(1)) && owner.equals(r.getString(2))
                        && ("PG_DESKTOP_SYNTHETIC_" + suffix).equals(r.getString(3))
                        && "127.0.0.1".equals(r.getString(4)) && r.getInt(5) == 5432 && owner.equals(r.getString(6)));
                }
                require(number(db, "SELECT count(*) FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname NOT IN ('public','information_schema')") == 0);
                require(number(db, "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'") == 0);
                sql(db, "CREATE SCHEMA workspace AUTHORIZATION " + owner);
                sql(db, "REVOKE ALL ON SCHEMA workspace FROM PUBLIC");
                sql(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
                sql(db, "GRANT USAGE ON SCHEMA workspace TO " + app);
            }
            stage = "WORKSPACE_V1_V2";
            var flyway = Flyway.configure().dataSource(migrator).schemas("workspace").defaultSchema("workspace")
                .createSchemas(false).locations("classpath:db/workspace/migration").cleanDisabled(true).load();
            var result = flyway.migrate(); require(result.migrationsExecuted == 2); flyway.validate();
            stage = "APP_GRANTS";
            try (var db = migrator.getConnection()) {
                sql(db, "GRANT SELECT ON ALL TABLES IN SCHEMA workspace TO " + app);
                sql(db, "GRANT UPDATE ON workspace.write_lock,workspace.knowledge_control TO " + app);
                sql(db, "GRANT INSERT,UPDATE,DELETE ON workspace.memory_items,workspace.conversations,workspace.conversation_turns,workspace.conversation_messages,workspace.conversation_memory_selections,workspace.knowledge_documents,workspace.knowledge_revisions,workspace.knowledge_jobs,workspace.knowledge_deletes,workspace.knowledge_index_metadata,workspace.knowledge_chunks TO " + app);
                sql(db, "REVOKE ALL ON FUNCTION workspace.reject_immutable_update() FROM PUBLIC");
                require(number(db, "SELECT count(*) FROM workspace.flyway_schema_history WHERE success AND version IN ('1','2')") == 2);
            }
            stage = "APP_PREFLIGHT";
            var application = source(database, app, appPassword); application.setCurrentSchema("workspace,pg_catalog");
            try (var db = application.getConnection()) {
                db.setReadOnly(true); db.setAutoCommit(false);
                io.github.qianlixunbai.workspace.persistence.PostgresqlWorkspace.verify(db);
                require(number(db, "SELECT format FROM workspace.knowledge_control WHERE singleton=1") == 1);
                require(number(db, "SELECT count(*) FROM workspace.memory_items") == 0
                    && number(db, "SELECT count(*) FROM workspace.conversations") == 0
                    && number(db, "SELECT count(*) FROM workspace.knowledge_documents") == 0);
                require(number(db, "SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)") == 0);
                require(number(db, "SELECT CASE WHEN has_schema_privilege(current_user,'public','CREATE') THEN 1 ELSE 0 END") == 0);
                db.rollback();
            }
            output.println("PG_DESKTOP_MIGRATION_PASS");
        } catch (Exception | LinkageError failure) {
            String state = failure instanceof SQLException sql ? sql.getSQLState() : null;
            output.println("PG_DESKTOP_STOP_" + stage + (state != null && state.matches("[A-Z0-9]{5}") ? "_SQLSTATE_" + state : ""));
            System.exit(10);
        } finally { if (input != null) Arrays.fill(input, (byte) 0); }
    }
}
