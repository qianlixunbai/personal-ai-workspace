import io.github.qianlixunbai.workspace.persistence.*;
import io.github.qianlixunbai.workspace.security.*;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Narrow synthetic acceptance of durable binding, configuration recovery and startup fencing.
 * The parent owns the actual Gate handle transfer, fresh cluster restart and cleanup. */
public final class MaintenanceActivationProbe {
    public static void main(String[] args)throws Exception {
        var output=System.out;System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(System.out);
        String stage="GATE";
        try {
            require(args.length==1);WindowsNativeGate.admit();
            if(args[0].equals("startup")) {
                try{WindowsNativeGate.profileRoot();throw new IllegalStateException();}
                catch(IOException blocked){require(blocked.getMessage().startsWith("Maintenance activation incomplete"));}
                output.println("PUBLISHED_CONFIG_STARTUP_FENCED");return;
            }
            output.println("ACTIVATION_READY "+ProcessHandle.current().pid());output.flush();
            var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
            var json=JsonMapper.builder().build().readTree(input.readLine());
            var target=new PGSimpleDataSource();target.setServerNames(new String[]{"127.0.0.1"});target.setPortNumbers(new int[]{json.path("port").asInt()});
            target.setDatabaseName("cutover_fixture");target.setUser("cutover_app");target.setPassword(json.path("appPassword").asString());
            target.setCurrentSchema("workspace,pg_catalog");target.setConnectTimeout(3);target.setSocketTimeout(30);
            try(var exclusion=WindowsNativeGate.receiveMaintenanceExclusion(json.path("handle").asLong())) {
                Path root=WindowsNativeGate.maintenanceProfileRoot(exclusion);
                if(args[0].equals("prepare")) {
                    stage="BOUND_IMPORT";
                    try(var owner=SqliteMaintenanceOwner.begin(exclusion)) {
                        owner.createBackup();var expected=owner.importTarget(target);
                        require(expected.binding()!=null&&expected.binding().operation().equals(owner.operationId()));
                        var wrong=new SqliteCombinedMigration.Expected(expected.workspace(),expected.knowledge(),expected.target(),
                                new MaintenanceTarget.Binding(expected.binding().systemIdentifier(),expected.binding().databaseOid(),
                                expected.binding().roleOid(),expected.binding().ownerOid(),expected.binding().schemaOid(),expected.binding().receiptOid(),
                                UUID.randomUUID().toString().replace("-","")));
                        require(SqliteCombinedMigration.inspect(target,wrong)==SqliteCombinedMigration.Inspection.TARGET_CHANGED);
                        stage="CONFIGURATION_INTENT";
                        var config=Map.of("format",1,"backend","postgresql","host","127.0.0.1","port",json.path("port").asInt(),
                                "database","cutover_fixture","username","cutover_app","password",json.path("appPassword").asString());
                        byte[] plain=JsonMapper.builder().build().writeValueAsBytes(config);
                        try{owner.prepareConfiguration(plain);}finally{Arrays.fill(plain,(byte)0);}
                        require(!Files.exists(root.resolve("data/database.dpapi")));owner.requireHeld();
                        output.println("CONFIGURATION_PREPARED "+owner.operationId());output.flush();
                    }
                }else if(args[0].equals("recover")) {
                    String operation=json.path("operation").asString();
                    stage="RESTART_BINDING";
                    var inspected=SqliteMaintenanceOwner.inspect(exclusion,operation,target);
                    require(inspected.lastStage()==SqliteMaintenanceOwner.Stage.TARGET_VERIFIED
                            &&inspected.target()==SqliteCombinedMigration.Inspection.MATCHING_DATA_AND_RECEIPTS);
                    output.println("RESTART_STABLE_BINDING_MATCHED");output.flush();
                    stage="PRESERVE_LATER_TARGET_DATA";
                    String added=UUID.randomUUID().toString();
                    try(var db=target.getConnection();var s=db.prepareStatement("INSERT INTO workspace.memory_items(id,type,title,content,status,revision,source,created_at,updated_at) VALUES(?,'PROJECT_NOTE','Later synthetic','Preserve later target data','ACTIVE',1,'MANUAL',1,1)")) {
                        s.setString(1,added);require(s.executeUpdate()==1);
                    }
                    require(MaintenanceConfiguration.recover(exclusion,operation).equals("SOURCE_OR_TARGET_CHANGED_PRESERVED"));
                    require(!Files.exists(root.resolve("data/database.dpapi")));
                    try(var db=target.getConnection();var s=db.prepareStatement("SELECT content FROM workspace.memory_items WHERE id=?")) {
                        s.setString(1,added);try(var rows=s.executeQuery()){require(rows.next()&&rows.getString(1).equals("Preserve later target data")&&!rows.next());}
                    }
                    output.println("LATER_TARGET_DATA_PRESERVED_PUBLICATION_REFUSED");output.flush();
                    // Test-only cleanup of the exact row just inserted, to continue the independent happy path.
                    try(var db=target.getConnection();var s=db.prepareStatement("DELETE FROM workspace.memory_items WHERE id=? AND content='Preserve later target data'")) {
                        s.setString(1,added);require(s.executeUpdate()==1);
                    }
                    stage="RECOVER_CONFIG";
                    require(MaintenanceConfiguration.recover(exclusion,operation).equals("RECORDED_CONFIGURATION_PRESENT_ACTIVATION_BLOCKED"));
                    Path config=root.resolve("data/database.dpapi");byte[] original=Files.readAllBytes(config);
                    require(MaintenanceConfiguration.recover(exclusion,operation).equals("RECORDED_CONFIGURATION_PRESENT_ACTIVATION_BLOCKED"));
                    require(Arrays.equals(original,Files.readAllBytes(config)));
                    output.println("FRESH_PROCESS_CONFIG_RECOVERED_NO_IMPORT_REPLAY");output.flush();
                    stage="PRESERVE_LATER_CONFIG";
                    byte[] later="synthetic later configuration".getBytes(StandardCharsets.UTF_8);Files.write(config,later);
                    require(MaintenanceConfiguration.recover(exclusion,operation).equals("LATER_OR_PARTIAL_CONFIGURATION_PRESERVED"));
                    require(Arrays.equals(later,Files.readAllBytes(config)));
                    output.println("LATER_CONFIGURATION_PRESERVED");output.flush();
                    // Only this fixture's own sentinel is removed, never production recovery.
                    Files.delete(config);
                    require(MaintenanceConfiguration.recover(exclusion,operation).equals("PUBLICATION_UNCONFIRMED"));
                    require(!Files.exists(config));
                    output.println("MISSING_AFTER_ATTEMPT_NOT_RECREATED");output.flush();
                    // Keep a synthetic non-secret configuration for the separate startup-fence probe.
                    WindowsPrivateAcl.createFile(config);Files.write(config,later);
                }else throw new IOException();
            }
        }catch(Exception failure){output.println("ACTIVATION_PROBE_STOP "+stage+" "+failure.getClass().getSimpleName());output.flush();System.exit(10);}
    }
    private static void require(boolean condition){if(!condition)throw new IllegalStateException();}
}
