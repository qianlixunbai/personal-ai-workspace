package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.memory.PrivateMemoryDirectory;
import io.github.qianlixunbai.workspace.memory.MemoryBackup;
import io.github.qianlixunbai.workspace.memory.MemoryItem;
import io.github.qianlixunbai.workspace.conversation.ConversationLimits;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteOpenMode;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Offline, explicitly invoked migration. No startup hook, HTTP route, credential input or fallback. */
public final class SqliteWorkspaceMigration {
    private SqliteWorkspaceMigration() {}
    private record Table(String name, String columns, String order) {}
    private static final List<Table> TABLES = List.of(
            new Table("memory_items", "id,type,title,content,status,revision,source,created_at,updated_at", "id"),
            new Table("conversations", "id,title,status,created_at,updated_at", "id"),
            new Table("conversation_turns", "id,conversation_id,sequence,status,created_at,updated_at,task_id,failure_code", "conversation_id,sequence"),
            new Table("conversation_messages", "id,turn_id,role,content,created_at", "id"),
            new Table("conversation_memory_selections", "turn_id,position,memory_id,revision", "turn_id,position"));

    public record Result(Map<String, Long> rows, String contentDigest) {
        public Result { rows = Map.copyOf(rows); }
        @Override public String toString() { return "WorkspaceMigration[private]"; }
    }

