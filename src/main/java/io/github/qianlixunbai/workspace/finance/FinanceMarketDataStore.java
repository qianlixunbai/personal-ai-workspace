package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.persistence.TrustedMarketDataFile;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Only provider data/configuration; no account, quantity, transaction, or Finance identity is stored. */
final class FinanceMarketDataStore {
    interface FilePort { byte[] read() throws IOException; void write(byte[] bytes) throws IOException; }
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Sample(String symbol, String price, String time, String fetchedAt, String exchange) {
        @Override public String toString() { return "MarketSample[private]"; }
    }
    record State(int format, boolean enabled, boolean licensed, String apiKey, int retentionHours,
                 List<Sample> samples, List<String> requests, String blockedUntil) {
        @Override public String toString() { return "MarketData[private]"; }
    }
    private final FilePort file;
    // No production license evidence has been reviewed. UI consent and a saved key cannot grant data rights.
    final boolean verifiedLicense;
    final java.util.function.Supplier<String> dataContext;
    final Clock clock;
    FinanceMarketDataStore(Clock clock, java.util.function.Supplier<String> dataContext) {
        this(clock, new FilePort() {
            private final TrustedMarketDataFile file = new TrustedMarketDataFile();
            public byte[] read() throws IOException { return file.read(); }
            public void write(byte[] bytes) throws IOException { file.write(bytes); }
        }, false, dataContext);
    }
    FinanceMarketDataStore(Clock clock, FilePort file) { this(clock, file, false); }
    // Package-private deterministic fixture seam; production wiring never supplies a license grant.
    FinanceMarketDataStore(Clock clock, FilePort file, boolean verifiedFixture) { this(clock, file, verifiedFixture, () -> "SYNTHETIC"); }
    private FinanceMarketDataStore(Clock clock, FilePort file, boolean verifiedLicense, java.util.function.Supplier<String> dataContext) {
        this.clock = clock; this.file = file; this.verifiedLicense = verifiedLicense; this.dataContext = dataContext;
    }
    synchronized State read() throws IOException {
        byte[] bytes = file.read();
        if (bytes == null) return new State(1, false, false, "", 24, List.of(), List.of(), Instant.EPOCH.toString());
        try {
            State s = JSON.readValue(bytes, State.class);
            if (s.format != 1 || s.retentionHours < 1 || s.retentionHours > 168 || !validKey(s.apiKey)
                    || s.enabled && (!s.licensed || s.apiKey.isEmpty()) || s.samples.size() > 128 || s.requests.size() > 800) throw new IOException();
            Instant.parse(s.blockedUntil);
            for (var sample : s.samples) {
                if (!sample.symbol.matches("USD/CNY|[A-Z][A-Z0-9.-]{0,14}") || !sample.price.matches("[0-9]{1,20}(\\.[0-9]{1,12})?")
                        || new BigDecimal(sample.price).signum() <= 0 || !sample.exchange.matches("[A-Z0-9_ -]{1,30}")) throw new IOException();
                Instant.parse(sample.time); Instant.parse(sample.fetchedAt);
            }
            for (String request : s.requests) Instant.parse(request);
            return s;
        } catch (RuntimeException invalid) { throw new IOException("Market data state unavailable"); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private void write(State state) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(state);
        try { file.write(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
    }
    static boolean validKey(String key) { return key != null && key.matches("[A-Za-z0-9_-]{0,256}"); }
    synchronized void configure(boolean enabled, boolean licensed, String apiKey, int retention) throws IOException {
        State s = read();
        String key = apiKey.isEmpty() ? s.apiKey : apiKey;
        if (!validKey(key) || retention < 1 || retention > 168 || enabled && (!licensed || key.isEmpty())) throw new IllegalArgumentException();
        // Never reset quota/cooldown when credentials or consent change.
        write(new State(1, enabled, licensed, key, retention, retained(s, retention), s.requests, s.blockedUntil));
    }
    private List<Sample> retained(State s, int hours) {
        var earliest = clock.instant().minus(Duration.ofHours(hours));
        return s.samples.stream().filter(v -> Instant.parse(v.fetchedAt).isAfter(earliest)).toList();
    }
    synchronized Sample sample(String symbol) throws IOException {
        return samples().stream().filter(v -> v.symbol.equals(symbol)).findFirst().orElse(null);
    }
    synchronized List<Sample> samples() throws IOException {
        if (!verifiedLicense) return List.of(); // Also suppress existing M1 provider caches and derived valuations.
        State s = read(); var kept = retained(s, s.retentionHours);
        if (kept.size() != s.samples.size()) write(new State(1, s.enabled, s.licensed, s.apiKey, s.retentionHours, kept, s.requests, s.blockedUntil));
        return kept;
    }
    synchronized void put(Sample sample) throws IOException {
        if (!verifiedLicense) throw new IOException("LICENSE_UNVERIFIED");
        State s = read(); var samples = new ArrayList<>(retained(s, s.retentionHours));
        var old = samples.stream().filter(v -> v.symbol.equals(sample.symbol)).findFirst().orElse(null);
        if (old != null && Instant.parse(old.time).isAfter(Instant.parse(sample.time))) return;
        samples.removeIf(v -> v.symbol.equals(sample.symbol));
        if (samples.size() >= 128) samples.remove(0);
        samples.add(sample);
        write(new State(1, s.enabled, s.licensed, s.apiKey, s.retentionHours, samples, s.requests, s.blockedUntil));
    }
    synchronized String reserve() throws IOException {
        State s = read(); Instant now = clock.instant();
        if (!s.enabled || !s.licensed || s.apiKey.isEmpty()) return "DISABLED";
        if (!verifiedLicense) return "LICENSE_UNVERIFIED";
        var requests = s.requests.stream().filter(t -> Instant.parse(t).isAfter(now.minus(Duration.ofDays(1)))).toList();
        if (now.isBefore(Instant.parse(s.blockedUntil)) || requests.size() >= 800
                || requests.stream().filter(t -> Instant.parse(t).isAfter(now.minusSeconds(60))).count() >= 8) return "RATE_LIMITED";
        var next = new ArrayList<>(requests); next.add(now.toString());
        write(new State(1, s.enabled, s.licensed, s.apiKey, s.retentionHours, retained(s, s.retentionHours), next, s.blockedUntil));
        return "READY";
    }
    synchronized void throttle() throws IOException {
        State s = read();
        write(new State(1, s.enabled, s.licensed, s.apiKey, s.retentionHours, s.samples, s.requests,
                clock.instant().plusSeconds(60).toString()));
    }
    boolean fresh(Sample s, int minutes) {
        return s != null && !clock.instant().isBefore(Instant.parse(s.time))
                && !clock.instant().isBefore(Instant.parse(s.fetchedAt))
                && clock.instant().isBefore(Instant.parse(s.time).plusSeconds(minutes * 60L))
                && clock.instant().isBefore(Instant.parse(s.fetchedAt).plusSeconds(minutes * 60L));
    }
}
