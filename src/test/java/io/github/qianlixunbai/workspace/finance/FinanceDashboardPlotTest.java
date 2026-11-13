package io.github.qianlixunbai.workspace.finance;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FinanceDashboardPlotTest {
    @Test
    void analyticsProjectionKeepsExactStringsSignedNetAndClosedDistributionWithoutFinancialRounding() {
        var m = new FinanceAnalyticsQueries.Month("2026-01", "OBSERVED", "9007199254740993.1234567890123456", "9007199254740993.1234567890123457", "-0.0000000000000001", "2");
        var future = new FinanceAnalyticsQueries.Month("2026-02", "FUTURE", null, null, null, null);
        var p = FinanceAnalyticsQueries.plot(List.of(m, future));
        assertTrue(p.zero() >= 0 && p.zero() <= 10000); assertEquals(10000, p.net().getFirst());
        assertNull(p.net().get(1)); assertEquals(m.income(), "9007199254740993.1234567890123456");
        var d = FinanceAnalyticsQueries.distribution(List.of(
                new FinanceAnalyticsQueries.Part("a", "A", java.math.BigDecimal.ONE),
                new FinanceAnalyticsQueries.Part("b", "B", java.math.BigDecimal.ONE),
                new FinanceAnalyticsQueries.Part("c", "C", java.math.BigDecimal.ONE)));
        assertEquals("3", d.total()); assertEquals("33.3333", d.items().getFirst().percentage());
        assertEquals(0, d.items().getFirst().start()); assertEquals(10000, d.items().getLast().end());
        assertEquals(d.items().getFirst().end(), d.items().get(1).start());
        assertEquals("UNSUPPORTED_SIGNED", FinanceAnalyticsQueries.distribution(List.of(
                new FinanceAnalyticsQueries.Part("negative", "Negative", java.math.BigDecimal.valueOf(-1)))).state());
    }
}
