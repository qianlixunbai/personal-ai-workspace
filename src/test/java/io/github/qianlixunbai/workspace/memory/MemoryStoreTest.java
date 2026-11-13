package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.memory.MemoryItem.*;

class MemoryStoreTest {
    @Test void recallRanksCurrentSourcesAndPreservesExplicitExactAdmission() throws Exception {
        try (var store = open()) {
            var titleMatch = store.create(Type.PROJECT_NOTE, "Java 项目", "编程习惯");
            var bodyMatch = store.create(Type.PREFERENCE, "工作偏好", "java 项目");
            var archived = store.create(Type.PROJECT_NOTE, "Java 项目", "java 项目");
            store.archive(archived.id(), 1);
            var result = store.recall("ＪＡＶＡ 项目计划", null);
            assertEquals(List.of(titleMatch.id(), bodyMatch.id()), result.hits().stream().map(h -> h.item().id()).toList());
            assertEquals(List.of("java", "项目"), result.hits().getFirst().matchedTerms());
            assertEquals(6, result.hits().getFirst().score());
            assertEquals(2, result.hits().getLast().score());
            assertEquals(List.of(bodyMatch.id()), store.recall("Java 项目", Type.PREFERENCE).hits().stream().map(h -> h.item().id()).toList());
            assertEquals(0, store.recall("totally-unrelated", null).total());
            assertEquals(0, store.recall("😀 %_", null).total());
            assertEquals(2, store.recall("项", null).total());
            assertFalse(result.toString().contains("Java"));
            assertFalse(result.hits().getFirst().toString().contains("项目"));
            var refs = List.of(new MemoryReference(titleMatch.id(), result.hits().getFirst().item().revision()));
            assertEquals("编程习惯", store.snapshotForAsk(refs).getFirst().content());
            store.update(titleMatch.id(), 1, Type.PROJECT_NOTE, "新偏好", "已变更");
            code(ErrorCode.MEMORY_SELECTION_STALE, () -> store.snapshotForAsk(refs));
            assertEquals(1, store.recall("java", null).total());
            store.delete(bodyMatch.id(), 1);
            assertEquals(0, store.recall("java", null).total());
            for (String invalid : List.of("", " ", "x".repeat(161), "\uD800", "x\0y"))
                code(ErrorCode.MEMORY_INVALID, () -> store.recall(invalid, null));
            code(ErrorCode.MEMORY_INVALID, () -> store.recall(null, null));
            for (int i = 0; i < 25; i++) store.create(Type.PROJECT_NOTE, "bounded", "bounded bounded bounded");
            sql("UPDATE memory_items SET updated_at=1893456000000");
            var top = store.recall("bounded bounded", null);
            assertEquals(25, top.total()); assertEquals(20, top.hits().size()); assertEquals(20, top.limit());
            assertTrue(top.hits().stream().allMatch(h -> h.score() == 4)); // Repetition cannot inflate ranking.
            assertEquals(top.hits().stream().map(h -> h.item().id().toString()).sorted().toList(),
                    top.hits().stream().map(h -> h.item().id().toString()).toList());
            assertEquals(top, store.recall("bounded", null));
            assertEquals(2, store.get(titleMatch.id()).revision()); // Recall has no writes or admission side effects.
        }
    }

