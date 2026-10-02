package io.github.qianlixunbai.workspace.security;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Small single-process security registry. No task, prompt or result persistence. */
@Component
public final class BrowserClients implements AutoCloseable {
    static final int MAX_CLIENTS = 32, MAX_PAIRINGS = 8, MAX_FAILURES = 5, MAX_BYTES = 65536;
    static final Duration TTL = Duration.ofMinutes(3);
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final Path file, pending;
    private final Clock clock;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private List<Registration> clients = new ArrayList<>();
    private Instant exchangeWindow;
    private int exchangeAttempts;
    private FileChannel lockChannel;
    private java.nio.channels.FileLock registryLock;

    @org.springframework.beans.factory.annotation.Autowired
    public BrowserClients(RuntimeProperties properties) {
        this(properties.security().tokenFile().toAbsolutePath().normalize().resolveSibling("browser-clients.json"), Clock.systemUTC());
    }

    BrowserClients(Path file, Clock clock) {
        this.file = file.toAbsolutePath().normalize(); this.pending = this.file.resolveSibling("browser-clients.pending");
        this.clock = clock; this.exchangeWindow = clock.instant();
        try {
            Files.createDirectories(this.file.getParent());
            for (Path part = this.file.getParent(); part != null; part = part.getParent()) {
                if (Files.isSymbolicLink(part) || Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isOther()) throw new IllegalStateException();
            }
            LocalClientToken.restrict(this.file.getParent(), true);
            Path lockFile = this.file.resolveSibling("browser-clients.lock");
            if (!Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) Files.createFile(lockFile);
            regular(lockFile); LocalClientToken.restrict(lockFile, false);
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            registryLock = lockChannel.tryLock();
            if (registryLock == null) throw new IllegalStateException();
            if (Files.exists(this.file, LinkOption.NOFOLLOW_LINKS)) {
                regular(this.file); LocalClientToken.restrict(this.file, false);
                Registry registry = json.readValue(Files.readAllBytes(this.file), Registry.class);
                validate(registry); clients = new ArrayList<>(registry.clients());
            } else if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                // Never promote an uncommitted file or silently reset ambiguous security state.
                throw new IllegalStateException();
            }
            if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) { regular(pending); Files.delete(pending); }
        } catch (Exception ignored) { close(); throw new IllegalStateException("Cannot initialize private browser security registry"); }
    }

    private static void regular(Path path) throws java.io.IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) throw new java.io.IOException();
    }

    private void validate(Registry registry) {
        if (registry == null || registry.version() != 1 || registry.clients() == null || registry.clients().size() > MAX_CLIENTS)
            throw new IllegalStateException();
        Set<String> ids = new HashSet<>();
        for (Registration entry : registry.clients()) {
            if (entry == null || entry.identity() == null) throw new IllegalStateException();
            ClientIdentity client = entry.identity();
            if (!UUID.fromString(client.clientId()).toString().equals(client.clientId()) || !ids.add(client.clientId())
                    || !"browser-extension".equals(client.clientType()) || !validOrigin(client.origin())
                    || !validName(client.displayName()) || client.createdAt() == null
                    || !Set.of("translate").equals(client.allowedCapabilities())
                    || entry.verifier() == null || !entry.verifier().matches("[0-9a-f]{64}")) throw new IllegalStateException();
        }
    }

    private void save(List<Registration> next) {
        boolean created = false;
        try {
            byte[] bytes = json.writeValueAsBytes(new Registry(1, next));
            if (bytes.length > MAX_BYTES) throw new java.io.IOException();
            Files.createFile(pending); created = true; LocalClientToken.restrict(pending, false);
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            clients = next; // Publish in memory only after the atomic commit succeeds.
        } catch (Exception ignored) {
            throw new WorkspaceException(ErrorCode.INTERNAL_ERROR, "SECURITY_STATE");
        } finally {
            if (created) try { Files.deleteIfExists(pending); } catch (java.io.IOException ignored) { }
        }
    }

    public synchronized Pairing create(String origin, String displayName) {
        if (!validOrigin(origin) || !validName(displayName)) throw new WorkspaceException(ErrorCode.INVALID_REQUEST, "PAIRING");
        expire();
        if (sessions.size() >= MAX_PAIRINGS || clients.size() + sessions.size() >= MAX_CLIENTS)
            throw new WorkspaceException(ErrorCode.QUEUE_FULL, "PAIRING");
        UUID id = UUID.randomUUID(); String proof = random(); Instant expires = clock.instant().plus(TTL);
        sessions.put(id, new Session(origin, displayName, digest(proof), expires));
        return new Pairing(id, proof, expires);
    }

    public synchronized Exchange exchange(UUID pairingId, String proof, String origin) {
        expire();
        Instant now = clock.instant();
        if (!now.isBefore(exchangeWindow.plusSeconds(60))) { exchangeWindow = now; exchangeAttempts = 0; }
        if (++exchangeAttempts > 60) throw unauthorized();
        Session session = sessions.get(pairingId);
        if (session == null) throw unauthorized();
        if (!session.origin.equals(origin) || proof == null || !proof.matches("[A-Za-z0-9_-]{43}")
                || !MessageDigest.isEqual(session.verifier, digest(proof))) {
            if (++session.failures >= MAX_FAILURES) sessions.remove(pairingId);
            throw unauthorized();
        }
        sessions.remove(pairingId); // Consumed even if persistence fails; never expose an uncommitted credential.
        if (clients.size() >= MAX_CLIENTS) throw new WorkspaceException(ErrorCode.QUEUE_FULL, "PAIRING");
        String id = UUID.randomUUID().toString();
        ClientIdentity identity = new ClientIdentity(id, "browser-extension", session.displayName, origin, now, Set.of("translate"));
        String credential = "br1." + id + "." + random();
        List<Registration> next = new ArrayList<>(clients);
        next.add(new Registration(identity, HexFormat.of().formatHex(digest(credential)))); save(next);
        return new Exchange(identity, credential);
    }

    public synchronized ClientIdentity authenticate(String authorization, String origin) {
        ClientIdentity identity = authenticateCredential(authorization);
        return identity != null && validOrigin(origin) && identity.origin().equals(origin) ? identity : null;
    }

    /** Authenticates the bearer secret only; HTTP admission must separately enforce Origin/metadata/routes. */
    public synchronized ClientIdentity authenticateCredential(String authorization) {
        if (authorization == null || !authorization.matches("Bearer br1\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}")) return null;
        String credential = authorization.substring(7); String id = credential.substring(4, 40);
        for (Registration entry : clients) {
            if (entry.identity().clientId().equals(id)
                    && MessageDigest.isEqual(HexFormat.of().parseHex(entry.verifier()), digest(credential))) return entry.identity();
        }
        return null;
    }

    synchronized boolean allowsOrigin(String origin, boolean exchange) {
        expire();
        return validOrigin(origin) && (exchange ? sessions.values().stream().anyMatch(s -> s.origin.equals(origin))
                : clients.stream().anyMatch(c -> c.identity().origin().equals(origin)));
    }

    public synchronized List<ClientIdentity> list() { return clients.stream().map(Registration::identity).toList(); }

    public synchronized void revoke(String clientId) {
        // Deleting a verifier is revocation and server-side forget; unknown IDs are idempotent.
        List<Registration> next = new ArrayList<>(clients);
        if (next.removeIf(c -> c.identity().clientId().equals(clientId))) save(next);
    }

    private void expire() { sessions.values().removeIf(s -> !clock.instant().isBefore(s.expires)); }
    @jakarta.annotation.PreDestroy
    @Override public synchronized void close() {
        sessions.clear();
        try { if (registryLock != null) registryLock.release(); } catch (java.io.IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (java.io.IOException ignored) { }
    }
    public static boolean validOrigin(String origin) { return origin != null && origin.matches("chrome-extension://[a-p]{32}"); }
    private static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9 ._-]{1,64}") && !name.isBlank(); }
    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static WorkspaceException unauthorized() { return new WorkspaceException(ErrorCode.UNAUTHORIZED, "PAIRING"); }
    private static final class Session {
        final String origin, displayName; final byte[] verifier; final Instant expires; int failures;
        Session(String origin, String displayName, byte[] verifier, Instant expires) {
            this.origin = origin; this.displayName = displayName; this.verifier = verifier; this.expires = expires;
        }
    }
    public record Pairing(UUID pairingId, String pairingSecret, Instant expiresAt) {
        @Override public String toString() { return "Pairing[REDACTED]"; }
    }
    public record Exchange(ClientIdentity client, String credential) {
        @Override public String toString() { return "Exchange[REDACTED]"; }
    }
    record Registration(ClientIdentity identity, String verifier) {
        @Override public String toString() { return "Registration[REDACTED]"; }
    }
    record Registry(int version, List<Registration> clients) {}
}
