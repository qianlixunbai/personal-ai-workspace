package io.github.qianlixunbai.workspace.finance;

import com.financeos.common.BusinessException;
import com.financeos.module.investment.read.dto.*;
import com.financeos.module.investment.read.model.LogicalTransactionListQuery;
import com.financeos.module.investment.read.service.InvestmentLogicalTransactionReadQueryService;
import com.financeos.module.investment.read.service.InvestmentLogicalTransactionDetailQueryService;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Transport IDs are strings; source financial values, receipts and event grouping remain unchanged. */
public final class FinanceInvestmentHistoryQueries {
    private final FinanceAccountQueries identity;
    private final InvestmentLogicalTransactionReadQueryService lists;
    private final InvestmentLogicalTransactionDetailQueryService details;
    FinanceInvestmentHistoryQueries(FinanceAccountQueries identity, InvestmentLogicalTransactionReadQueryService lists,
                                    InvestmentLogicalTransactionDetailQueryService details) {
        this.identity = identity; this.lists = lists; this.details = details;
    }
    public record Account(String id, String displayName) { }
    public record Instrument(String id, String symbol, String name, String market, String assetClass, String quoteCurrency) { }
    public record Item(String logicalTransactionId, String positionId, Account account, Instrument instrument,
            String transactionType, Instant effectiveTradeTime, String quantity, String unitPrice, String grossAmount,
            String feeAmount, String taxAmount, String netAmount, String releasedCostAmount, String realizedProfitLoss,
            String correctionStatus, boolean effective, Instant correctionCreatedAt) { }
    public record Page(List<Item> records, String nextCursor, boolean hasMore, int size) { }
    public record Correction(Instant createdAt, String reason, String reversalTransactionId, String replacementTransactionId) { }
    public record Detail(String logicalTransactionId, String transactionType, String correctionStatus, boolean effective,
            Instant effectiveTradeTime, Instant settlementTime, Account account, Instrument instrument,
            InvestmentTransactionDetail.TransactionBusinessValues originalBusinessValues,
            InvestmentTransactionDetail.TransactionBusinessValues effectiveBusinessValues,
            InvestmentTransactionDetail.PostingReceipt postingReceipt, InvestmentTransactionDetail.CorrectionFinalReceipt correctionFinalReceipt,
            Correction correction, InvestmentTransactionDetail.CurrentPosition currentPosition) { }
    public record Fact(String role, String physicalFactId, String factType, String receiptApplicability,
                       InvestmentTransactionAuditTimeline.AuditBusinessValues businessValues) { }
    public record Event(String eventKind, Instant createdAt, String physicalFactId, String originalFactId,
            String factType, String reason, String receiptApplicability,
            InvestmentTransactionAuditTimeline.AuditBusinessValues businessValues,
            InvestmentTransactionAuditTimeline.AuditPostingReceipt postingReceipt,
            InvestmentTransactionAuditTimeline.AuditCorrectionFinalReceipt correctionFinalReceipt, List<Fact> facts) { }
    public record Timeline(String logicalTransactionId, String correctionStatus, List<Event> events,
                            InvestmentTransactionAuditTimeline.AuditCurrentPosition currentPosition) { }

    public Page list(LogicalTransactionListQuery q) {
        if (q.size() == null || q.size() < 1 || q.size() > 20) throw invalid();
        if (q.cursor() != null) validateCursor(q.cursor());
        return read(() -> identity.query(user -> {
            var page = lists.list(user, q);
            return new Page(page.records().stream().map(p -> new Item(id(p.logicalTransactionId()), id(p.positionId()),
                    new Account(id(p.account().id()), p.account().displayName()),
                    new Instrument(id(p.instrument().id()), p.instrument().symbol(), p.instrument().name(), p.instrument().market(), p.instrument().assetClass(), p.instrument().quoteCurrency()),
                    p.transactionType(), p.effectiveTradeTime(), p.quantity(), p.unitPrice(), p.grossAmount(), p.feeAmount(), p.taxAmount(),
                    p.netAmount(), p.releasedCostAmount(), p.realizedProfitLoss(), p.correctionStatus(), p.effective(), p.correctionCreatedAt())).toList(),
                    page.nextCursor(), page.hasMore(), page.size());
        }));
    }
    public Detail detail(long id) {
        if (id < 1) throw invalid();
        return read(() -> identity.query(user -> {
            var p = details.get(user, id); var c = p.correction();
            return new Detail(id(p.logicalTransactionId()), p.transactionType(), p.correctionStatus(), p.effective(), p.effectiveTradeTime(), p.settlementTime(),
                    new Account(id(p.account().id()), p.account().displayName()),
                    new Instrument(id(p.instrument().id()), p.instrument().symbol(), p.instrument().name(), p.instrument().market(), p.instrument().assetClass(), p.instrument().quoteCurrency()),
                    p.originalBusinessValues(), p.effectiveBusinessValues(), p.postingReceipt(), p.correctionFinalReceipt(),
                    c == null ? null : new Correction(c.createdAt(), c.reason(), id(c.reversalTransactionId()), id(c.replacementTransactionId())), p.currentPosition());
        }));
    }
    public Timeline timeline(long id) {
        if (id < 1) throw invalid();
        return read(() -> identity.query(user -> {
            var p = details.auditTimeline(user, id);
            return new Timeline(id(p.logicalTransactionId()), p.correctionStatus(), p.events().stream().map(e ->
                    new Event(e.eventKind(), e.createdAt(), id(e.physicalFactId()), id(e.originalFactId()), e.factType(), e.reason(), e.receiptApplicability(),
                            e.businessValues(), e.postingReceipt(), e.correctionFinalReceipt(), e.facts().stream().map(f ->
                            new Fact(f.role(), id(f.physicalFactId()), f.factType(), f.receiptApplicability(), f.businessValues())).toList())).toList(), p.currentPosition());
        }));
    }
    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static <T> T read(Supplier<T> operation) {
        try { return operation.get(); }
        catch (BusinessException e) { throw new WorkspaceException(e.getCode() == 404 ? ErrorCode.FINANCE_TRANSACTION_NOT_FOUND
                : e.getCode() == 400 ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR, "FINANCE"); }
    }
    // Reject ambiguous/coercible JSON before the unchanged source codec validates version, fingerprint and seek keys.
    private static void validateCursor(String raw) {
        try {
            if (!raw.matches("[A-Za-z0-9_-]{1,2048}")) throw invalid();
            var json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var n = json.readTree(Base64.getUrlDecoder().decode(raw));
            var keys = Set.of("version", "cursorKind", "filterFingerprint", "effectiveTradeTime", "logicalTransactionId");
            if (!n.isObject() || n.size() != keys.size()) throw invalid();
            var names = n.fieldNames(); while (names.hasNext()) if (!keys.contains(names.next())) throw invalid();
            for (String k : List.of("version", "logicalTransactionId"))
                if (!n.get(k).isIntegralNumber() || !n.get(k).canConvertToLong() || n.get(k).longValue() < 1) throw invalid();
            if (n.get("version").longValue() != 1 || !n.path("cursorKind").asText().equals("LOGICAL_TRANSACTION_LIST")
                    || !n.path("filterFingerprint").asText().matches("[0-9a-f]{64}") || !n.path("effectiveTradeTime").isTextual()) throw invalid();
            Instant.parse(n.get("effectiveTradeTime").textValue());
        } catch (Exception e) { throw invalid(); }
    }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
}
