package io.github.qianlixunbai.workspace.finance;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Fixed HTTPS origin, no redirects, bounded response, header credential, no raw errors/logging. */
final class MassiveClient {
    interface Transport { Reply get(String path, String key) throws Exception; }
    record Reply(int status, byte[] body) { }
    enum Stage { LOCAL, STOCK_BARS, FOREX_BARS, STOCK_REFERENCE }
    enum Reason { TARGET, ADMISSION, HTTP_STATUS, RESPONSE_SIZE, JSON_FORMAT, PROVIDER_STATUS, TICKER,
        RESULTS_SHAPE, EMPTY_RESULTS, ADJUSTMENT_FLAG, TIMESTAMP_TYPE, TIMESTAMP_RANGE,
        MARKET, CURRENCY, ASSET_TYPE, EXCHANGE, PRICE_TYPE, PRICE_SIGN, PRICE_SCALE, PRICE_RANGE, TRANSPORT, INTERRUPTED }
    // Enum-only metadata: never include payloads, URLs, keys, symbols, prices, dates or exception messages.
    record Diagnostic(Stage stage, Reason reason) { }
    static final class Failure extends IOException {
        final String code;
        final Diagnostic diagnostic;
        Failure(String code, Stage stage, Reason reason) { super(code); this.code = code; this.diagnostic = new Diagnostic(stage, reason); }
    }
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Transport transport;
    private final Clock clock;
    MassiveClient(Clock clock) { this(clock, new Https()); }
    MassiveClient(Clock clock, Transport transport) { this.clock = clock; this.transport = transport; }
    interface Admission { String reserve() throws IOException; }
    FinanceMarketDataStore.Sample fetch(String symbol, String assetClass, String key, Admission admission) throws Failure {
        boolean fx = symbol.equals("USD/CNY");
        if (!fx && (!symbol.matches("[A-Z][A-Z0-9.-]{0,14}") || !Set.of("STOCK", "ETF").contains(assetClass)))
            throw new Failure("IDENTITY_MISMATCH", Stage.LOCAL, Reason.TARGET);
        String ticker = fx ? "C:USDCNY" : symbol;
        ZoneId zone = fx ? ZoneOffset.UTC : ZoneId.of("America/New_York");
        Stage stage = fx ? Stage.FOREX_BARS : Stage.STOCK_BARS;
        try {
            // Only completed dates, with a bounded holiday/weekend lookback.
            LocalDate today = LocalDate.now(clock.withZone(zone)), end = today.minusDays(1), start = today.minusDays(14);
            // Also stay behind the Basic ET recency boundary when UTC has already rolled over.
            LocalDate basicEnd = LocalDate.now(clock.withZone(ZoneId.of("America/New_York"))).minusDays(1);
            if (basicEnd.isBefore(end)) end = basicEnd;
            var n = get("/v2/aggs/ticker/" + ticker + "/range/1/day/" + start + "/" + end
                    + "?adjusted=false&sort=desc&limit=14", key, admission, stage);
            if (!ticker.equals(n.path("ticker").asString())) throw new Failure("IDENTITY_MISMATCH", stage, Reason.TICKER);
            var bars = n.path("results");
            if (bars.isMissingNode() || bars.isArray() && bars.isEmpty()) throw new Failure("NO_DATA", stage, Reason.EMPTY_RESULTS);
            if (!bars.isArray() || bars.size() > 14) throw new Failure("INVALID_DATA", stage, Reason.RESULTS_SHAPE);
            if (!n.path("adjusted").isBoolean() || n.path("adjusted").asBoolean()) throw new Failure("INVALID_DATA", stage, Reason.ADJUSTMENT_FLAG);
            tools.jackson.databind.JsonNode bar = null; Instant time = null;
            for (var candidate : bars) {
                var t = candidate.path("t");
                if (!t.isIntegralNumber() || !t.canConvertToLong()) throw new Failure("INVALID_DATA", stage, Reason.TIMESTAMP_TYPE);
                Instant candidateTime = Instant.ofEpochMilli(t.longValue());
                LocalDate date = candidateTime.atZone(zone).toLocalDate();
                if (date.isBefore(start) || date.isAfter(end) || candidateTime.isAfter(clock.instant())) throw new Failure("INVALID_DATA", stage, Reason.TIMESTAMP_RANGE);
                if (time == null || candidateTime.isAfter(time)) { time = candidateTime; bar = candidate; }
            }
            var value = bar.path("c");
            if (!value.isNumber()) throw new Failure("INVALID_DATA", stage, Reason.PRICE_TYPE);
            // JSON number scale is representation, not economic precision. Remove zeros without rounding.
            BigDecimal decimal = value.decimalValue().stripTrailingZeros();
            if (decimal.signum() <= 0) throw new Failure("INVALID_DATA", stage, Reason.PRICE_SIGN);
            if (decimal.scale() > (fx ? 12 : 8)) throw new Failure("INVALID_DATA", stage, Reason.PRICE_SCALE);
            if (decimal.precision() > 32 || decimal.scale() < -20 || decimal.precision() - decimal.scale() > 20)
                throw new Failure("INVALID_DATA", stage, Reason.PRICE_RANGE);
            String price = decimal.toPlainString();
            String exchange = "FOREX";
            if (!fx) {
                stage = Stage.STOCK_REFERENCE;
                // The bar itself has no currency, venue or instrument type. Match at the bar's date.
                var metadata = get("/v3/reference/tickers/" + symbol + "?date=" + time.atZone(zone).toLocalDate(), key, admission, stage).path("results");
                exchange = metadata.path("primary_exchange").asString();
                if (!symbol.equals(metadata.path("ticker").asString())) throw new Failure("IDENTITY_MISMATCH", stage, Reason.TICKER);
                if (!"stocks".equals(metadata.path("market").asString()) || !"us".equals(metadata.path("locale").asString()))
                    throw new Failure("IDENTITY_MISMATCH", stage, Reason.MARKET);
                if (!"usd".equals(metadata.path("currency_name").asString())) throw new Failure("IDENTITY_MISMATCH", stage, Reason.CURRENCY);
                if (!(assetClass.equals("ETF") ? "ETF" : "CS").equals(metadata.path("type").asString()))
                    throw new Failure("IDENTITY_MISMATCH", stage, Reason.ASSET_TYPE);
                if (!Set.of("XNAS", "XNYS", "ARCX", "XASE", "BATS", "IEXG").contains(exchange)) throw new Failure("IDENTITY_MISMATCH", stage, Reason.EXCHANGE);
            }
            return new FinanceMarketDataStore.Sample(symbol, price, time.toString(), clock.instant().toString(), exchange,
                    time.atZone(zone).toLocalDate().toString(), fx ? "UTC" : zone.getId());
        } catch (Failure safe) { throw safe; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new Failure("PROVIDER_FAILED", stage, Reason.INTERRUPTED); }
        catch (Exception rejected) { throw new Failure("PROVIDER_FAILED", stage, Reason.TRANSPORT); }
    }
    private tools.jackson.databind.JsonNode get(String path, String key, Admission admission, Stage stage) throws Exception {
        String allowed = admission.reserve(); if (!"READY".equals(allowed)) throw new Failure(allowed, stage, Reason.ADMISSION);
        var reply = transport.get(path, key);
        if (reply.status == 429) throw new Failure("RATE_LIMITED", stage, Reason.HTTP_STATUS);
        if (reply.status == 401 || reply.status == 403) throw new Failure("ACCESS_DENIED", stage, Reason.HTTP_STATUS);
        if (reply.status == 404) throw new Failure("NO_DATA", stage, Reason.HTTP_STATUS);
        if (reply.status != 200) throw new Failure("PROVIDER_FAILED", stage, Reason.HTTP_STATUS);
        if (reply.body.length > 32768) throw new Failure("PROVIDER_FAILED", stage, Reason.RESPONSE_SIZE);
        tools.jackson.databind.JsonNode n;
        try { n = JSON.readTree(reply.body); }
        catch (RuntimeException invalid) { throw new Failure("PROVIDER_FAILED", stage, Reason.JSON_FORMAT); }
        if (!n.isObject()) throw new Failure("PROVIDER_FAILED", stage, Reason.JSON_FORMAT);
        if (!"OK".equals(n.path("status").asString())) throw new Failure("PROVIDER_FAILED", stage, Reason.PROVIDER_STATUS);
        return n;
    }
    private static final class Https implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        public Reply get(String path, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("https://api.massive.com" + path)).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + key).header("Accept", "application/json").GET().build();
            // ofByteArray accumulates only this bounded subscriber, including chunked responses.
            var response = client.send(request, info -> new LimitedBody());
            return new Reply(response.statusCode(), response.body());
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 32768) { subscription.cancel(); result.completeExceptionally(new IOException()); return; }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(new IOException()); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
