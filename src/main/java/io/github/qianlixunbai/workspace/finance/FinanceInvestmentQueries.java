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

    public record Portfolio(String currency, int positionCount, int openPositionCount, int closedPositionCount,
            String openTotalCost, String cumulativeRealizedProfitLoss,
            com.financeos.module.investment.read.dto.InvestmentPortfolioResponse.PortfolioReferenceValuation referenceValuation,
            FinanceAnalyticsQueries.Distribution statusDistribution, FinanceAnalyticsQueries.Distribution coverageDistribution) { }
    public Portfolio portfolio() {
        return identity.query(user -> source(() -> {
            var p = portfolio.get(user); var r = p.referenceValuation();
            return new Portfolio(p.currency(), p.positionCount(), p.openPositionCount(), p.closedPositionCount(),
                    p.openTotalCost(), p.cumulativeRealizedProfitLoss(), r,
                    FinanceAnalyticsQueries.distribution(List.of(
                        new FinanceAnalyticsQueries.Part("OPEN", "OPEN · 持有中", java.math.BigDecimal.valueOf(p.openPositionCount())),
                        new FinanceAnalyticsQueries.Part("CLOSED", "CLOSED · 已平仓", java.math.BigDecimal.valueOf(p.closedPositionCount())))),
                    FinanceAnalyticsQueries.distribution(List.of(
                        new FinanceAnalyticsQueries.Part("VALUED", "可估值（含过期）", java.math.BigDecimal.valueOf(r.valuedPositionCount())),
                        new FinanceAnalyticsQueries.Part("UNVALUED", "无法估值", java.math.BigDecimal.valueOf(r.totalOpenPositionCount() - r.valuedPositionCount())))));
        }));
    }

    public record Account(String id, String displayName, String type, String status) { }
    public record Instrument(String id, String symbol, String name, String market, String assetClass,
                             String quoteCurrency, String status) { }
    public record Position(String positionId, String positionMode, Account account, Instrument instrument,
            String quantity, String averageCost, String totalCost, String cumulativeRealizedProfitLoss, String status,
            InvestmentPositionListItem.PositionReferenceValuation referenceValuation) { }
    public record HoldingPoint(String positionId, Integer cost, Integer reference) { }
    public record HoldingPlot(List<HoldingPoint> items, int zero, String minimum, String maximum) { }
    public record PositionPage(List<Position> records, String nextCursor, boolean hasMore, int size, HoldingPlot comparison) { }
    private static HoldingPlot comparison(List<Position> records) {
        var values = records.stream().flatMap(p -> java.util.stream.Stream.of(p.totalCost(), p.referenceValuation().value()))
                .filter(java.util.Objects::nonNull).map(java.math.BigDecimal::new).toList();
        var minimum = values.stream().min(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO).min(java.math.BigDecimal.ZERO);
        var maximum = values.stream().max(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO).max(java.math.BigDecimal.ZERO);
        var range = maximum.subtract(minimum);
        java.util.function.Function<String, Integer> x = s -> s == null ? null : range.signum() == 0 ? 0
                : new java.math.BigDecimal(s).subtract(minimum).multiply(java.math.BigDecimal.valueOf(10000))
                    .divide(range, 0, java.math.RoundingMode.HALF_UP).intValueExact();
        return new HoldingPlot(records.stream().map(p -> new HoldingPoint(p.positionId(), x.apply(p.totalCost()), x.apply(p.referenceValuation().value()))).toList(),
                x.apply("0"), minimum.toPlainString(), maximum.toPlainString());
    }
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
            var records = page.records().stream().map(p -> new Position(p.positionId().toString(), p.positionMode(),
                    new Account(p.account().id().toString(), p.account().displayName(), p.account().type(), p.account().status()),
                    new Instrument(p.instrument().id().toString(), p.instrument().symbol(), p.instrument().name(), p.instrument().market(),
                            p.instrument().assetClass(), p.instrument().quoteCurrency(), p.instrument().status()),
                    p.quantity(), p.averageCost(), p.totalCost(), p.cumulativeRealizedProfitLoss(), p.status(), p.referenceValuation())).toList();
            return new PositionPage(records, page.nextCursor(), page.hasMore(), page.size(), comparison(records));
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
