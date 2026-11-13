package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.conversation.Conversation.*;

class ConversationStoreTest {
    @TempDir Path temp;
    private MemoryStore memory;
    private ConversationStore store;
    @BeforeEach void open() {
        memory = new MemoryStore(temp.resolve("data"), temp.resolve("auth/token"));
        store = new ConversationStore(memory.databaseFile());
    }
    @AfterEach void close() {
        // Initialization may fail before either store has been assigned.
        try { if (store != null) store.close(); }
        finally { if (memory != null) memory.close(); }
    }
    private void sql(String sql) throws Exception { try (var db = jdbc(); var s = db.createStatement()) { s.execute(sql); } }
    private Connection jdbc() throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + memory.databaseFile()); }
    private long count(String sql) throws Exception { try (var db = jdbc(); var s = db.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }
    private static void code(ErrorCode expected, org.junit.jupiter.api.function.Executable op) {
        var failure = assertThrows(WorkspaceException.class, op);
        assertEquals(expected, failure.error().code()); assertNull(failure.getCause()); assertEquals(expected.name(), failure.getMessage());
    }
    @Test void memoryUsageFindsExactVersionsPagesAndArchivedTurnsWithoutChangingHistory() throws Exception {
        var item = memory.create(MemoryItem.Type.PROJECT_NOTE, "合成记忆", "保存正文");
        var active = store.create("使用记忆"); var archived = store.create("归档会话");
        for (int i = 0; i < 11; i++) {
            var turn = store.createTurnWithUserMessage(active.id(), "问题", null, List.of(new MemoryReference(item.id(), i == 10 ? Long.MAX_VALUE : 1)));
            store.completeTurnWithAssistantMessage(active.id(), turn.id(), "回答");
        }
        var cancelled = store.createTurnWithUserMessage(archived.id(), "取消的问题", null, List.of(new MemoryReference(item.id(), 1)));
        store.markTurnTerminated(archived.id(), cancelled.id(), TurnStatus.CANCELLED); store.archive(archived.id());
        store.createTurnWithUserMessage(active.id(), "尚未结束", null, List.of(new MemoryReference(item.id(), 1)));
        var unrelated = store.createTurnWithUserMessage(active.id(), "未选择记忆");
        store.markTurnTerminated(active.id(), unrelated.id(), TurnStatus.CANCELLED);
        var before = store.detail(active.id(), 1, 10);
        var first = store.memoryUsage(new ConversationMemoryUsage.Request(item.id(), null, 0));
        var second = store.memoryUsage(new ConversationMemoryUsage.Request(item.id(), null, 1));
        assertEquals(10, first.items().size()); assertTrue(first.hasMore()); assertEquals(2, second.items().size()); assertFalse(second.hasMore());
        assertEquals(12, java.util.stream.Stream.concat(first.items().stream(), second.items().stream()).map(ConversationMemoryUsage.Hit::turnId).distinct().count());
        assertTrue(java.util.stream.Stream.concat(first.items().stream(), second.items().stream()).anyMatch(hit -> hit.conversation().status() == Status.ARCHIVED && hit.status() == TurnStatus.CANCELLED));
        var exact = new ConversationMemoryUsage.Request(item.id(), Long.MAX_VALUE, 0);
        assertEquals(11, store.memoryUsage(exact).items().getFirst().sequence()); assertEquals(1, store.memoryUsage(exact).items().size());
        assertTrue(store.memoryUsage(new ConversationMemoryUsage.Request(UUID.randomUUID(), null, 0)).items().isEmpty());
        code(ErrorCode.CONVERSATION_INVALID, () -> store.memoryUsage(new ConversationMemoryUsage.Request(item.id(), 0L, 0)));
        assertEquals(before, store.detail(active.id(), 1, 10));
        store.close(); store = new ConversationStore(memory.databaseFile()); assertEquals(first, store.memoryUsage(new ConversationMemoryUsage.Request(item.id(), null, 0)));
        store.delete(archived.id()); assertEquals(1, store.memoryUsage(new ConversationMemoryUsage.Request(item.id(), null, 1)).items().size());
    }
    @Test void writeFailuresRollbackEntireTurnAndCompletionWithSafeError() throws Exception {
        var c = store.create(null);
        sql("CREATE TRIGGER fault BEFORE INSERT ON conversation_messages BEGIN SELECT RAISE(ABORT,'private SQL failure'); END");
        code(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE, () -> store.createTurnWithUserMessage(c.id(), "conversation-private-user"));
        assertEquals(0, store.detail(c.id(),0,10).totalTurns()); sql("DROP TRIGGER fault");
        var turn = store.createTurnWithUserMessage(c.id(), "valid"); assertEquals(1, turn.sequence());
        sql("CREATE TRIGGER fault BEFORE UPDATE ON conversation_turns BEGIN SELECT RAISE(ABORT,'private SQL failure'); END");
        code(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE, () -> store.completeTurnWithAssistantMessage(c.id(),turn.id(),"conversation-private-assistant"));
        assertEquals(turn, store.detail(c.id(),0,10).turns().getFirst()); assertEquals(1, count("SELECT count(*) FROM conversation_messages"));
        sql("DROP TRIGGER fault"); store.close(); code(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE, () -> store.detail(c.id(),0,10));
    }
}
