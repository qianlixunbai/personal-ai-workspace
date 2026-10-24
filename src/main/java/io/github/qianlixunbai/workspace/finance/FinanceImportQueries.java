package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.importing.dto.TransactionImportReceipt;
import com.financeos.module.importing.dto.TransactionImportReceipt.AccountImpact;
import com.financeos.module.importing.dto.TransactionImportReceipt.TransactionReference;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Read-only adaptation of frozen ConfirmService.getReceipt/reconstruct; no confirm service is wired. */
public final class FinanceImportQueries {
    public static final int SEGMENT_SIZE = 20;
    private static final String CONTRACT = "TRANSACTION_IMPORT_CONFIRM_V1";
    private final FinanceAccountQueries identity;
    private final JdbcTemplate jdbc;
    FinanceImportQueries(FinanceAccountQueries identity, DataSource source) {
        this.identity = identity; this.jdbc = new JdbcTemplate(source);
    }
    public record Batch(String importBatchId, String sourceFileName, Instant confirmedAt, String status,
                        int totalRows, int warningCount, String contractVersion) { }
    public record Page(List<Batch> items, int page, int size, String total) { }
    public record Reference(int rowNumber, String transactionId) { }
    public record Impact(String accountId, int rowCount, String balanceBefore, String delta, String balanceAfter) { }
    public record Receipt(UUID importSessionId, UUID importBatchId, String status, String sourceFileName, String fileDigest,
            int totalRows, int acceptedRows, int createdCount, int skippedCount, int warningCount,
            List<Reference> transactions, List<Impact> accountImpacts, Instant confirmedAt, String contractVersion, String resultDigest) { }
    /** Arrays are explicitly paged views of a fully verified immutable receipt, never a truncated full receipt. */
    public record Segment(Receipt receipt, int transactionPage, int accountImpactPage, int segmentSize,
                          int accountImpactCount, boolean completeReceipt) { }

