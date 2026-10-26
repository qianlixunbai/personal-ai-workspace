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
    private final TwelveDataClient provider;
    FinanceMarketDataQueries(FinanceAccountQueries identity, FinanceInvestmentQueries positions, FinanceMarketDataStore store, TwelveDataClient provider) {
        this.identity = identity; this.positions = positions; this.store = store; this.provider = provider;
    }
    record Fx(String rate, String time, String fetchedAt, String freshness) { }
    record Status(String provider, boolean enabled, boolean keyConfigured, int retentionHours, String result, Fx fx,
                  String licenseStatus, String providerValidation, String dataContext) { }
    Status status() { identity.query(user -> true); return status("IDLE"); }
    private Status status(String result) {
        try {
            var s = store.read(); var fx = store.sample("USD/CNY");
            return new Status("TWELVE_DATA", s.enabled() && store.verifiedLicense, !s.apiKey().isEmpty(), s.retentionHours(), result,
                    fx == null ? null : new Fx(fx.price(), fx.time(), fx.fetchedAt(), store.fresh(fx, 60) ? "FRESH" : "STALE"),
                    store.verifiedLicense ? "TEST_FIXTURE" : "UNVERIFIED", "NOT_RUN", identity.dataContext());
        } catch (IOException invalid) { throw unavailable(); }
    }
    synchronized Status configure(boolean enabled, boolean licensed, String key, int hours) {
        identity.query(user -> true);
        try { store.configure(enabled, licensed, key, hours); return status("SAVED"); }
        catch (IllegalArgumentException invalid) { throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
        catch (IOException invalid) { throw unavailable(); }
    }
    synchronized Status refresh(String positionId) {
        // Resolve ownership/market/asset type from the bound server snapshot before any provider access.
        String symbol = "USD/CNY", type = "FX";
        if (positionId.isEmpty()) identity.query(user -> true);
        else {
            long id;
            try { if (!positionId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(); id = Long.parseLong(positionId); }
            catch (IllegalArgumentException invalid) { throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "FINANCE"); }
            var p = positions.position(id);
            if (!"US".equals(p.instrument().market()) || !"USD".equals(p.instrument().quoteCurrency())
                    || !Set.of("STOCK", "ETF").contains(p.instrument().assetClass())
                    || !p.instrument().symbol().matches("[A-Za-z][A-Za-z0-9.-]{0,14}")) return status("UNSUPPORTED");
            symbol = p.instrument().symbol().toUpperCase(Locale.ROOT); type = p.instrument().assetClass();
        }
        try {
            var s = store.read();
            if (!s.enabled()) return status("DISABLED");
            if (!store.verifiedLicense) return status("LICENSE_UNVERIFIED");
            String result = fetch(symbol, type);
            // FX shares the same quota; preserve successful quote when FX fails.
            if (!positionId.isEmpty() && Set.of("UPDATED", "CACHED").contains(result)) {
                String fx = fetch("USD/CNY", "FX");
                if (!Set.of("UPDATED", "CACHED").contains(fx)) result = "PARTIAL_" + fx;
            }
            identity.query(user -> true); // Do not deliver a response if binding/data identity changed in flight.
            return status(result);
        } catch (IOException failure) { throw unavailable(); }
    }
    private String fetch(String symbol, String type) throws IOException {
        if (store.fresh(store.sample(symbol), symbol.equals("USD/CNY") ? 60 : 15)) return "CACHED";
        String admission = store.reserve(); if (!admission.equals("READY")) return admission;
        try {
            store.put(provider.fetch(symbol, type, store.read().apiKey()));
            return "UPDATED";
        } catch (TwelveDataClient.Failure failure) {
            if (failure.code.equals("RATE_LIMITED")) store.throttle();
            return failure.code;
        }
    }
    static WorkspaceException unavailable() { return new WorkspaceException(ErrorCode.FINANCE_UNAVAILABLE, "FINANCE"); }

    static MarketQuoteQueryService quotes(com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper mapper, FinanceMarketDataStore store) {
        return new MarketQuoteQueryService(mapper, new com.financeos.module.asset.marketdata.config.MarketDataProperties(), store.clock) {
            @Override public Map<String, MarketQuoteSnapshotResponse> findCachedUsQuotes(Collection<String> symbols) {
                var result = new HashMap<String, MarketQuoteSnapshotResponse>();
                if ("SYNTHETIC".equals(store.dataContext.get())) {
                    super.findCachedUsQuotes(symbols).forEach((symbol, q) -> {
                        if (!isTwelveData(q.provider())) result.put(symbol, new MarketQuoteSnapshotResponse(q.symbol(), q.market(), q.currency(),
                                q.price(), q.quoteTime(), q.fetchedAt(), "SYNTHETIC_LEGACY", q.freshness()));
                    });
                }
                var normalized = symbols.stream().map(this::normalizeSymbol).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
                if (normalized.isEmpty()) return result;
                try { for (var sample : store.samples()) if (normalized.contains(sample.symbol())) result.put(sample.symbol(), snapshot(sample)); }
                catch (IOException invalid) { throw unavailable(); }
                return result;
            }
            private MarketQuoteSnapshotResponse snapshot(FinanceMarketDataStore.Sample s) {
                return new MarketQuoteSnapshotResponse(s.symbol(), "US", "USD", new BigDecimal(s.price()), Instant.parse(s.time()),
                        Instant.parse(s.fetchedAt()), "TWELVE_DATA", store.fresh(s, 15) ? MarketQuoteFreshness.FRESH : MarketQuoteFreshness.STALE);
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
                if ("USD".equals(normalize(base)) && "CNY".equals(normalize(quote))) {
                    try {
                        var s = store.sample("USD/CNY");
                        if (s != null) {
                            var rate = new ExchangeRate(); rate.setBaseCurrency("USD"); rate.setQuoteCurrency("CNY");
                            rate.setRate(new BigDecimal(s.price())); rate.setRateTime(Instant.parse(s.time()));
                            rate.setFetchedAt(Instant.parse(s.fetchedAt())); rate.setProvider("TWELVE_DATA"); return rate;
                        }
                    } catch (IOException invalid) { throw unavailable(); }
                }
                var legacy = super.find(base, quote);
                if (legacy == null || !"SYNTHETIC".equals(store.dataContext.get()) || isTwelveData(legacy.getProvider())) return null;
                legacy.setProvider("SYNTHETIC_LEGACY"); return legacy;
            }
            @Override public List<ExchangeRate> findAll(List<String> bases, String quote) {
                var result = new ArrayList<ExchangeRate>();
                if ("SYNTHETIC".equals(store.dataContext.get())) for (var rate : super.findAll(bases, quote)) {
                    if (!isTwelveData(rate.getProvider())) { rate.setProvider("SYNTHETIC_LEGACY"); result.add(rate); }
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
    private static boolean isTwelveData(String provider) {
        return provider != null && provider.toUpperCase(Locale.ROOT).replace("_", "").replace(" ", "").contains("TWELVEDATA");
    }
}
