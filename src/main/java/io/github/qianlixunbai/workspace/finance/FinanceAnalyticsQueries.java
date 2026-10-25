package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

/** Bounded read projections inside the existing Finance identity/repeatable-read boundary. */
public final class FinanceAnalyticsQueries {
    static final int MIN_YEAR = 1900, MAX_YEAR = 2199;
    private final FinanceAccountQueries identity;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    FinanceAnalyticsQueries(FinanceAccountQueries identity, DataSource source, Clock clock) {
        this.identity = identity; jdbc = new JdbcTemplate(source); this.clock = clock.withZone(ZoneId.of("Asia/Shanghai"));
    }
    public record Month(String month, String state, String income, String expense, String net, String transactionCount) { }
    public record Tick(int coordinate, String amount) { }
    public record Plot(List<Integer> income, List<Integer> expense, List<Integer> net, int zero, List<Tick> ticks) { }
    public record Slice(String key, String label, String value, String percentage, int start, int end) { }
    public record Distribution(String state, String total, List<Slice> items) { }
    record Part(String key, String label, BigDecimal value) { }
    public record Annual(String currency, String businessZone, String asOf, int currentYear, int year, List<Integer> years,
                         String income, String expense, String net, String transactionCount, List<Month> months,
                         Plot plot, Distribution expenseBreakdown) {
        @Override public String toString() { return "FinanceAnnual[private]"; }
    }
    public Annual annual(int requestedYear) {
        if (requestedYear != 0 && (requestedYear < MIN_YEAR || requestedYear > MAX_YEAR)) throw invalid();
        return identity.query(user -> {
            var now = LocalDateTime.now(clock);
            int current = now.getYear(), year = requestedYear == 0 ? current : requestedYear;
            if (current < MIN_YEAR || current > MAX_YEAR || year > current) throw invalid();
            var years = new TreeSet<Integer>(Comparator.reverseOrder()); years.add(current);
            years.addAll(jdbc.query("""
                    SELECT DISTINCT extract(year FROM transacted_at)::int AS year FROM public.transactions
                    WHERE user_id=? AND currency='CNY' AND type IN ('INCOME','EXPENSE')
                      AND transacted_at>=? AND transacted_at<? ORDER BY year DESC LIMIT 300
                    """, (r, i) -> r.getInt("year"), user, LocalDate.of(MIN_YEAR, 1, 1).atStartOfDay(), now));
            var months = months(jdbc, user, YearMonth.of(year, 1), 12, now);
            BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO; long count = 0;
            for (var m : months) if (!m.state().equals("FUTURE")) {
                income = income.add(new BigDecimal(m.income())); expense = expense.add(new BigDecimal(m.expense()));
                count = Math.addExact(count, Long.parseLong(m.transactionCount()));
            }
            var end = LocalDate.of(year + 1, 1, 1).atStartOfDay(); if (end.isAfter(now)) end = now;
            return new Annual("CNY", "Asia/Shanghai", now.toString(), current, year, List.copyOf(years),
                    income.toPlainString(), expense.toPlainString(), income.subtract(expense).toPlainString(), Long.toString(count),
                    months, plot(months), expenses(jdbc, user, LocalDate.of(year, 1, 1).atStartOfDay(), end));
        });
    }
    static List<Month> months(JdbcTemplate jdbc, long user, YearMonth first, int size, LocalDateTime asOf) {
        var end = first.plusMonths(size).atDay(1).atStartOfDay(); if (end.isAfter(asOf)) end = asOf;
        var aggregates = jdbc.query("""
                SELECT date_trunc('month',transacted_at)::date AS month_start,
                  COALESCE(sum(amount) FILTER (WHERE type='INCOME'),0) AS income,
                  COALESCE(sum(amount) FILTER (WHERE type='EXPENSE'),0) AS expense, count(*) AS count
                FROM public.transactions WHERE user_id=? AND currency='CNY' AND type IN ('INCOME','EXPENSE')
                  AND transacted_at>=? AND transacted_at<? GROUP BY month_start ORDER BY month_start
                """, (r, i) -> {
            var income = r.getBigDecimal("income"); var expense = r.getBigDecimal("expense");
            return new Month(YearMonth.from(r.getDate("month_start").toLocalDate()).toString(), "OBSERVED", income.toPlainString(),
                    expense.toPlainString(), income.subtract(expense).toPlainString(), r.getString("count"));
        }, user, first.atDay(1).atStartOfDay(), end);
        return IntStream.range(0, size).mapToObj(first::plusMonths).map(m -> m.isAfter(YearMonth.from(asOf))
                ? new Month(m.toString(), "FUTURE", null, null, null, null)
                : aggregates.stream().filter(a -> a.month().equals(m.toString())).findFirst()
                    .orElse(new Month(m.toString(), "OBSERVED", "0", "0", "0", "0"))).toList();
    }
    static Plot plot(List<Month> months) {
        var values = months.stream().filter(m -> !m.state().equals("FUTURE"))
                .flatMap(m -> java.util.stream.Stream.of(m.income(), m.expense(), m.net())).map(BigDecimal::new).toList();
        var min = values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO).min(BigDecimal.ZERO);
        var max = values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO).max(BigDecimal.ZERO);
        var range = max.subtract(min);
        java.util.function.Function<String, Integer> coordinate = s -> s == null ? null : range.signum() == 0 ? 10000
                : max.subtract(new BigDecimal(s)).multiply(BigDecimal.valueOf(10000)).divide(range, 0, RoundingMode.HALF_UP).intValueExact();
        var ticks = IntStream.rangeClosed(0, 4).mapToObj(i -> new Tick(i * 2500,
                max.subtract(range.multiply(BigDecimal.valueOf(i)).divide(BigDecimal.valueOf(4))).toPlainString())).toList();
        return new Plot(months.stream().map(m -> coordinate.apply(m.income())).toList(),
                months.stream().map(m -> coordinate.apply(m.expense())).toList(),
                months.stream().map(m -> coordinate.apply(m.net())).toList(), coordinate.apply("0"), ticks);
    }
    static Distribution distribution(List<Part> parts) {
        BigDecimal total = parts.stream().map(Part::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (parts.stream().anyMatch(p -> p.value().signum() < 0)) return new Distribution("UNSUPPORTED_SIGNED", total.toPlainString(), List.of());
        if (total.signum() == 0) return new Distribution("EMPTY", total.toPlainString(), List.of());
        var items = new ArrayList<Slice>(); BigDecimal cumulative = BigDecimal.ZERO; int start = 0;
        for (var p : parts) {
            cumulative = cumulative.add(p.value());
            int end = cumulative.multiply(BigDecimal.valueOf(10000)).divide(total, 0, RoundingMode.HALF_UP).intValueExact();
            items.add(new Slice(p.key(), p.label(), p.value().toPlainString(),
                    p.value().multiply(BigDecimal.valueOf(100)).divide(total, 4, RoundingMode.HALF_UP).toPlainString(), start, end)); start = end;
        }
        return new Distribution("COMPLETE", total.toPlainString(), List.copyOf(items));
    }
    static Distribution counts(JdbcTemplate jdbc, long user, boolean assets) {
        // Both identifiers are fixed in code. No caller controls SQL identifiers.
        return distribution(jdbc.query("SELECT type,count(*) AS count FROM public." + (assets ? "assets" : "accounts")
                + " WHERE user_id=? GROUP BY type ORDER BY type LIMIT 10",
                (r, i) -> new Part(r.getString("type"), r.getString("type"), r.getBigDecimal("count")), user));
    }
    static Distribution expenses(JdbcTemplate jdbc, long user, LocalDateTime start, LocalDateTime end) {
        boolean[] signed = { false };
        var parts = jdbc.query("""
                WITH grouped AS (
                  SELECT COALESCE(c.id::text,'UNCLASSIFIED') AS key,COALESCE(c.name,'未分类 / 类别不可用') AS label,sum(t.amount) AS value,bool_or(t.amount<0) AS signed
                  FROM public.transactions t LEFT JOIN public.categories c ON c.id=t.category_id AND c.type=t.type
                    AND (c.user_id=t.user_id OR (c.user_id IS NULL AND c.is_system=true))
                  WHERE t.user_id=? AND t.currency='CNY' AND t.type='EXPENSE' AND t.transacted_at>=? AND t.transacted_at<?
                  GROUP BY c.id,c.name
                ), ranked AS (SELECT *,row_number() OVER(ORDER BY value DESC,key) AS rank FROM grouped),
                buckets AS (SELECT CASE WHEN rank<=12 THEN key ELSE 'REMAINDER' END AS key,
                  CASE WHEN rank<=12 THEN label ELSE '其余类别合计' END AS label,value,signed FROM ranked)
                SELECT key,label,sum(value) AS value,bool_or(signed) AS signed FROM buckets GROUP BY key,label ORDER BY value DESC,key
                """, (r, i) -> { signed[0] |= r.getBoolean("signed"); return new Part(r.getString("key"), r.getString("label"), r.getBigDecimal("value")); }, user, start, end);
        var result = distribution(parts);
        return signed[0] ? new Distribution("UNSUPPORTED_SIGNED", result.total(), List.of()) : result;
    }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
}
