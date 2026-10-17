package io.github.qianlixunbai.workspace.persistence;

import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.*;

/** One opt-in real Windows flow, using the existing probe's exact genuine admission contract.
 * No mocks, alternate admission, reflection, credentials in arguments, or real database.
 * A separate-account logon/decryption test is deliberately not claimed by this flow. */
public final class TrustedWindowsConfigurationProbe {
    private TrustedWindowsConfigurationProbe() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IOException("NO_CONFIGURATION_PROBE_ARGUMENTS_ALLOWED");
        WindowsNativeGate.admit();
        Path root = WindowsNativeGate.profileRoot();
        WindowsNativeGate.requireRuntimeArguments(new String[]{"--server.address=127.0.0.1", "--server.port=18765",
            "--workspace.security.token-file=" + root.resolve("credentials/client-token"),
            "--workspace.data-directory=" + root.resolve("data"),
            "--workspace.model-state-directory=" + root.resolve("model-state")});
        System.out.println("REAL_JAVA_NATIVE_GATE_ADMITTED_DEVELOPMENT_ONLY");
        verify();
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("TRUSTED_CONFIGURATION_CONTRACT_FAILED");
    }
    private static void rejected(Checked operation, TrustedDatabaseConfiguration.Reason reason) throws Exception {
        try { operation.run(); }
        catch (TrustedDatabaseConfiguration.Failure failure) { require(failure.reason() == reason); return; }
        throw new AssertionError("EXPECTED_CONFIGURATION_REJECTION");
    }
    private interface Checked { void run() throws Exception; }

    public static void verify() throws Exception {
        // Derive everything from the actual Gate. No caller-supplied root or admission state.
        Path root = WindowsNativeGate.profileRoot(), data = root.resolve("data"), file = data.resolve("database.dpapi");
        try (var entries = Files.list(data)) { require(entries.findAny().isEmpty()); }
        WindowsPrivateAcl.verify(root, true); WindowsPrivateAcl.verify(data, true);
        var missing = TrustedDatabaseConfiguration.load();
        var defaults = new StandardEnvironment();
        TrustedDatabaseConfiguration.install(defaults, missing);
        require(defaults.getProperty("workspace.persistence.backend", "sqlite").equals("sqlite"));
        require(!defaults.getProperty("workspace.finance.enabled", Boolean.class, false));
        // An explicit PostgreSQL selection is rejected before connection or fallback, without a file.
        var explicit = new StandardEnvironment();
        explicit.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
            "explicitSyntheticSelection", java.util.Map.of("workspace.persistence.backend", "postgresql")));
        rejected(() -> TrustedDatabaseConfiguration.install(explicit, missing),
            TrustedDatabaseConfiguration.Reason.POSTGRESQL_NOT_CONFIGURED);
        System.out.println("MISSING_CONFIGURATION_FAIL_CLOSED_PASS");

        byte[] random = new byte[32], plaintext = null, encrypted = null;
        String identity = null;
        boolean creationAttempted = false;
        // A loopback socket only closes one attempted JDBC connection; it is not a database.
        // Keeping the port bound prevents accidentally contacting an unrelated local service.
        try (var endpoint = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            endpoint.setSoTimeout(10000);
            new SecureRandom().nextBytes(random);
            String password = HexFormat.of().formatHex(random);
            plaintext = ("{\"format\":1,\"backend\":\"postgresql\",\"host\":\"127.0.0.1\",\"port\":"
                + endpoint.getLocalPort() + ",\"database\":\"workspace_b4a4_fixture\",\"username\":\"workspace_b4a4_app\",\"password\":\""
                + password + "\"}").getBytes(StandardCharsets.UTF_8);
            creationAttempted = true;
            TrustedDatabaseConfiguration.create(plaintext);
            identity = TrustedDatabaseConfiguration.Identity.read(file, false);
            WindowsPrivateAcl.verify(file, false);
            encrypted = Files.readAllBytes(file);
            require(!Arrays.equals(plaintext, encrypted));
            System.out.println("REAL_DPAPI_CURRENT_USER_ENCRYPT_AND_PRIVATE_FILE_PASS");
            var source = TrustedDatabaseConfiguration.load();
            require(password.equals(source.getProperty("workspace.persistence.postgresql.password")));
            require(!source.toString().contains(password) && !source.getSource().toString().contains(password));
            require(identity.equals(TrustedDatabaseConfiguration.Identity.read(file, false)));
            System.out.println("REAL_DPAPI_CURRENT_USER_DECRYPT_AND_IDENTITY_PASS");
            final byte[] input = plaintext;
            rejected(() -> TrustedDatabaseConfiguration.create(input), TrustedDatabaseConfiguration.Reason.CONFIGURATION_UNTRUSTED);
            require(Arrays.equals(encrypted, Files.readAllBytes(file)));
            require(identity.equals(TrustedDatabaseConfiguration.Identity.read(file, false)));
            System.out.println("CREATE_NEW_NO_OVERWRITE_PASS");

            // Exercise the real loader against an actual competing writer, without interception.
            try (var writer = FileChannel.open(file, StandardOpenOption.WRITE)) {
                rejected(TrustedDatabaseConfiguration::load, TrustedDatabaseConfiguration.Reason.CONFIGURATION_UNTRUSTED);
            }
            // Check the exact production sharing flags against a real rename attempt.
            Path replacement = data.resolve("database-replacement-test.dpapi");
            try (var pin = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)) {
                boolean denied = false;
                try { Files.move(file, replacement); } catch (IOException expected) { denied = true; }
                require(denied && Files.notExists(replacement, LinkOption.NOFOLLOW_LINKS));
            }
            require(identity.equals(TrustedDatabaseConfiguration.Identity.read(file, false)));
            System.out.println("REAL_WRITER_REJECTED_AND_RENAME_SHARING_DENIED_PASS");

            ExecutorService peer = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> connection = peer.submit(() -> { try (var socket = endpoint.accept()) { return true; } });
                try (var context = new GenericApplicationContext()) {
                    context.setEnvironment(new StandardEnvironment());
                    // Invoke the same production initializer operation; no refresh or DataSource
                    // construction after rejection, and no fabricated ready/schema response.
                    org.springframework.context.ApplicationContextInitializer<GenericApplicationContext> initializer = ctx -> {
                        try { TrustedDatabaseConfiguration.install(ctx.getEnvironment(), source); }
                        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    };
                    boolean failedClosed = false;
                    try { initializer.initialize(context); }
                    catch (java.io.UncheckedIOException failure) {
                        require(failure.getCause() instanceof TrustedDatabaseConfiguration.Failure controlled
                            && controlled.reason() == TrustedDatabaseConfiguration.Reason.CONNECTION_FAILED);
                        failedClosed = true;
                    }
                    require(failedClosed && TrustedDatabaseConfiguration.trusted(context.getEnvironment()));
                    require(context.getEnvironment().getProperty("workspace.persistence.backend").equals("postgresql"));
                    require(!context.getEnvironment().getProperty("workspace.finance.enabled", Boolean.class, false));
                    require(context.getBeanFactory().getBeanDefinitionCount() == 0);
                    require(connection.get(10, TimeUnit.SECONDS));
                }
            } finally { peer.shutdownNow(); }
            System.out.println("REAL_PROPERTY_SOURCE_INSTALLED_INITIALIZER_CONNECTION_FAILED_CLOSED_PASS");
            System.out.println("DATASOURCE_SUCCESSFUL_ASSEMBLY_PENDING_NO_DATABASE");

            require(identity.equals(TrustedDatabaseConfiguration.Identity.read(file, false)));
            WindowsPrivateAcl.verify(file, false);
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)) {
                channel.truncate(0); channel.write(ByteBuffer.wrap(new byte[]{0})); channel.force(true);
            }
            rejected(TrustedDatabaseConfiguration::load, TrustedDatabaseConfiguration.Reason.CREDENTIAL_UNAVAILABLE);
            System.out.println("CORRUPT_CONFIGURATION_REJECTED_WITHOUT_FALLBACK_PASS");
            System.out.println("OTHER_ACCOUNT_DYNAMIC_DECRYPTION_PENDING");
        } finally {
            Arrays.fill(random, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            // Delete only the exact new synthetic file whose native identity and private ACL
            // remain known. Never delete an interrupted create with uncertain identity.
            if (identity != null) {
                WindowsPrivateAcl.verify(file, false);
                require(identity.equals(TrustedDatabaseConfiguration.Identity.read(file, false)));
                Files.delete(file);
                require(Files.notExists(file, LinkOption.NOFOLLOW_LINKS));
                System.out.println("SYNTHETIC_CREDENTIAL_FILE_REMOVED_PROFILE_RETAINED_PASS");
            } else if (creationAttempted && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                System.out.println("STOP_SYNTHETIC_FILE_RETAINED_IDENTITY_UNCERTAIN");
            }
        }
    }
}
