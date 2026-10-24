package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.account.service.AccountQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

/** Frozen DashboardService/TransactionMapper read slice, inside the existing Finance identity snapshot. */
public final class FinanceDashboardQueries {
    private final FinanceAccountQueries identity;
    private final AccountQueryService accounts;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private static final String ZONE = "Asia/Shanghai";
    private static final String ELIGIBLE = "currency='CNY' AND current_price IS NOT NULL AND quantity IS NOT NULL AND quantity>0 AND current_price*quantity>0";

    FinanceDashboardQueries(FinanceAccountQueries identity, AccountQueryService accounts, DataSource source, Clock clock) {
        this.identity = identity; this.accounts = accounts; this.jdbc = new JdbcTemplate(source);
        this.clock = clock.withZone(ZoneId.of(ZONE));
    }
    public record Flow(String month, String income, String expense, String net) { }
    public record AllocationItem(String id, String name, String value, String percentage) { }
    public record Allocation(List<AllocationItem> items, String total, boolean complete, String referenceTotal,
            String assetTotal, String excludedCurrency, String excludedUnpriced, String excludedQuantity, String excludedValue) { }
    public record Recent(String id, String type, String amount, String categoryName, String accountName, String transactedAt) { }
    public record Dashboard(String currency, String asOf, String businessZone, String cnyAccountBalance,
            String monthIncome, String monthExpense, String monthNet, List<Flow> monthlyCashFlow,
            Allocation allocation, List<Recent> recentTransactions) {
        @Override public String toString() { return "FinanceDashboard[private]"; }
    }
    private record Aggregate(YearMonth month, BigDecimal income, BigDecimal expense) { }

    public Dashboard dashboard() {
        return identity.query(userId -> {
            LocalDateTime now = LocalDateTime.now(clock); // Exactly one business now after identity validation.
            YearMonth month = YearMonth.from(now), first = month.minusMonths(5);
            var aggregates = jdbc.query("""
                    SELECT date_trunc('month', transacted_at)::date AS month_start,
                           COALESCE(SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END),0) AS income,
                           COALESCE(SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END),0) AS expense
                    FROM public.transactions WHERE user_id=? AND currency='CNY' AND type IN ('INCOME','EXPENSE')
                      AND transacted_at>=? AND transacted_at<?
                    GROUP BY date_trunc('month', transacted_at) ORDER BY month_start
                    """, (r, i) -> new Aggregate(YearMonth.from(r.getDate("month_start").toLocalDate()),
                    r.getBigDecimal("income"), r.getBigDecimal("expense")), userId, first.atDay(1).atStartOfDay(), now);
            List<Flow> flows = IntStream.range(0, 6).mapToObj(first::plusMonths).map(m -> {
                var a = aggregates.stream().filter(v -> v.month().equals(m)).findFirst().orElse(new Aggregate(m, BigDecimal.ZERO, BigDecimal.ZERO));
                return new Flow(m.toString(), a.income().toPlainString(), a.expense().toPlainString(), a.income().subtract(a.expense()).toPlainString());
            }).toList();
            var coverage = jdbc.queryForMap("""
                    SELECT count(*) AS assets,
                      count(*) FILTER (WHERE currency<>'CNY') AS currency,
                      count(*) FILTER (WHERE currency='CNY' AND current_price IS NULL) AS unpriced,
                      count(*) FILTER (WHERE currency='CNY' AND current_price IS NOT NULL AND (quantity IS NULL OR quantity<=0)) AS quantity,
                      count(*) FILTER (WHERE currency='CNY' AND current_price IS NOT NULL AND quantity>0 AND current_price*quantity<=0) AS value,
                      count(*) FILTER (WHERE %s) AS eligible,
                      COALESCE(sum(current_price*quantity) FILTER (WHERE %s),0) AS denominator
                    FROM public.assets WHERE user_id=?
                    """.formatted(ELIGIBLE, ELIGIBLE), userId);
            BigDecimal denominator = (BigDecimal) coverage.get("denominator");
            var items = jdbc.query("SELECT id,name,current_price,quantity FROM public.assets WHERE user_id=? AND " + ELIGIBLE + " ORDER BY id ASC LIMIT 20",
                    (r, i) -> {
                        BigDecimal value = r.getBigDecimal("current_price").multiply(r.getBigDecimal("quantity"));
                        return new AllocationItem(r.getString("id"), r.getString("name"), value.toPlainString(),
                                value.multiply(BigDecimal.valueOf(100)).divide(denominator, 4, RoundingMode.HALF_UP).toPlainString());
                    }, userId);
            var allocation = new Allocation(items, coverage.get("eligible").toString(),
                    ((Number) coverage.get("eligible")).longValue() == items.size(), denominator.toPlainString(),
                    coverage.get("assets").toString(), coverage.get("currency").toString(), coverage.get("unpriced").toString(),
                    coverage.get("quantity").toString(), coverage.get("value").toString());
            // LIMIT belongs to ordinary transactions, with no extra type/time predicate. Names cannot cross ownership.
            var recent = jdbc.query("""
                    SELECT t.id,t.type,t.amount,t.transacted_at,a.name AS account_name,c.name AS category_name
                    FROM (SELECT * FROM public.transactions WHERE user_id=? AND currency='CNY'
                          ORDER BY transacted_at DESC,id DESC LIMIT 5) t
                    LEFT JOIN public.accounts a ON a.id=t.account_id AND a.user_id=t.user_id
                    LEFT JOIN public.categories c ON c.id=t.category_id AND
                          (c.user_id=t.user_id OR (c.user_id IS NULL AND c.is_system=true))
                    ORDER BY t.transacted_at DESC,t.id DESC
                    """, (r, i) -> new Recent(r.getString("id"), r.getString("type"), r.getBigDecimal("amount").toPlainString(),
                    r.getString("category_name"), r.getString("account_name"), r.getTimestamp("transacted_at").toLocalDateTime().toString()), userId);
            var current = flows.getLast();
            return new Dashboard("CNY", now.toString(), ZONE, accounts.sumBalanceByUser(userId).toPlainString(),
                    current.income(), current.expense(), current.net(), flows, allocation, recent);
        });
    }
}