    public Page list(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 20) throw failure(ErrorCode.INVALID_REQUEST);
        return identity.query(user -> new Page(jdbc.query("""
                SELECT id, original_file_name, confirmed_at, status, total_rows, warning_count, contract_version
                FROM transaction_import_batches WHERE user_id = ? AND status = 'CONFIRMED'
                ORDER BY confirmed_at DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> new Batch(rs.getString("id"), safeFileName(rs.getString("original_file_name")),
                rs.getTimestamp("confirmed_at").toInstant(), rs.getString("status"), rs.getInt("total_rows"),
                rs.getInt("warning_count"), rs.getString("contract_version")), user, size, (long) page * size), page, size,
                Long.toString(jdbc.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ? AND status = 'CONFIRMED'", Long.class, user))));
    }

    public Segment detail(String id, int transactionPage, int accountImpactPage) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || transactionPage < 0 || transactionPage > 499 || accountImpactPage < 0 || accountImpactPage > 499)
            throw failure(ErrorCode.INVALID_REQUEST);
        return identity.query(user -> {
            var batches = jdbc.query("""
                    SELECT session_id, id, status, original_file_name, file_digest, total_rows, warning_count,
                           confirmed_at, contract_version, result_digest
                    FROM transaction_import_batches WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'
                    """, (rs, n) -> new TransactionImportReceipt(rs.getObject("session_id", UUID.class), rs.getObject("id", UUID.class),
                    rs.getString("status"), rs.getString("original_file_name"), rs.getString("file_digest"), rs.getInt("total_rows"),
                    rs.getInt("total_rows"), rs.getInt("total_rows"), 0, rs.getInt("warning_count"), List.of(), List.of(),
                    rs.getTimestamp("confirmed_at").toInstant(), rs.getString("contract_version"), rs.getString("result_digest")), UUID.fromString(id), user);
            if (batches.isEmpty()) throw failure(ErrorCode.FINANCE_IMPORT_NOT_FOUND);
            var batch = batches.getFirst();
            if (!CONTRACT.equals(batch.contractVersion()) || batch.totalRows() < 1 || batch.totalRows() > 10000
                    || batch.warningCount() < 0 || batch.warningCount() > batch.totalRows()
                    || batch.fileDigest() == null || !batch.fileDigest().matches("[0-9a-f]{64}")) throw inconsistent();
            // Source maximum is 10,000 rows. The extra row detects corrupt/oversized evidence without an unbounded read.
            var refs = jdbc.query("""
                    SELECT source_row_number, created_transaction_id FROM transaction_import_items
                    WHERE user_id = ? AND batch_id = ? ORDER BY source_row_number LIMIT 10001
                    """, (rs, n) -> new TransactionReference(rs.getInt(1), rs.getObject(2, Long.class)), user, UUID.fromString(id));
            if (refs.size() != batch.totalRows() || refs.stream().anyMatch(r -> r.rowNumber() < 1 || r.transactionId() == null || r.transactionId() < 1)
                    || refs.stream().map(TransactionReference::rowNumber).distinct().count() != refs.size()
                    || refs.stream().map(TransactionReference::transactionId).distinct().count() != refs.size()) throw inconsistent();
            var impacts = jdbc.query("""
                    SELECT account_id, row_count, balance_before, delta, balance_after FROM transaction_import_batch_account_impacts
                    WHERE user_id = ? AND batch_id = ? ORDER BY account_id LIMIT 10001
                    """, (rs, n) -> new AccountImpact(rs.getObject(1, Long.class), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)), user, UUID.fromString(id));
            if (impacts.isEmpty() || impacts.size() > batch.totalRows() || impacts.stream().anyMatch(r -> r.accountId() == null || r.accountId() < 1
                    || r.rowCount() < 1 || r.rowCount() > batch.totalRows() || r.balanceBefore() == null || r.delta() == null || r.balanceAfter() == null
                    || r.balanceAfter().compareTo(r.balanceBefore().add(r.delta())) != 0)
                    || impacts.stream().map(AccountImpact::accountId).distinct().count() != impacts.size()
                    || impacts.stream().mapToLong(AccountImpact::rowCount).sum() != batch.totalRows()) throw inconsistent();
            // Keep source record toString(), ordering, BigDecimal scale and Instant formatting byte-for-byte.
            // V20 intentionally allows referenced transactions to be deleted later; never join current transactions/balances.
            String actual = resultDigest(batch.importSessionId(), batch.importBatchId(), refs, impacts, batch.confirmedAt());
            if (!actual.equals(batch.resultDigest())) throw inconsistent();
            if ((long) transactionPage * SEGMENT_SIZE >= refs.size() || (long) accountImpactPage * SEGMENT_SIZE >= impacts.size())
                throw failure(ErrorCode.INVALID_REQUEST);
            var receipt = new Receipt(batch.importSessionId(), batch.importBatchId(), batch.status(), safeFileName(batch.sourceFileName()), batch.fileDigest(),
                    batch.totalRows(), batch.acceptedRows(), batch.createdCount(), batch.skippedCount(), batch.warningCount(),
                    slice(refs, transactionPage).stream().map(r -> new Reference(r.rowNumber(), r.transactionId().toString())).toList(),
                    slice(impacts, accountImpactPage).stream().map(r -> new Impact(r.accountId().toString(), r.rowCount(), r.balanceBefore().toPlainString(),
                            r.delta().toPlainString(), r.balanceAfter().toPlainString())).toList(), batch.confirmedAt(), batch.contractVersion(), batch.resultDigest());
            return new Segment(receipt, transactionPage, accountImpactPage, SEGMENT_SIZE, impacts.size(), refs.size() <= SEGMENT_SIZE && impacts.size() <= SEGMENT_SIZE);
        });
    }
    static String resultDigest(UUID session, UUID batch, List<TransactionReference> refs, List<AccountImpact> impacts, Instant time) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((session + "|" + batch + "|" + time + "|" + refs + "|" + impacts).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> List<T> slice(List<T> rows, int page) { return rows.subList(page * SEGMENT_SIZE, Math.min(rows.size(), (page + 1) * SEGMENT_SIZE)); }
    private static String safeFileName(String value) {
        if (value == null) return "[redacted]";
        String name = value.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        return name.isBlank() || name.length() > 255 || name.matches("(?is).*(?:[\\p{Cntrl}:]|password|secret|token|api[_-]?key|credential).*") ? "[redacted]" : name;
    }
    private static WorkspaceException inconsistent() { return failure(ErrorCode.FINANCE_IMPORT_RECEIPT_INVALID); }
    private static WorkspaceException failure(ErrorCode code) { return new WorkspaceException(code, "FINANCE"); }
}
