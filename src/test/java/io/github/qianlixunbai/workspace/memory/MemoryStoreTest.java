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

    @Test void askSnapshotIsOrderedImmutableExactAndAllOrNothingAcrossMutations() {
        try (var store = open()) {
            var first = store.create(Type.PROJECT_NOTE, "first", "old first");
            var second = store.create(Type.PREFERENCE, "second", "old second");
            var references = List.of(new MemoryReference(second.id(), 1), new MemoryReference(first.id(), 1));
            var snapshot = store.snapshotForAsk(references);
            assertEquals(List.of(second.id(), first.id()), snapshot.stream().map(MemorySnapshot::id).toList());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
            store.update(first.id(), 1, Type.PROJECT_NOTE, "changed", "new first");
            code(ErrorCode.MEMORY_SELECTION_STALE, () -> store.snapshotForAsk(references));
            var current = List.of(new MemoryReference(second.id(), 1), new MemoryReference(first.id(), 2));
            store.archive(second.id(), 1);
            code(ErrorCode.MEMORY_SELECTION_STALE, () -> store.snapshotForAsk(current));
            code(ErrorCode.MEMORY_SELECTION_STALE, () -> store.snapshotForAsk(List.of(new MemoryReference(second.id(), 2))));
            store.delete(first.id(), 2);
            code(ErrorCode.MEMORY_SELECTION_STALE, () -> store.snapshotForAsk(current));
            assertEquals("old first", snapshot.get(1).content());
            assertEquals("old second", snapshot.get(0).content());
            assertEquals(1, snapshot.get(1).revision());
            assertFalse(snapshot.toString().contains("old first"));
        }
    }

    @Test void askReferencesRejectInvalidSelectionsBeforeStorageAccess() {
        try (var store = open()) {
            var ref = new MemoryReference(UUID.randomUUID(), 1);
            for (var refs : List.of(List.<MemoryReference>of(), List.of(ref, ref),
                    List.of(new MemoryReference(ref.id(), 0)), List.of(new MemoryReference(ref.id(), -1)),
                    List.of(new MemoryReference(new UUID(0, 0), 1)), List.of(new MemoryReference(null, 1)),
                    java.util.stream.IntStream.range(0, 5).mapToObj(i -> new MemoryReference(UUID.randomUUID(), 1)).toList(),
                    Arrays.asList((MemoryReference)null)))
                code(ErrorCode.INVALID_REQUEST, () -> store.snapshotForAsk(refs));
            code(ErrorCode.INVALID_REQUEST, () -> store.snapshotForAsk(null));
        }
    }

    @Test void realFileRestartCrudAndRevisionLifecycle() throws Exception {
        MemoryItem created;
        try (var store = open()) {
            created = store.create(Type.PREFERENCE, "  private-title  ", "  private-content\r\n中文  ");
            assertEquals(1, created.revision()); assertEquals(Source.MANUAL, created.source());
            assertEquals(created.createdAt(), created.updatedAt());
            assertEquals(2, scalar("PRAGMA user_version"));
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

    @Test void substringSearchFiltersPaginationAndRebuildAreSourceBacked() throws Exception {
        try (var store = open()) {
            var item = store.create(Type.PROJECT_NOTE, "Alphabet 中文项目", "中 😀🦊🚀 a\"b 100% a_b ' OR ' spaced   phrase");
            var other = store.create(Type.PREFERENCE, "Alphabet", "other");
            for (String query : List.of("pha", "中文项", "中", "😀", "😀🦊", "😀🦊🚀", "a\"b", "100%", "a_b", "' OR '", "spaced   phrase"))
                assertEquals(1, store.list(null, Type.PROJECT_NOTE, query, 0, 20).total(), query);
            assertEquals(0, store.list(null, null, "alphabet", 0, 20).total()); // Literal, case-sensitive for all lengths.
            assertEquals(0, store.list(null, null, "%_", 0, 20).total());
            assertEquals(0, store.list(null, null, "\" OR missing", 0, 20).total());
            sql("UPDATE memory_items SET updated_at=123");
            List<UUID> ids = List.of(item.id(), other.id()).stream().sorted(Comparator.comparing(UUID::toString)).toList();
            var first = store.list(null, null, "pha", 0, 1);
            assertEquals(2, first.total()); assertEquals(ids.getFirst(), first.items().getFirst().id());
            assertEquals(ids.getLast(), store.list(null, null, "pha", 1, 1).items().getFirst().id());
            assertTrue(store.list(null, null, null, Integer.MAX_VALUE, 100).items().isEmpty());
            var changed = store.update(item.id(), 1, Type.PROJECT_NOTE, "新项目计划", "different");
            assertEquals(0, store.list(null, Type.PROJECT_NOTE, "中文项", 0, 20).total());
            assertEquals(1, store.list(null, null, "项目计", 0, 20).total());
            store.archive(item.id(), 2);
            assertEquals(0, store.list(null, null, "项目计", 0, 20).total());
            assertEquals(1, store.list(Status.ARCHIVED, null, "项目计", 0, 20).total());
            var before = store.get(item.id());
            sql("DROP TABLE memory_fts");
            store.rebuildSearchIndex();
            assertEquals(before, store.get(item.id()));
            assertEquals(1, store.list(Status.ARCHIVED, null, "项目计", 0, 20).total());
            store.delete(item.id(), 3);
            assertEquals(0, store.list(Status.ARCHIVED, null, "项目计", 0, 20).total());
            assertEquals(changed.createdAt(), before.createdAt());
        }
    }

    @Test void capacityUnicodeAndInputBoundsRejectWithoutTruncation() throws Exception {
        try (var store = open()) {
            code(ErrorCode.MEMORY_INVALID, () -> store.create(null, "title", "content"));
            code(ErrorCode.MEMORY_INVALID, () -> store.create(Type.PREFERENCE, " \t", "content"));
            code(ErrorCode.MEMORY_INVALID, () -> store.create(Type.PREFERENCE, "title", "\r\n"));
            code(ErrorCode.MEMORY_INVALID, () -> store.create(Type.PREFERENCE, "title", "\uD800"));
            code(ErrorCode.MEMORY_INVALID, () -> store.create(Type.PREFERENCE, "title", "a\0b"));
            code(ErrorCode.MEMORY_LIMIT_EXCEEDED, () -> store.create(Type.PREFERENCE, "😀".repeat(161), "content"));
            code(ErrorCode.MEMORY_LIMIT_EXCEEDED, () -> store.create(Type.PREFERENCE, "title", "x".repeat(2001)));
            // Both guards execute on original text. At <=2000 valid UTF-16 units, UTF-8 cannot exceed 6000 bytes;
            // the independent 8KiB guard is intentionally redundant protection if char limits evolve.
            assertTrue("中".repeat(2731).getBytes(StandardCharsets.UTF_8).length > MemoryLimits.CONTENT_UTF8_BYTES);
            code(ErrorCode.MEMORY_LIMIT_EXCEEDED, () -> store.create(Type.PREFERENCE, "title", "中".repeat(2731)));
            var largest = store.create(Type.PREFERENCE, "😀".repeat(160), "中".repeat(2000));
            assertEquals(2000, largest.content().length());
            assertEquals(6000, largest.content().getBytes(StandardCharsets.UTF_8).length);
            code(ErrorCode.MEMORY_LIMIT_EXCEEDED, () -> store.update(largest.id(), 1, Type.PREFERENCE, "title", "x".repeat(2001)));
            assertEquals(largest, store.get(largest.id()));
            for (int[] pagination : List.of(new int[]{-1,20},new int[]{0,0},new int[]{0,101}))
                code(ErrorCode.MEMORY_INVALID, () -> store.list(null, null, null, pagination[0], pagination[1]));
            code(ErrorCode.MEMORY_INVALID, () -> store.list(null, null, "x".repeat(161), 0, 20));
            code(ErrorCode.MEMORY_INVALID, () -> store.delete(largest.id(), 0));
            // Seed remaining boundary with real SQL and product triggers, not mocks or 998 duplicate HTTP tests.
            sql("""
                WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<998)
                INSERT INTO memory_items(id,type,title,content,status,revision,source,created_at,updated_at)
                SELECT printf('00000000-0000-0000-0000-%012d',x),'PREFERENCE','seed','seed','ARCHIVED',1,'MANUAL',0,0 FROM n
                """);
            var last = store.create(Type.PROJECT_NOTE, "last", "last");
            code(ErrorCode.MEMORY_LIMIT_EXCEEDED, () -> store.create(Type.PREFERENCE, "overflow", "overflow"));
            assertEquals(1000, scalar("SELECT count(*) FROM memory_items"));
            store.delete(last.id(), 1);
            store.create(Type.PROJECT_NOTE, "replacement", "replacement");
            assertEquals(1000, scalar("SELECT count(*) FROM memory_fts"));
        }
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

    @Test void schemaFailuresPreserveOriginalAndNeverReset() throws Exception {
        MemoryItem item;
        try (var store = open()) { item = store.create(Type.PREFERENCE, "preserved", "preserved"); }
        sql("PRAGMA user_version=99");
        byte[] newer = Files.readAllBytes(data().resolve("memory.db"));
        code(ErrorCode.MEMORY_SCHEMA_UNSUPPORTED, this::open);
        assertArrayEquals(newer, Files.readAllBytes(data().resolve("memory.db")));
        sql("PRAGMA user_version=2");
        // Break a derived shadow table: transactional startup rebuild fails after dropping derived objects.
        sql("DROP TABLE memory_fts");
        sql("CREATE TABLE memory_fts_data(sentinel TEXT)");
        sql("INSERT INTO memory_fts_data VALUES('private-preserved')");
        code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, this::open);
        assertEquals(2, scalar("PRAGMA user_version"));
        assertEquals(1, scalar("SELECT count(*) FROM memory_items"));
        assertEquals(1, scalar("SELECT count(*) FROM memory_fts_data"));
        sql("DROP TABLE memory_fts_data");
        try (var store = open()) { assertEquals(item, store.get(item.id())); }
        sql("PRAGMA user_version=0");
        code(ErrorCode.MEMORY_SCHEMA_UNSUPPORTED, this::open); // Unversioned nonempty is not a supported migration.
        assertEquals(1, scalar("SELECT count(*) FROM memory_items"));
        byte[] corrupt = "synthetic-corrupt-private-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(data().resolve("memory.db"), corrupt);
        code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, this::open);
        assertArrayEquals(corrupt, Files.readAllBytes(data().resolve("memory.db")));
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

    @Test void dataDirectoryIsPrivateSeparateAndRejectsUnsafeLocations() throws Exception {
        code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE,
                () -> new MemoryStore(Path.of("target/memory-unsafe"), temporary.resolve("auth/token")));
        code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE,
                () -> new MemoryStore(Path.of("memory-unsafe"), temporary.resolve("auth/token")));
        code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE,
                () -> new MemoryStore(temporary.resolve("auth/data"), temporary.resolve("auth/token")));
        try (var store = open()) {
            var acl = Files.getFileAttributeView(data(), java.nio.file.attribute.AclFileAttributeView.class);
            var posix = Files.getFileAttributeView(data(), java.nio.file.attribute.PosixFileAttributeView.class);
            if (posix != null) assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), posix.readAttributes().permissions());
            else {
                assertNotNull(acl); assertEquals(1, acl.getAcl().size());
                assertEquals(acl.getOwner(), acl.getAcl().getFirst().principal());
                assertTrue(acl.getAcl().getFirst().flags().contains(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT));
            }
        }
        // An unexpected directory at a sidecar path must fail closed rather than be removed.
        Files.createDirectory(data().resolve("memory.db-journal"));
        code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, this::open);
        assertTrue(Files.isDirectory(data().resolve("memory.db-journal")));
    }
}
