package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import com.financeos.module.investment.read.mapper.*;
import com.financeos.module.investment.read.service.InvestmentPortfolioQueryService;
import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceMarketDataTest {
    static final class Time extends Clock {
        Instant now = Instant.parse("2026-10-26T14:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    static final class File implements FinanceMarketDataStore.FilePort {
        byte[] bytes;
        public byte[] read() { return bytes == null ? null : bytes.clone(); }
        public void write(byte[] value) { bytes = value.clone(); }
    }
    private static TwelveDataClient.Reply reply(String body) { return new TwelveDataClient.Reply(200, body.getBytes(StandardCharsets.UTF_8)); }
    private static String quote(Instant time, String symbol, String type, String mic) {
        return "{\"symbol\":\"" + symbol + "\",\"currency\":\"USD\",\"type\":\"" + type + "\",\"mic_code\":\"" + mic
                + "\",\"close\":\"100.12345678\",\"timestamp\":" + time.getEpochSecond() + "}";
    }
    @Test void manualRefreshReusesSourceValuationAndKeepsOfflineStaleCacheQuotaAndOwnership() throws Exception {
        var time = new Time(); var file = new File(); var store = new FinanceMarketDataStore(time, file, true);
        var identity = mock(FinanceAccountQueries.class);
        when(identity.dataContext()).thenReturn("SYNTHETIC");
        when(identity.query(any())).thenAnswer(i -> ((Function<Long, ?>) i.getArgument(0)).apply(1L));
        var positions = mock(FinanceInvestmentQueries.class);
        when(positions.position(1)).thenReturn(new FinanceInvestmentQueries.Detail("1", "TRANSACTION_DRIVEN", null,
                new FinanceInvestmentQueries.Instrument("10", "SPY", "US ETF", "US", "ETF", "USD", "ACTIVE"), "2.25", null, null, null, "OPEN", null, null));
        when(positions.position(2)).thenThrow(new WorkspaceException(ErrorCode.FINANCE_POSITION_NOT_FOUND, "FINANCE"));
        when(positions.position(3)).thenReturn(new FinanceInvestmentQueries.Detail("3", "TRANSACTION_DRIVEN", null,
                new FinanceInvestmentQueries.Instrument("30", "SPY", "Other market", "HK", "ETF", "HKD", "ACTIVE"), null, null, null, null, "OPEN", null, null));
        var requests = new ArrayList<String>(); boolean[] offline = { false };
        var provider = new TwelveDataClient(time, (path, key) -> {
            requests.add(path); assertEquals("synthetic_key", key);
            assertFalse(path.contains("key")); assertFalse(path.contains("2.25"));
            if (offline[0]) throw new java.io.IOException("synthetic_key must never surface");
            return path.startsWith("/quote") ? reply(quote(time.now, "SPY", "ETF", "ARCX"))
                    : reply("{\"symbol\":\"USD/CNY\",\"rate\":7.123456789012,\"timestamp\":" + time.now.getEpochSecond() + "}");
        });
        var queries = new FinanceMarketDataQueries(identity, positions, store, provider);
        assertFalse(queries.status().enabled()); assertEquals("DISABLED", queries.refresh("1").result()); assertTrue(requests.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.configure(true, false, "synthetic_key", 24));
        queries.configure(true, true, "synthetic_key", 24);
        assertThrows(WorkspaceException.class, () -> queries.refresh("2")); assertEquals("UNSUPPORTED", queries.refresh("3").result()); assertTrue(requests.isEmpty());
        var unreviewed = new FinanceMarketDataStore(time, file);
        var blocked = new FinanceMarketDataQueries(identity, positions, unreviewed, provider);
        assertEquals("LICENSE_UNVERIFIED", blocked.refresh("1").result());
        assertFalse(blocked.status().enabled()); assertTrue(requests.isEmpty());
        assertEquals("UNVERIFIED", blocked.status().licenseStatus());
        assertEquals("UPDATED", queries.refresh("1").result()); assertEquals(2, requests.size());
        assertTrue(requests.getFirst().contains("country=United%20States&type=ETF"));
        assertEquals("7.123456789012", queries.status().fx().rate());
        assertNull(blocked.status().fx()); assertTrue(unreviewed.samples().isEmpty());
        assertNull(FinanceMarketDataQueries.quotes(mock(MarketQuoteMapper.class), unreviewed).findCachedUsQuote("SPY"));
        assertNull(FinanceMarketDataQueries.rates(mock(ExchangeRateMapper.class), unreviewed).find("USD", "CNY"));
        assertEquals("CACHED", queries.refresh("1").result()); assertEquals(2, requests.size());
        var quotes = FinanceMarketDataQueries.quotes(mock(MarketQuoteMapper.class), store);
        var rates = FinanceMarketDataQueries.rates(mock(ExchangeRateMapper.class), store);
        var valuations = new ReferenceValuationService(rates, time);
        var asset = new Asset(); asset.setId(1L); asset.setSymbol("SPY"); asset.setMarket("US"); asset.setQuantity(new BigDecimal("2.25"));
        var expected = new BigDecimal("2.25").multiply(new BigDecimal("100.12345678")).multiply(new BigDecimal("7.123456789012")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(expected, valuations.calculateAll(List.of(asset), quotes.findCachedUsQuotes(List.of("SPY"))).get(1L).baseCurrencyMarketValue());
        var readMapper = mock(InvestmentReadMapper.class);
        when(readMapper.selectPortfolioStatistics(1L)).thenReturn(new InvestmentPortfolioStatisticsRow(2, 2, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        var missing = new Asset(); missing.setId(4L); missing.setSymbol("SPY"); missing.setMarket("HK"); missing.setQuantity(BigDecimal.ONE);
        assertNull(valuations.calculateAll(List.of(asset, missing), quotes.findCachedUsQuotes(List.of("SPY"))).get(4L).baseCurrencyMarketValue());
        when(readMapper.selectOpenTransactionDrivenPositionAssets(1L)).thenReturn(List.of(asset, missing));
        var portfolio = new InvestmentPortfolioQueryService(readMapper, quotes, valuations);
        assertEquals(expected.toPlainString(), portfolio.get(1L).referenceValuation().value());
        assertEquals("PARTIAL", portfolio.get(1L).referenceValuation().freshness()); assertEquals(1, portfolio.get(1L).referenceValuation().valuedPositionCount());
        time.now = time.now.plusSeconds(3700); offline[0] = true;
        assertEquals("PROVIDER_FAILED", queries.refresh("1").result());
        assertEquals(expected, valuations.calculateAll(List.of(asset), quotes.findCachedUsQuotes(List.of("SPY"))).get(1L).baseCurrencyMarketValue());
        assertTrue(portfolio.get(1L).referenceValuation().warnings().stream().allMatch(w -> w.positionId() != null));
        assertEquals("STALE", quotes.findCachedUsQuote("SPY").freshness().name()); assertEquals("STALE", queries.status().fx().freshness());
        var restarted = new FinanceMarketDataStore(time, file, true);
        assertNotNull(restarted.sample("SPY")); assertEquals(3, restarted.read().requests().size());
        assertFalse(queries.status().toString().contains("synthetic_key"));
        queries.configure(false, false, "", 24); assertEquals("DISABLED", queries.refresh("1").result()); assertEquals(3, requests.size());
        time.now = time.now.plus(Duration.ofHours(24)); assertNull(restarted.sample("SPY"));
    }
    @Test void providerRejectsWrongMarketFutureAndErrorsWithoutReplacingCacheOrLeakingMessages() throws Exception {
        var time = new Time();
        for (String body : List.of(quote(time.now, "SPY", "ETF", "XHKG"), quote(time.now, "OTHER", "ETF", "ARCX"),
                quote(time.now.plusSeconds(120), "SPY", "ETF", "ARCX"), "{\"status\":\"error\",\"code\":429,\"message\":\"synthetic_key\"}")) {
            var failure = assertThrows(TwelveDataClient.Failure.class, () -> new TwelveDataClient(time, (p, k) -> reply(body)).fetch("SPY", "ETF", "synthetic_key"));
            assertFalse(failure.toString().contains("synthetic_key"));
        }
        var file = new File(); var store = new FinanceMarketDataStore(time, file, true); store.configure(true, true, "synthetic", 24);
        for (int i = 0; i < 8; i++) assertEquals("READY", store.reserve());
        store = new FinanceMarketDataStore(time, file, true); assertEquals("RATE_LIMITED", store.reserve());
        store.configure(true, true, "replacement", 24); assertEquals("RATE_LIMITED", store.reserve());
        time.now = time.now.plusSeconds(61); assertEquals("READY", store.reserve());
        store.throttle(); assertEquals("RATE_LIMITED", new FinanceMarketDataStore(time, file, true).reserve());
        time.now = time.now.plusSeconds(61);
        store.put(new FinanceMarketDataStore.Sample("SPY", "10", time.now.toString(), time.now.toString(), "ARCX"));
        store.put(new FinanceMarketDataStore.Sample("SPY", "9", time.now.minusSeconds(1).toString(), time.now.toString(), "ARCX"));
        assertEquals("10", store.sample("SPY").price());
        assertFalse(store.fresh(new FinanceMarketDataStore.Sample("SPY", "10", time.now.minusSeconds(3600).toString(), time.now.toString(), "ARCX"), 15));
    }
}
