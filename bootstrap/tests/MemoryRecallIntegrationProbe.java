import io.github.qianlixunbai.workspace.memory.PrivateMemoryDirectory;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

/** Manual Windows fixture entry; uses the unchanged native gate and real owning-layer tests. */
public final class MemoryRecallIntegrationProbe {
    public static final class PrivateTemp implements TempDirFactory {
        public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws Exception {
            Path path = WindowsNativeGate.profileRoot().resolve("evidence").resolve("memory-test-" + UUID.randomUUID());
            PrivateMemoryDirectory.directory(path);
            return path;
        }
    }
    public static void main(String[] args) throws Exception {
        WindowsNativeGate.admit();
        WindowsNativeGate.profileRoot();
        System.out.println("MEMORY_RECALL_NATIVE_ADMITTED");
        boolean postgresql = System.getProperty("memory.postgresql.fixture") != null;
        var builder = LauncherDiscoveryRequestBuilder.request();
        if (postgresql) builder.selectors(selectMethod("io.github.qianlixunbai.workspace.backup.MemoryPostgresqlLifecycleTest",
                "freshPostgresqlPreservesReviewedMemoryLifecycleAndConversationReferences"));
        else builder.selectors(selectMethod("io.github.qianlixunbai.workspace.memory.MemoryStoreTest",
                                "recallRanksCurrentSourcesAndPreservesExplicitExactAdmission"),
                        selectMethod("io.github.qianlixunbai.workspace.api.RuntimeApiTest", "nativeMemoryCrudContractAndPrivateErrors"),
                        selectMethod("io.github.qianlixunbai.workspace.api.RuntimeApiTest", "nativeCandidateUsesFixedSchemaWithoutFallbackOrMemoryWrites"),
                        selectMethod("io.github.qianlixunbai.workspace.api.RuntimeApiTest", "memoryAuthorizationAndEveryMutationBodyAreBounded"));
        var request = builder
                .configurationParameter("junit.jupiter.tempdir.factory.default", PrivateTemp.class.getName())
                .configurationParameter("junit.jupiter.tempdir.cleanup.mode.default", "NEVER")
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build();
        var summary = new SummaryGeneratingListener();
        try (var session = LauncherFactory.openSession()) { session.getLauncher().execute(request, summary); }
        var result = summary.getSummary();
        System.out.printf("MEMORY_RECALL_TESTS found=%d passed=%d failed=%d aborted=%d skipped=%d%n",
                result.getTestsFoundCount(), result.getTestsSucceededCount(), result.getTestsFailedCount(),
                result.getTestsAbortedCount(), result.getTestsSkippedCount());
        for (var failure : result.getFailures()) {
            Throwable error = failure.getException();
            System.out.println("FAILURE " + failure.getTestIdentifier().getDisplayName() + " " + error.getClass().getSimpleName());
            for (var frame : error.getStackTrace()) if (frame.getClassName().startsWith("io.github.qianlixunbai"))
                System.out.println("FRAME " + frame); // No private assertion messages or values.
        }
        System.exit(result.getTestsSucceededCount() == (postgresql ? 1 : 4) && result.getTotalFailureCount() == 0 ? 0 : 10);
    }
}
