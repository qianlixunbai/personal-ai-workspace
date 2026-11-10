package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.knowledge.SqliteKnowledgeMigration;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;

/** One target transaction for already isolated Workspace/Knowledge snapshots.
 * Not a live-file migration entry: a future native maintenance owner must prove source stop,
 * retain complete backups and own configuration publication. This class never changes authority. */
public final class SqliteCombinedMigration {
    private SqliteCombinedMigration() {}

    /** Conservative same-instance binding, not a durable cluster identity or admission capability. */
    public record Target(String address, int port, long databaseOid, long roleOid, String startedAt) {
        @Override public String toString() { return "MigrationTarget[private]"; }
    }
    public record Expected(SqliteWorkspaceMigration.Result workspace, SqliteKnowledgeMigration.Result knowledge,
                           Target target) {
        @Override public String toString() { return "CombinedMigration[private]"; }
    }
    public enum Inspection { EMPTY, MATCHING_DATA_AND_RECEIPTS, DIFFERENT_OR_PARTIAL, TARGET_CHANGED }
    public enum Outcome { NO_COMMIT_ATTEMPTED, COMMIT_UNCONFIRMED }
    public static final class Failure extends IOException {
        private final Outcome outcome;
        private final Expected expected;
        Failure(String stage, Outcome outcome, Expected expected) {
            super("Combined import stopped: " + stage + "; " + outcome
                    + "; inspect both domains before any new attempt; configuration unchanged");
            this.outcome = outcome; this.expected = expected;
        }
        public Outcome outcome() { return outcome; }
        /** Null if admission did not reach a complete expected-content and target binding. */
        public Expected expected() { return expected; }
    }

    /** Caller owns isolated source connections and files. There is exactly one target COMMIT.
     * Any exception at/after COMMIT remains unconfirmed; never retry or activate automatically. */
    public static Expected importSnapshots(Connection workspace, Connection knowledge, Path knowledgeFiles,
                                           DataSource target) throws IOException {
        String stage = "SOURCE_VALIDATION";
        boolean commitAttempted = false;
        Expected expected = null;
        try (var memory = new SqliteWorkspaceMigration.Snapshot(workspace);
             var documents = new SqliteKnowledgeMigration.Snapshot(knowledge, knowledgeFiles)) {
            stage = "TARGET_ADMISSION";
            try (var db = target.getConnection()) {
                if (!db.getAutoCommit()) throw new SQLException();
                PostgresqlWorkspace.verify(db);
                expected = new Expected(memory.expected, documents.expected(), identity(db));
                db.setAutoCommit(false);
                try {
                    stage = "TARGET_EXCLUSION";
                    sql(db, "SET LOCAL lock_timeout='3s'");
                    sql(db, "SET LOCAL statement_timeout='30s'");
                    // Same keys as KnowledgeStore: exclude store lifetime first, then writers.
                    requireTrue(db, "SELECT pg_try_advisory_xact_lock(170017,2)");
                    sql(db, "SELECT pg_advisory_xact_lock(170017,3)");
                    requireTrue(db, "SELECT singleton=1 FROM workspace.knowledge_control WHERE singleton=1 FOR UPDATE");
                    requireTrue(db, "SELECT singleton=1 FROM workspace.write_lock WHERE singleton=1 FOR UPDATE");
                    stage = "WORKSPACE_COPY"; memory.writeTo(db);
                    stage = "KNOWLEDGE_COPY"; documents.writeTo(db);
                    stage = "SOURCE_RECHECK"; documents.verifyFiles();
                    stage = "COMMIT"; commitAttempted = true; db.commit();
                } catch (Exception failure) {
                    // A rollback after a failed COMMIT cannot prove that COMMIT did not happen.
                    try { db.rollback(); } catch (SQLException ignored) { /* Close below. */ }
                    throw failure;
                }
            }
            stage = "COMMIT_VISIBILITY";
            if (inspect(target, expected) != Inspection.MATCHING_DATA_AND_RECEIPTS) throw new SQLException();
            documents.verifyFiles();
            return expected;
        } catch (Exception failure) {
            // No SQL diagnostics, source text, credential or path escapes this boundary.
            throw new Failure(stage, commitAttempted ? Outcome.COMMIT_UNCONFIRMED : Outcome.NO_COMMIT_ATTEMPTED, expected);
        }
    }

    /** Read-only observation of both domains in one snapshot; never authorizes cutover, restore
     * or replay. Matching legacy receipts alone do not prove a historical coordinated commit. */
    public static Inspection inspect(DataSource target, Expected expected) throws IOException {
        if (expected == null || expected.workspace == null || expected.knowledge == null || expected.target == null)
            throw new IOException("Expected migration binding required");
        try (var db = target.getConnection()) {
            if (!db.getAutoCommit()) throw new SQLException();
            PostgresqlWorkspace.verify(db);
            PostgresqlWorkspace.begin(db, false);
            try {
                Inspection result;
                if (!expected.target.equals(identity(db))) result = Inspection.TARGET_CHANGED;
                else if (SqliteWorkspaceMigration.matchesCommitted(db, expected.workspace)
                        && SqliteKnowledgeMigration.matchesCommitted(db, expected.knowledge))
                    result = Inspection.MATCHING_DATA_AND_RECEIPTS;
                else result = empty(db) ? Inspection.EMPTY : Inspection.DIFFERENT_OR_PARTIAL;
                sql(db, "COMMIT");
                return result;
            } catch (Exception failure) {
                try { sql(db, "ROLLBACK"); } catch (SQLException ignored) { /* Close below. */ }
                throw failure;
            }
        } catch (Exception failure) { throw new IOException("Combined inspection unavailable; preserve both domains and configuration"); }
    }

    private static Target identity(Connection db) throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery(
                "SELECT host(inet_server_addr()),inet_server_port(),d.oid,r.oid,pg_postmaster_start_time()::text "
                + "FROM pg_database d,pg_roles r WHERE d.datname=current_database() AND r.rolname=current_user")) {
            if (!r.next()) throw new SQLException();
            var target = new Target(r.getString(1), r.getInt(2), r.getLong(3), r.getLong(4), r.getString(5));
            if (target.address == null || target.port < 1 || target.databaseOid == 0 || target.roleOid == 0
                    || target.startedAt == null || r.next()) throw new SQLException();
            return target;
        }
    }
    private static boolean empty(Connection db) throws SQLException {
        for (String table : List.of("memory_items", "conversations", "conversation_turns", "conversation_messages",
                "conversation_memory_selections", "sqlite_import", "knowledge_documents", "knowledge_revisions",
                "knowledge_jobs", "knowledge_deletes", "knowledge_chunks", "knowledge_index_metadata", "knowledge_sqlite_import")) {
            try (var s = db.createStatement(); var r = s.executeQuery("SELECT EXISTS(SELECT 1 FROM workspace." + table + ")")) {
                if (!r.next() || r.getBoolean(1)) return false;
            }
        }
        return true;
    }
    private static void requireTrue(Connection db, String query) throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery(query)) {
            if (!r.next() || !r.getBoolean(1) || r.next()) throw new SQLException();
        }
    }
    private static void sql(Connection db, String query) throws SQLException {
        try (var s = db.createStatement()) { s.execute(query); }
    }
}
