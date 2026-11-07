package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.knowledge.LexicalAnalyzer;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static io.github.qianlixunbai.workspace.memory.MemoryLimits.*;

/** Bounded, deterministic lexical suggestions; never an admission or a memory write. */
public final class MemoryRecall {
    public static final int LIMIT = 20;
    private MemoryRecall() {}
    public record Hit(MemoryItem item, int score, List<String> matchedTerms) {
        public Hit { matchedTerms = List.copyOf(matchedTerms); }
        @Override public String toString() { return "MemoryRecallHit[id=" + item.id() + ",score=" + score + "]"; }
    }
    public record Result(List<Hit> hits, int total, int limit) {
        public Result { hits = List.copyOf(hits); }
        @Override public String toString() { return "MemoryRecallResult[total=" + total + "]"; }
    }
    static Set<String> terms(String query) {
        MemoryLimits.query(query, 0, LIMIT);
        if (query == null || query.isBlank()) throw error(ErrorCode.MEMORY_INVALID);
        var terms = new LinkedHashSet<>(LexicalAnalyzer.tokens(query));
        // In multi-character CJK queries, bigrams avoid ubiquitous single-character matches.
        if (terms.stream().anyMatch(t -> t.startsWith("b"))) terms.removeIf(t -> t.startsWith("u"));
        return terms;
    }
    /** Caller owns the admitted connection and read transaction. No connection, filesystem,
     * schema, lifecycle or authorization is created here. Package-private for query-layer tests. */
    static Result query(Connection connection, MemoryItem.Type type, Set<String> terms) throws SQLException {
        List<Hit> hits = new ArrayList<>();
        if (terms.isEmpty()) return rank(hits);
        String sql = "SELECT * FROM memory_items WHERE status='ACTIVE'"
                + (type == null ? "" : " AND type=?") + " LIMIT " + (TOTAL_ITEMS + 1);
        try (var statement = connection.prepareStatement(sql)) {
            if (type != null) statement.setString(1, type.name());
            try (var result = statement.executeQuery()) {
                int count = 0;
                while (result.next()) {
                    if (++count > TOTAL_ITEMS) throw error(ErrorCode.MEMORY_STORAGE_UNAVAILABLE);
                    var hit = match(MemoryStore.item(result), terms);
                    if (hit.score() > 0) hits.add(hit);
                }
            }
        }
        return rank(hits);
    }
    static Hit match(MemoryItem item, Set<String> terms) {
        var title = new HashSet<>(LexicalAnalyzer.tokens(item.title()));
        var content = new HashSet<>(LexicalAnalyzer.tokens(item.content()));
        int score = 0;
        List<String> matched = new ArrayList<>();
        for (String term : terms) {
            int weight = (title.contains(term) ? 3 : 0) + (content.contains(term) ? 1 : 0);
            if (weight > 0) {
                score += weight;
                var label = new StringBuilder();
                for (String cp : term.substring(1).split("x")) label.appendCodePoint(Integer.parseInt(cp, 16));
                matched.add(label.toString());
            }
        }
        return new Hit(item, score, matched);
    }
    static Result rank(List<Hit> hits) {
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparing(hit -> hit.item().updatedAt(), Comparator.reverseOrder())
                .thenComparing(hit -> hit.item().id().toString()));
        return new Result(hits.stream().limit(LIMIT).toList(), hits.size(), LIMIT);
    }
}
