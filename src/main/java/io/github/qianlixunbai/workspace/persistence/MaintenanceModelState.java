package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.model.ModelStateStore;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owns only the Profile's model selection/validation files. No runner, weights, remote
 * service or previous execution can be adopted by these file handles. Pending/guard state
 * is preserved and rejected. The actual ModelStateStore must acquire its normal writer. */
final class MaintenanceModelState implements AutoCloseable {
    private final Path root;
    private final SqliteSourceLease.DirectoryPin directory;
    private final Map<String,SqliteSourceLease.FilePin> files=new TreeMap<>();
    private final Set<String> names;
    private final String digest;
    private ModelStateStore store;
    private boolean closed;

    MaintenanceModelState(Path profile)throws IOException {
        root=profile.resolve("model-state");directory=new SqliteSourceLease.DirectoryPin(root);
        try {
            try(var entries=Files.list(root)){names=entries.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());}
            if(!names.contains(".lock")||!Set.of(".lock","active-model.json","validation-required.json").containsAll(names))throw stopped();
            var sha=MessageDigest.getInstance("SHA-256");
            sha.update(TrustedDatabaseConfiguration.Identity.read(root,true).getBytes(StandardCharsets.US_ASCII));
            for(String name:new TreeSet<>(names)){
                if(name.equals(".lock"))continue;
                var pin=new SqliteSourceLease.FilePin(root.resolve(name));files.put(name,pin);
                sha.update(name.getBytes(StandardCharsets.US_ASCII));sha.update(pin.digest().getBytes(StandardCharsets.US_ASCII));
            }
            digest=HexFormat.of().formatHex(sha.digest());requireSnapshot();
        }catch(Exception failure){close();throw stopped();}
    }
    String digest()throws IOException{requireSnapshot();return digest;}
    void requireSnapshot()throws IOException {
        if(closed)throw stopped();directory.verify();
        try(var entries=Files.list(root)){if(!entries.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(names))throw stopped();}
        for(var pin:files.values())pin.verify();
    }
    void attach(ModelStateStore actual)throws IOException {
        if(store!=null||actual==null)throw stopped();requireSnapshot();actual.requireMaintenanceOwnership(root);store=actual;
    }
    void requireWriter()throws IOException {
        requireSnapshot();if(store==null)throw stopped();store.requireMaintenanceOwnership(root);
    }
    void requireClosed()throws IOException {
        requireSnapshot();if(store==null||!store.maintenanceClosed())throw stopped();
    }
    @Override public void close(){if(closed)return;closed=true;for(var pin:files.values())try{pin.close();}catch(Exception ignored){}directory.close();}
    private static IOException stopped(){return new IOException("Maintenance model state ownership unavailable; preserve model state");}
}
