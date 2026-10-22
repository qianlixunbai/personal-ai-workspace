package io.github.qianlixunbai.workspace.finance;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.List;

/** Narrow, bounded reads using the caller's verified Finance transaction and user scope. */
final class FinanceAccountListing {
    private final JdbcTemplate jdbc;
    FinanceAccountListing(DataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }

    long count(long userId, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM public.accounts WHERE user_id=? AND (?='ALL' OR status=?)",
                Long.class, userId, status, status);
    }

    List<FinanceAccountQueries.AccountView> page(long userId, int page, int size, String status, String sort) {
        String order = switch (sort) {
            case "ID_ASC" -> "id ASC";
            case "ID_DESC" -> "id DESC";
            case "NAME_ASC" -> "name ASC, id ASC";
            default -> throw new IllegalArgumentException("Unsupported account order");
        };
        return jdbc.query("SELECT id,name,type,currency,balance,status FROM public.accounts "
                        + "WHERE user_id=? AND (?='ALL' OR status=?) ORDER BY " + order + " LIMIT ? OFFSET ?",
                (row, index) -> new FinanceAccountQueries.AccountView(row.getString("id"), row.getString("name"),
                        row.getString("type"), row.getString("currency"), row.getBigDecimal("balance").toPlainString(), row.getString("status")),
                userId, status, status, size, (long) page * size);
    }
}
