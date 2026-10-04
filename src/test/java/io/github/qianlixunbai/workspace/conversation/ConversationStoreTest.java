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
    @AfterEach void close() { store.close(); memory.close(); }
    private void sql(String sql) throws Exception { try (var db = jdbc(); var s = db.createStatement()) { s.execute(sql); } }
    private Connection jdbc() throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + memory.databaseFile()); }
    private long count(String sql) throws Exception { try (var db = jdbc(); var s = db.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }
    private static void code(ErrorCode expected, org.junit.jupiter.api.function.Executable op) {
        var failure = assertThrows(WorkspaceException.class, op);
        assertEquals(expected, failure.error().code()); assertNull(failure.getCause()); assertEquals(expected.name(), failure.getMessage());
    }
    @Test void lifecycleDefaultRenameArchiveRestartAndCascadeDeletion() throws Exception {
        var item = store.create(null); assertEquals("New conversation", item.title()); assertEquals(Status.ACTIVE, item.status());
        item = store.rename(item.id(), "  synthetic renamed  "); assertEquals("synthetic renamed", item.title());
        var turn = store.createTurnWithUserMessage(item.id(), "synthetic question");
        store.completeTurnWithAssistantMessage(item.id(), turn.id(), "synthetic response");
        var archived = store.archive(item.id()); assertEquals(Status.ARCHIVED, archived.status());
        assertEquals(0, store.list(null, 0, 10).total()); assertEquals(1, store.list(Status.ARCHIVED, 0, 10).total());
        UUID id = item.id(); code(ErrorCode.CONVERSATION_CONFLICT, () -> store.createTurnWithUserMessage(id, "rejected"));
        var before = store.detail(id, 0, 10); close(); open(); assertEquals(before, store.detail(id, 0, 10));
        assertEquals(Status.ACTIVE, store.unarchive(id).status()); store.delete(id);
        code(ErrorCode.CONVERSATION_NOT_FOUND, () -> store.detail(id, 0, 10));
        assertEquals(0, count("SELECT count(*) FROM conversation_turns")); assertEquals(0, count("SELECT count(*) FROM conversation_messages"));
        close(); open(); code(ErrorCode.CONVERSATION_NOT_FOUND, () -> store.detail(id, 0, 10));
    }
    @Test void orderedImmutableHistoryOptionalAssistantAndTerminalTransitions() {
        var conversation = store.create("conversation-private-title");
        for (TurnStatus status : TurnStatus.values()) {
            var turn = store.createTurnWithUserMessage(conversation.id(), "conversation-private-user");
            assertNull(turn.assistantMessage()); assertEquals(TurnStatus.PENDING, turn.status());
            if (status == TurnStatus.SUCCEEDED) store.completeTurnWithAssistantMessage(conversation.id(), turn.id(), "conversation-private-assistant");
            else if (status != TurnStatus.PENDING) store.markTurnTerminated(conversation.id(), turn.id(), status);
            if (status != TurnStatus.PENDING) code(ErrorCode.CONVERSATION_CONFLICT,
                    () -> store.completeTurnWithAssistantMessage(conversation.id(), turn.id(), "late"));
        }
        var detail = store.detail(conversation.id(), 0, 10);
        assertEquals(List.of(1L,2L,3L,4L,5L), detail.turns().stream().map(Turn::sequence).toList());
        assertEquals(List.of(TurnStatus.values()), detail.turns().stream().map(Turn::status).toList());
        assertEquals(Role.USER, detail.turns().getFirst().userMessage().role());
        assertEquals(Role.ASSISTANT, detail.turns().get(1).assistantMessage().role());
        assertThrows(UnsupportedOperationException.class, () -> detail.turns().clear());
        for (Object value : List.of(conversation, detail, detail.turns().getFirst(), detail.turns().getFirst().userMessage()))
            assertFalse(value.toString().contains("conversation-private"));
        close(); open(); assertEquals(detail, store.detail(conversation.id(), 0, 10));
    }
    @Test void validationAndOwnershipRejectWithoutPartialWrites() {
        var c = store.create(null); var other = store.create(null); var turn = store.createTurnWithUserMessage(c.id(), "valid");
        for (String title : List.of(" \t", "x".repeat(161), "\uD800", "x\0y"))
            code(ErrorCode.CONVERSATION_INVALID, () -> store.rename(c.id(), title));
        code(ErrorCode.CONVERSATION_INVALID, () -> store.rename(c.id(), null));
        code(ErrorCode.CONVERSATION_INVALID, () -> store.detail(new UUID(0,0), 0, 10));
        code(ErrorCode.CONVERSATION_NOT_FOUND, () -> store.detail(UUID.randomUUID(), 0, 10));
        code(ErrorCode.CONVERSATION_INVALID, () -> store.detail(c.id(), -1, 10));
        code(ErrorCode.CONVERSATION_INVALID, () -> store.detail(c.id(), 0, 11));
        for (String content : List.of("\n\t", "\uD800", "x\0y"))
            code(ErrorCode.CONVERSATION_INVALID, () -> store.createTurnWithUserMessage(c.id(), content));
        code(ErrorCode.CONVERSATION_LIMIT_EXCEEDED, () -> store.createTurnWithUserMessage(c.id(), "x".repeat(8193)));
        code(ErrorCode.CONVERSATION_LIMIT_EXCEEDED, () -> store.completeTurnWithAssistantMessage(c.id(), turn.id(), "中".repeat(2731)));
        code(ErrorCode.CONVERSATION_NOT_FOUND, () -> store.completeTurnWithAssistantMessage(other.id(), turn.id(), "wrong owner"));
        code(ErrorCode.CONVERSATION_INVALID, () -> store.markTurnTerminated(c.id(), turn.id(), TurnStatus.SUCCEEDED));
        assertEquals(1, store.detail(c.id(), 0, 10).totalTurns()); assertNull(store.detail(c.id(), 0, 10).turns().getFirst().assistantMessage());
    }
    @Test void listTieBreakAndBoundedDetailSurviveRecreation() throws Exception {
        var c = store.create("one"); var second = store.create("two");
        // Assign exact equal timestamps without relying on wall-clock resolution.
        sql("UPDATE conversations SET created_at=123,updated_at=123");
        var sorted = List.of(c.id(),second.id()).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertEquals(sorted, store.list(null, 0, 10).items().stream().map(Conversation::id).toList());
        for (int i=0;i<12;i++) store.createTurnWithUserMessage(c.id(), "synthetic " + i);
        assertEquals(10, store.detail(c.id(), 0, 10).turns().size());
        assertEquals(List.of(11L,12L), store.detail(c.id(), 1, 10).turns().stream().map(Turn::sequence).toList());
        assertTrue(store.detail(c.id(), Integer.MAX_VALUE, 10).turns().isEmpty());
        var expected = store.detail(c.id(), 1, 10); close(); open(); assertEquals(expected, store.detail(c.id(), 1, 10));
    }
    @Test void concurrentConnectionsAllocateUniqueSequenceAndKeepMemorySeparate() throws Exception {
        var m = memory.create(MemoryItem.Type.PROJECT_NOTE, "memory separate", "memory separate");
        var c = store.create(null);
        try (var second = new ConversationStore(memory.databaseFile()); var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var a = pool.submit(() -> { barrier.await(); return store.createTurnWithUserMessage(c.id(), "a"); });
            var b = pool.submit(() -> { barrier.await(); return second.createTurnWithUserMessage(c.id(), "b"); });
            assertNotEquals(a.get(10, TimeUnit.SECONDS).sequence(), b.get(10, TimeUnit.SECONDS).sequence());
        }
        assertEquals(List.of(1L,2L), store.detail(c.id(), 0, 10).turns().stream().map(Turn::sequence).toList());
        code(ErrorCode.CONVERSATION_CONFLICT, () -> store.delete(c.id())); store.reconcilePending();
        store.delete(c.id()); assertEquals(m, memory.get(m.id())); assertEquals(1, memory.list(null,null,null,0,20).total());
        assertEquals(1, memory.backupSnapshot().size());
    }
    @Test void databaseConstraintsRejectUnknownRolesEditsDuplicatesAndOrphans() throws Exception {
        var c = store.create(null); var turn = store.createTurnWithUserMessage(c.id(), "original");
        try (var db = jdbc(); var s = db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO conversation_messages VALUES('x','" + turn.id() + "','SYSTEM','x',0)"));
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO conversation_messages VALUES('x','missing','USER','x',0)"));
            assertThrows(SQLException.class, () -> s.execute("UPDATE conversation_messages SET content='edited'"));
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at) VALUES('x','" + c.id() + "',1,'PENDING',0,0)"));
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at) VALUES('x','" + c.id() + "',0,'PENDING',0,0)"));
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at) VALUES('x','" + c.id() + "',2,'UNKNOWN',0,0)"));
        }
        assertEquals("original", store.detail(c.id(),0,10).turns().getFirst().userMessage().content());
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
    @Test void capacityAndDeleteFailurePreserveWholeAggregate() throws Exception {
        var c = store.create(null); var turn = store.createTurnWithUserMessage(c.id(), "synthetic");
        store.completeTurnWithAssistantMessage(c.id(),turn.id(),"synthetic");
        var before = store.detail(c.id(),0,10);
        sql("CREATE TRIGGER fault BEFORE DELETE ON conversation_messages BEGIN SELECT RAISE(ABORT,'private SQL failure'); END");
        code(ErrorCode.CONVERSATION_STORAGE_UNAVAILABLE, () -> store.delete(c.id()));
        assertEquals(before,store.detail(c.id(),0,10)); sql("DROP TRIGGER fault");
        sql("""
            WITH RECURSIVE n(x) AS (VALUES(2) UNION ALL SELECT x+1 FROM n WHERE x<1000)
            INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at)
            SELECT printf('00000000-0000-0000-0000-%%012d',x),'%s',x,'PENDING',0,0 FROM n
            """.formatted(c.id()));
        code(ErrorCode.CONVERSATION_LIMIT_EXCEEDED, () -> store.createTurnWithUserMessage(c.id(),"overflow"));
        assertEquals(1000,count("SELECT count(*) FROM conversation_turns"));
        sql("""
            WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<999)
            INSERT INTO conversations(id,title,status,created_at,updated_at)
            SELECT printf('00000000-0000-0000-0000-%012d',x),'seed','ARCHIVED',0,0 FROM n
            """);
        code(ErrorCode.CONVERSATION_LIMIT_EXCEEDED, () -> store.create(null));
        store.reconcilePending(); store.delete(c.id()); assertEquals(0,count("SELECT count(*) FROM conversation_turns"));
        store.create(null); assertEquals(1000,count("SELECT count(*) FROM conversations"));
    }
}
