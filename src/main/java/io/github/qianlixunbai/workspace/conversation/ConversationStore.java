package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.persistence.WorkspaceSchema;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.github.qianlixunbai.workspace.conversation.Conversation.*;
import static io.github.qianlixunbai.workspace.conversation.ConversationLimits.*;

/** Independent durable domain; no TaskManager, provider or Memory business dependency. */
public final class ConversationStore implements AutoCloseable {
    private Connection db;
    /** Location must come from the already initialized, private Workspace SQLite infrastructure. */
    public ConversationStore(Path databaseFile) {
        try {
            db = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            execute("PRAGMA foreign_keys=ON"); execute("PRAGMA busy_timeout=3000");
            if (scalar("PRAGMA foreign_keys") != 1 || scalar("PRAGMA user_version") != WorkspaceSchema.VERSION)
                throw new SQLException();
            WorkspaceSchema.verify(db);
        } catch (Exception ignored) { close(); throw error(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE); }
    }
    public synchronized Conversation create(String title) {
        String value = title(title == null ? DEFAULT_TITLE : title);
        return transaction(true, () -> {
            if (scalar("SELECT count(*) FROM conversations") >= TOTAL_CONVERSATIONS)
                throw error(ErrorCode.CONVERSATION_LIMIT_EXCEEDED);
            UUID id = UUID.randomUUID(); long now = Instant.now().toEpochMilli();
            update("INSERT INTO conversations VALUES(?,?,'ACTIVE',?,?)", id.toString(), value, now, now);
            return read(id);
        });
    }
    public synchronized Conversation rename(UUID id, String title) {
        id(id); String value = title(title);
        return transaction(true, () -> {
            read(id); update("UPDATE conversations SET title=?,updated_at=max(updated_at,?) WHERE id=?", value, now(), id.toString());
            return read(id);
        });
    }
    public synchronized Conversation archive(UUID id) { return lifecycle(id, Status.ARCHIVED); }
    public synchronized Conversation unarchive(UUID id) { return lifecycle(id, Status.ACTIVE); }
    private Conversation lifecycle(UUID id, Status status) {
        id(id);
        return transaction(true, () -> {
            read(id); update("UPDATE conversations SET status=?,updated_at=max(updated_at,?) WHERE id=?", status.name(), now(), id.toString());
            return read(id);
        });
    }
    public synchronized void delete(UUID id) {
        id(id); transaction(true, () -> { read(id); update("DELETE FROM conversations WHERE id=?", id.toString()); return null; });
    }
    public synchronized Page list(Status status, int page, int limit) {
        page(page, limit); Status filter = status == null ? Status.ACTIVE : status;
        return transaction(false, () -> {
            long total = count("SELECT count(*) FROM conversations WHERE status=?", filter.name());
            List<Conversation> items = new ArrayList<>();
            try (var s = prepare("SELECT * FROM conversations WHERE status=? ORDER BY updated_at DESC,id ASC LIMIT ? OFFSET ?",
                    filter.name(), limit, (long)page * limit); var r = s.executeQuery()) {
                while (r.next()) items.add(conversation(r));
            }
            return new Page(items, total, page, limit);
        });
    }
    public synchronized Detail detail(UUID id, int page, int limit) {
        id(id); page(page, limit);
        return transaction(false, () -> {
            Conversation conversation = read(id);
            long total = count("SELECT count(*) FROM conversation_turns WHERE conversation_id=?", id.toString());
            List<Turn> turns = new ArrayList<>();
            try (var s = prepare("SELECT id FROM conversation_turns WHERE conversation_id=? ORDER BY sequence ASC LIMIT ? OFFSET ?",
                    id.toString(), limit, (long)page * limit); var r = s.executeQuery()) {
                while (r.next()) turns.add(readTurn(id, UUID.fromString(r.getString(1))));
            }
            return new Detail(conversation, turns, total, page, limit);
        });
    }
    /** Internal Java service primitive only; no HTTP write route for turns or messages. */
    public synchronized Turn createTurnWithUserMessage(UUID conversationId, String content) {
        id(conversationId); content(content);
        return transaction(true, () -> {
            Conversation conversation = read(conversationId);
            if (conversation.status() != Status.ACTIVE) throw error(ErrorCode.CONVERSATION_CONFLICT);
            long sequence = count("SELECT coalesce(max(sequence),0)+1 FROM conversation_turns WHERE conversation_id=?", conversationId.toString());
            if (sequence > TURNS_PER_CONVERSATION) throw error(ErrorCode.CONVERSATION_LIMIT_EXCEEDED);
            UUID turnId = UUID.randomUUID(); long now = Math.max(now(), conversation.updatedAt().toEpochMilli());
            update("INSERT INTO conversation_turns VALUES(?,?,?,'PENDING',?,?)", turnId.toString(), conversationId.toString(), sequence, now, now);
            message(turnId, Role.USER, content, now);
            touch(conversationId, now);
            return readTurn(conversationId, turnId);
        });
    }
    public synchronized Turn completeTurnWithAssistantMessage(UUID conversationId, UUID turnId, String content) {
        content(content); return finish(conversationId, turnId, TurnStatus.SUCCEEDED, content);
    }
    public synchronized Turn markTurnTerminated(UUID conversationId, UUID turnId, TurnStatus status) {
        if (status != TurnStatus.FAILED && status != TurnStatus.CANCELLED && status != TurnStatus.TIMED_OUT)
            throw error(ErrorCode.CONVERSATION_INVALID);
        return finish(conversationId, turnId, status, null);
    }
    private Turn finish(UUID conversationId, UUID turnId, TurnStatus status, String assistant) {
        id(conversationId); id(turnId);
        return transaction(true, () -> {
            read(conversationId); Turn turn = readTurn(conversationId, turnId);
            if (turn.status() != TurnStatus.PENDING) throw error(ErrorCode.CONVERSATION_CONFLICT);
            long now = Math.max(now(), turn.updatedAt().toEpochMilli());
            if (assistant != null) message(turnId, Role.ASSISTANT, assistant, now);
            update("UPDATE conversation_turns SET status=?,updated_at=? WHERE id=?", status.name(), now, turnId.toString());
            touch(conversationId, now);
            return readTurn(conversationId, turnId);
        });
    }
    private void touch(UUID conversationId, long time) throws SQLException {
        update("UPDATE conversations SET updated_at=max(updated_at,?) WHERE id=?", time, conversationId.toString());
    }
    private void message(UUID turnId, Role role, String content, long time) throws SQLException {
        update("INSERT INTO conversation_messages VALUES(?,?,?,?,?)", UUID.randomUUID().toString(), turnId.toString(), role.name(), content, time);
    }
    private Turn readTurn(UUID conversationId, UUID id) throws SQLException {
        try (var s = prepare("SELECT * FROM conversation_turns WHERE id=? AND conversation_id=?", id.toString(), conversationId.toString()); var r = s.executeQuery()) {
            if (!r.next()) throw error(ErrorCode.CONVERSATION_NOT_FOUND);
            Message user = null, assistant = null;
            try (var messages = prepare("SELECT * FROM conversation_messages WHERE turn_id=?", id.toString()); var rows = messages.executeQuery()) {
                while (rows.next()) {
                    Message m = new Message(UUID.fromString(rows.getString("id")), id, Role.valueOf(rows.getString("role")),
                            rows.getString("content"), Instant.ofEpochMilli(rows.getLong("created_at")));
                    if (m.role() == Role.USER) user = m; else assistant = m;
                }
            }
            TurnStatus status = TurnStatus.valueOf(r.getString("status"));
            if (user == null || (status == TurnStatus.SUCCEEDED) != (assistant != null)) throw new SQLException();
            return new Turn(id, conversationId, r.getLong("sequence"), status,
                    Instant.ofEpochMilli(r.getLong("created_at")), Instant.ofEpochMilli(r.getLong("updated_at")), user, assistant);
        }
    }
    private Conversation read(UUID id) throws SQLException {
        try (var s = prepare("SELECT * FROM conversations WHERE id=?", id.toString()); var r = s.executeQuery()) {
            if (!r.next()) throw error(ErrorCode.CONVERSATION_NOT_FOUND);
            return conversation(r);
        }
    }
    private static Conversation conversation(ResultSet r) throws SQLException {
        return new Conversation(UUID.fromString(r.getString("id")), r.getString("title"), Status.valueOf(r.getString("status")),
                Instant.ofEpochMilli(r.getLong("created_at")), Instant.ofEpochMilli(r.getLong("updated_at")));
    }
    private static long now() { return Instant.now().toEpochMilli(); }
    private PreparedStatement prepare(String sql, Object... values) throws SQLException {
        var s = db.prepareStatement(sql);
        try { for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]); return s; }
        catch (SQLException failure) { s.close(); throw failure; }
    }
    private void update(String sql, Object... values) throws SQLException { try (var s = prepare(sql, values)) { s.executeUpdate(); } }
    private long count(String sql, Object... values) throws SQLException { try (var s = prepare(sql, values); var r = s.executeQuery()) { r.next(); return r.getLong(1); } }
    private long scalar(String sql) throws SQLException { return count(sql); }
    private void execute(String sql) throws SQLException { try (var s = db.createStatement()) { s.execute(sql); } }
    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private <T> T transaction(boolean write, Work<T> work) {
        boolean begun = false;
        try {
            if (db == null || db.isClosed()) throw new SQLException();
            execute(write ? "BEGIN IMMEDIATE" : "BEGIN"); begun = true;
            T result = work.run(); execute("COMMIT"); return result;
        } catch (Exception failure) {
            if (begun) { try { execute("ROLLBACK"); } catch (SQLException ignored) { close(); } }
            if (failure instanceof WorkspaceException controlled) throw controlled;
            throw error(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE);
        }
    }
    @Override public synchronized void close() {
        if (db != null) { try { db.close(); } catch (SQLException ignored) { } db = null; }
    }
}
