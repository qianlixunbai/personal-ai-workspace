package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import io.github.qianlixunbai.workspace.common.ErrorCode;
import io.github.qianlixunbai.workspace.common.WorkspaceException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** AssetService's read slice; the frozen reference valuation algorithm is reused unchanged.
 * No AssetService writes or market/FX refresh services are registered. */
public final class FinanceAssetQueries {
    private final FinanceAccountQueries identity;
    private final JdbcTemplate jdbc;
    private final MarketQuoteQueryService quotes;
    private final ReferenceValuationService valuations;

    FinanceAssetQueries(FinanceAccountQueries identity, DataSource source,
                        MarketQuoteQueryService quotes, ReferenceValuationService valuations) {
        this.identity = identity; this.jdbc = new JdbcTemplate(source);
        this.quotes = quotes; this.valuations = valuations;
    }

    public record ReferenceView(String quoteCurrency, String quotePrice, String quoteTime, String quoteFetchedAt,
            String quoteProvider, String quoteFreshness, boolean fxRequired, String fxBaseCurrency, String fxQuoteCurrency,
            String fxRate, String fxRateTime, String fxFetchedAt, String fxProvider, String fxFreshness,
            String nativeMarketValue, String baseCurrency, String baseCurrencyMarketValue, String valuationFreshness,
            String calculatedAt, String formulaVersion, List<String> warnings) { }
    public record AssetView(String id, String name, String symbol, String type, String market, String currency,
            String quantity, String avgCost, String currentPrice, String manualMarketValue, String positionMode,
            String createdAt, String updatedAt, ReferenceView referenceValuation) {
        @Override public String toString() { return "FinanceAsset[private]"; }
    }
    public record AssetPage(List<AssetView> items, int page, int size, String total, String type, String sort, FinanceAnalyticsQueries.Distribution typeDistribution) { }
    private static final Set<String> TYPES = Set.of("ALL", "STOCK", "ETF", "FUND", "BOND", "GOLD", "CRYPTO", "CASH", "OTHER");
    private static final String COLUMNS = "id,name,symbol,type,market,currency,quantity,avg_cost,current_price,position_mode,created_at,updated_at";

    public AssetPage list(int page, int size, String type, String sort) {
        if (page < 0 || page > 100000 || size < 1 || size > 20 || !TYPES.contains(type)
                || !Set.of("CREATED_DESC", "ID_ASC", "NAME_ASC").contains(sort)) throw invalid();
        return identity.query(userId -> {
            String where = " FROM public.assets WHERE user_id=? AND (?='ALL' OR type=?)";
            long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, userId, type, type);
            String order = switch (sort) {
                case "ID_ASC" -> "id ASC";
                case "NAME_ASC" -> "name ASC,id ASC";
                default -> "created_at DESC,id DESC";
            };
            var assets = jdbc.query("SELECT " + COLUMNS + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                    FinanceAssetQueries::asset, userId, type, type, size, (long) page * size);
            return new AssetPage(views(assets), page, size, Long.toString(total), type, sort, FinanceAnalyticsQueries.counts(jdbc, userId, true));
        });
    }

    public AssetView asset(long id) {
        if (id < 1) throw invalid();
        return identity.query(userId -> {
            var assets = jdbc.query("SELECT " + COLUMNS + " FROM public.assets WHERE user_id=? AND id=?",
                    FinanceAssetQueries::asset, userId, id);
            if (assets.isEmpty()) throw new WorkspaceException(ErrorCode.FINANCE_ASSET_NOT_FOUND, "FINANCE");
            return views(assets).getFirst();
        });
    }

    private List<AssetView> views(List<Asset> assets) {
        // Same US STOCK/ETF boundary as AssetService. Batch queries never call a provider.
        var supported = assets.stream().filter(a -> ("STOCK".equalsIgnoreCase(a.getType()) || "ETF".equalsIgnoreCase(a.getType()))
                && a.getMarket() != null && "US".equals(a.getMarket().trim().toUpperCase(Locale.ROOT))).toList();
        var cached = quotes.findCachedUsQuotes(supported.stream().map(Asset::getSymbol).toList());
        // The source permits nullable symbols. Its batch lookup needs a map that accepts a missing/null key.
        var references = valuations.calculateAll(supported, new java.util.HashMap<>(cached));
        return assets.stream().map(a -> new AssetView(a.getId().toString(), a.getName(), a.getSymbol(), a.getType(),
                a.getMarket(), a.getCurrency(), decimal(a.getQuantity()), decimal(a.getAvgCost()), decimal(a.getCurrentPrice()),
                // Preserve unknown prices; AssetService's null-to-zero response fallback is intentionally not used.
                a.getCurrentPrice() == null ? null : decimal(a.getCurrentPrice().multiply(a.getQuantity())),
                a.getPositionMode(), a.getCreatedAt().toString(), a.getUpdatedAt().toString(), reference(references.get(a.getId())))).toList();
    }

    private static ReferenceView reference(ReferenceValuationResponse r) {
        return r == null ? null : new ReferenceView(r.quoteCurrency(), decimal(r.quotePrice()), time(r.quoteTime()), time(r.quoteFetchedAt()),
                r.quoteProvider(), r.quoteFreshness().name(), r.fxRequired(), r.fxBaseCurrency(), r.fxQuoteCurrency(), decimal(r.fxRate()),
                time(r.fxRateTime()), time(r.fxFetchedAt()), r.fxProvider(), r.fxFreshness().name(), decimal(r.nativeMarketValue()),
                r.baseCurrency(), decimal(r.baseCurrencyMarketValue()), r.valuationFreshness().name(), time(r.calculatedAt()),
                r.formulaVersion(), r.warnings().stream().map(w -> w.code()).toList());
    }

    private static Asset asset(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        Asset a = new Asset(); a.setId(row.getLong("id")); a.setName(row.getString("name")); a.setSymbol(row.getString("symbol"));
        a.setType(row.getString("type")); a.setMarket(row.getString("market")); a.setCurrency(row.getString("currency"));
        a.setQuantity(row.getBigDecimal("quantity")); a.setAvgCost(row.getBigDecimal("avg_cost")); a.setCurrentPrice(row.getBigDecimal("current_price"));
        a.setPositionMode(row.getString("position_mode")); a.setCreatedAt(row.getTimestamp("created_at").toLocalDateTime());
        a.setUpdatedAt(row.getTimestamp("updated_at").toLocalDateTime()); return a;
    }
    private static String decimal(BigDecimal amount) { return amount == null ? null : amount.toPlainString(); }
    private static String time(java.time.Instant value) { return value == null ? null : value.toString(); }
    private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
}
