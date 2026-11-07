import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

/** Opt-in fixture only. Native admission is identical to the existing integration probe. */
public final class MemoryDesktopIntegrationProbe {
    public static void main(String[] args) throws Exception {
        WindowsNativeGate.admit();
        WindowsNativeGate.profileRoot();
        if (!Boolean.getBoolean("memory.desktop.fixture")) throw new IllegalStateException();
        System.out.println("MEMORY_DESKTOP_NATIVE_ADMITTED");
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectMethod("io.github.qianlixunbai.workspace.api.RuntimeApiTest", "desktopMemoryWorkflowWithSyntheticProvider"))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build();
        var summary = new SummaryGeneratingListener();
        try (var session = LauncherFactory.openSession()) { session.getLauncher().execute(request, summary); }
        var result = summary.getSummary();
        System.out.printf("MEMORY_DESKTOP_TESTS found=%d passed=%d failed=%d skipped=%d%n",
                result.getTestsFoundCount(), result.getTestsSucceededCount(), result.getTestsFailedCount(), result.getTestsSkippedCount());
        for (var failure : result.getFailures()) {
            System.out.println("FAILURE " + failure.getException().getClass().getSimpleName());
            for (var frame : failure.getException().getStackTrace()) if (frame.getClassName().startsWith("io.github.qianlixunbai")) System.out.println(frame);
        }
        System.exit(result.getTestsSucceededCount() == 1 && result.getTotalFailureCount() == 0 ? 0 : 10);
    }
}
