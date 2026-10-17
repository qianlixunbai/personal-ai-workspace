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

    @Test void trustedInitializerPrecedesConditionalDataSourceAndMapsOnlyObservedStartupFailures() throws Exception {
        var source = parse(bytes(CONFIG));
        var ready = new StorageStatusService.Status("2026-10-18T00:00:00Z", "POSTGRESQL", "TRUSTED_LOCAL", true,
            "CONNECTED", "V1_V2_READY", "READY", false, "DISABLED", "EXPLICIT_APPROVAL_REQUIRED", "MAINTENANCE_ONLY",
            "BLOCKED_SECURITY_REQUIREMENTS", List.of(), "NONE");
        try (var inspection = mockConstruction(StorageStatusService.class, (mock, context) -> when(mock.inspect()).thenReturn(ready))) {
            var app = new SpringApplication(WorkspacePostgresqlConfiguration.class);
            app.setWebApplicationType(WebApplicationType.NONE); app.setLogStartupInfo(false);
            app.addInitializers(context -> {
                try { install(context.getEnvironment(), source); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            try (var context = app.run()) {
                assertTrue(trusted(context.getEnvironment()));
                var ds = context.getBean("workspacePostgresqlDataSource", PGSimpleDataSource.class);
                assertEquals("workspace_app", ds.getUser()); assertEquals("workspace_fixture", ds.getDatabaseName());
                assertFalse(context.getEnvironment().getProperty("workspace.finance.enabled", Boolean.class, false));
            }
        }
        for (var pair : Map.of("AUTHENTICATION_FAILED", Reason.AUTHENTICATION_FAILED, "CONNECTION_FAILED", Reason.CONNECTION_FAILED,
                "ROLE_REJECTED", Reason.ROLE_REJECTED, "CONNECTED", Reason.SCHEMA_INITIALIZATION_REQUIRED).entrySet()) {
            var failed = new StorageStatusService.Status(ready.checkedAt(), ready.backend(), ready.configurationTrust(), true,
                pair.getKey(), "INITIALIZATION_REQUIRED", "NOT_CHECKED", false, "DISABLED", ready.migration(), ready.encryptedBackup(), ready.restore(), List.of(), pair.getKey());
            try (var inspection = mockConstruction(StorageStatusService.class, (mock, context) -> when(mock.inspect()).thenReturn(failed))) {
                assertEquals(pair.getValue(), assertThrows(Failure.class, () -> install(new MockEnvironment(), source)).reason());
            }
        }
    }
}
