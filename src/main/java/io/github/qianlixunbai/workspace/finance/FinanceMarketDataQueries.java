package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.asset.marketdata.dto.*;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.service.*;
import io.github.qianlixunbai.workspace.common.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class FinanceMarketDataQueries {
    private final FinanceAccountQueries identity;
    private final FinanceInvestmentQueries positions;
    final FinanceMarketDataStore store;
    private final MassiveClient provider;
    FinanceMarketDataQueries(FinanceAccountQueries identity, FinanceInvestmentQueries positions, FinanceMarketDataStore store, MassiveClient provider) {
        this.identity = identity; this.positions = positions; this.store = store; this.provider = provider;
    }
    record Rights(boolean display, boolean displayMemory, boolean persistence, boolean offline, boolean valuation) { }
    record Status(String provider, boolean enabled, boolean keyConfigured, String result,
                  FinanceMarketDataStore.Sample quote, FinanceMarketDataStore.Sample fx, Rights rights,
                  String licenseStatus, String providerValidation, String dataContext, MassiveClient.Diagnostic diagnostic) { }
    synchronized Status status() { identity.query(user -> true); return status("IDLE", null, false); }
    private Status status(String result, FinanceMarketDataStore.Sample sample, boolean fx) {
        return status(result, sample, fx, null);
    }
    private Status status(String result, FinanceMarketDataStore.Sample sample, boolean fx, MassiveClient.Diagnostic diagnostic) {
        try {
            var s = store.read();
            var rights = new Rights(FinanceMarketDataStore.permits(s.provider(), FinanceMarketDataStore.Use.DISPLAY),
                    FinanceMarketDataStore.permits(s.provider(), FinanceMarketDataStore.Use.DISPLAY_MEMORY),
                    FinanceMarketDataStore.permits(s.provider(), FinanceMarketDataStore.Use.PERSISTENCE),
                    FinanceMarketDataStore.permits(s.provider(), FinanceMarketDataStore.Use.OFFLINE),
                    FinanceMarketDataStore.permits(s.provider(), FinanceMarketDataStore.Use.VALUATION));
            return new Status("MASSIVE", rights.display && !s.apiKey().isEmpty(), !s.apiKey().isEmpty(), result,
                    fx ? null : sample, fx ? sample : null, rights, "PERSONAL_DISPLAY_ONLY",
                    sample != null ? "RESPONSE_VALIDATED" : diagnostic != null ? "NOT_VALIDATED" : "NOT_RUN", identity.dataContext(), diagnostic);
        } catch (IOException invalid) { throw unavailable(); }
    }
    synchronized Status configure(String key) {
        identity.query(user -> true);
        try { store.configure(key); return status("SAVED", null, false); }
        catch (IllegalArgumentException invalid) { throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
        catch (IOException invalid) { throw unavailable(); }
    }
    synchronized Status refresh(String positionId) { return refresh(positionId, ""); }
    synchronized Status refresh(String positionId, String referenceSymbol) {
        // Resolve ownership/market/asset type from the bound server snapshot before any provider access.
        String symbol = "USD/CNY", type = "FX";
        if (!referenceSymbol.isEmpty()) {
            if (!positionId.isEmpty() || !Set.of("AAPL", "SPY").contains(referenceSymbol)) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE");
            identity.query(user -> true);
            symbol = referenceSymbol; type = referenceSymbol.equals("SPY") ? "ETF" : "STOCK";
        } else if (positionId.isEmpty()) identity.query(user -> true);
        else {
            long id;
            try { if (!positionId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(); id = Long.parseLong(positionId); }
            catch (IllegalArgumentException invalid) { throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
            var p = positions.position(id);
            if (!"US".equals(p.instrument().market()) || !"USD".equals(p.instrument().quoteCurrency())
                    || !Set.of("STOCK", "ETF").contains(p.instrument().assetClass())
                    || !p.instrument().symbol().matches("[A-Za-z][A-Za-z0-9.-]{0,14}")) return status("UNSUPPORTED", null, false);
            symbol = p.instrument().symbol().toUpperCase(Locale.ROOT); type = p.instrument().assetClass();
        }
        try {
            var s = store.read();
            if (s.apiKey().isEmpty()) return status("KEY_REQUIRED", null, false);
            var sample = provider.fetch(symbol, type, s.apiKey(), store::reserve);
            identity.query(user -> true); // Do not deliver a response if binding/data identity changed in flight.
            return status("UPDATED", sample, symbol.equals("USD/CNY"));
        } catch (MassiveClient.Failure failure) {
            try { if (failure.code.equals("RATE_LIMITED")) store.throttle(); }
            catch (IOException invalid) { throw unavailable(); }
            return status(failure.code, null, false, failure.diagnostic);
        } catch (IOException failure) { throw unavailable(); }
    }
    static WorkspaceException unavailable() { return new WorkspaceException(ErrorCode.FINANCE_UNAVAILABLE, "FINANCE"); }

    static MarketQuoteQueryService quotes(com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper mapper, FinanceMarketDataStore store) {
        return new MarketQuoteQueryService(mapper, new com.financeos.module.asset.marketdata.config.MarketDataProperties(), store.clock) {
            @Override public Map<String, MarketQuoteSnapshotResponse> findCachedUsQuotes(Collection<String> symbols) {
                var result = new HashMap<String, MarketQuoteSnapshotResponse>();
                if ("SYNTHETIC".equals(store.dataContext.get())) {
                    super.findCachedUsQuotes(symbols).forEach((symbol, q) -> {
                        if (!isExternalProvider(q.provider())) result.put(symbol, new MarketQuoteSnapshotResponse(q.symbol(), q.market(), q.currency(),
                                q.price(), q.quoteTime(), q.fetchedAt(), "SYNTHETIC_LEGACY", q.freshness()));
                    });
                }
                return result;
            }
            @Override public MarketQuoteSnapshotResponse findCachedUsQuote(String symbol) {
                String normalized = normalizeSymbol(symbol); if (normalized == null) return null;
                return findCachedUsQuotes(List.of(normalized)).get(normalized);
            }
            @Override public MarketQuoteFreshness freshnessOf(MarketQuote q) {
                if (q == null || q.getFetchedAt() == null) return MarketQuoteFreshness.NEVER_FETCHED;
                return q.getQuoteTime() == null || store.clock.instant().isBefore(q.getQuoteTime())
                        || !store.clock.instant().isBefore(q.getQuoteTime().plusSeconds(900)) ? MarketQuoteFreshness.STALE : super.freshnessOf(q);
            }
        };
    }
    static ExchangeRateQueryService rates(com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper mapper, FinanceMarketDataStore store) {
        return new ExchangeRateQueryService(mapper, new com.financeos.module.asset.marketdata.fx.config.FxDataProperties(), store.clock) {
            @Override public ExchangeRate find(String base, String quote) {
                var legacy = super.find(base, quote);
                if (legacy == null || !"SYNTHETIC".equals(store.dataContext.get()) || isExternalProvider(legacy.getProvider())) return null;
                legacy.setProvider("SYNTHETIC_LEGACY"); return legacy;
            }
            @Override public List<ExchangeRate> findAll(List<String> bases, String quote) {
                var result = new ArrayList<ExchangeRate>();
                if ("SYNTHETIC".equals(store.dataContext.get())) for (var rate : super.findAll(bases, quote)) {
                    if (!isExternalProvider(rate.getProvider())) { rate.setProvider("SYNTHETIC_LEGACY"); result.add(rate); }
                }
                if (bases.contains("USD") && "CNY".equals(normalize(quote))) {
                    var rate = find("USD", "CNY");
                    if (rate != null) { result.removeIf(r -> "USD".equals(r.getBaseCurrency())); result.add(rate); }
                }
                return result;
            }
            @Override public ExchangeRateFreshness freshnessOf(ExchangeRate r) {
                if (r == null || r.getFetchedAt() == null) return ExchangeRateFreshness.NEVER_FETCHED;
                return r.getRateTime() == null || store.clock.instant().isBefore(r.getRateTime())
                        || !store.clock.instant().isBefore(r.getRateTime().plusSeconds(3600)) ? ExchangeRateFreshness.STALE : super.freshnessOf(r);
            }
        };
    }
    private static boolean isExternalProvider(String provider) {
        if (provider == null) return false;
        String normalized = provider.toUpperCase(Locale.ROOT).replace("_", "").replace(" ", "");
        return normalized.contains("TWELVEDATA") || normalized.contains("MASSIVE") || normalized.contains("POLYGON");
    }
}
