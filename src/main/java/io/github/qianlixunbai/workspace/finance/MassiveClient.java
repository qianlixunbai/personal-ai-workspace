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
    static final class Failure extends IOException {
        final String code;
        Failure(String code) { super(code); this.code = code; }
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
        if (!fx && (!symbol.matches("[A-Z][A-Z0-9.-]{0,14}") || !Set.of("STOCK", "ETF").contains(assetClass))) throw new Failure("IDENTITY_MISMATCH");
        String ticker = fx ? "C:USDCNY" : symbol;
        ZoneId zone = fx ? ZoneOffset.UTC : ZoneId.of("America/New_York");
        try {
            // Only completed dates, with a bounded holiday/weekend lookback.
            LocalDate today = LocalDate.now(clock.withZone(zone)), end = today.minusDays(1), start = today.minusDays(14);
            // Also stay behind the Basic ET recency boundary when UTC has already rolled over.
            LocalDate basicEnd = LocalDate.now(clock.withZone(ZoneId.of("America/New_York"))).minusDays(1);
            if (basicEnd.isBefore(end)) end = basicEnd;
            var n = get("/v2/aggs/ticker/" + ticker + "/range/1/day/" + start + "/" + end
                    + "?adjusted=false&sort=desc&limit=14", key, admission);
            if (!ticker.equals(n.path("ticker").asString())) throw new Failure("IDENTITY_MISMATCH");
            var bars = n.path("results");
            if (bars.isMissingNode() || bars.isArray() && bars.isEmpty()) throw new Failure("NO_DATA");
            if (!bars.isArray() || bars.size() > 14 || !n.path("adjusted").isBoolean() || n.path("adjusted").asBoolean()) throw new Failure("INVALID_DATA");
            tools.jackson.databind.JsonNode bar = null; Instant time = null;
            for (var candidate : bars) {
                var t = candidate.path("t");
                if (!t.isIntegralNumber() || !t.canConvertToLong()) throw new Failure("INVALID_DATA");
                Instant candidateTime = Instant.ofEpochMilli(t.longValue());
                LocalDate date = candidateTime.atZone(zone).toLocalDate();
                if (date.isBefore(start) || date.isAfter(end) || candidateTime.isAfter(clock.instant())) throw new Failure("INVALID_DATA");
                if (time == null || candidateTime.isAfter(time)) { time = candidateTime; bar = candidate; }
            }
            String exchange = "FOREX";
            if (!fx) {
                // The bar itself has no currency, venue or instrument type. Match at the bar's date.
                var metadata = get("/v3/reference/tickers/" + symbol + "?date=" + time.atZone(zone).toLocalDate(), key, admission).path("results");
                exchange = metadata.path("primary_exchange").asString();
                if (!Set.of("XNAS", "XNYS", "ARCX", "XASE", "BATS", "IEXG").contains(exchange)
                        || !symbol.equals(metadata.path("ticker").asString()) || !"stocks".equals(metadata.path("market").asString())
                        || !"us".equals(metadata.path("locale").asString()) || !"usd".equals(metadata.path("currency_name").asString())
                        || !(assetClass.equals("ETF") ? "ETF" : "CS").equals(metadata.path("type").asString()))
                    throw new Failure("IDENTITY_MISMATCH");
            }
            var value = bar.path("c");
            if (!value.isNumber()) throw new Failure("INVALID_DATA");
            BigDecimal decimal = value.decimalValue();
            if (decimal.precision() > 32 || decimal.scale() < -20 || decimal.precision() - decimal.scale() > 20
                    || decimal.scale() > (fx ? 12 : 8) || decimal.signum() <= 0) throw new Failure("INVALID_DATA");
            String price = decimal.toPlainString();
            if (!price.matches("[0-9]{1,20}(\\.[0-9]{1,12})?") || new BigDecimal(price).signum() <= 0
                    || new BigDecimal(price).scale() > (fx ? 12 : 8)) throw new Failure("INVALID_DATA");
            return new FinanceMarketDataStore.Sample(symbol, price, time.toString(), clock.instant().toString(), exchange,
                    time.atZone(zone).toLocalDate().toString(), fx ? "UTC" : zone.getId());
        } catch (Failure safe) { throw safe; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new Failure("PROVIDER_FAILED"); }
        catch (Exception rejected) { throw new Failure("PROVIDER_FAILED"); }
    }
    private tools.jackson.databind.JsonNode get(String path, String key, Admission admission) throws Exception {
        String allowed = admission.reserve(); if (!"READY".equals(allowed)) throw new Failure(allowed);
        var reply = transport.get(path, key);
        if (reply.status == 429) throw new Failure("RATE_LIMITED");
        if (reply.status == 401 || reply.status == 403) throw new Failure("ACCESS_DENIED");
        if (reply.status == 404) throw new Failure("NO_DATA");
        if (reply.status != 200 || reply.body.length > 32768) throw new Failure("PROVIDER_FAILED");
        var n = JSON.readTree(reply.body);
        if (!"OK".equals(n.path("status").asString())) throw new Failure("PROVIDER_FAILED");
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