    /**
     * The operator must stop Runtime before invoking this offline tool and keep it stopped until
     * switching authority. BEGIN IMMEDIATE additionally excludes SQLite writers during this call.
     * The source is never modified; a failed attempt retains its private consistent backup.
     */
    public static Result migrate(Path source, Path backup, DataSource target) throws IOException {
        source = source.toAbsolutePath().normalize(); backup = backup.toAbsolutePath().normalize();
        try {
            if (source.equals(backup) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(backup.getParent(), LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            PrivateMemoryDirectory.noLinks(source); verifyPrivate(source, false);
            PrivateMemoryDirectory.noLinks(backup); verifyPrivate(backup.getParent(), true);
            if (WindowsPrivateAcl.isWindows()) WindowsPrivateAcl.createFile(backup);
            else Files.createFile(backup, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            PrivateMemoryDirectory.protect(backup, false);
            var write = new SQLiteConfig(); write.resetOpenMode(SQLiteOpenMode.CREATE); write.setBusyTimeout(3000);
            try (var freeze = DriverManager.getConnection("jdbc:sqlite:" + source, write.toProperties())) {
                sql(freeze, "BEGIN IMMEDIATE");
                try (var reader = readOnly(source)) {
                    verifySource(reader);
                    int code = reader.unwrap(SQLiteConnection.class).getDatabase().backup("main", backup.toString(), null);
                    if (code != 0) throw new SQLException();
                    PrivateMemoryDirectory.protect(backup, false);
                    try (var snapshot = readOnly(backup)) {
                        verifySource(snapshot);
                        if (!summarize(freeze).equals(summarize(snapshot))) throw new SQLException();
                        return importSnapshot(snapshot, target);
                    }
                } finally { sql(freeze, "ROLLBACK"); }
            }
        } catch (Exception failure) {
            // Never log paths, SQL parameters, source text or database diagnostics.
            throw new IOException("Workspace migration stopped; retain backup and inspect target receipt");
        }
    }

    /**
     * Database import primitive for an already isolated SQLite snapshot (including in-memory
     * synthetic datasets). The source read transaction pins the entire count/digest/import view.
     * Real-file callers use migrate(), which owns private backup and source writer exclusion.
     */
    public static Result importSnapshot(Connection snapshot, DataSource target) throws IOException {
        boolean sourceBegun = false;
        String stage = "SOURCE_VALIDATION";
        try {
            if (!snapshot.getAutoCommit() || !"SQLite".equals(snapshot.getMetaData().getDatabaseProductName())) throw new SQLException();
            sql(snapshot, "BEGIN"); sourceBegun = true;
            verifySource(snapshot);
            Result expected = summarize(snapshot);
            stage = "TARGET_ADMISSION";
            try (var db = target.getConnection()) {
                PostgresqlWorkspace.verify(db);
                PostgresqlWorkspace.begin(db, true);
                try {
                    stage = "EMPTY_TARGET_CHECK";
                    if (count(db, "SELECT count(*) FROM workspace.sqlite_import") != 0) throw new SQLException();
                    for (Table table : TABLES)
                        if (count(db, "SELECT count(*) FROM " + table.name) != 0) throw new SQLException();
                    stage = "COPY_AND_VERIFY";
                    for (Table table : TABLES) copy(snapshot, db, table);
                    validateRows(db);
                    if (!expected.equals(summarize(db))) throw new SQLException();
                    try (var s = db.prepareStatement("INSERT INTO workspace.sqlite_import VALUES(1,?,?)")) {
                        s.setString(1, expected.contentDigest); s.setLong(2, System.currentTimeMillis()); s.executeUpdate();
                    }
                    stage = "COMMIT"; sql(db, "COMMIT");
                } catch (Exception failure) {
                    try { sql(db, "ROLLBACK"); } catch (SQLException ignored) { /* Connection is closed below. */ }
                    throw failure;
                }
            }
            // Prove commit visibility with a fresh application connection, never merely connection success.
            stage = "COMMIT_VISIBILITY";
            try (var committed = target.getConnection()) {
                PostgresqlWorkspace.begin(committed, false);
                if (!expected.equals(summarize(committed))) throw new SQLException();
                try (var s = committed.createStatement(); var r = s.executeQuery("SELECT source_digest FROM workspace.sqlite_import WHERE singleton=1")) {
                    if (!r.next() || !expected.contentDigest.equals(r.getString(1))) throw new SQLException();
                }
                sql(committed, "COMMIT");
            }
            return expected;
        } catch (Exception failure) {
            String state = failure instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().matches("[A-Z0-9]{5}") ? sql.getSQLState() : "UNAVAILABLE";
            throw new IOException("Workspace snapshot import stopped: " + stage + "; SQLSTATE=" + state
                    + "; inspect receipt before retry; no automatic fallback");
        } finally {
            if (sourceBegun) try { sql(snapshot, "ROLLBACK"); } catch (SQLException ignored) { /* Caller closes source. */ }
        }
    }

    private static Connection readOnly(Path path) throws SQLException {
        var config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(3000);
        return DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
    }
    private static void verifyPrivate(Path path, boolean directory) throws IOException {
        if (WindowsPrivateAcl.isWindows()) { WindowsPrivateAcl.verify(path, directory); return; }
        var attributes = Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var user = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if (attributes.isDirectory() != directory || !attributes.owner().equals(user)
                || !attributes.permissions().equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")))
            throw new IOException("Private migration object required");
    }
    private static void verifySource(Connection db) throws SQLException {
        if (count(db, "PRAGMA user_version") != WorkspaceSchema.VERSION) throw new SQLException();
        try (var s = db.createStatement(); var r = s.executeQuery("PRAGMA integrity_check")) {
            if (!r.next() || !"ok".equals(r.getString(1)) || r.next()) throw new SQLException();
        }
        try (var s = db.createStatement(); var r = s.executeQuery("PRAGMA foreign_key_check")) { if (r.next()) throw new SQLException(); }
        Set<String> allowed = new HashSet<>(List.of("memory_fts", "memory_fts_data", "memory_fts_idx",
                "memory_fts_content", "memory_fts_docsize", "memory_fts_config"));
        TABLES.forEach(t -> allowed.add(t.name));
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (r.next()) if (!allowed.contains(r.getString(1))) throw new SQLException();
        }
        for (Table table : TABLES) {
            List<String> columns = new ArrayList<>();
            try (var s = db.createStatement(); var r = s.executeQuery("PRAGMA table_info(" + table.name + ")")) {
                while (r.next()) columns.add(r.getString("name"));
            }
            if (!columns.equals(List.of(table.columns.split(",")))) throw new SQLException();
        }
        validateRows(db);
    }
    private static void validateRows(Connection db) throws SQLException {
        if (count(db, "SELECT count(*) FROM memory_items") > 1000 || count(db, "SELECT count(*) FROM conversations") > 1000
                || count(db, "SELECT count(*) FROM conversation_turns WHERE status='PENDING'") != 0
                || count(db, "SELECT count(*) FROM (SELECT conversation_id FROM conversation_turns GROUP BY conversation_id HAVING min(sequence)<>1 OR max(sequence)<>count(*) OR count(*)>1000) AS invalid_sequences") != 0
                || count(db, "SELECT count(*) FROM conversation_turns t WHERE (SELECT count(*) FROM conversation_messages m WHERE m.turn_id=t.id AND m.role='USER')<>1 OR (SELECT count(*) FROM conversation_messages m WHERE m.turn_id=t.id AND m.role='ASSISTANT')<>CASE WHEN t.status='SUCCEEDED' THEN 1 ELSE 0 END") != 0)
            throw new SQLException();
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT * FROM memory_items")) {
            while (r.next()) MemoryBackup.validateRow(new MemoryItem(canonicalId(r.getString("id")),
                    MemoryItem.Type.valueOf(r.getString("type")), r.getString("title"), r.getString("content"),
                    MemoryItem.Status.valueOf(r.getString("status")), r.getLong("revision"),
                    MemoryItem.Source.valueOf(r.getString("source")), java.time.Instant.ofEpochMilli(r.getLong("created_at")),
                    java.time.Instant.ofEpochMilli(r.getLong("updated_at"))));
        }
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT title FROM conversations")) {
            while (r.next()) {
                String title = r.getString(1); ConversationLimits.content(title);
                if (!title.equals(title.strip()) || title.codePointCount(0, title.length()) > ConversationLimits.TITLE_POINTS)
                    throw new SQLException();
            }
        }
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT content FROM conversation_messages")) {
            while (r.next()) ConversationLimits.content(r.getString(1));
        }
        for (Table table : TABLES) {
            try (var s = db.createStatement(); var r = s.executeQuery("SELECT " + table.columns + " FROM " + table.name)) {
                while (r.next()) for (String column : table.columns.split(","))
                    if (column.equals("id") || column.endsWith("_id")) {
                        String value = r.getString(column);
                        if (value != null) canonicalId(value);
                    }
            }
        }
    }
    private static UUID canonicalId(String value) throws SQLException {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value) || id.equals(new UUID(0, 0))) throw new SQLException();
            return id;
        } catch (IllegalArgumentException failure) { throw new SQLException(); }
    }
    private static void copy(Connection source, Connection target, Table table) throws SQLException {
        String[] columns = table.columns.split(",");
        try (var read = source.createStatement(); var rows = read.executeQuery("SELECT " + table.columns + " FROM " + table.name + " ORDER BY " + table.order);
             var insert = target.prepareStatement("INSERT INTO " + table.name + "(" + table.columns + ") VALUES(" + String.join(",", Collections.nCopies(columns.length, "?")) + ")")) {
            while (rows.next()) {
                for (int i = 0; i < columns.length; i++) {
                    Object value = rows.getObject(i + 1);
                    if (value == null) insert.setNull(i + 1, Types.VARCHAR);
                    else insert.setObject(i + 1, value);
                }
                insert.executeUpdate();
            }
        }
    }
    private static Result summarize(Connection db) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<String, Long> counts = new LinkedHashMap<>();
        try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            for (Table table : TABLES) {
                output.writeUTF(table.name); output.writeUTF(table.columns); long rows = 0;
                try (var s = db.createStatement(); var r = s.executeQuery("SELECT " + table.columns + " FROM " + table.name + " ORDER BY " + table.order)) {
                    while (r.next()) {
                        rows++; output.writeByte(1);
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                            String value = r.getString(i);
                            byte[] bytes = value == null ? null : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            output.writeInt(bytes == null ? -1 : bytes.length);
                            if (bytes != null) output.write(bytes);
                        }
                    }
                }
                output.writeByte(0); counts.put(table.name, rows);
            }
        }
        return new Result(counts, HexFormat.of().formatHex(digest.digest()));
    }
    private static long count(Connection db, String query) throws SQLException {
        try (var s = db.createStatement(); var r = s.executeQuery(query)) { if (!r.next()) throw new SQLException(); return r.getLong(1); }
    }
    private static void sql(Connection db, String query) throws SQLException {
        try (var s = db.createStatement()) { s.execute(query); }
    }
}
