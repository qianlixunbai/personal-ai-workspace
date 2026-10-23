package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Read-only adaptation of frozen TransactionService.pageByUser and the separate V19 transfer ledger.
 * All SQL runs inside the existing verified Finance identity/read-only snapshot boundary. */
public final class FinanceTransactionQueries {
    private final FinanceAccountQueries identity;
    private final JdbcTemplate jdbc;

    FinanceTransactionQueries(FinanceAccountQueries identity, DataSource dataSource) {
        this.identity = identity;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record TransactionView(String kind, String id, String type, String amount, String currency,
            String transactedAt, String description, String accountId, String accountName,
            String toAccountId, String toAccountName) {
        @Override public String toString() { return "FinanceTransaction[private]"; }
    }
    public record TransactionPage(List<TransactionView> items, int page, int size, String total,
            String accountId, String startDate, String endDate, String type) { }

    // Distinct source keys are required: transactions.id and transfers.id can overlap.
    private static final String ROWS = """
            SELECT 'TRANSACTION' AS kind,t.id,t.type,t.amount,t.currency,t.transacted_at,t.description,
                   a.id AS account_id,a.name AS account_name,NULL::bigint AS to_account_id,NULL::varchar AS to_account_name
            FROM public.transactions t JOIN public.accounts a ON a.id=t.account_id AND a.user_id=t.user_id
            WHERE t.user_id=?
            UNION ALL
            SELECT 'TRANSFER' AS kind,t.id,'TRANSFER' AS type,t.amount,t.currency,t.transacted_at,t.description,
                   a.id AS account_id,a.name AS account_name,b.id AS to_account_id,b.name AS to_account_name
            FROM public.transfers t
            JOIN public.accounts a ON a.id=t.from_account_id AND a.user_id=t.user_id
            JOIN public.accounts b ON b.id=t.to_account_id AND b.user_id=t.user_id
            WHERE t.user_id=?
            """;

    public TransactionPage list(int page, int size, String accountId, String startDate, String endDate, String type) {
        if (page < 0 || page > 100000 || size < 1 || size > 20 || type == null
                || !Set.of("ALL", "INCOME", "EXPENSE", "ADJUSTMENT", "TRANSFER", "REFUND").contains(type)) throw invalid();
        Long account = null;
        if (!accountId.isEmpty()) {
            try {
                if (!accountId.matches("[1-9][0-9]{0,18}")) throw invalid();
                account = Long.valueOf(accountId);
            } catch (NumberFormatException bad) { throw invalid(); }
        }
        LocalDate start = date(startDate), end = date(endDate);
        if (start != null && end != null && start.isAfter(end)) throw invalid();
        Long selectedAccount = account;
        return identity.query(userId -> {
            var args = new ArrayList<Object>(List.of(userId, userId));
            String where = " WHERE 1=1";
            if (selectedAccount != null) { where += " AND (account_id=? OR to_account_id=?)"; args.add(selectedAccount); args.add(selectedAccount); }
            if (start != null) { where += " AND transacted_at>=?"; args.add(start.atStartOfDay()); }
            if (end != null) { where += " AND transacted_at<?"; args.add(end.plusDays(1).atStartOfDay()); }
            if (!type.equals("ALL")) { where += " AND type=?"; args.add(type); }
            String sql = " FROM (" + ROWS + ") ledger" + where;
            long total = jdbc.queryForObject("SELECT count(*)" + sql, Long.class, args.toArray());
            args.add(size); args.add((long) page * size);
            var items = jdbc.query("SELECT *" + sql + " ORDER BY transacted_at DESC,id DESC,kind ASC LIMIT ? OFFSET ?",
                    FinanceTransactionQueries::view, args.toArray());
            return new TransactionPage(items, page, size, Long.toString(total), accountId, startDate, endDate, type);
        });
    }

    public TransactionView transaction(String kind, long id) {
        if (id < 1 || !Set.of("TRANSACTION", "TRANSFER").contains(kind)) throw invalid();
        return identity.query(userId -> {
            var rows = jdbc.query("SELECT * FROM (" + ROWS + ") ledger WHERE kind=? AND id=?",
                    FinanceTransactionQueries::view, userId, userId, kind, id);
            if (rows.isEmpty()) throw new WorkspaceException(ErrorCode.FINANCE_TRANSACTION_NOT_FOUND, "FINANCE");
            return rows.getFirst();
        });
    }

    private static TransactionView view(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new TransactionView(row.getString("kind"), row.getString("id"), row.getString("type"),
                row.getBigDecimal("amount").toPlainString(), row.getString("currency"),
                row.getTimestamp("transacted_at").toLocalDateTime().toString(), row.getString("description"),
                row.getString("account_id"), row.getString("account_name"), row.getString("to_account_id"), row.getString("to_account_name"));
    }

    private static LocalDate date(String text) {
        if (text.isEmpty()) return null;
        try {
            if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || text.startsWith("0000")) throw invalid();
            return LocalDate.parse(text);
        } catch (DateTimeParseException bad) { throw invalid(); }
    }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
}
