package io.github.qianlixunbai.workspace.memory;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure retrieval behavior; does not claim to validate a filesystem or HTTP admission. */
class MemoryRecallTest {
    private MemoryItem item(int id, String title, String content) {
        return new MemoryItem(new UUID(0, id), MemoryItem.Type.PROJECT_NOTE, title, content,
                MemoryItem.Status.ACTIVE, 9007199254740993L, MemoryItem.Source.MANUAL, Instant.EPOCH, Instant.EPOCH);
    }
    @Test void questionFindsNormalizedWordsAndCjkPairsWithBoundedDeterministicRanking() {
        var query = MemoryRecall.terms("ＪＡＶＡ 项目计划");
        var first = MemoryRecall.match(item(1, "Java 项目", "编程习惯"), query);
        var second = MemoryRecall.match(item(2, "工作偏好", "java java 项目"), query);
        var none = MemoryRecall.match(item(3, "伙食", "爱吃苹果"), query);
        assertEquals(List.of("java", "项目"), first.matchedTerms());
        assertTrue(first.score() > second.score()); assertEquals(0, none.score());
        assertEquals(first.score(), MemoryRecall.match(first.item(), MemoryRecall.terms("java java 项目计划")).score());
        assertEquals(1, MemoryRecall.match(item(4, "偏好", "中"), MemoryRecall.terms("中")).score());
        assertEquals(0, MemoryRecall.match(item(5, "javafx", "非匹配"), MemoryRecall.terms("java")).score());
        assertTrue(MemoryRecall.terms("😀 %_").isEmpty());
        var hits = new ArrayList<MemoryRecall.Hit>();
        for (int id = 25; id >= 1; id--) hits.add(MemoryRecall.match(item(id, "Java 项目", "编程习惯"), query));
        var result = MemoryRecall.rank(hits);
        assertEquals(25, result.total()); assertEquals(20, result.hits().size());
        assertEquals(item(1, "", "").id(), result.hits().getFirst().item().id());
        assertEquals(item(20, "", "").id(), result.hits().getLast().item().id());
        assertEquals(9007199254740993L, result.hits().getFirst().item().revision());
        assertThrows(UnsupportedOperationException.class, () -> result.hits().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.matchedTerms().clear());
        assertFalse(result.toString().contains("项目")); assertFalse(first.toString().contains("java"));
    }
}
