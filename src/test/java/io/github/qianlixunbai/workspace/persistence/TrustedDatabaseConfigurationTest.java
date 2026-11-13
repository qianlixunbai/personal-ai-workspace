package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;
import org.postgresql.ds.PGSimpleDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.*;

/** Deterministic contract tests. Admission, OS ACL/identity and DPAPI are mocked, never Windows E2E evidence. */
class TrustedDatabaseConfigurationTest {
    @TempDir Path root;
    private static final String CONFIG = """
        {"format":1,"backend":"postgresql","host":"127.0.0.1","port":5432,
         "database":"workspace_fixture","username":"workspace_app","password":"synthetic-secret"}
        """;
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private void directories() throws IOException { Files.createDirectory(root.resolve("data")); Files.createDirectory(root.resolve("credentials")); }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void earlyRuntimeLeaseExcludesMaintenanceBeforeCredentialsAndCannotReopenAfterLoss() throws Exception {
        // A new @TempDir tree and real JDK/Windows sharing only. Cache a synthetic admitted root
        // and mock ACL/identity primitives: no helper/JNA/DPAPI, real Profile, Spring or database.
        Path profile = Files.createDirectories(root.resolve("Profiles").resolve("4".repeat(32)));
        Path evidence = Files.createDirectory(profile.resolve("evidence"));
        Path file = evidence.resolve("runtime-admission.lock");
        var rootField = WindowsNativeGate.class.getDeclaredField("admittedRoot"); rootField.setAccessible(true);
        var leaseField = WindowsNativeGate.class.getDeclaredField("runtimeLease"); leaseField.setAccessible(true);
        assertNull(rootField.get(null)); assertNull(leaseField.get(null));
        String[] arguments = { "--server.address=127.0.0.1", "--server.port=18765",
            "--workspace.security.token-file=" + profile.resolve("credentials/client-token"),
            "--workspace.data-directory=" + profile.resolve("data"),
            "--workspace.model-state-directory=" + profile.resolve("model-state") };
        try (var acl = mockStatic(WindowsPrivateAcl.class); var identities = mockStatic(Identity.class)) {
            acl.when(() -> WindowsPrivateAcl.createFile(file)).thenAnswer(call -> { Files.createFile(file); return null; });
            identities.when(() -> Identity.read(file, false)).thenReturn("synthetic-file-identity");
            rootField.set(null, profile); // Bypass only native admission in this isolated test JVM.
            assertEquals(profile, WindowsNativeGate.profileRoot());
            var owned = (NativeProfileRecovery.RuntimeLease)leaseField.get(null);
            assertNotNull(owned); assertEquals(0, Files.size(file));
            WindowsNativeGate.requireRuntimeArguments(arguments);
            try (var secondReader = NativeProfileRecovery.acquireRuntimeLease(profile)) {
                secondReader.requireHeld(profile); // Runtime and native setup may coexist as before.
                assertThrows(IOException.class, () -> java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE,
                        com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE));
                assertThrows(IOException.class, () -> Files.move(file, evidence.resolve("moved.lock")));
                identities.when(() -> Identity.read(file, false)).thenReturn("synthetic-replacement");
                assertThrows(IOException.class, () -> owned.requireHeld(profile));
                identities.when(() -> Identity.read(file, false)).thenReturn("synthetic-file-identity");
            }
            owned.close();
            assertThrows(IOException.class, WindowsNativeGate::profileRoot); // Never reacquire a lost cached lease.
            leaseField.set(null, null); // Only the fixture simulates a different process.
            try (var maintenance = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                var output = new java.io.ByteArrayOutputStream();
                var input = new java.io.ByteArrayInputStream(bytes("SAVE!\n" + CONFIG));
                assertEquals(10, NativeDatabaseSetup.run(arguments, input, new java.io.PrintStream(output, true, StandardCharsets.UTF_8)));
                assertEquals(bytes("SAVE!\n" + CONFIG).length, input.available());
                assertEquals("WORKSPACE_DATABASE_V1\tADMISSION_DENIED" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
                assertNull(leaseField.get(null)); // Failed acquisition grants nothing and retains no half lease.
                assertEquals(0, maintenance.size());
            }
            assertEquals(profile, WindowsNativeGate.profileRoot());
            ((NativeProfileRecovery.RuntimeLease)leaseField.get(null)).close(); leaseField.set(null, null);
            Files.writeString(file, "synthetic malformed lock");
            assertThrows(IOException.class, WindowsNativeGate::profileRoot);
            assertEquals("synthetic malformed lock", Files.readString(file));
            assertNull(leaseField.get(null));
            assertFalse(Files.exists(profile.resolve("data")));
            assertFalse(Files.exists(profile.resolve("credentials")));
        } finally {
            var lease = (NativeProfileRecovery.RuntimeLease)leaseField.get(null);
            if (lease != null) lease.close();
            leaseField.set(null, null); rootField.set(null, null);
        }
    }

