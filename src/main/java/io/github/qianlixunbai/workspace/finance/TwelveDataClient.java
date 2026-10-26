package io.github.qianlixunbai.workspace.finance;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Fixed HTTPS origin, no redirects, bounded response, header credential, no raw errors/logging. */
final class TwelveDataClient {
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
    TwelveDataClient(Clock clock) { this(clock, new Https()); }
    TwelveDataClient(Clock clock, Transport transport) { this.clock = clock; this.transport = transport; }
    FinanceMarketDataStore.Sample fetch(String symbol, String assetClass, String key) throws Failure {
        boolean fx = symbol.equals("USD/CNY");
        String path = fx ? "/exchange_rate?symbol=USD%2FCNY"
                : "/quote?symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "&country=United%20States&type="
                    + (assetClass.equals("ETF") ? "ETF" : "Common%20Stock");
        try {
            var reply = transport.get(path, key);
            if (reply.status == 429) throw new Failure("RATE_LIMITED");
            if (reply.status == 401 || reply.status == 403) throw new Failure("ACCESS_DENIED");
            if (reply.status != 200 || reply.body.length > 32768) throw new Failure("PROVIDER_FAILED");
            var n = JSON.readTree(reply.body);
            if (n.has("code") || "error".equals(n.path("status").asString())) {
                int code = n.path("code").asInt(0);
                throw new Failure(code == 429 ? "RATE_LIMITED" : code == 401 || code == 403 ? "ACCESS_DENIED" : "PROVIDER_FAILED");
            }
            if (!symbol.equals(n.path("symbol").asString())) throw new Failure("IDENTITY_MISMATCH");
            String exchange = "FOREX";
            if (!fx) {
                exchange = n.path("mic_code").asString();
                if (!Set.of("XNAS", "XNYS", "ARCX", "XASE", "BATS", "IEXG").contains(exchange)
                        || !"USD".equals(n.path("currency").asString())
                        || !(assetClass.equals("ETF") ? "ETF" : "Common Stock").equals(n.path("type").asString()))
                    throw new Failure("IDENTITY_MISMATCH");
            }
            var value = n.path(fx ? "rate" : "close");
            String price = value.isString() ? value.asString() : value.toString();
            if (!price.matches("[0-9]{1,20}(\\.[0-9]{1,12})?") || new BigDecimal(price).signum() <= 0
                    || new BigDecimal(price).scale() > (fx ? 12 : 8)
                    || !n.path("timestamp").isIntegralNumber() || !n.path("timestamp").canConvertToLong()) throw new Failure("INVALID_DATA");
            Instant time = Instant.ofEpochSecond(n.path("timestamp").longValue());
            if (time.isBefore(Instant.parse("2000-01-01T00:00:00Z")) || time.isAfter(clock.instant().plusSeconds(60))) throw new Failure("INVALID_DATA");
            return new FinanceMarketDataStore.Sample(symbol, price, time.toString(), clock.instant().toString(), exchange);
        } catch (Failure safe) { throw safe; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new Failure("PROVIDER_FAILED"); }
        catch (Exception rejected) { throw new Failure("PROVIDER_FAILED"); }
    }
    private static final class Https implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        public Reply get(String path, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("https://api.twelvedata.com" + path)).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "apikey " + key).header("Accept", "application/json").GET().build();
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
