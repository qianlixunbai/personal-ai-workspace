package io.github.qianlixunbai.workspace.persistence;

import java.sql.*;

/** Workspace DB version is independent of the unchanged Memory logical backup schema v1. */
public final class WorkspaceSchema {
    public static final int VERSION = 2;
    private WorkspaceSchema() {}

    /** Called inside the existing BEGIN IMMEDIATE migration transaction, after Memory v1 initialization. */
    public static void upgrade(Connection db, long version) throws SQLException {
        if (version < VERSION) {
            execute(db, """
                CREATE TABLE conversations (
                  id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL,
                  status TEXT NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
                  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL CHECK(updated_at >= created_at)
                )
                """);
            execute(db, "CREATE INDEX conversations_order ON conversations(status,updated_at DESC,id ASC)");
            execute(db, """
                CREATE TABLE conversation_turns (
                  id TEXT PRIMARY KEY NOT NULL,
                  conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                  sequence INTEGER NOT NULL CHECK(sequence > 0),
                  status TEXT NOT NULL CHECK(status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
                  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL CHECK(updated_at >= created_at),
                  UNIQUE(conversation_id,sequence)
                )
                """);
            execute(db, """
                CREATE TABLE conversation_messages (
                  id TEXT PRIMARY KEY NOT NULL,
                  turn_id TEXT NOT NULL REFERENCES conversation_turns(id) ON DELETE CASCADE,
                  role TEXT NOT NULL CHECK(role IN ('USER','ASSISTANT')),
                  content TEXT NOT NULL, created_at INTEGER NOT NULL,
                  UNIQUE(turn_id,role)
                )
                """);
            execute(db, """
                CREATE TRIGGER conversation_message_immutable BEFORE UPDATE ON conversation_messages
                BEGIN SELECT RAISE(ABORT,'immutable message'); END
                """);
            execute(db, "PRAGMA user_version=2");
        }
        verify(db);
    }
    public static void verify(Connection db) throws SQLException {
        execute(db, "SELECT id,title,status,created_at,updated_at FROM conversations LIMIT 0");
        execute(db, "SELECT id,conversation_id,sequence,status,created_at,updated_at FROM conversation_turns LIMIT 0");
        execute(db, "SELECT id,turn_id,role,content,created_at FROM conversation_messages LIMIT 0");
    }
    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }
}
