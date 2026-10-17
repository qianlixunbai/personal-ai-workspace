package io.github.qianlixunbai.workspace.security;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.PointerByReference;
import io.github.qianlixunbai.workspace.config.RuntimeProperties;
import io.github.qianlixunbai.workspace.knowledge.PrivateKnowledgeDirectory;
import io.github.qianlixunbai.workspace.memory.PrivateMemoryDirectory;
import io.github.qianlixunbai.workspace.memory.MemoryStore;
import io.github.qianlixunbai.workspace.knowledge.KnowledgeStore;
import io.github.qianlixunbai.workspace.model.ModelStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsPrivateAclTest {
    @TempDir Path root;

    /** One integrated regression for the historical Protection loss and private atomic publication. */
    @Test void protectedBoundarySurvivesOwningFlowsAndRejectsUnsafeInput() throws Exception {
        Path credentials = root.resolve("credentials"), data = root.resolve("data"), token = credentials.resolve("client-token");
        WindowsPrivateAcl.directory(credentials);
        byte[] before = descriptor(credentials);
        LocalClientToken.restrict(credentials, true);
        assertArrayEquals(before, descriptor(credentials)); // Existing descriptor is not rewritten.
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Security(token), null, null, null, null, null);
        new LocalClientToken(properties);
        new LocalClientToken(properties); // Existing token admission on reinitialization.
        assertEquals(43, Files.size(token)); // No token contents in assertions/evidence.
        Path db = PrivateMemoryDirectory.prepare(data, token);
        Path knowledge = PrivateKnowledgeDirectory.prepare(data, token);
        Path temporary = knowledge.resolve("staging/synthetic.upload"), published = knowledge.resolve("sources/synthetic.source");
        PrivateKnowledgeDirectory.file(temporary);
        protectedObject(temporary);
        Files.writeString(temporary, "synthetic non-personal fixture");
        Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
        PrivateKnowledgeDirectory.protect(published, false);
        Path knowledgeLock = knowledge.resolve("knowledge.lock");
        PrivateKnowledgeDirectory.file(knowledgeLock);
        try (var clients = new BrowserClients(credentials.resolve("browser-clients.json"), Clock.systemUTC())) {
            var pairing = clients.create("chrome-extension://" + "a".repeat(32), "Synthetic fixture");
            var issued = clients.exchange(pairing.pairingId(), pairing.pairingSecret(), "chrome-extension://" + "a".repeat(32));
            assertNotNull(clients.authenticateCredential("Bearer " + issued.credential()));
            clients.revoke(issued.client().clientId());
            assertThrows(IllegalStateException.class, () -> new BrowserClients(credentials.resolve("browser-clients.json"), Clock.systemUTC()));
        }
        try (var reopened = new BrowserClients(credentials.resolve("browser-clients.json"), Clock.systemUTC())) { }
        Path model = root.resolve("model-state");
        try (var state = new ModelStateStore(model, token, data)) {
            state.commit(0, "synthetic:latest", "a".repeat(64)); // Store only, no provider/model operation.
            state.armGuard();
            assertThrows(java.io.IOException.class, () -> new ModelStateStore(model, token, data));
        }
        try (var state = new ModelStateStore(model, token, data)) {
            assertTrue(state.unresolved());
            assertEquals(1, state.selection().selectionRevision());
            assertThrows(java.io.IOException.class, state::clearGuard); // Restart does not invent trusted completion.
        }
        for (Path path : List.of(credentials, token, credentials.resolve("browser-clients.lock"),
                credentials.resolve("browser-clients.json"), data, db, knowledge, knowledgeLock,
                knowledge.resolve("sources"), knowledge.resolve("staging"), published, model,
                model.resolve(".lock"), model.resolve("active-model.json"), model.resolve("execution-guard.json"))) {
            WindowsPrivateAcl.verify(path, Files.isDirectory(path));
            protectedObject(path);
        }
        Path unsafe = root.resolve("unsafe");
        Files.createDirectory(unsafe); // Inherited/unprotected input must not be silently migrated.
        byte[] unsafeBefore = descriptor(unsafe);
        assertThrows(java.io.IOException.class, () -> WindowsPrivateAcl.directory(unsafe));
        assertArrayEquals(unsafeBefore, descriptor(unsafe));
        var view = Files.getFileAttributeView(unsafe, AclFileAttributeView.class);
        String everyoneName = Advapi32Util.getAccountBySid("S-1-1-0").fqn;
        var everyone = unsafe.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(everyoneName);
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build(),
                AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                        .setPermissions(AclEntryPermission.READ_DATA)
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build()));
        protectFixtureDacl(unsafe);
        protectedObject(unsafe);
        byte[] foreignBefore = descriptor(unsafe);
        assertThrows(java.io.IOException.class, () -> WindowsPrivateAcl.verify(unsafe, true));
        assertArrayEquals(foreignBefore, descriptor(unsafe));
        Path child = unsafe.resolve("new-private");
        WindowsPrivateAcl.directory(child); // Foreign inheritable parent ACEs must not enter this new child.
        WindowsPrivateAcl.verify(child, true);
        protectedObject(child);

        try (var memory = new MemoryStore(data, token)) {
            memory.create(io.github.qianlixunbai.workspace.memory.MemoryItem.Type.PREFERENCE, "Synthetic", "Non-personal backup fixture");
            var backup = io.github.qianlixunbai.workspace.memory.MemoryBackup.export(memory);
            Path emptyPrivate = root.resolve("memory-empty");
            WindowsPrivateAcl.directory(emptyPrivate);
            for (Path restoredData : List.of(root.resolve("memory-restored"), emptyPrivate)) {
                new io.github.qianlixunbai.workspace.memory.MemoryBackupService(data, token).restore(backup, restoredData.toString());
                protectedObject(restoredData);
                protectedObject(restoredData.resolve("memory.db"));
                try (var restored = new MemoryStore(restoredData, token)) { assertEquals(backup.items(), restored.backupSnapshot()); }
            }
        }

        // Real SQLite-created rollback journals survive PERSIST close. Restart admits their exact
        // owner-only inherited ACL, while the general private-file gate remains strict.
        for (boolean isKnowledge : List.of(false, true)) {
            Path sqliteData = root.resolve(isKnowledge ? "sqlite-knowledge" : "sqlite-memory");
            try (var store = isKnowledge ? (AutoCloseable) new KnowledgeStore(sqliteData, token) : new MemoryStore(sqliteData, token)) {
                var field = store.getClass().getDeclaredField(isKnowledge ? "db" : "connection");
                field.setAccessible(true);
                var connection = (java.sql.Connection) field.get(store);
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode=PERSIST");
                    statement.execute("BEGIN IMMEDIATE");
                    statement.execute("CREATE TABLE acl_fixture(value)");
                    statement.execute("DROP TABLE acl_fixture");
                    statement.execute("COMMIT");
                }
            }
            Path journal = isKnowledge ? sqliteData.resolve("knowledge/knowledge.db-journal") : sqliteData.resolve("memory.db-journal");
            byte[] journalBefore = descriptor(journal);
            assertEquals(0, journalBefore[3] & 0x10);
            assertThrows(java.io.IOException.class, () -> WindowsPrivateAcl.verify(journal, false));
            WindowsPrivateAcl.verifySqliteSidecar(journal);
            assertArrayEquals(journalBefore, descriptor(journal)); // Admission never repairs the sidecar.
            try (var restarted = isKnowledge ? (AutoCloseable) new KnowledgeStore(sqliteData, token) : new MemoryStore(sqliteData, token)) { }
        }
        Path inherited = child.resolve("memory.db-wal");
        Files.createFile(inherited);
        WindowsPrivateAcl.verifySqliteSidecar(inherited);
        Files.createFile(child.resolve("ordinary-file"));
        assertThrows(java.io.IOException.class, () -> WindowsPrivateAcl.verifySqliteSidecar(child.resolve("ordinary-file")));
        var inheritedView = Files.getFileAttributeView(inherited, AclFileAttributeView.class);
        inheritedView.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(inheritedView.getOwner())
                .setPermissions(AclEntryPermission.READ_DATA).build()));
        assertThrows(java.io.IOException.class, () -> WindowsPrivateAcl.verifySqliteSidecar(inherited)); // An extra explicit ACE is not the SQLite contract.
        Files.createFile(unsafe.resolve("memory.db-shm"));
        assertThrows(java.io.IOException.class, () -> WindowsPrivateAcl.verifySqliteSidecar(unsafe.resolve("memory.db-shm")));

        // Exercise the actual helper directory handle, not an independently stronger test handle.
        var nativeAcl = Class.forName(WindowsPrivateAcl.class.getName() + "$NativeAcl");
        var open = nativeAcl.getDeclaredMethod("open", Path.class, boolean.class, boolean.class);
        open.setAccessible(true);
        var handle = (WinNT.HANDLE) open.invoke(null, child, true, true);
        try {
            var deletion = Kernel32.INSTANCE.CreateFile(child.toString(), 0x10000, 7, null, 3, 0x02200000, null);
            if (!WinBase.INVALID_HANDLE_VALUE.equals(deletion)) {
                Kernel32.INSTANCE.CloseHandle(deletion);
                fail("Directory DELETE open was not blocked");
            }
            assertEquals(32, Kernel32.INSTANCE.GetLastError());
            assertFalse(Kernel32.INSTANCE.MoveFile(child.toString(), unsafe.resolve("renamed").toString()));
            assertEquals(32, Kernel32.INSTANCE.GetLastError());
        } finally { assertTrue(Kernel32.INSTANCE.CloseHandle(handle)); }
        Files.move(child, unsafe.resolve("renamed")); // Pin lifetime ends with CloseHandle.

        // Confirm the actual SQLite module used by this integrated restart flow; configuration
        // strings alone do not prove that JDBC avoided its default extraction directory.
        ModuleSource modules = com.sun.jna.Native.load("kernel32", ModuleSource.class);
        var module = modules.GetModuleHandleW(new com.sun.jna.WString("sqlitejdbc.dll"));
        assertNotNull(module);
        char[] buffer = new char[32768];
        int length = modules.GetModuleFileNameW(module, buffer, buffer.length);
        assertTrue(length > 0 && length < buffer.length);
        Path sqliteModule = Path.of(new String(buffer, 0, length));
        assertEquals(Path.of(System.getProperty("org.sqlite.lib.path"), "sqlitejdbc.dll"), sqliteModule);
        assertEquals("7d3281c7adbf3d2fe6b07462dee9cb310194c302154175f172ced3aac05c91c3",
                HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sqliteModule))));
    }

    interface ModuleSource extends com.sun.jna.win32.StdCallLibrary {
        WinDef.HMODULE GetModuleHandleW(com.sun.jna.WString name);
        int GetModuleFileNameW(WinDef.HMODULE module, char[] path, int length);
    }

    // Independent binary SECURITY_DESCRIPTOR_RELATIVE readback, not Java getAcl equality.
    static byte[] descriptor(Path path) throws Exception {
        var kernel = Kernel32.INSTANCE;
        var handle = kernel.CreateFile(path.toString(), 0x20000, 7, null, 3, 0x02200000, null);
        assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
        PointerByReference sd = new PointerByReference();
        try {
            assertEquals(0, Advapi32.INSTANCE.GetSecurityInfo(handle, 1, 5, null, null, null, null, sd));
            Pointer pointer = sd.getValue();
            int owner = pointer.getInt(4), acl = pointer.getInt(16);
            assertTrue(owner >= 20 && acl >= 20);
            int ownerEnd = owner + 8 + Byte.toUnsignedInt(pointer.getByte(owner + 1)) * 4;
            int aclEnd = acl + Short.toUnsignedInt(pointer.getShort(acl + 2));
            return pointer.getByteArray(0, Math.max(ownerEnd, aclEnd));
        } finally {
            if (sd.getValue() != null) assertNull(kernel.LocalFree(sd.getValue()));
            assertTrue(kernel.CloseHandle(handle));
        }
    }
    static void protectedObject(Path path) throws Exception {
        byte[] bytes = descriptor(path);
        int control = Byte.toUnsignedInt(bytes[2]) | Byte.toUnsignedInt(bytes[3]) << 8;
        assertEquals(0x1004, control & 0x1004);
    }
    static void protectFixtureDacl(Path path) throws Exception {
        var kernel = Kernel32.INSTANCE;
        var handle = kernel.CreateFile(path.toString(), 0x60000, 7, null, 3, 0x02200000, null);
        assertNotEquals(WinBase.INVALID_HANDLE_VALUE, handle);
        PointerByReference dacl = new PointerByReference(), sd = new PointerByReference();
        try {
            assertEquals(0, Advapi32.INSTANCE.GetSecurityInfo(handle, 1, 4, null, null, dacl, null, sd));
            assertNotNull(dacl.getValue());
            assertEquals(0, Advapi32.INSTANCE.SetSecurityInfo(handle, 1, 0x80000004, null, null, dacl.getValue(), null));
        } finally {
            if (sd.getValue() != null) assertNull(kernel.LocalFree(sd.getValue()));
            assertTrue(kernel.CloseHandle(handle));
        }
    }
}