    @Test void admissionAndAclFailuresNeverReachCredentialsAndReadPinsRejectReplacement() throws Exception {
        directories(); Path file = root.resolve("data/database.dpapi"); Files.write(file, new byte[]{1,2,3});
        try (var gate = mockStatic(WindowsNativeGate.class); var acl = mockStatic(WindowsPrivateAcl.class);
             var dpapi = mockStatic(Protection.class); var identities = mockStatic(Identity.class)) {
            identities.when(() -> Identity.read(any(), anyBoolean())).thenAnswer(call -> call.getArgument(0).toString());
            gate.when(WindowsNativeGate::profileRoot).thenThrow(new IOException("synthetic private detail"));
            assertThrows(IOException.class, TrustedDatabaseConfiguration::load);
            assertThrows(IOException.class, () -> create(bytes(CONFIG)));
            acl.verifyNoInteractions(); dpapi.verifyNoInteractions();
            gate.when(WindowsNativeGate::profileRoot).thenReturn(root);
            acl.when(() -> WindowsPrivateAcl.verify(root, true)).thenThrow(new IOException("ACL rejected"));
            assertEquals(Reason.CONFIGURATION_UNTRUSTED, assertThrows(Failure.class, TrustedDatabaseConfiguration::load).reason());
            dpapi.verifyNoInteractions(); acl.reset();
            // The file is already pinned when its ACL is checked. Replacement fails on Windows.
            acl.when(() -> WindowsPrivateAcl.verify(file, false)).thenAnswer(call -> {
                Files.move(file, file.resolveSibling("replacement")); throw new IOException("Replacement attempted");
            });
            assertEquals(Reason.CONFIGURATION_UNTRUSTED, assertThrows(Failure.class, TrustedDatabaseConfiguration::load).reason());
            dpapi.verifyNoInteractions();
            acl.reset();
            identities.when(() -> Identity.read(root, true)).thenReturn("original-root", "replaced-root");
            assertEquals(Reason.CONFIGURATION_UNTRUSTED, assertThrows(Failure.class, TrustedDatabaseConfiguration::load).reason());
            dpapi.verifyNoInteractions();
        }
    }

    @Test void fixedPrivateReadIsStrictAndMissingSelectionNeverFallsBackFromPostgresql() throws Exception {
        directories();
        try (var gate = mockStatic(WindowsNativeGate.class); var acl = mockStatic(WindowsPrivateAcl.class);
             var dpapi = mockStatic(Protection.class); var identities = mockStatic(Identity.class)) {
            identities.when(() -> Identity.read(any(), anyBoolean())).thenAnswer(call -> call.getArgument(0).toString());
            gate.when(WindowsNativeGate::profileRoot).thenReturn(root);
            var empty = load(); var environment = new MockEnvironment(); install(environment, empty);
            assertEquals("sqlite", environment.getProperty("workspace.persistence.backend", "sqlite"));
            assertFalse(environment.getProperty("workspace.finance.enabled", Boolean.class, false));
            environment.withProperty("workspace.persistence.backend", "postgresql");
            assertEquals(Reason.POSTGRESQL_NOT_CONFIGURED, assertThrows(Failure.class, () -> install(environment, empty)).reason());
            var external = new MockEnvironment().withProperty("workspace.persistence.postgresql.url", "jdbc:postgresql://127.0.0.1:5432/untrusted");
            assertEquals(Reason.CONFIGURATION_UNTRUSTED, assertThrows(Failure.class, () -> install(external, empty)).reason());
            dpapi.verifyNoInteractions();
            Path file = root.resolve("data/database.dpapi");
            dpapi.when(() -> Protection.protect(any())).thenReturn(new byte[]{1,2,3});
            acl.when(() -> WindowsPrivateAcl.createFile(file)).thenAnswer(call -> { Files.createFile(file); return null; });
            create(bytes(CONFIG));
            assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(file));
            assertThrows(Failure.class, () -> create(bytes(CONFIG))); // Existing credentials must never be overwritten.
            assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(file));
            dpapi.when(() -> Protection.unprotect(any())).thenAnswer(call -> bytes(CONFIG));
            var source = load(); assertEquals("postgresql", source.getProperty("workspace.persistence.backend"));
            assertEquals("synthetic-secret", source.getProperty("workspace.persistence.postgresql.password"));
            assertFalse(source.toString().contains("synthetic-secret")); assertFalse(source.getSource().toString().contains("synthetic-secret"));
            dpapi.when(() -> Protection.unprotect(any())).thenThrow(new Failure(Reason.CREDENTIAL_UNAVAILABLE));
            assertEquals(Reason.CREDENTIAL_UNAVAILABLE, assertThrows(Failure.class, TrustedDatabaseConfiguration::load).reason());
        }
        for (String invalid : List.of(CONFIG.replace("\"format\":1", "\"format\":4294967297"),
                CONFIG.replace("\"format\":1", "\"format\":2"), CONFIG.replace("127.0.0.1", "localhost"),
                CONFIG.replace("workspace_app", "postgres"), CONFIG.replace("workspace_fixture", "../fixture"),
                CONFIG.replace("\"format\":1", "\"format\":1,\"unknown\":false"),
                CONFIG.replace("\"format\":1", "\"format\":1,\"format\":1"), CONFIG + " {}"))
            assertEquals(Reason.CONFIGURATION_UNTRUSTED, assertThrows(Failure.class, () -> parse(bytes(invalid))).reason());
        assertEquals(Reason.CREDENTIAL_UNAVAILABLE, assertThrows(Failure.class, () -> parse(bytes(CONFIG.replace("synthetic-secret", "")))).reason());
    }

}
