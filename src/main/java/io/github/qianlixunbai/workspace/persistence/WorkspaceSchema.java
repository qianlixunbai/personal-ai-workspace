package io.github.qianlixunbai.workspace.persistence;

import java.sql.*;

/** Workspace DB version is independent of the unchanged Memory logical backup schema v1. */
public final class WorkspaceSchema {
    public static final int VERSION = 3;
    private WorkspaceSchema() {}

    /** Called inside the existing BEGIN IMMEDIATE migration transaction, after Memory v1 initialization. */
    public static void upgrade(Connection db, long version) throws SQLException {
        if (version < 2) {
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
        if (version < 3) {
            execute(db, "ALTER TABLE conversation_turns ADD COLUMN task_id TEXT");
            execute(db, "CREATE UNIQUE INDEX conversation_task_id ON conversation_turns(task_id)");
            execute(db, "ALTER TABLE conversation_turns ADD COLUMN failure_code TEXT CHECK(failure_code IN ('EXECUTION_INTERRUPTED','PROVIDER_UNAVAILABLE','MODEL_UNAVAILABLE','QUEUE_FULL','POLICY_DENIED','EXECUTION_FAILED','STORAGE_UNAVAILABLE'))");
            execute(db, """
                CREATE TABLE conversation_memory_selections (
                  turn_id TEXT NOT NULL REFERENCES conversation_turns(id) ON DELETE CASCADE,
                  position INTEGER NOT NULL CHECK(position BETWEEN 0 AND 3),
                  memory_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0),
                  PRIMARY KEY(turn_id,position), UNIQUE(turn_id,memory_id)
                )
                """);
            execute(db, """
                CREATE TRIGGER conversation_selection_immutable BEFORE UPDATE ON conversation_memory_selections
                BEGIN SELECT RAISE(ABORT,'immutable selection'); END
                """);
            execute(db, """
                CREATE TRIGGER conversation_terminal_immutable BEFORE UPDATE ON conversation_turns
                WHEN old.status != 'PENDING'
                BEGIN SELECT RAISE(ABORT,'terminal turn'); END
                """);
            execute(db, "PRAGMA user_version=3");
        }
        verify(db);
    }
    public static void verify(Connection db) throws SQLException {
        execute(db, "SELECT id,title,status,created_at,updated_at FROM conversations LIMIT 0");
        execute(db, "SELECT id,conversation_id,sequence,status,created_at,updated_at,task_id,failure_code FROM conversation_turns LIMIT 0");
        execute(db, "SELECT turn_id,position,memory_id,revision FROM conversation_memory_selections LIMIT 0");
        execute(db, "SELECT id,turn_id,role,content,created_at FROM conversation_messages LIMIT 0");
    }
    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }
}
