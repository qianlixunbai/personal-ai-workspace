package io.github.qianlixunbai.workspace.finance;

import com.financeos.common.BusinessException;
import com.financeos.module.investment.read.dto.InvestmentPositionDetail;
import com.financeos.module.investment.read.dto.InvestmentPositionListItem;
import com.financeos.module.investment.read.model.PositionListQuery;
import com.financeos.module.investment.read.service.InvestmentPositionDetailQueryService;
import com.financeos.module.investment.read.service.InvestmentPositionReadQueryService;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;

import java.util.List;
import java.util.Base64;
import java.util.Set;
import java.util.function.Supplier;

/** Source queries execute inside the identity-checked, dedicated Finance read snapshot. */
public final class FinanceInvestmentQueries {
    private static final com.fasterxml.jackson.databind.ObjectMapper CURSOR_JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final FinanceAccountQueries identity;
    private final InvestmentPositionReadQueryService positions;
    private final InvestmentPositionDetailQueryService details;
    private final com.financeos.module.investment.read.service.InvestmentPortfolioQueryService portfolio;

    FinanceInvestmentQueries(FinanceAccountQueries identity, InvestmentPositionReadQueryService positions,
                             InvestmentPositionDetailQueryService details,
                             com.financeos.module.investment.read.service.InvestmentPortfolioQueryService portfolio) {
        this.identity = identity; this.positions = positions; this.details = details; this.portfolio = portfolio;
    }

    public com.financeos.module.investment.read.dto.InvestmentPortfolioResponse portfolio() {
        return identity.query(user -> source(() -> portfolio.get(user)));
    }

    public record Account(String id, String displayName, String type, String status) { }
    public record Instrument(String id, String symbol, String name, String market, String assetClass,
                             String quoteCurrency, String status) { }
    public record Position(String positionId, String positionMode, Account account, Instrument instrument,
            String quantity, String averageCost, String totalCost, String cumulativeRealizedProfitLoss, String status,
            InvestmentPositionListItem.PositionReferenceValuation referenceValuation) { }
    public record PositionPage(List<Position> records, String nextCursor, boolean hasMore, int size) { }
    public record Detail(String positionId, String positionMode, Account account, Instrument instrument,
            String quantity, String averageCost, String totalCost, String cumulativeRealizedProfitLoss, String status,
            InvestmentPositionDetail.ManualReference manualReference,
            InvestmentPositionDetail.CachedReferenceValuation referenceValuation) { }

    public PositionPage list(String status, Long accountId, Long instrumentId, String cursor, int size) {
        // Keep pages inside the existing 64 KiB typed bridge limit, without changing source seek semantics.
        if (!Set.of("OPEN", "CLOSED", "ALL").contains(status) || size < 1 || size > 20
                || accountId != null && accountId < 1 || instrumentId != null && instrumentId < 1
                || cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,2048}")) throw invalid();
        if (cursor != null) validateCursor(cursor);
        return identity.query(user -> source(() -> {
            var page = positions.list(user, new PositionListQuery(status, accountId, instrumentId, cursor, size));
            return new PositionPage(page.records().stream().map(p -> new Position(p.positionId().toString(), p.positionMode(),
                    new Account(p.account().id().toString(), p.account().displayName(), p.account().type(), p.account().status()),
                    new Instrument(p.instrument().id().toString(), p.instrument().symbol(), p.instrument().name(), p.instrument().market(),
                            p.instrument().assetClass(), p.instrument().quoteCurrency(), p.instrument().status()),
                    p.quantity(), p.averageCost(), p.totalCost(), p.cumulativeRealizedProfitLoss(), p.status(), p.referenceValuation())).toList(),
                    page.nextCursor(), page.hasMore(), page.size());
        }));
    }

    public Detail position(long id) {
        if (id < 1) throw invalid();
        return identity.query(user -> source(() -> {
            var p = details.get(user, id);
            return new Detail(p.positionId().toString(), p.positionMode(),
                    new Account(p.account().id().toString(), p.account().displayName(), p.account().type(), p.account().status()),
                    new Instrument(p.instrument().id().toString(), p.instrument().symbol(), p.instrument().name(), p.instrument().market(),
                            p.instrument().assetClass(), p.instrument().quoteCurrency(), p.instrument().status()),
                    p.quantity(), p.averageCost(), p.totalCost(), p.cumulativeRealizedProfitLoss(), p.status(),
                    p.manualReference(), p.referenceValuation());
        }));
    }

    private static <T> T source(Supplier<T> read) {
        try { return read.get(); }
        catch (BusinessException error) {
            throw new WorkspaceException(error.getCode() == 404 ? ErrorCode.FINANCE_POSITION_NOT_FOUND
                    : error.getCode() == 400 ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR, "FINANCE");
        }
    }
    // Source decoding still owns kind/version/filter binding and seek keys. Reject ambiguous JSON before it
    // can coerce fractional or overflowing Numbers into longs. Do not reinterpret or manufacture a cursor.
    private static void validateCursor(String raw) {
        try {
            var node = CURSOR_JSON.readTree(Base64.getUrlDecoder().decode(raw));
            var fields = Set.of("version", "cursorKind", "filterFingerprint", "instrumentId", "accountId", "positionId");
            if (!node.isObject() || node.size() != fields.size()) throw invalid();
            var names = node.fieldNames();
            while (names.hasNext()) if (!fields.contains(names.next())) throw invalid();
            for (String name : List.of("version", "instrumentId", "accountId", "positionId")) {
                var value = node.get(name);
                if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw invalid();
            }
            if (node.get("version").longValue() != 1 || !node.path("cursorKind").asText().equals("POSITION_LIST")
                    || !node.path("filterFingerprint").asText().matches("[0-9a-f]{64}")) throw invalid();
        } catch (Exception rejected) { throw invalid(); }
    }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
}
