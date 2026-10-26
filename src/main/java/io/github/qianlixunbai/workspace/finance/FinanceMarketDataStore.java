package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.persistence.TrustedMarketDataFile;
import java.io.IOException;
import java.time.*;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Provider credentials and quota only. Market responses never enter this file or Finance SQL. */
final class FinanceMarketDataStore {
    interface FilePort { byte[] read() throws IOException; void write(byte[] bytes) throws IOException; }
    enum Use { DISPLAY, DISPLAY_MEMORY, PERSISTENCE, OFFLINE, VALUATION }
    // Reviewed official personal-display grant, not a user-selected plan or mutable configuration.
    // Evidence and limits: docs/design/FINANCE-MARKET-DATA-M12-REFERENCE.md.
    static boolean permits(String provider, Use use) {
        return "MASSIVE".equals(provider) && (use == Use.DISPLAY || use == Use.DISPLAY_MEMORY);
    }
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Sample(String symbol, String price, String time, String fetchedAt, String exchange, String date, String timeZone) {
        @Override public String toString() { return "MarketSample[private]"; }
    }
    record State(int format, String provider, String apiKey, List<String> requests, String blockedUntil) {
        @Override public String toString() { return "MarketConfiguration[private]"; }
    }
    private final FilePort file;
    final java.util.function.Supplier<String> dataContext;
    final Clock clock;
    FinanceMarketDataStore(Clock clock, java.util.function.Supplier<String> dataContext) {
        this(clock, new FilePort() {
            private final TrustedMarketDataFile file = new TrustedMarketDataFile();
            public byte[] read() throws IOException { return file.read(); }
            public void write(byte[] bytes) throws IOException { file.write(bytes); }
        }, dataContext);
    }
    FinanceMarketDataStore(Clock clock, FilePort file, java.util.function.Supplier<String> dataContext) {
        this.clock = clock; this.file = file; this.dataContext = dataContext;
    }
    synchronized State read() throws IOException {
        byte[] bytes = file.read();
        if (bytes == null) return new State(2, "MASSIVE", "", List.of(), Instant.EPOCH.toString());
        try {
            State s = JSON.readValue(bytes, State.class);
            if (s.format != 2 || !"MASSIVE".equals(s.provider) || !validKey(s.apiKey) || s.requests.size() > 5) throw new IOException();
            Instant.parse(s.blockedUntil);
            for (String request : s.requests) Instant.parse(request);
            return s;
        } catch (RuntimeException invalid) { throw new IOException("Market configuration unavailable"); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private void write(State state) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(state);
        try { file.write(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
    }
    static boolean validKey(String key) { return key != null && key.matches("[A-Za-z0-9_-]{0,256}"); }
    synchronized void configure(String apiKey) throws IOException {
        if (!validKey(apiKey)) throw new IllegalArgumentException();
        State s = read();
        write(new State(2, "MASSIVE", apiKey.isEmpty() ? s.apiKey : apiKey, s.requests, s.blockedUntil));
    }
    synchronized String reserve() throws IOException {
        State s = read(); Instant now = clock.instant();
        if (!permits(s.provider, Use.DISPLAY)) return "LICENSE_UNVERIFIED";
        if (s.apiKey.isEmpty()) return "KEY_REQUIRED";
        var requests = s.requests.stream().filter(t -> Instant.parse(t).isAfter(now.minusSeconds(60))).toList();
        if (now.isBefore(Instant.parse(s.blockedUntil)) || requests.size() >= 5) return "RATE_LIMITED";
        var next = new ArrayList<>(requests); next.add(now.toString());
        write(new State(2, s.provider, s.apiKey, next, s.blockedUntil));
        return "READY";
    }
    synchronized void throttle() throws IOException {
        State s = read();
        write(new State(2, s.provider, s.apiKey, s.requests, clock.instant().plusSeconds(60).toString()));
    }
}
