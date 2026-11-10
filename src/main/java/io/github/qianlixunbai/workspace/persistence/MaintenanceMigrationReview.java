package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.knowledge.SqliteKnowledgeMigration;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A live, read/lock-only review before the first durable maintenance intent. Never a saved
 * authority token: the exact source pins, model snapshot and target transaction must survive
 * the native dialog. Cancellation leaves the ordinary source startup fence unchanged. */
final class MaintenanceMigrationReview implements AutoCloseable {
    private record Review(int version,String kind,String profile,String operation,String nonce,
                          String sourceIdentity,String sourceDigest,String modelState,
                          SqliteCombinedMigration.Expected expected,String database,String role,
                          String scope,boolean modelExecutionAllowed) {}
    private final String operation=UUID.randomUUID().toString().replace("-","");
    private final long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
    private final MaintenanceModelState model;
    private SqliteSourceLease source;
    final SqliteCombinedMigration.PreparedTarget target;
    final String json,digest;
    private boolean attempted,closed,transferred;

    MaintenanceMigrationReview(MaintenanceExclusion exclusion,DataSource dataSource,MaintenanceModelState model)throws IOException {
        this.model=model;
        SqliteCombinedMigration.PreparedTarget prepared=null;
        try {
            var root=WindowsNativeGate.maintenanceProfileRoot(exclusion);
            MaintenanceStartupFence.requireNoPendingOperation(root);model.requireSnapshot();
            source=SqliteSourceLease.acquire(exclusion);
            try(var memory=SqliteSourceLease.readOnly(root.resolve("data/memory.db"));
                    var knowledge=SqliteSourceLease.readOnly(root.resolve("data/knowledge/knowledge.db"));
                    var workspace=new SqliteWorkspaceMigration.Snapshot(memory);
                    var documents=new SqliteKnowledgeMigration.Snapshot(knowledge,root.resolve("data/knowledge"))){
                prepared=new SqliteCombinedMigration.PreparedTarget(dataSource,workspace.expected,documents.expected(),operation);
                documents.verifyFiles();
            }
            target=prepared;
            var fingerprint=source.fingerprint();
            var review=new Review(1,"LIVE_MIGRATION_REVIEW",root.getFileName().toString(),operation,
                    HexFormat.of().formatHex(new java.security.SecureRandom().generateSeed(32)),fingerprint.rootIdentity(),
                    SqliteMaintenanceOwner.sourceDigest(fingerprint),model.digest(),target.expected,target.database,target.role,
                    "WORKSPACE_KNOWLEDGE_WITH_BACKUP_MODEL_DISABLED",false);
            json=JsonMapper.builder().build().writeValueAsString(review);
            digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            requireCurrent();
        }catch(Exception failure){if(prepared!=null)prepared.close();if(source!=null)source.close();throw new IOException("Migration review unavailable; source unchanged");}
    }
    String operation(){return operation;}
    private void requireCurrent()throws IOException {
        if(closed||System.nanoTime()>deadline)throw new IOException("Migration review expired");
        source.requireHeld();model.requireSnapshot();target.requireCurrent();
    }
    SqliteMaintenanceOwner confirm(String operation,String digest)throws IOException {
        if(attempted)throw new IOException("Migration confirmation already consumed");attempted=true;
        if(!this.operation.equals(operation)||!this.digest.equals(digest))throw new IOException("Migration confirmation mismatch");
        requireCurrent();
        // First durable operation write happens only after this exact live confirmation.
        transferred=true;return SqliteMaintenanceOwner.beginReviewed(source,operation,digest);
    }
    public void close(){if(closed)return;closed=true;target.close();if(!transferred&&source!=null)source.close();}
}
