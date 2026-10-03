package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.conversation.*;
import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMigrationTest {
    @TempDir Path temp;
    @Test void m3SchemaUpgradePreservesExactMemoryAndBackupRestoreV1() throws Exception {
        Path data = temp.resolve("data"), token = temp.resolve("auth/token"); MemoryItem item;
        try (var old = new MemoryStore(data, token, false)) {
            item = old.create(MemoryItem.Type.PROJECT_NOTE,"synthetic title","synthetic 中文 note");
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("memory.db")); var s = db.createStatement(); var r = s.executeQuery("PRAGMA user_version")) {
            r.next(); assertEquals(1,r.getInt(1));
        }
        try (var current = new MemoryStore(data,token); var conversations = new ConversationStore(current.databaseFile())) {
            assertEquals(item,current.get(item.id())); assertEquals(1,current.list(null,null,"中文",0,20).total());
            conversations.create("unrelated conversation");
            var backup = MemoryBackup.export(current);
            assertEquals(1,backup.metadata().schemaVersion());
            Path restored = temp.resolve("restored"); new MemoryBackupService(data, token).restore(backup, restored.toString());
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + restored.resolve("memory.db")); var s = db.createStatement(); var r = s.executeQuery("PRAGMA user_version")) {
                r.next(); assertEquals(1,r.getInt(1));
            }
            try (var reopened = new MemoryStore(restored,token); var emptyConversations = new ConversationStore(reopened.databaseFile())) {
                assertEquals(item,reopened.get(item.id())); assertEquals(0,emptyConversations.list(null,0,10).total());
            }
            assertEquals(item,current.get(item.id())); assertEquals(1,conversations.list(null,0,10).total());
        }
    }
    @Test void failedMigrationRollsBackAndPreservesM3SourceAndVersion() throws Exception {
        Path data = temp.resolve("data"), token = temp.resolve("auth/token"); MemoryItem item;
        try (var old = new MemoryStore(data,token,false)) { item = old.create(MemoryItem.Type.PREFERENCE,"preserved","preserved"); }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("memory.db")); var s = db.createStatement()) { s.execute("CREATE TABLE conversation_turns(sentinel TEXT)"); }
        var failure = assertThrows(WorkspaceException.class, () -> new MemoryStore(data,token));
        assertEquals(ErrorCode.MEMORY_STORAGE_UNAVAILABLE,failure.error().code()); assertNull(failure.getCause());
        try (var old = new MemoryStore(data,token,false)) { assertEquals(item,old.get(item.id())); }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("memory.db")); var s = db.createStatement()) {
            try (var r = s.executeQuery("PRAGMA user_version")) { r.next(); assertEquals(1,r.getInt(1)); }
            try (var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='conversations'")) { r.next(); assertEquals(0,r.getInt(1)); }
        }
    }
}
