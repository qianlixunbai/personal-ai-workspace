package io.github.qianlixunbai.workspace.finance;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FinanceDashboardPlotTest {
    @Test
    void sharesABoundedScaleWithoutChangingExactMoneyIncludingNegativeAndZeroFlows() {
        var high = new FinanceDashboardQueries.Flow("2026-10", "9007199254740993.1234567890123456", "4503599627370496.5617283945061728", "4503599627370496.5617283945061728");
        var original = List.of(high);
        var plot = FinanceDashboardQueries.plot(original);
        assertEquals(List.of(0), plot.income()); assertEquals(List.of(5000), plot.expense()); assertEquals(10000, plot.zero());
        assertEquals("9007199254740993.1234567890123456", original.getFirst().income());
        assertEquals("4503599627370496.5617283945061728", original.getFirst().expense());
        var signed = FinanceDashboardQueries.plot(List.of(new FinanceDashboardQueries.Flow("2026-10", "-0.0000000000000001", "0.0000000000000001", "-0.0000000000000002")));
        assertEquals(List.of(10000), signed.income()); assertEquals(List.of(0), signed.expense()); assertEquals(5000, signed.zero());
        var zeros = FinanceDashboardQueries.plot(List.of(new FinanceDashboardQueries.Flow("2026-10", "0", "0", "0")));
        assertEquals(List.of(10000), zeros.income()); assertEquals(zeros.income(), zeros.expense()); assertEquals(10000, zeros.zero());
    }
}
