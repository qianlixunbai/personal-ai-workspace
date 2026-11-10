package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.IOException;
import java.nio.file.*;

/** Called under the ordinary runtime lease, before any credentials or Spring startup.
 * A historical maintenance operation is never an activation capability. Until a native
 * ownership handoff exists, even a matching published configuration remains fenced. */
public final class MaintenanceStartupFence {
    private MaintenanceStartupFence() {}
    public static void requireNoPendingOperation(Path root) throws IOException {
        for(String registry:new String[]{"sqlite-maintenance","sqlite-configuration"}) {
            Path directory=root.resolve("evidence").resolve(registry);
            try{Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
            catch(NoSuchFileException absent){continue;}
            WindowsPrivateAcl.verify(directory,true);
            try(var entries=Files.list(directory)) {
                if(entries.anyMatch(p->!registry.equals("sqlite-maintenance")||!p.getFileName().toString().equals("owner.lock")))
                    throw new IOException("Maintenance activation incomplete; native ownership handoff required");
            }
        }
    }
}