    @TempDir Path temporary;
    private Path data() { return temporary.resolve("data"); }
    private MemoryStore open() { return new MemoryStore(data(), temporary.resolve("auth/client-token")); }
    private Connection jdbc() throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + data().resolve("memory.db")); }
    private void sql(String statement) throws Exception { try (var db = jdbc(); var s = db.createStatement()) { s.execute(statement); } }
    private long scalar(String sql) throws Exception {
        try (var db = jdbc(); var s = db.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
    private static void code(ErrorCode expected, org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(WorkspaceException.class, operation);
        assertEquals(expected, failure.error().code());
        assertNull(failure.getCause());
        assertEquals(expected.name(), failure.getMessage());
    }

    @Test void realFileRestartCrudAndRevisionLifecycle() throws Exception {
        MemoryItem created;
        try (var store = open()) {
            created = store.create(Type.PREFERENCE, "  private-title  ", "  private-content\r\n中文  ");
            assertEquals(1, created.revision()); assertEquals(Source.MANUAL, created.source());
            assertEquals(created.createdAt(), created.updatedAt());
            assertEquals(3, scalar("PRAGMA user_version"));
            assertFalse(created.toString().contains("private-title"));
            assertFalse(created.toString().contains("private-content"));
        }
        try (var store = open()) {
            assertEquals(created, store.get(created.id()));
            var updated = store.update(created.id(), 1, Type.PROJECT_NOTE, "edited", "edited-content");
            assertEquals(2, updated.revision()); assertEquals(created.createdAt(), updated.createdAt());
            code(ErrorCode.MEMORY_REVISION_CONFLICT, () -> store.update(created.id(), 1, Type.PREFERENCE, "stale", "stale"));
            code(ErrorCode.MEMORY_REVISION_CONFLICT, () -> store.archive(created.id(), 1));
            code(ErrorCode.MEMORY_REVISION_CONFLICT, () -> store.delete(created.id(), 1));
            assertEquals(3, store.archive(created.id(), 2).revision());
            assertEquals(0, store.list(null, null, null, 0, 20).total());
            assertEquals(1, store.list(Status.ARCHIVED, Type.PROJECT_NOTE, null, 0, 20).total());
            code(ErrorCode.MEMORY_REVISION_CONFLICT, () -> store.restore(created.id(), 2));
            assertEquals(4, store.restore(created.id(), 3).revision());
            assertEquals(5, store.restore(created.id(), 4).revision());
            store.delete(created.id(), 5);
            code(ErrorCode.MEMORY_NOT_FOUND, () -> store.get(created.id()));
            assertEquals(0, store.list(null, null, "edited", 0, 20).total());
        }
        try (var store = open()) { code(ErrorCode.MEMORY_NOT_FOUND, () -> store.get(created.id())); }
    }

    @Test void indexFailureRollsBackSourceAndRevisionAndBusyIsControlled() throws Exception {
        try (var store = open()) {
            var item = store.create(Type.PREFERENCE, "original", "original");
            // FTS storage failure occurs after trigger removes old derived row, within the source UPDATE.
            sql("CREATE TRIGGER fault BEFORE INSERT ON memory_fts_content BEGIN SELECT RAISE(ABORT,'private SQL failure'); END");
            code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, () -> store.update(item.id(), 1, Type.PREFERENCE, "modified", "modified"));
            code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, () -> store.create(Type.PREFERENCE, "partial", "partial"));
            assertEquals(item, store.get(item.id()));
            assertEquals(1, store.list(null, null, "original", 0, 20).total());
            assertEquals(0, store.list(null, null, "modified", 0, 20).total());
            assertEquals(1, scalar("SELECT count(*) FROM memory_items"));
            sql("DROP TRIGGER fault");
            try (var db = jdbc(); var lock = db.createStatement()) {
                lock.execute("BEGIN IMMEDIATE");
                code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, () -> store.archive(item.id(), 1));
                lock.execute("ROLLBACK");
            }
            assertEquals(item, store.get(item.id()));
            store.rebuildSearchIndex();
        }
    }

    @Test void concurrentStoresCannotLoseRevisionOrOverrunCapacity() throws Exception {
        try (var one = open(); var two = open(); var pool = Executors.newFixedThreadPool(2)) {
            var item = one.create(Type.PREFERENCE, "race", "race");
            var barrier = new CyclicBarrier(2);
            Callable<Boolean> a = () -> { barrier.await(); try { one.archive(item.id(), 1); return true; }
                catch (WorkspaceException failure) { assertEquals(ErrorCode.MEMORY_REVISION_CONFLICT, failure.error().code()); return false; } };
            // Both updates target a surviving row, so stale conflict is deterministic regardless of winning order.
            Callable<Boolean> b = () -> { barrier.await(); try { two.update(item.id(), 1, Type.PROJECT_NOTE, "winner", "winner"); return true; }
                catch (WorkspaceException failure) { assertEquals(ErrorCode.MEMORY_REVISION_CONFLICT, failure.error().code()); return false; } };
            var f = pool.submit(a); var g = pool.submit(b);
            assertNotEquals(f.get(10, TimeUnit.SECONDS), g.get(10, TimeUnit.SECONDS));
            assertEquals(2, one.get(item.id()).revision());
            sql("""
                WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<998)
                INSERT INTO memory_items(id,type,title,content,status,revision,source,created_at,updated_at)
                SELECT printf('00000000-0000-0000-0000-%012d',x),'PREFERENCE','seed','seed','ACTIVE',1,'MANUAL',0,0 FROM n
                """);
            Callable<Boolean> createOne = () -> { barrier.await(); try { one.create(Type.PREFERENCE, "a", "a"); return true; }
                catch (WorkspaceException failure) { assertEquals(ErrorCode.MEMORY_LIMIT_EXCEEDED, failure.error().code()); return false; } };
            Callable<Boolean> createTwo = () -> { barrier.await(); try { two.create(Type.PREFERENCE, "b", "b"); return true; }
                catch (WorkspaceException failure) { assertEquals(ErrorCode.MEMORY_LIMIT_EXCEEDED, failure.error().code()); return false; } };
            f = pool.submit(createOne); g = pool.submit(createTwo);
            assertNotEquals(f.get(10, TimeUnit.SECONDS), g.get(10, TimeUnit.SECONDS));
            assertEquals(1000, scalar("SELECT count(*) FROM memory_items"));
        }
    }

}
