package io.github.qianlixunbai.workspace.persistence;

import java.sql.*;

/** The PostgreSQL path never initializes schemas or falls back to SQLite. */
public final class PostgresqlWorkspace {
    private PostgresqlWorkspace() {}

    public static void verify(Connection db) throws SQLException {
        if (!"PostgreSQL".equals(db.getMetaData().getDatabaseProductName())) throw new SQLException();
        try (var s = db.createStatement(); var r = s.executeQuery(
                "SELECT current_schema() = 'workspace' AND version = 1 FROM workspace.storage_metadata WHERE singleton = 1")) {
            if (!r.next() || !r.getBoolean(1) || r.next()) throw new SQLException();
        }
        try (var s = db.createStatement(); var r = s.executeQuery(
                "SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls) "
                + "AND NOT has_schema_privilege(current_user,'workspace','CREATE') FROM pg_roles WHERE rolname=current_user")) {
            if (!r.next() || !r.getBoolean(1)) throw new SQLException();
        }
        WorkspaceSchema.verify(db);
    }

    public static void begin(Connection db, boolean write) throws SQLException {
        try (var s = db.createStatement()) {
            s.execute(write ? "BEGIN" : "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY");
            s.execute("SET LOCAL lock_timeout = '3s'");
            s.execute("SET LOCAL statement_timeout = '30s'");
            // SQLite BEGIN IMMEDIATE serialized all writers, including cross-store count/sequence checks.
            if (write) {
                try (var r = s.executeQuery("SELECT singleton FROM workspace.write_lock WHERE singleton = 1 FOR UPDATE")) {
                    if (!r.next()) throw new SQLException();
                }
            }
        } catch (SQLException failure) {
            try (var s = db.createStatement()) { s.execute("ROLLBACK"); }
            catch (SQLException rollback) { db.close(); }
            throw failure;
        }
    }
}
