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
            Path pending = file().resolveSibling("browser-clients.pending");
            if (WindowsPrivateAcl.isWindows()) WindowsPrivateAcl.createFile(pending);
            else Files.createFile(pending);
            Files.writeString(pending, committed);
        }
        try (var clients = new BrowserClients(file(), Clock.systemUTC())) {
            assertNull(clients.authenticate("Bearer " + issued.credential(), ORIGIN));
            assertFalse(Files.exists(file().resolveSibling("browser-clients.pending")));
        }
    }

}
