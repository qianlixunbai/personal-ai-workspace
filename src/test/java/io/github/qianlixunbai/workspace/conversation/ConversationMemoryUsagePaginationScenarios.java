package io.github.qianlixunbai.workspace.conversation;

import io.github.qianlixunbai.workspace.persistence.WorkspaceSchema;
import java.sql.*;
import java.util.*;
import java.util.stream.IntStream;

/** SQL-only checks in an in-memory synthetic database; no Profile or Runtime is opened. */
class ConversationMemoryUsagePaginationScenarios {
    private static final UUID MEMORY = id(2001), OTHER_MEMORY = id(2002);
    private record Row(String conversationId, String turnId, long sequence, String status, long revision) {}
    private record Page(List<Row> rows, boolean hasMore) {}

    private static void assertTrue(boolean value) { if (!value) throw new AssertionError("Expected true"); }
    private static void assertFalse(boolean value) { if (value) throw new AssertionError("Expected false"); }
    private static void assertEquals(long expected, long actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static UUID id(int number) { return new UUID(0, number); }
    private static Connection database() throws Exception {
        Connection db = DriverManager.getConnection("jdbc:sqlite::memory:");
        WorkspaceSchema.upgrade(db, 1);
        return db;
    }
    private static void add(Connection db, int conversation, int sequence, long created, long updated,
                            String conversationStatus, String turnStatus, UUID memory, long revision) throws SQLException {
        String conversationId = id(conversation).toString(), turnId = id(10000 + conversation * 100 + sequence).toString();
        try (var s = db.prepareStatement("INSERT OR IGNORE INTO conversations VALUES(?,?,?,?,?)")) {
            s.setString(1, conversationId); s.setString(2, "Synthetic"); s.setString(3, conversationStatus);
            s.setLong(4, created); s.setLong(5, updated); s.executeUpdate();
        }
        try (var s = db.prepareStatement("INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at) VALUES(?,?,?,?,?,?)")) {
            s.setString(1, turnId); s.setString(2, conversationId); s.setInt(3, sequence);
            s.setString(4, turnStatus); s.setLong(5, created); s.setLong(6, created); s.executeUpdate();
        }
        try (var s = db.prepareStatement("INSERT INTO conversation_memory_selections(turn_id,position,memory_id,revision) VALUES(?,0,?,?)")) {
            s.setString(1, turnId); s.setString(2, memory.toString()); s.setLong(3, revision); s.executeUpdate();
        }
    }
    private static void add(Connection db, int conversation) throws SQLException {
        add(db, conversation, 1, 100, 100 + conversation, "ACTIVE", "SUCCEEDED", MEMORY, 1);
    }
    private static Page page(Connection db, UUID memory, Long revision, int page) throws SQLException {
        var request = new ConversationMemoryUsage.Request(memory, revision, page); request.validate();
        var rows = new ArrayList<Row>();
        try (var s = ConversationStore.prepareMemoryUsageQuery(db, request); var r = s.executeQuery()) {
            while (r.next()) rows.add(new Row(r.getString("id"), r.getString("turn_id"), r.getLong("sequence"),
                    r.getString("turn_status"), r.getLong("revision")));
        }
        return new Page(List.copyOf(rows.subList(0, Math.min(rows.size(), 10))), rows.size() > 10);
    }

    void elevenMatchesStayAcrossPagesWhenTheOtherConversationIsUpdated() throws Exception {
        try (var db = database()) {
            for (int i = 1; i <= 11; i++) add(db, i);
            var first = page(db, MEMORY, null, 0);
            assertEquals(10, first.rows().size()); assertTrue(first.hasMore());
            Set<String> firstIds = new HashSet<>(); first.rows().forEach(row -> firstIds.add(row.conversationId()));
            String remaining = IntStream.rangeClosed(1, 11).mapToObj(i -> id(i).toString())
                    .filter(id -> !firstIds.contains(id)).findFirst().orElseThrow();
            try (var s = db.prepareStatement("UPDATE conversations SET title='Renamed',status='ARCHIVED',updated_at=9999 WHERE id=?")) {
                s.setString(1, remaining); assertEquals(1, s.executeUpdate());
            }
            var second = page(db, MEMORY, null, 1);
            assertFalse(second.hasMore()); assertEquals(1, second.rows().size());
            assertEquals(remaining, second.rows().getFirst().conversationId());
            assertTrue(firstIds.add(second.rows().getFirst().conversationId()));
            assertEquals(11, firstIds.size());
        }
    }

    void equalCreationTimesHaveAStableConversationAndSequenceBoundary() throws Exception {
        try (var db = database()) {
            for (int sequence = 1; sequence <= 11; sequence++)
                add(db, 1, sequence, 100, 100, "ACTIVE", "SUCCEEDED", MEMORY, 1);
            add(db, 2, 1, 100, 100, "ACTIVE", "SUCCEEDED", MEMORY, 1);
            var first = page(db, MEMORY, null, 0); var second = page(db, MEMORY, null, 1);
            assertEquals(IntStream.rangeClosed(1, 10).mapToLong(i -> i).boxed().toList(),
                    first.rows().stream().map(Row::sequence).toList());
            assertEquals(List.of(id(1).toString(), id(2).toString()), second.rows().stream().map(Row::conversationId).toList());
            assertEquals(11, second.rows().getFirst().sequence());
            assertTrue(first.hasMore()); assertFalse(second.hasMore());
        }
    }

    void memoryRevisionAndTerminalScopeArePreservedAndEmptyPagesRemainEmpty() throws Exception {
        try (var db = database()) {
            add(db, 1, 1, 100, 100, "ACTIVE", "SUCCEEDED", MEMORY, 1);
            add(db, 2, 1, 100, 100, "ACTIVE", "CANCELLED", MEMORY, 2);
            add(db, 3, 1, 100, 100, "ACTIVE", "SUCCEEDED", OTHER_MEMORY, 1);
            add(db, 4, 1, 100, 100, "ACTIVE", "PENDING", MEMORY, 1);
            add(db, 5, 1, 100, 100, "ARCHIVED", "FAILED", MEMORY, 1);
            var all = page(db, MEMORY, null, 0);
            assertEquals(Set.of(id(1).toString(), id(2).toString(), id(5).toString()),
                    new HashSet<>(all.rows().stream().map(Row::conversationId).toList()));
            assertEquals(Set.of(1L, 2L), new HashSet<>(all.rows().stream().map(Row::revision).toList()));
            assertEquals(Set.of(id(1).toString(), id(5).toString()),
                    new HashSet<>(page(db, MEMORY, 1L, 0).rows().stream().map(Row::conversationId).toList()));
            assertEquals(List.of(id(3).toString()), page(db, OTHER_MEMORY, null, 0).rows().stream().map(Row::conversationId).toList());
            assertTrue(page(db, MEMORY, 3L, 0).rows().isEmpty());
            var empty = page(db, MEMORY, null, 1); assertTrue(empty.rows().isEmpty()); assertFalse(empty.hasMore());
        }
    }

    void newlyInsertedMatchesRemainLiveAndARefreshStartsFromPageZero() throws Exception {
        try (var db = database()) {
            for (int i = 1; i <= 11; i++) add(db, i);
            var first = page(db, MEMORY, null, 0);
            add(db, 12, 1, 200, 200, "ACTIVE", "SUCCEEDED", MEMORY, 1);
            var next = page(db, MEMORY, null, 1);
            Set<String> firstIds = new HashSet<>(first.rows().stream().map(Row::conversationId).toList());
            assertTrue(next.rows().stream().anyMatch(row -> firstIds.contains(row.conversationId())));
            var refreshed = page(db, MEMORY, null, 0);
            assertEquals(id(12).toString(), refreshed.rows().getFirst().conversationId());
            assertTrue(page(db, MEMORY, null, 2).rows().isEmpty());
        }
    }
}
