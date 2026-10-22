package io.github.qianlixunbai.workspace.finance;

import io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration;
import io.github.qianlixunbai.workspace.security.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Direct admitted native child, bounded stdin/stdout. No HTTP listener or WebView confirmation payload. */
public final class NativeFinanceSetup {
    private NativeFinanceSetup() { }
    public static int run(String[] arguments, InputStream input, PrintStream output) {
        byte[] configuration = null;
        String stage = "ADMISSION";
        try {
            WindowsNativeGate.admit(); WindowsNativeGate.requireRuntimeArguments(arguments);
            var root = WindowsNativeGate.profileRoot();
            reply(output, "ADMITTED");
            System.setOut(new PrintStream(OutputStream.nullOutputStream())); // Library banners must never enter the protocol.
            stage = "CONFIGURATION";
            String supplied = line(input, 24000);
            var existing = TrustedDatabaseConfiguration.loadFinance();
            boolean configured = Boolean.TRUE.equals(existing.getProperty("workspace.finance.enabled"));
            if (!supplied.equals("-")) {
                if (configured) throw new IOException(); // Existing connection is never overwritten.
                configuration = Base64.getDecoder().decode(supplied);
            }
            var source = configuration == null ? existing : TrustedDatabaseConfiguration.parseFinance(configuration);
            var environment = new StandardEnvironment();
            TrustedDatabaseConfiguration.install(environment, TrustedDatabaseConfiguration.load());
            TrustedDatabaseConfiguration.installFinance(environment, source);
            if (!environment.getProperty("workspace.finance.enabled", Boolean.class, false)) throw new IOException();
            environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("nativeFinancePaths", Map.of(
                    "workspace.data-directory", root.resolve("data").toString(), "workspace.security.token-file", root.resolve("credentials/client-token").toString())));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ClientIdentity.NATIVE,
                    null, List.of(new SimpleGrantedAuthority("ROLE_LOCAL_CLIENT"))));
            stage = "CONTEXT";
            try (var context = new AnnotationConfigApplicationContext()) {
                context.setEnvironment(environment); context.register(FinanceModuleConfiguration.class); context.refresh();
                var queries = context.getBean(FinanceAccountQueries.class);
                stage = "REVIEW";
                var review = queries.reviewHistoricalBinding();
                reply(output, "REVIEW\t" + Base64.getEncoder().encodeToString(JsonMapper.builder().build().writeValueAsBytes(review)));
                String confirmation = line(input, 32);
                if (!confirmation.matches("CONFIRM [0-9]{1,3}")) throw new IOException();
                int index = Integer.parseInt(confirmation.substring(8));
                if (index >= review.candidates().size()) throw new IOException();
                stage = "CONFIRMATION";
                // Confirmation selects an index in this child's server-owned snapshot, never a caller-provided user ID.
                if (configuration != null) TrustedDatabaseConfiguration.createFinance(configuration);
                queries.confirmHistoricalBinding(review.candidates().get(index).identity());
                reply(output, "BOUND");
            }
            return 0;
        } catch (Exception | LinkageError rejected) {
            reply(output, "REJECTED_" + stage); return 10;
        } finally {
            SecurityContextHolder.clearContext();
            if (configuration != null) Arrays.fill(configuration, (byte) 0);
        }
    }
    private static String line(InputStream input, int limit) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int b; (b = input.read()) != -1;) {
            if (b == '\n') return bytes.toString(StandardCharsets.UTF_8);
            if (b < 32 || b > 126 || bytes.size() == limit) throw new IOException();
            bytes.write(b);
        }
        throw new IOException();
    }
    private static void reply(PrintStream output, String value) { output.println("WORKSPACE_FINANCE_V1\t" + value); output.flush(); }
}
