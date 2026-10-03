package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.common.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.github.qianlixunbai.workspace.memory.MemoryLimits.*;

/** Small file-backed store. One connection per Runtime; SQLite arbitrates other processes. */
public final class MemoryStore implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    private Connection connection;
    public record Page(List<MemoryItem> items, long total, int page, int limit) {
        public Page { items = List.copyOf(items); }
    }

    public MemoryStore(Path directory, Path tokenFile) {
        try {
            Path file = PrivateMemoryDirectory.prepare(directory, tokenFile);
            connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
            execute("PRAGMA foreign_keys = ON");
            if (number("PRAGMA foreign_keys") != 1) throw new SQLException();
            try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equals(result.getString(1))) throw new SQLException();
            }
            transaction(true, () -> {
                long version = number("PRAGMA user_version");
                if (version > SCHEMA_VERSION || version < 0) throw error(ErrorCode.MEMORY_SCHEMA_UNSUPPORTED);
                if (version == 0) {
                    // v0 is an empty database only. Never infer or overwrite an unversioned existing schema.
                    if (number("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") != 0)
                        throw error(ErrorCode.MEMORY_SCHEMA_UNSUPPORTED);
                    createSource();
                    rebuildIndex(); // Verifies actual driver FTS5 + trigram availability inside migration.
                    execute("PRAGMA user_version = 1");
                } else {
                    // Fail closed on a missing/invalid source; derived index may be absent and is rebuilt.
                    execute("SELECT id,type,title,content,status,revision,source,created_at,updated_at FROM memory_items LIMIT 0");
                    rebuildIndex();
                }
                return null;
            });
            try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA journal_mode = DELETE")) {
                if (!result.next() || !"delete".equalsIgnoreCase(result.getString(1))) throw new SQLException();
            }
        } catch (WorkspaceException controlled) {
            close(); throw controlled;
        } catch (Exception ignored) {
            close(); throw error(ErrorCode.MEMORY_STORAGE_UNAVAILABLE);
        }
    }

    private void createSource() throws SQLException {
        execute("""
            CREATE TABLE memory_items (
              id TEXT NOT NULL UNIQUE,
              type TEXT NOT NULL CHECK(type IN ('PREFERENCE','PROJECT_NOTE')),
              title TEXT NOT NULL, content TEXT NOT NULL,
              status TEXT NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
              revision INTEGER NOT NULL CHECK(revision > 0),
              source TEXT NOT NULL CHECK(source = 'MANUAL'),
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
            """);
        execute("CREATE INDEX memory_items_order ON memory_items(status, updated_at DESC, id ASC)");
    }

    private void rebuildIndex() throws SQLException {
        execute("DROP TRIGGER IF EXISTS memory_insert");
        execute("DROP TRIGGER IF EXISTS memory_update");
        execute("DROP TRIGGER IF EXISTS memory_delete");
        execute("DROP TABLE IF EXISTS memory_fts");
        execute("CREATE VIRTUAL TABLE memory_fts USING fts5(title, content, tokenize='trigram case_sensitive 1')");
        execute("INSERT INTO memory_fts(rowid,title,content) SELECT rowid,title,content FROM memory_items");
        execute("""
            CREATE TRIGGER memory_insert AFTER INSERT ON memory_items BEGIN
              INSERT INTO memory_fts(rowid,title,content) VALUES(new.rowid,new.title,new.content);
            END
            """);
        execute("""
            CREATE TRIGGER memory_update AFTER UPDATE OF title,content ON memory_items BEGIN
              DELETE FROM memory_fts WHERE rowid=old.rowid;
              INSERT INTO memory_fts(rowid,title,content) VALUES(new.rowid,new.title,new.content);
            END
            """);
        execute("""
            CREATE TRIGGER memory_delete AFTER DELETE ON memory_items BEGIN
              DELETE FROM memory_fts WHERE rowid=old.rowid;
            END
            """);
    }

    /** Native maintenance primitive; source items, revisions and timestamps remain unchanged. */
    public synchronized void rebuildSearchIndex() {
        transaction(true, () -> { rebuildIndex(); return null; });
    }

    /** One read transaction, including both lifecycle states, ordered independently of UI pagination. */
    public synchronized List<MemoryItem> backupSnapshot() {
        return transaction(false, this::allSource);
    }

    private List<MemoryItem> allSource() throws SQLException {
        List<MemoryItem> rows = new ArrayList<>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT * FROM memory_items ORDER BY id ASC")) {
            while (result.next()) {
                if (rows.size() == TOTAL_ITEMS) throw error(ErrorCode.MEMORY_BACKUP_TOO_LARGE);
                rows.add(item(result));
            }
        }
        return List.copyOf(rows);
    }

    /** Staging-only reconstruction. Never use ordinary create/lifecycle commands for restore. */
    synchronized void reconstruct(List<MemoryItem> rows) {
        transaction(true, () -> {
            if (number("SELECT count(*) FROM memory_items") != 0) throw error(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY);
            for (MemoryItem row : rows) {
                try (var statement = prepare("INSERT INTO memory_items(id,type,title,content,status,revision,source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        row.id().toString(), row.type().name(), row.title(), row.content(), row.status().name(), row.revision(),
                        row.source().name(), row.createdAt().toEpochMilli(), row.updatedAt().toEpochMilli())) { statement.executeUpdate(); }
            }
            rebuildIndex();
            if (!allSource().equals(rows) || number("PRAGMA user_version") != SCHEMA_VERSION
                    || number("SELECT count(*) FROM memory_fts") != rows.size()
                    || number("SELECT count(*) FROM memory_items m LEFT JOIN memory_fts f ON f.rowid=m.rowid WHERE f.rowid IS NULL OR f.title!=m.title OR f.content!=m.content") != 0)
                throw error(ErrorCode.MEMORY_RESTORE_FAILED);
            execute("INSERT INTO memory_fts(memory_fts) VALUES('integrity-check')");
            try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equals(result.getString(1)) || result.next()) throw error(ErrorCode.MEMORY_RESTORE_FAILED);
            }
            // Exercise the actual search contract on every source, including archived records.
            for (MemoryItem row : rows) {
                String query = row.title().substring(0, row.title().offsetByCodePoints(0, Math.min(3, row.title().codePointCount(0, row.title().length()))));
                String sql = query.codePointCount(0, query.length()) >= 3
                        ? "SELECT count(*) FROM memory_fts f JOIN memory_items m ON m.rowid=f.rowid WHERE m.id=? AND memory_fts MATCH ?"
                        : "SELECT count(*) FROM memory_items WHERE id=? AND instr(title,?)>0";
                String value = query.codePointCount(0, query.length()) >= 3 ? "\"" + query.replace("\"", "\"\"") + "\"" : query;
                try (var statement = prepare(sql, row.id().toString(), value); var result = statement.executeQuery()) {
                    if (!result.next() || result.getLong(1) != 1) throw error(ErrorCode.MEMORY_RESTORE_FAILED);
                }
            }
            return null;
        });
    }

    public synchronized MemoryItem create(MemoryItem.Type type, String title, String content) {
        text(type, title, content);
        return transaction(true, () -> {
            if (number("SELECT count(*) FROM memory_items") >= TOTAL_ITEMS) throw error(ErrorCode.MEMORY_LIMIT_EXCEEDED);
            UUID id = UUID.randomUUID(); long now = Instant.now().toEpochMilli();
            try (var statement = prepare("INSERT INTO memory_items(id,type,title,content,status,revision,source,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,'MANUAL',?,?)",
                    id.toString(), type.name(), title, content, now, now)) { statement.executeUpdate(); }
            return read(id);
        });
    }

    public synchronized MemoryItem get(UUID id) {
        if (id == null) throw error(ErrorCode.MEMORY_INVALID);
        return transaction(false, () -> read(id));
    }

    public synchronized MemoryItem update(UUID id, long expectedRevision, MemoryItem.Type type, String title, String content) {
        text(type, title, content); revision(expectedRevision);
        return transaction(true, () -> {
            checkRevision(id, expectedRevision);
            try (var statement = prepare("UPDATE memory_items SET type=?,title=?,content=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                    type.name(), title, content, Instant.now().toEpochMilli(), id.toString(), expectedRevision)) {
                if (statement.executeUpdate() != 1) throw error(ErrorCode.MEMORY_REVISION_CONFLICT);
            }
            return read(id);
        });
    }

    public synchronized MemoryItem archive(UUID id, long expectedRevision) { return lifecycle(id, expectedRevision, MemoryItem.Status.ARCHIVED); }
    public synchronized MemoryItem restore(UUID id, long expectedRevision) { return lifecycle(id, expectedRevision, MemoryItem.Status.ACTIVE); }
    private MemoryItem lifecycle(UUID id, long expectedRevision, MemoryItem.Status status) {
        revision(expectedRevision);
        return transaction(true, () -> {
            checkRevision(id, expectedRevision);
            // Every accepted lifecycle command, including an already matching status, advances revision.
            try (var statement = prepare("UPDATE memory_items SET status=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                    status.name(), Instant.now().toEpochMilli(), id.toString(), expectedRevision)) {
                if (statement.executeUpdate() != 1) throw error(ErrorCode.MEMORY_REVISION_CONFLICT);
            }
            return read(id);
        });
    }

    public synchronized void delete(UUID id, long expectedRevision) {
        revision(expectedRevision);
        transaction(true, () -> {
            checkRevision(id, expectedRevision);
            try (var statement = prepare("DELETE FROM memory_items WHERE id=? AND revision=?", id.toString(), expectedRevision)) {
                if (statement.executeUpdate() != 1) throw error(ErrorCode.MEMORY_REVISION_CONFLICT);
            }
            return null;
        });
    }

    public synchronized Page list(MemoryItem.Status status, MemoryItem.Type type, String query, int page, int limit) {
        query(query, page, limit);
        return transaction(false, () -> {
            List<Object> parameters = new ArrayList<>();
            String where = " WHERE m.status=?";
            parameters.add((status == null ? MemoryItem.Status.ACTIVE : status).name());
            if (type != null) { where += " AND m.type=?"; parameters.add(type.name()); }
            if (query != null && !query.isEmpty()) {
                if (query.codePointCount(0, query.length()) >= 3) {
                    // Quoted literal phrase, never raw FTS syntax. Doubling quotes escapes FTS strings.
                    where += " AND m.rowid IN (SELECT rowid FROM memory_fts WHERE memory_fts MATCH ?)";
                    parameters.add("\"" + query.replace("\"", "\"\"") + "\"");
                } else {
                    where += " AND (instr(m.title,?)>0 OR instr(m.content,?)>0)";
                    parameters.add(query); parameters.add(query);
                }
            }
            long total;
            try (var statement = prepare("SELECT count(*) FROM memory_items m" + where, parameters.toArray());
                 var result = statement.executeQuery()) { result.next(); total = result.getLong(1); }
            parameters.add(limit); parameters.add((long) page * limit);
            List<MemoryItem> items = new ArrayList<>();
            try (var statement = prepare("SELECT m.* FROM memory_items m" + where + " ORDER BY m.updated_at DESC,m.id ASC LIMIT ? OFFSET ?", parameters.toArray());
                 var result = statement.executeQuery()) { while (result.next()) items.add(item(result)); }
            return new Page(items, total, page, limit);
        });
    }

    /** All references are resolved in request order in one SQLite read transaction, before task admission. */
    public synchronized List<MemorySnapshot> snapshotForAsk(List<MemoryReference> references) {
        MemoryReference.validate(references);
        var selection = List.copyOf(references);
        return transaction(false, () -> {
            List<MemorySnapshot> snapshot = new ArrayList<>();
            for (var reference : selection) {
                MemoryItem item;
                try { item = read(reference.id()); }
                catch (WorkspaceException failure) {
                    if (failure.error().code() == ErrorCode.MEMORY_NOT_FOUND) throw error(ErrorCode.MEMORY_SELECTION_STALE);
                    throw failure;
                }
                if (item.status() != MemoryItem.Status.ACTIVE || item.revision() != reference.revision())
                    throw error(ErrorCode.MEMORY_SELECTION_STALE);
                snapshot.add(new MemorySnapshot(item.id(), item.type(), item.title(), item.content(), item.revision()));
            }
            return List.copyOf(snapshot);
        });
    }

    private void checkRevision(UUID id, long expectedRevision) throws SQLException {
        if (read(id).revision() != expectedRevision) throw error(ErrorCode.MEMORY_REVISION_CONFLICT);
    }
    private MemoryItem read(UUID id) throws SQLException {
        if (id == null) throw error(ErrorCode.MEMORY_INVALID);
        try (var statement = prepare("SELECT * FROM memory_items WHERE id=?", id.toString()); var result = statement.executeQuery()) {
            if (!result.next()) throw error(ErrorCode.MEMORY_NOT_FOUND);
            return item(result);
        }
    }
    private static MemoryItem item(ResultSet result) throws SQLException {
        return new MemoryItem(UUID.fromString(result.getString("id")), MemoryItem.Type.valueOf(result.getString("type")),
                result.getString("title"), result.getString("content"), MemoryItem.Status.valueOf(result.getString("status")),
                result.getLong("revision"), MemoryItem.Source.valueOf(result.getString("source")),
                Instant.ofEpochMilli(result.getLong("created_at")), Instant.ofEpochMilli(result.getLong("updated_at")));
    }
    private PreparedStatement prepare(String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        } catch (SQLException failure) { statement.close(); throw failure; }
    }
    private long number(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
    }
    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private <T> T transaction(boolean write, Work<T> work) {
        boolean begun = false;
        try {
            if (connection == null || connection.isClosed()) throw new SQLException();
            execute(write ? "BEGIN IMMEDIATE" : "BEGIN"); begun = true;
            T result = work.run(); execute("COMMIT"); return result;
        } catch (Exception failure) {
            if (begun) {
                try { execute("ROLLBACK"); }
                catch (SQLException ignored) { close(); } // No uncertain transaction can be reused.
            }
            if (failure instanceof WorkspaceException controlled) throw controlled;
            throw error(ErrorCode.MEMORY_STORAGE_UNAVAILABLE);
        }
    }
    @Override public synchronized void close() {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) { /* Never log driver details. */ }
            connection = null;
        }
    }
}
