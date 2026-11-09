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
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Opt-in adapter for the existing MemoryRecallIntegrationProbe native host. Always fresh
 * synthetic data, no runtime/server/model/PG/activation. Compile separately from the memory adapter. */
public final class MemoryRecallIntegrationProbe {
    public static final class PrivateTemp implements TempDirFactory {
        public Path createTempDirectory(AnnotatedElementContext element,ExtensionContext context)throws Exception{
            Path path=WindowsNativeGate.profileRoot().resolve("evidence").resolve("ke-"+UUID.randomUUID().toString().replace("-","").substring(0,12));
            PrivateMemoryDirectory.directory(path);return path;
        }
    }
    public static void main(String[] args)throws Exception{
        WindowsNativeGate.admit();WindowsNativeGate.profileRoot();System.out.println("KNOWLEDGE_ENCRYPTED_SNAPSHOT_NATIVE_ADMITTED");
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("io.github.qianlixunbai.workspace.knowledge.SyntheticKnowledgeSnapshotTest"),
                selectClass("io.github.qianlixunbai.workspace.knowledge.KnowledgeBackupTest"),
                selectClass("io.github.qianlixunbai.workspace.backup.OpenPgpStreamsTest"))
            .configurationParameter("junit.jupiter.tempdir.factory.default",PrivateTemp.class.getName())
            .configurationParameter("junit.jupiter.tempdir.cleanup.mode.default","NEVER")
            .configurationParameter("junit.jupiter.execution.parallel.enabled","false").build();
        var summary=new SummaryGeneratingListener();
        try(var session=LauncherFactory.openSession()){session.getLauncher().execute(request,summary);}
        var result=summary.getSummary();
        System.out.printf("KNOWLEDGE_ENCRYPTED_SNAPSHOT_TESTS found=%d passed=%d failed=%d aborted=%d skipped=%d%n",
                result.getTestsFoundCount(),result.getTestsSucceededCount(),result.getTestsFailedCount(),result.getTestsAbortedCount(),result.getTestsSkippedCount());
        for(var failure:result.getFailures()){
            System.out.println("FAILURE "+failure.getTestIdentifier().getDisplayName()+" "+failure.getException().getClass().getSimpleName());
            for(Throwable error=failure.getException();error!=null;error=error.getCause())
                for(var frame:error.getStackTrace())if(frame.getClassName().startsWith("io.github.qianlixunbai"))System.out.println("FRAME "+frame);
        }
        System.exit(result.getTestsSucceededCount()==16&&result.getTotalFailureCount()==0?0:10);
    }
}
