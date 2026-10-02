package io.github.qianlixunbai.workspace.security;

import io.github.qianlixunbai.workspace.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserClientsTest {
    @TempDir Path directory;
    static final String ORIGIN = "chrome-extension://" + "a".repeat(32);
    Path file() { return directory.resolve("private/browser-clients.json"); }
    static BrowserClients.Exchange pair(BrowserClients clients) {
        var session = clients.create(ORIGIN, "Synthetic client");
        return clients.exchange(session.pairingId(), session.pairingSecret(), ORIGIN);
    }

    @Test void credentialAndRevocationSurviveRestartButPairingDoesNot() throws Exception {
        BrowserClients.Exchange issued; BrowserClients.Pairing pending;
        try (var clients = new BrowserClients(file(), Clock.systemUTC())) {
            issued = pair(clients); pending = clients.create(ORIGIN, "Pending");
            assertEquals(issued.client(), clients.authenticate("Bearer " + issued.credential(), ORIGIN));
            String stored = Files.readString(file());
            assertFalse(stored.contains(issued.credential())); assertFalse(stored.contains(pending.pairingSecret()));
            assertEquals("Exchange[REDACTED]", issued.toString());
        }
        try (var reloaded = new BrowserClients(file(), Clock.systemUTC())) {
            assertNotNull(reloaded.authenticate("Bearer " + issued.credential(), ORIGIN));
            assertThrows(WorkspaceException.class, () -> reloaded.exchange(pending.pairingId(), pending.pairingSecret(), ORIGIN));
            reloaded.revoke(issued.client().clientId());
            assertNull(reloaded.authenticate("Bearer " + issued.credential(), ORIGIN));
        }
        try (var reloaded = new BrowserClients(file(), Clock.systemUTC())) {
            assertTrue(reloaded.list().isEmpty()); assertNull(reloaded.authenticate("Bearer " + issued.credential(), ORIGIN));
        }
    }

    @Test void expiryReplayFailedAttemptAndGlobalBudgets() {
        MutableClock clock = new MutableClock();
        try (var clients = new BrowserClients(file(), clock)) {
            var expired = clients.create(ORIGIN, "Expired"); clock.now = clock.now.plus(BrowserClients.TTL);
            assertThrows(WorkspaceException.class, () -> clients.exchange(expired.pairingId(), expired.pairingSecret(), ORIGIN));
            var one = clients.create(ORIGIN, "One"); clients.exchange(one.pairingId(), one.pairingSecret(), ORIGIN);
            assertThrows(WorkspaceException.class, () -> clients.exchange(one.pairingId(), one.pairingSecret(), ORIGIN));
            var failed = clients.create(ORIGIN, "Failures");
            for (int i=0; i<5; i++) assertThrows(WorkspaceException.class, () -> clients.exchange(failed.pairingId(), "bad", ORIGIN));
            assertThrows(WorkspaceException.class, () -> clients.exchange(failed.pairingId(), failed.pairingSecret(), ORIGIN));
            var global = clients.create(ORIGIN, "Global");
            for (int i=0; i<60; i++) assertThrows(WorkspaceException.class, () -> clients.exchange(UUID.randomUUID(), "bad", ORIGIN));
            assertThrows(WorkspaceException.class, () -> clients.exchange(global.pairingId(), global.pairingSecret(), ORIGIN));
            clock.now = clock.now.plusSeconds(60);
            assertNotNull(clients.exchange(global.pairingId(), global.pairingSecret(), ORIGIN));
        }
    }

    @Test void boundedSessionsRegistryAndExactOrigin() {
        MutableClock clock = new MutableClock();
        try (var clients = new BrowserClients(file(), clock)) {
            for (String invalid : List.of("*", "https://example.com", ORIGIN + "/", "chrome-extension://" + "q".repeat(32)))
                assertThrows(WorkspaceException.class, () -> clients.create(invalid, "Invalid"));
            for (int i=0; i<8; i++) clients.create(ORIGIN, "Bounded");
            assertEquals(ErrorCode.QUEUE_FULL, assertThrows(WorkspaceException.class, () -> clients.create(ORIGIN, "Overflow")).error().code());
            clock.now = clock.now.plus(BrowserClients.TTL);
            for (int i=0; i<32; i++) pair(clients);
            assertEquals(32, clients.list().size());
            assertThrows(WorkspaceException.class, () -> clients.create(ORIGIN, "Full"));
            clients.revoke(clients.list().getFirst().clientId()); assertNotNull(pair(clients));
        }
    }

    @Test void malformedAndAmbiguousRegistryFailClosed() throws Exception {
        Files.createDirectories(file().getParent());
        for (String invalid : List.of("{broken", "{\"version\":2,\"clients\":[]}", "{\"version\":1,\"clients\":null}",
                "{\"version\":1,\"version\":1,\"clients\":[]}", "{\"version\":1,\"clients\":[],\"extra\":true}", "x".repeat(65537))) {
            Files.writeString(file(), invalid);
            assertThrows(IllegalStateException.class, () -> new BrowserClients(file(), Clock.systemUTC()));
        }
        Files.delete(file()); Files.writeString(file().resolveSibling("browser-clients.pending"), "uncommitted");
        assertThrows(IllegalStateException.class, () -> new BrowserClients(file(), Clock.systemUTC()));
    }

    @Test void atomicFailureDoesNotPublishAndStalePendingNeverResurrectsRevocation() throws Exception {
        BrowserClients.Exchange issued;
        try (var clients = new BrowserClients(file(), Clock.systemUTC())) {
            issued = pair(clients); String committed = Files.readString(file());
            var session = clients.create(ORIGIN, "Not committed");
            Files.createDirectory(file().resolveSibling("browser-clients.pending"));
            assertThrows(WorkspaceException.class, () -> clients.exchange(session.pairingId(), session.pairingSecret(), ORIGIN));
            assertEquals(committed, Files.readString(file())); assertEquals(1, clients.list().size());
            assertThrows(WorkspaceException.class, () -> clients.revoke(issued.client().clientId()));
            assertNotNull(clients.authenticate("Bearer " + issued.credential(), ORIGIN));
            Files.delete(file().resolveSibling("browser-clients.pending")); clients.revoke(issued.client().clientId());
            Files.writeString(file().resolveSibling("browser-clients.pending"), committed);
        }
        try (var clients = new BrowserClients(file(), Clock.systemUTC())) {
            assertNull(clients.authenticate("Bearer " + issued.credential(), ORIGIN));
            assertFalse(Files.exists(file().resolveSibling("browser-clients.pending")));
        }
    }

    @Test void privatePermissionsAndExclusiveRegistryLock() throws Exception {
        try (var clients = new BrowserClients(file(), Clock.systemUTC())) {
            pair(clients);
            assertThrows(IllegalStateException.class, () -> new BrowserClients(file(), Clock.systemUTC()));
            for (Path path : List.of(file(), file().getParent(), file().resolveSibling("browser-clients.lock"))) {
                var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
                if (posix != null) assertEquals(PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"), posix.readAttributes().permissions());
                else {
                    var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
                    assertNotNull(acl); assertFalse(acl.getAcl().isEmpty());
                    for (AclEntry entry : acl.getAcl()) if (entry.type() == AclEntryType.ALLOW) assertEquals(acl.getOwner(), entry.principal());
                }
            }
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
