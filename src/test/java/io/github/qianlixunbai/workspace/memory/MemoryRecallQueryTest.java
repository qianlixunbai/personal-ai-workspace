package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.memory.MemoryItem.*;

/** Real SQL over volatile synthetic rows. Does not instantiate a Store, admit a Profile,
 * connect to PostgreSQL, or claim coverage of the HTTP/security/transaction owner. */
class MemoryRecallQueryTest {
    private Connection open() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var statement = db.createStatement()) {
            // Source row contract only; schema creation/migration is covered by Store tests.
            statement.execute("CREATE TABLE memory_items(id TEXT PRIMARY KEY,type TEXT,title TEXT,content TEXT,"
                    + "status TEXT,revision INTEGER,source TEXT,created_at INTEGER,updated_at INTEGER)");
        }
        return db;
    }
    private UUID id(int n) { return new UUID(0, n); }
    private void put(Connection db, int n, Type type, String title, String content, Status status, long revision, long updated) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO memory_items VALUES(?,?,?,?,?,?,'MANUAL',0,?)")) {
            s.setString(1, id(n).toString()); s.setString(2, type.name()); s.setString(3, title); s.setString(4, content);
            s.setString(5, status.name()); s.setLong(6, revision); s.setLong(7, updated); s.executeUpdate();
        }
    }
    private void sql(Connection db, String text) throws SQLException {
        try (var s = db.createStatement()) { s.execute(text); }
    }
    private long scalar(Connection db, String text) throws SQLException {
        try (var s = db.createStatement(); var rows = s.executeQuery(text)) { assertTrue(rows.next()); return rows.getLong(1); }
    }
    private MemoryRecall.Result recall(Connection db, String text, Type type) throws SQLException {
        // Enforce read-only SQL in the fixture itself; no writes may hide behind successful recall.
        sql(db, "PRAGMA query_only=ON");
        long before = scalar(db, "SELECT total_changes()");
        try {
            return MemoryRecall.query(db, type, MemoryRecall.terms(text));
        } finally {
            assertEquals(before, scalar(db, "SELECT total_changes()"));
            sql(db, "PRAGMA query_only=OFF");
        }
    }
    @Test void realSqlFiltersRanksAndReadsCurrentRevisionsWithoutWriting() throws Exception {
        try (var db = open()) {
            String original = "  编程习惯\r\n<script>text only</script>  ";
            put(db, 1, Type.PROJECT_NOTE, "Java 项目", original, Status.ACTIVE, 9007199254740993L, 10);
            put(db, 2, Type.PREFERENCE, "工作偏好", "java 项目", Status.ACTIVE, 7, 30);
            put(db, 3, Type.PROJECT_NOTE, "Java 项目", "java 项目", Status.ARCHIVED, 4, 40);
            put(db, 4, Type.PROJECT_NOTE, "Java 项目", "另一项目", Status.ACTIVE, 2, 20);
            var found = recall(db, "ＪＡＶＡ 项目计划", null);
            assertEquals(List.of(id(4), id(1), id(2)), found.hits().stream().map(h -> h.item().id()).toList());
            var source = found.hits().get(1).item();
            assertEquals(original, source.content()); assertEquals(9007199254740993L, source.revision());
            assertEquals(Source.MANUAL, source.source()); assertEquals(Instant.ofEpochMilli(10), source.updatedAt());
            assertEquals(List.of("java", "项目"), found.hits().get(1).matchedTerms());
            assertEquals(List.of(id(2)), recall(db, "java 项目", Type.PREFERENCE).hits().stream().map(h -> h.item().id()).toList());
            assertEquals(0, recall(db, "%' OR 1=1 --", null).total());
            assertEquals(0, recall(db, "😀 %_", null).total());
            assertEquals(0, recall(db, "unrelated", null).total());
            sql(db, "UPDATE memory_items SET title='new preference',content='new text',revision=revision+1 WHERE id='" + id(1) + "'");
            var updated = recall(db, "preference", null).hits().getFirst().item();
            assertEquals(id(1), updated.id()); assertEquals(9007199254740994L, updated.revision());
            assertEquals(original, source.content()); // Earlier returned result stays immutable.
            sql(db, "UPDATE memory_items SET status='ARCHIVED',revision=revision+1 WHERE id='" + id(4) + "'");
            sql(db, "DELETE FROM memory_items WHERE id='" + id(2) + "'");
            assertEquals(0, recall(db, "java", null).total());
            sql(db, "UPDATE memory_items SET status='ACTIVE',revision=revision+1 WHERE id='" + id(3) + "'");
            var restored = recall(db, "java", null).hits().getFirst().item();
            assertEquals(id(3), restored.id()); assertEquals(5, restored.revision());
            assertEquals(3, scalar(db, "SELECT count(*) FROM memory_items"));
        }
    }
    @Test void boundedSqlScanRejectsOverflowAndInvalidQueriesKeepPreciseErrors() throws Exception {
        try (var db = open()) {
            for (int i = 1000; i >= 1; i--) put(db, i, Type.PROJECT_NOTE, "bounded", "bounded bounded", Status.ACTIVE, 1, 0);
            var top = recall(db, "bounded", null);
            assertEquals(1000, top.total()); assertEquals(20, top.hits().size()); assertEquals(20, top.limit());
            assertEquals(id(1), top.hits().getFirst().item().id()); assertEquals(id(20), top.hits().getLast().item().id());
            put(db, 1001, Type.PROJECT_NOTE, "bounded", "content", Status.ACTIVE, 1, 0);
            var overflow = assertThrows(WorkspaceException.class, () -> recall(db, "bounded", null));
            assertEquals(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, overflow.error().code());
            assertFalse(overflow.toString().contains("bounded"));
            for (String invalid : Arrays.asList(null, "", " \n", "x".repeat(161), "\uD800", "x\0y")) {
                var error = assertThrows(WorkspaceException.class, () -> recall(db, invalid, null));
                assertEquals(ErrorCode.MEMORY_INVALID, error.error().code());
            }
            assertEquals(1001, scalar(db, "SELECT count(*) FROM memory_items"));
        }
    }
}
