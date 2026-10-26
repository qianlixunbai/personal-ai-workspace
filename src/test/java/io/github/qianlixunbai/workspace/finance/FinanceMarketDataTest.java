package io.github.qianlixunbai.workspace.finance;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
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
    private static final String EXACT_FX = "7." + "123456789".repeat(5);
    static final class Time extends Clock {
        Instant now = Instant.parse("2026-10-26T14:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
    static final class File implements FinanceMarketDataStore.FilePort {
        byte[] bytes;
        public byte[] read() { return bytes == null ? null : bytes.clone(); }
        public void write(byte[] value) { bytes = value.clone(); }
    }
    private static MassiveClient.Reply reply(String body) { return new MassiveClient.Reply(200, body.getBytes(StandardCharsets.UTF_8)); }
    private static String bars(String ticker, String price, String time) {
        return "{\"status\":\"OK\",\"ticker\":\"" + ticker + "\",\"adjusted\":false,\"results\":[{\"c\":" + price
                + ",\"t\":" + Instant.parse(time).toEpochMilli() + "}]}";
    }
    private static String metadata(String type, String venue) {
        return "{\"status\":\"OK\",\"results\":{\"ticker\":\"SPY\",\"market\":\"stocks\",\"locale\":\"us\",\"currency_name\":\"usd\",\"type\":\"" + type + "\",\"primary_exchange\":\"" + venue + "\"}}";
    }
    @Test void equivalentJsonPriceRepresentationsDoNotLosePrecision() throws Exception {
        var time = new Time();
        for (String symbol : List.of("SPY", "USD/CNY")) {
            boolean fx = symbol.equals("USD/CNY");
            String ticker = fx ? "C:USDCNY" : symbol;
            var client = new MassiveClient(time, (p, k) -> p.startsWith("/v3/") ? reply(metadata("ETF", "ARCX"))
                    : reply(bars(ticker, fx ? EXACT_FX + "000" : "100.12345678000000000000", fx ? "2026-10-23T00:00:00Z" : "2026-10-23T04:00:00Z")));
            assertEquals(fx ? EXACT_FX : "100.12345678", client.fetch(symbol, fx ? "FX" : "ETF", "synthetic_key", () -> "READY").price());
            var excessive = new MassiveClient(time, (p, k) -> reply(bars(ticker, fx ? "1e-127" : "100.123456781", fx ? "2026-10-23T00:00:00Z" : "2026-10-23T04:00:00Z")));
            var failure = assertThrows(MassiveClient.Failure.class, () -> excessive.fetch(symbol, fx ? "FX" : "ETF", "synthetic_key", () -> "READY"));
            assertEquals("INVALID_DATA", failure.code);
            assertEquals(fx ? MassiveClient.Stage.FOREX_BARS : MassiveClient.Stage.STOCK_BARS, failure.diagnostic.stage());
            assertEquals(fx ? MassiveClient.Reason.PRICE_RANGE : MassiveClient.Reason.PRICE_SCALE, failure.diagnostic.reason());
            assertEquals("INVALID_DATA", failure.getMessage()); // No upstream content or credential in errors.
        }
        var boundary = new MassiveClient(time, (p, k) -> reply(bars("C:USDCNY", "1e-126", "2026-10-23T00:00:00Z")));
        assertEquals("0." + "0".repeat(125) + "1", boundary.fetch("USD/CNY", "FX", "synthetic_key", () -> "READY").price());
        for (String invalid : List.of("-1", "0", "1e20", "NaN")) {
            var rejected = new MassiveClient(time, (p, k) -> reply(bars("C:USDCNY", invalid, "2026-10-23T00:00:00Z")));
            assertThrows(MassiveClient.Failure.class, () -> rejected.fetch("USD/CNY", "FX", "synthetic_key", () -> "READY"));
        }
        var fx = new MassiveClient(time, (p, k) -> reply(bars("C:USDCNY", "7.1", "2026-10-23T00:00:00Z").replace("\"adjusted\":false", "\"adjusted\":true")));
        assertEquals(MassiveClient.Reason.ADJUSTMENT_FLAG, assertThrows(MassiveClient.Failure.class,
                () -> fx.fetch("USD/CNY", "FX", "synthetic_key", () -> "READY")).diagnostic.reason());
    }
    @Test void personalDisplayWorksWithoutPersistenceOfflineOrValuationRightsAndPreservesOwnership() throws Exception {
        var time = new Time(); var file = new File(); var store = new FinanceMarketDataStore(time, file, () -> "SYNTHETIC");
        var identity = mock(FinanceAccountQueries.class);
        when(identity.dataContext()).thenReturn("SYNTHETIC");
        when(identity.query(any())).thenAnswer(i -> ((Function<Long, ?>) i.getArgument(0)).apply(1L));
        var positions = mock(FinanceInvestmentQueries.class);
        when(positions.position(1)).thenReturn(new FinanceInvestmentQueries.Detail("1", "TRANSACTION_DRIVEN", null,
                new FinanceInvestmentQueries.Instrument("10", "SPY", "US ETF", "US", "ETF", "USD", "ACTIVE"), "2.25", null, null, null, "OPEN", null, null));
        when(positions.position(2)).thenThrow(new WorkspaceException(ErrorCode.FINANCE_POSITION_NOT_FOUND, "FINANCE"));
        when(positions.position(3)).thenReturn(new FinanceInvestmentQueries.Detail("3", "TRANSACTION_DRIVEN", null,
                new FinanceInvestmentQueries.Instrument("30", "SPY", "Other market", "HK", "ETF", "HKD", "ACTIVE"), null, null, null, null, "OPEN", null, null));
        var requests = new ArrayList<String>(); boolean[] deniedFx = { false };
        var provider = new MassiveClient(time, (path, key) -> {
            requests.add(path); assertEquals("synthetic_key", key);
            assertFalse(path.contains("key")); assertFalse(path.contains("2.25"));
            if (path.contains("C:USDCNY")) return deniedFx[0] ? new MassiveClient.Reply(403, new byte[0])
                    : reply(bars("C:USDCNY", EXACT_FX, "2026-10-23T00:00:00Z"));
            return path.startsWith("/v3/") ? reply(metadata("ETF", "ARCX")) : reply(bars("SPY", "100.12345678", "2026-10-23T04:00:00Z"));
        });
        var queries = new FinanceMarketDataQueries(identity, positions, store, provider);
        assertFalse(queries.status().enabled()); assertEquals("KEY_REQUIRED", queries.refresh("1").result()); assertTrue(requests.isEmpty());
        queries.configure("synthetic_key"); assertTrue(queries.status().enabled());
        assertThrows(WorkspaceException.class, () -> queries.refresh("2")); assertEquals("UNSUPPORTED", queries.refresh("3").result());
        assertThrows(WorkspaceException.class, () -> queries.refresh("", "ANYTHING")); assertTrue(requests.isEmpty());
        var quote = queries.refresh("1"); assertEquals("UPDATED", quote.result()); assertEquals("100.12345678", quote.quote().price());
        assertEquals("2026-10-23", quote.quote().date()); assertEquals("America/New_York", quote.quote().timeZone());
        assertEquals("RESPONSE_VALIDATED", quote.providerValidation()); assertNull(quote.fx());
        assertEquals(List.of("/v2/aggs/ticker/SPY/range/1/day/2026-10-12/2026-10-25?adjusted=false&sort=desc&limit=14", "/v3/reference/tickers/SPY?date=2026-10-23"), requests);
        assertTrue(quote.rights().display()); assertTrue(quote.rights().displayMemory());
        assertFalse(quote.rights().persistence()); assertFalse(quote.rights().offline()); assertFalse(quote.rights().valuation());
        assertEquals(EXACT_FX, queries.refresh("").fx().price());
        assertNull(queries.status().quote()); assertNull(queries.status().fx()); // Status/reopen never replay market responses.
        assertNull(new FinanceMarketDataQueries(identity, positions, new FinanceMarketDataStore(time, file, () -> "SYNTHETIC"), provider).status().quote());
        String persisted = new String(file.bytes, StandardCharsets.UTF_8);
        assertFalse(persisted.contains("100.12345678")); assertFalse(persisted.contains(EXACT_FX)); assertFalse(persisted.contains("SPY"));
        assertFalse(queries.status().toString().contains("synthetic_key"));
        var quotes = FinanceMarketDataQueries.quotes(mock(MarketQuoteMapper.class), store);
        var rates = FinanceMarketDataQueries.rates(mock(ExchangeRateMapper.class), store);
        var asset = new Asset(); asset.setId(1L); asset.setSymbol("SPY"); asset.setMarket("US"); asset.setQuantity(new BigDecimal("2.25"));
        assertNull(new ReferenceValuationService(rates, time).calculateAll(List.of(asset), quotes.findCachedUsQuotes(List.of("SPY"))).get(1L).baseCurrencyMarketValue());
        // The original explicitly synthetic Finance cache keeps its exact valuation behavior.
        var quoteMapper = mock(MarketQuoteMapper.class); var rateMapper = mock(ExchangeRateMapper.class);
        var legacyQuote = new com.financeos.module.asset.marketdata.entity.MarketQuote();
        legacyQuote.setSymbol("SPY"); legacyQuote.setMarket("US"); legacyQuote.setCurrency("USD"); legacyQuote.setPrice(new BigDecimal("99.12345678"));
        legacyQuote.setQuoteTime(time.now.minusSeconds(3600)); legacyQuote.setFetchedAt(time.now); legacyQuote.setProvider("SYNTHETIC");
        when(quoteMapper.findByMarketAndSymbols(eq("US"), any())).thenReturn(List.of(legacyQuote));
        var legacyRate = new com.financeos.module.asset.marketdata.fx.entity.ExchangeRate();
        legacyRate.setBaseCurrency("USD"); legacyRate.setQuoteCurrency("CNY"); legacyRate.setRate(new BigDecimal("7.01"));
        legacyRate.setRateTime(time.now); legacyRate.setFetchedAt(time.now); legacyRate.setProvider("SYNTHETIC");
        when(rateMapper.findByBaseCurrenciesAndQuoteCurrency(any(), eq("CNY"))).thenReturn(List.of(legacyRate));
        when(rateMapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(legacyRate);
        var legacyQuotes = FinanceMarketDataQueries.quotes(quoteMapper, store).findCachedUsQuotes(List.of("SPY"));
        assertEquals("SYNTHETIC_LEGACY", legacyQuotes.get("SPY").provider());
        assertEquals(new BigDecimal("2.25").multiply(new BigDecimal("99.12345678")).multiply(new BigDecimal("7.01")).setScale(2, java.math.RoundingMode.HALF_UP),
                new ReferenceValuationService(FinanceMarketDataQueries.rates(rateMapper, store), time).calculateAll(List.of(asset), legacyQuotes).get(1L).baseCurrencyMarketValue());
        legacyQuote.setProvider("MASSIVE");
        assertTrue(FinanceMarketDataQueries.quotes(quoteMapper, store).findCachedUsQuotes(List.of("SPY")).isEmpty());
        deniedFx[0] = true; var denied = queries.refresh("");
        assertEquals("ACCESS_DENIED", denied.result()); assertEquals("NOT_VALIDATED", denied.providerValidation());
        assertEquals(new MassiveClient.Diagnostic(MassiveClient.Stage.FOREX_BARS, MassiveClient.Reason.HTTP_STATUS), denied.diagnostic());
        time.now = time.now.plusSeconds(61);
        assertEquals("UPDATED", queries.refresh("", "SPY").result()); // FX denial cannot lock the stock display grant.
        assertTrue(FinanceMarketDataStore.permits("MASSIVE", FinanceMarketDataStore.Use.DISPLAY));
        assertFalse(FinanceMarketDataStore.permits("TWELVE_DATA", FinanceMarketDataStore.Use.DISPLAY));
    }
    @Test void providerRejectsUnmatchedIncompleteMalformedDataAndQuotaSurvivesRestartWithoutRawErrors() throws Exception {
        var time = new Time();
        var bodies = List.of(bars("OTHER", "100", "2026-10-23T04:00:00Z"), bars("SPY", "100", "2026-10-26T04:00:00Z"),
                bars("SPY", "100", "2026-09-01T04:00:00Z"), bars("SPY", "1e999999", "2026-10-23T04:00:00Z"),
                "{\"status\":\"ERROR\",\"message\":\"synthetic_key\"}");
        for (String body : bodies) {
            var client = new MassiveClient(time, (p, k) -> p.startsWith("/v3/") ? reply(metadata("ETF", "ARCX")) : reply(body));
            var failure = assertThrows(MassiveClient.Failure.class, () -> client.fetch("SPY", "ETF", "synthetic_key", () -> "READY"));
            assertFalse(failure.toString().contains("synthetic_key"));
        }
        var wrong = new MassiveClient(time, (p, k) -> p.startsWith("/v3/") ? reply(metadata("CS", "XHKG")) : reply(bars("SPY", "100", "2026-10-23T04:00:00Z")));
        var wrongType = assertThrows(MassiveClient.Failure.class, () -> wrong.fetch("SPY", "ETF", "synthetic_key", () -> "READY"));
        assertEquals("IDENTITY_MISMATCH", wrongType.code); assertEquals(MassiveClient.Stage.STOCK_REFERENCE, wrongType.diagnostic.stage());
        assertEquals(MassiveClient.Reason.ASSET_TYPE, wrongType.diagnostic.reason());
        var incomplete = new MassiveClient(time, (p, k) -> reply(bars("SPY", "100", "2026-10-26T04:00:00Z")));
        assertEquals(MassiveClient.Reason.TIMESTAMP_RANGE, assertThrows(MassiveClient.Failure.class,
                () -> incomplete.fetch("SPY", "ETF", "synthetic_key", () -> "READY")).diagnostic.reason());
        var file = new File(); var store = new FinanceMarketDataStore(time, file, () -> "SYNTHETIC"); store.configure("synthetic_key");
        for (int i = 0; i < 5; i++) assertEquals("READY", store.reserve());
        var restarted = new FinanceMarketDataStore(time, file, () -> "SYNTHETIC"); restarted.configure("replacement");
        assertEquals("RATE_LIMITED", restarted.reserve());
        time.now = time.now.plusSeconds(61); assertEquals("READY", restarted.reserve());
        restarted.throttle(); assertEquals("RATE_LIMITED", store.reserve());
        // DST and UTC rollover: exclude the current ET date even for FX.
        time.now = Instant.parse("2026-11-03T01:00:00Z"); var paths = new ArrayList<String>();
        var fx = new MassiveClient(time, (p, k) -> { paths.add(p); return reply(bars("C:USDCNY", "7.1", "2026-10-30T00:00:00Z")); });
        assertEquals("2026-10-30", fx.fetch("USD/CNY", "FX", "synthetic_key", () -> "READY").date());
        assertTrue(paths.getFirst().contains("/2026-11-01?"));
    }
}
