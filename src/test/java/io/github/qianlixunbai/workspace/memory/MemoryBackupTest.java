package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static io.github.qianlixunbai.workspace.memory.MemoryItem.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryBackupTest {
    @TempDir Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();
    private Path source() { return temporary.resolve("source"); }
    private Path token() { return temporary.resolve("auth/client-token"); }
    private MemoryStore open(Path path) { return new MemoryStore(path, token()); }
    private MemoryBackupService service() { return new MemoryBackupService(source(), token()); }
    private static void code(ErrorCode expected, org.junit.jupiter.api.function.Executable work) {
        var error = assertThrows(WorkspaceException.class, work); assertEquals(expected, error.error().code());
        assertNull(error.getCause()); assertEquals(expected.name(), error.getMessage());
        assertFalse(error.toString().contains("secret"));
    }
    @Test void emptyExportAndDeterministicSerializationExcludeDerivedAndPrivateState() {
        try (var store = open(source())) {
            var backup = MemoryBackup.export(store); assertEquals(0, backup.metadata().itemCount());
            assertArrayEquals(backup.bytes(), backup.bytes()); assertEquals(backup, MemoryBackup.read(backup.bytes()));
            var root = json.readTree(backup.bytes()); assertEquals(7, root.size());
            assertEquals(MemoryBackup.FORMAT, root.path("format").asString());
            for (String excluded : List.of("fts", "token", "task", "prompt", "question", "answer", "credential"))
                assertFalse(new String(backup.bytes(), StandardCharsets.UTF_8).contains(excluded));
        }
    }
    @Test void newAndEmptyTargetsPreserveEveryFieldRebuildSearchAndLeaveLiveDatabaseBytesUnchanged() throws Exception {
        try (var store = open(source())) {
            var first = store.create(Type.PREFERENCE, "  secret preference  ", "  English recovery 中文搜索\r\n😀  ");
            store.update(first.id(), 1, Type.PREFERENCE, first.title(), first.content());
            var note = store.create(Type.PROJECT_NOTE, "secret project", "English recovery 中文搜索"); store.archive(note.id(), 1);
            var backup = MemoryBackup.export(store); var original = Files.readAllBytes(source().resolve("memory.db"));
            assertFalse(backup.toString().contains("secret"));
            var empty = Files.createDirectory(temporary.resolve("empty"));
            for (Path target : List.of(temporary.resolve("new"), empty)) {
                assertEquals(backup.metadata(), service().restore(MemoryBackup.read(backup.bytes()), target.toString()));
                assertArrayEquals(original, Files.readAllBytes(source().resolve("memory.db")));
                try (var restored = open(target)) {
                    assertEquals(backup.items(), restored.backupSnapshot());
                    assertEquals(1, restored.list(null, null, "English recovery", 0, 20).total());
                    assertEquals(1, restored.list(Status.ARCHIVED, null, "中文搜索", 0, 20).total());
                    assertEquals(1, restored.snapshotForAsk(List.of(new MemoryReference(first.id(), 2))).size());
                    code(ErrorCode.MEMORY_SELECTION_STALE, () -> restored.snapshotForAsk(List.of(new MemoryReference(note.id(), 2))));
                }
                try (var db = DriverManager.getConnection("jdbc:sqlite:" + target.resolve("memory.db")); var statement = db.createStatement()) {
                    try (var result = statement.executeQuery("PRAGMA quick_check")) { assertTrue(result.next()); assertEquals("ok", result.getString(1)); }
                    try (var result = statement.executeQuery("PRAGMA user_version")) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
                }
            }
        }
        noStaging();
    }
    @Test void strictFormatRejectsMalformedFieldsUnicodeKeysAndVersionsWithoutDisclosure() {
        try (var store = open(source())) {
            store.create(Type.PROJECT_NOTE, "secret-title", "secret-content");
            String valid = new String(MemoryBackup.export(store).bytes(), StandardCharsets.UTF_8);
            for (String invalid : List.of(valid.substring(0, valid.length() - 1), valid + "{}", valid.replace("\"format\":", "\"unknown\":0,\"format\":"),
                    valid.replace("\"format\":", "\"format\":\"duplicate\",\"format\":"), valid.replace(MemoryBackup.FORMAT, "other"),
                    valid.replace("\"title\":", "\"extra\":0,\"title\":"), valid.replace("\"title\":", "\"title\":\"duplicate\",\"title\":"),
                    valid.replace("PROJECT_NOTE", "OTHER"), valid.replace("ACTIVE", "OTHER"), valid.replace("MANUAL", "AUTO"),
                    valid.replace("\"revision\":1", "\"revision\":0"), valid.replace("\"revision\":1", "\"revision\":1.5"),
                    valid.replace("\"revision\":1", "\"revision\":\"1\""), valid.replace("secret-title", " "),
                    valid.replace("secret-title", "x".repeat(161)), valid.replace("secret-content", "x".repeat(2001)),
                    valid.replace("secret-content", "\\uD800"), valid.replace("secret-title", "\\u0000"),
                    valid.replace("secret-content", "tampered"), valid.replace("\"itemCount\":1", "\"itemCount\":0")))
                code(ErrorCode.MEMORY_BACKUP_INVALID, () -> MemoryBackup.read(invalid.getBytes(StandardCharsets.UTF_8)));
            for (String field : List.of("formatVersion", "schemaVersion"))
                code(ErrorCode.MEMORY_BACKUP_UNSUPPORTED, () -> MemoryBackup.read(valid.replace("\"" + field + "\":1", "\"" + field + "\":2").getBytes(StandardCharsets.UTF_8)));
            var root = json.readTree(valid); var row = (tools.jackson.databind.node.ObjectNode) root.path("items").get(0);
            for (String bad : List.of("1-2-3-4-5", "not-uuid", "00000000-0000-0000-0000-000000000000")) {
                row.put("id", bad); code(ErrorCode.MEMORY_BACKUP_INVALID, () -> MemoryBackup.read(root));
            }
            for (String bad : List.of("bad-time", "2026-10-03T01:00:00.000001Z")) {
                var changed = json.readTree(valid); ((tools.jackson.databind.node.ObjectNode)changed.path("items").get(0)).put("updatedAt", bad);
                code(ErrorCode.MEMORY_BACKUP_INVALID, () -> MemoryBackup.read(changed));
            }
            var backwards = json.readTree(valid); ((tools.jackson.databind.node.ObjectNode)backwards.path("items").get(0)).put("updatedAt", "1970-01-01T00:00:00Z");
            code(ErrorCode.MEMORY_BACKUP_INVALID, () -> MemoryBackup.read(backwards));
            code(ErrorCode.MEMORY_BACKUP_INVALID, () -> MemoryBackup.read(new byte[]{'{', (byte)0xff, '}'}));
        }
    }
    @Test void duplicateIdsCountsAndByteBudgetFailClosed() {
        try (var store = open(source())) {
            store.create(Type.PREFERENCE, "secret-title", "secret-content"); var backup = MemoryBackup.export(store);
            var root = (tools.jackson.databind.node.ObjectNode) json.readTree(backup.bytes());
            var rows = (tools.jackson.databind.node.ArrayNode) root.get("items"); rows.add(rows.get(0)); root.put("itemCount", 2);
            code(ErrorCode.MEMORY_BACKUP_INVALID, () -> MemoryBackup.read(root));
            while (rows.size() <= 1000) rows.add(rows.get(0)); root.put("itemCount", rows.size());
            code(ErrorCode.MEMORY_BACKUP_INVALID, () -> MemoryBackup.read(root));
            code(ErrorCode.MEMORY_BACKUP_TOO_LARGE, () -> MemoryBackup.read(new byte[MemoryBackup.MAX_BYTES + 1]));
        }
    }
    @Test void nonemptyUnknownActiveAndUnsafeTargetsRemainUntouched() throws Exception {
        try (var store = open(source())) {
            store.create(Type.PREFERENCE, "secret-title", "secret-content"); var backup = MemoryBackup.export(store);
            var original = Files.readAllBytes(source().resolve("memory.db"));
            var unknown = Files.createDirectory(temporary.resolve("unknown")); var sentinel = Files.writeString(unknown.resolve("unknown.txt"), "keep");
            for (Path target : List.of(source(), source().resolve("nested"), unknown, sentinel))
                code(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY, () -> service().restore(backup, target.toString()));
            for (Path target : List.of(temporary.resolve("auth/new"), temporary.resolve("logs"), Path.of("relative")))
                code(ErrorCode.MEMORY_RESTORE_FAILED, () -> service().restore(backup, target.toString()));
            assertArrayEquals(original, Files.readAllBytes(source().resolve("memory.db"))); assertEquals("keep", Files.readString(sentinel));
        }
        noStaging();
    }
    @Test void injectedFailureAndRacingTargetCannotPublishPartialDataOrChangeSource() throws Exception {
        try (var store = open(source())) {
            store.create(Type.PREFERENCE, "secret-title", "secret-content"); var backup = MemoryBackup.export(store);
            var original = Files.readAllBytes(source().resolve("memory.db")); var target = temporary.resolve("new");
            // Force an INSERT constraint failure after the first row, then verify transaction rollback directly.
            var duplicateRows = List.of(backup.items().getFirst(), backup.items().getFirst());
            try (var staging = open(temporary.resolve("constraint-check"))) {
                code(ErrorCode.MEMORY_STORAGE_UNAVAILABLE, () -> staging.reconstruct(duplicateRows));
                assertEquals(0, staging.backupSnapshot().size());
            }
            var invalidInternal = new MemoryBackup(backup.createdAt(), duplicateRows, backup.contentDigest());
            code(ErrorCode.MEMORY_RESTORE_FAILED, () -> service().restore(invalidInternal, target.toString()));
            assertFalse(Files.exists(target));
            var failing = new MemoryBackupService(source(), token(), () -> { throw new IllegalStateException("secret"); });
            code(ErrorCode.MEMORY_RESTORE_FAILED, () -> failing.restore(backup, target.toString())); assertFalse(Files.exists(target));
            var empty = Files.createDirectory(temporary.resolve("empty"));
            code(ErrorCode.MEMORY_RESTORE_FAILED, () -> failing.restore(backup, empty.toString()));
            try (var files = Files.list(empty)) { assertEquals(0, files.count()); }
            var racing = new MemoryBackupService(source(), token(), () -> {
                try { Files.createDirectory(target); Files.writeString(target.resolve("keep"), "keep"); }
                catch (Exception failure) { throw new IllegalStateException(); }
            });
            code(ErrorCode.MEMORY_RESTORE_TARGET_NOT_EMPTY, () -> racing.restore(backup, target.toString()));
            assertEquals("keep", Files.readString(target.resolve("keep"))); assertFalse(Files.exists(target.resolve("memory.db")));
            assertArrayEquals(original, Files.readAllBytes(source().resolve("memory.db"))); assertEquals(1, store.backupSnapshot().size());
        }
        noStaging();
    }
    @Test void maximumEscapedRecordsFitDerivedBudgetAndRoundtripWithoutTruncation() {
        Instant time = Instant.parse("2026-10-03T00:00:00Z");
        var rows = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> new MemoryItem(new UUID(0, i + 1), Type.PROJECT_NOTE,
                "😀".repeat(160), "\u0001".repeat(2000), Status.ACTIVE, 9, Source.MANUAL, time, time)).sorted(Comparator.comparing(row -> row.id().toString())).toList();
        try (var store = open(source())) {
            store.reconstruct(rows); var backup = MemoryBackup.export(store);
            assertTrue(backup.bytes().length > 1024 * 1024); assertTrue(backup.bytes().length <= MemoryBackup.MAX_BYTES);
            assertEquals(rows, MemoryBackup.read(backup.bytes()).items());
        }
    }
    @Test void exportReadsOneSnapshotWhileAnotherConnectionCommitsWholeGenerations() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = open(source())) {
            for (int i = 0; i < 20; i++) store.create(Type.PROJECT_NOTE, "snapshot", "generation-0");
            var writer = executor.submit(() -> {
                try (var db = DriverManager.getConnection("jdbc:sqlite:" + source().resolve("memory.db")); var s = db.createStatement()) {
                    s.execute("PRAGMA busy_timeout=3000");
                    for (int i = 1; i <= 20; i++) {
                        s.execute("BEGIN IMMEDIATE"); s.executeUpdate("UPDATE memory_items SET content='generation-" + i + "',revision=revision+1"); s.execute("COMMIT");
                    }
                } catch (Exception failure) { throw new IllegalStateException(); }
            });
            for (int i = 0; i < 30; i++) {
                var rows = MemoryBackup.export(store).items(); assertEquals(20, rows.size());
                assertEquals(1, rows.stream().map(MemoryItem::content).distinct().count());
                assertEquals(1, rows.stream().map(MemoryItem::revision).distinct().count());
            }
            writer.get(10, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
    private void noStaging() throws Exception {
        try (var files = Files.list(temporary)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".memory-restore-"))); }
    }
}
