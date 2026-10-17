import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import java.nio.file.*;
import java.util.List;

/** Isolated admission/preflight probe. Never calls application main, Spring, HTTP or model code. */
public final class ProfileNativeGateProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("PRELOAD_READY");
        System.out.flush();
        System.in.read();
        boolean admitted = false;
        try {
            WindowsNativeGate.admit();
            Path root = WindowsNativeGate.profileRoot();
            String[] runtime = { "--server.address=127.0.0.1", "--server.port=18765",
                "--workspace.security.token-file=" + root.resolve("credentials/client-token"),
                "--workspace.data-directory=" + root.resolve("data"),
                "--workspace.model-state-directory=" + root.resolve("model-state") };
            WindowsNativeGate.requireRuntimeArguments(runtime);
            if (args.length == 1 && args[0].equals("preflight")) {
                for (String directory : List.of("credentials", "model-state", "data", "webview2-profile",
                        "chrome-profile", "browser-test-extension", "logs")) {
                    try (var entries = Files.list(root.resolve(directory))) {
                        if (entries.findAny().isPresent()) throw new java.io.IOException();
                    }
                }
            }
            // Production-port and caller-root arguments never enter Spring.
            runtime[1] = "--server.port=8765";
            try { WindowsNativeGate.requireRuntimeArguments(runtime); throw new AssertionError(); }
            catch (java.io.IOException expected) { }
            admitted = true;
            System.out.println("ADMITTED_DEVELOPMENT_ONLY");
        } catch (java.io.IOException | LinkageError rejected) {
            System.out.println("STOP");
        }
        System.out.println("READY");
        System.out.flush();
        System.in.read();
        if (!admitted) System.exit(10);
    }
}
