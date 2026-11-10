import io.github.qianlixunbai.workspace.persistence.*;
import io.github.qianlixunbai.workspace.security.*;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

/** One real synthetic flow for retained post-publication ownership. No Runtime activation claim.
 * Native parent checks cross-process source/configuration writes while this owner stays alive. */
public final class MaintenancePublicationOwnerProbe {
    public static void main(String[] args)throws Exception {
        var output=System.out;System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(System.out);
        String stage="GATE";
        try {
            require(args.length==0);WindowsNativeGate.admit();
            output.println("PUBLICATION_READY "+ProcessHandle.current().pid());output.flush();
            var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
            var json=JsonMapper.builder().build().readTree(input.readLine());
            var target=new PGSimpleDataSource();target.setServerNames(new String[]{"127.0.0.1"});target.setPortNumbers(new int[]{json.path("port").asInt()});
            target.setDatabaseName("cutover_fixture");target.setUser("cutover_app");target.setPassword(json.path("appPassword").asString());
            target.setCurrentSchema("workspace,pg_catalog");target.setConnectTimeout(3);target.setSocketTimeout(30);
            try(var exclusion=WindowsNativeGate.receiveMaintenanceExclusion(json.path("handle").asLong())) {
                Path root=WindowsNativeGate.maintenanceProfileRoot(exclusion);
                String operation;
                stage="PUBLISH";
                try(var owner=SqliteMaintenanceOwner.begin(exclusion)) {
                    owner.createBackup();owner.importTarget(target);operation=owner.operationId();
                    byte[] plain=JsonMapper.builder().build().writeValueAsBytes(Map.of("format",1,"backend","postgresql",
                            "host","127.0.0.1","port",json.path("port").asInt(),"database","cutover_fixture",
                            "username","cutover_app","password",json.path("appPassword").asString()));
                    try{owner.prepareConfiguration(plain);}finally{Arrays.fill(plain,(byte)0);}
                    require(owner.publishConfiguration().equals("RECORDED_CONFIGURATION_PRESENT_ACTIVATION_BLOCKED"));
                    owner.requireHeld();
                    try{owner.publishConfiguration();throw new IllegalStateException();}catch(IOException expected){}
                    owner.requireHeld();
                    requireTargetWriteBlocked(target);
                    output.println("PUBLICATION_HELD "+operation);output.flush();
                    stage="INVALIDATE_EXCLUSION";
                    require("INVALIDATE".equals(input.readLine()));exclusion.close();
                    try{owner.requireHeld();throw new IllegalStateException();}catch(IOException expected){}
                    try{WindowsNativeGate.profileRoot();throw new IllegalStateException();}catch(IOException expected){}
                    requireTargetWriteBlocked(target);
                    output.println("EXCLUSION_LOST_OWNER_INVALID_RESOURCES_RETAINED");output.flush();
                    require("CLOSE".equals(input.readLine()));
                }
                stage="SUSPENDED";
                require(Files.isRegularFile(root.resolve("data/database.dpapi")));
                require(Files.isRegularFile(root.resolve("evidence/sqlite-configuration").resolve(operation).resolve("attempt.dpapi")));
                try{MaintenanceStartupFence.requireNoPendingOperation(root);throw new IllegalStateException();}catch(IOException expected){}
                // Write only this fixture's new row AFTER owner close. Product suspension must not
                // remove later target data, clear the fence, recreate source or delete configuration.
                String later=UUID.randomUUID().toString();
                try(var db=target.getConnection();var s=db.prepareStatement("INSERT INTO workspace.memory_items(id,type,title,content,status,revision,source,created_at,updated_at) VALUES(?,'PROJECT_NOTE','Later synthetic','Retained after owner suspension','ACTIVE',1,'MANUAL',1,1)")) {
                    s.setString(1,later);require(s.executeUpdate()==1);
                }
                try(var db=target.getConnection();var s=db.prepareStatement("SELECT content FROM workspace.memory_items WHERE id=?")) {
                    s.setString(1,later);try(var rows=s.executeQuery()){require(rows.next()&&rows.getString(1).equals("Retained after owner suspension")&&!rows.next());}
                }
                output.println("OWNER_CLOSED_RECORDS_CONFIG_LATER_DATA_PRESERVED");output.flush();
            }
        }catch(Exception failure){output.println("PUBLICATION_PROBE_STOP "+stage+" "+failure.getClass().getSimpleName());output.flush();System.exit(10);}
    }
    private static void requireTargetWriteBlocked(PGSimpleDataSource target)throws Exception {
        try(var db=target.getConnection();var s=db.createStatement()) {
            s.execute("SET lock_timeout='250ms'");
            try{s.executeUpdate("UPDATE workspace.memory_items SET title=title WHERE false");throw new IllegalStateException();}
            catch(SQLException blocked){require("55P03".equals(blocked.getSQLState()));}
        }
    }
    private static void require(boolean condition){if(!condition)throw new IllegalStateException();}
}
