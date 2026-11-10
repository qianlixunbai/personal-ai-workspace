package io.github.qianlixunbai.workspace;

import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import java.io.*;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Ordinary desktop-owned process lifetime. This protocol grants no maintenance authority.
 * The parent's private pipes bind one launch; EOF, cancellation and a successful old launch
 * never stand in for this launch's CLOSE / CONTEXT_CLOSED / actual process exit sequence. */
public final class NativeRuntimeLifecycle {
    private static volatile boolean entered, serving;
    private NativeRuntimeLifecycle() {}
    public static boolean entered() { return entered; }
    public static boolean requestsAllowed() { return !entered || serving; }

    public static int run(String[] args, InputStream rawInput, PrintStream rawOutput) {
        if (entered) return 10;
        entered = true;
        var input = new DataInputStream(rawInput);
        var output = new DataOutputStream(rawOutput);
        // Reserve stdout exclusively for bounded protocol messages, including startup failures.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(System.out);
        ConfigurableApplicationContext context = null;
        String failureCode = "NATIVE_ADMISSION_REJECTED";
        try {
            WindowsNativeGate.admit();
            WindowsNativeGate.requireRuntimeArguments(args); // Includes the unchanged maintenance fence.
            String profile = WindowsNativeGate.profileRoot().getFileName().toString();
            String challenge = HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
            long pid = ProcessHandle.current().pid();
            output.writeUTF("WORKSPACE_NATIVE_LIFECYCLE_V1");
            output.writeUTF(profile); output.writeLong(pid); output.writeUTF(challenge); output.flush();
            String attempt = input.readUTF();
            if (!attempt.matches("[a-f0-9]{32}") || !challenge.equals(input.readUTF())) throw new IOException();
            failureCode = "RUNTIME_START_FAILED";
            context = PersonalAiWorkspaceApplication.startRuntime(args);
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            if (!context.isActive() || port < 1 || port > 65535 || port == 18765) throw new IOException();
            serving = true;
            output.writeUTF("READY"); output.writeUTF(attempt); output.writeUTF(challenge);
            output.writeInt(port); output.flush();
            if (!"CLOSE".equals(input.readUTF()) || !attempt.equals(input.readUTF())
                    || !challenge.equals(input.readUTF())) throw new IOException();
            serving = false; // Reject new HTTP work before closing the real application context.
            context.close();
            if (context.isActive()) throw new IOException();
            output.writeUTF("CONTEXT_CLOSED"); output.writeUTF(attempt); output.writeUTF(challenge); output.flush();
            // System.exit in main terminates the JVM only after the context close returns.
            // External providers are outside this receipt's scope, even when exit code is zero.
            return 0;
        } catch (Exception | LinkageError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.Failure safe) {
                    failureCode = safe.reason().name(); break;
                }
            try { output.writeUTF("STOP"); output.writeUTF(failureCode); output.flush(); } catch (IOException ignored) {}
            return 10;
        } finally {
            serving = false;
            if (context != null) try { context.close(); } catch (Exception ignored) {}
        }
    }
}
