package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import org.springframework.core.env.StandardEnvironment;
import java.io.*;
import java.util.Arrays;

/** One native-owned, bounded pipe exchange. No HTTP listener, Spring context, migration or password arguments. */
public final class NativeDatabaseSetup {
    private NativeDatabaseSetup() {}
    public static int run(String[] runtimeArguments, InputStream input, PrintStream output) {
        byte[] configuration = null;
        String failure = "ADMISSION_DENIED";
        try {
            WindowsNativeGate.admit();
            WindowsNativeGate.requireRuntimeArguments(runtimeArguments);
            WindowsNativeGate.profileRoot(); // Also refuses this Windows-only entry on other platforms.
            reply(output, "ADMITTED"); // WPF sends no secret until this direct child's admission succeeds.
            failure = "CONFIGURATION_UNTRUSTED";
            String action = new String(input.readNBytes(6), java.nio.charset.StandardCharsets.US_ASCII);
            if (!action.equals("CHECK\n") && !action.equals("SAVE!\n") && !action.equals("STATE\n")) throw new IOException();
            configuration = input.readNBytes(16385);
            if (action.equals("STATE\n")) {
                if (configuration.length != 0) throw new IOException();
                reply(output, configured() ? "CONFIGURED" : "NOT_CONFIGURED");
                return 0;
            }
            if (action.equals("SAVE!\n") && configured()) {
                reply(output, "ALREADY_CONFIGURED"); return 0;
            }
            var source = TrustedDatabaseConfiguration.parse(configuration);
            var environment = new StandardEnvironment();
            // Reuse the real read-only connection / role / V1+V2 schema preflight. No Flyway.
            TrustedDatabaseConfiguration.install(environment, source);
            if (action.equals("SAVE!\n")) {
                TrustedDatabaseConfiguration.create(configuration); // Original Gate / DPAPI / private CREATE_NEW contract.
                var loaded = TrustedDatabaseConfiguration.load();
                for (String field : new String[]{"url", "username", "password"})
                    if (!java.util.Objects.equals(source.getProperty("workspace.persistence.postgresql." + field),
                            loaded.getProperty("workspace.persistence.postgresql." + field))) throw new IOException();
                reply(output, "SAVED");
            } else reply(output, "READY");
            return 0;
        } catch (Exception | LinkageError rejected) {
            if (rejected instanceof TrustedDatabaseConfiguration.Failure safe) failure = safe.reason().name();
            reply(output, failure); // Never print exception messages, causes, SQL, paths or supplied values.
            return 10;
        } finally { if (configuration != null) Arrays.fill(configuration, (byte) 0); }
    }
    private static boolean configured() throws IOException {
        return TrustedDatabaseConfiguration.load().getProperty("workspace.persistence.backend") != null;
    }
    private static void reply(PrintStream output, String code) {
        output.println("WORKSPACE_DATABASE_V1\t" + code); output.flush();
    }
}
