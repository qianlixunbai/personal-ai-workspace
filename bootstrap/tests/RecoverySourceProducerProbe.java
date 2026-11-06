package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.persistence.MaintenanceExclusion;
import org.flywaydb.core.Flyway;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;

/** Explicit fresh-cluster probe. No NativeGate call, fabricated admission, existing Profile,
 * shared service credential or product source registration. stdout belongs to the native pipe. */
public final class RecoverySourceProducerProbe {
    private static Path diagnosticRoot;
    private static String stage="FIXTURE";
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch(Exception | LinkageError failure){
            Throwable cause=failure instanceof java.lang.reflect.InvocationTargetException wrapper?wrapper.getCause():failure;
            if(diagnosticRoot!=null)write(diagnosticRoot.resolve("probe-failure.json"),JSON.writeValueAsBytes(Map.of(
                "stage",stage,"type",cause.getClass().getName(),"frames",Arrays.stream(cause.getStackTrace()).limit(8).map(StackTraceElement::toString).toList())));
            System.exit(10); // No exception message/SQL/credential in child output.
        }
    }
    private static void run(String[] args) throws Exception {
        if(args.length!=1)throw new IOException();
        var output=System.out;System.setOut(System.err);
        Path fixture=Path.of(args[0]);privatePath(fixture,false);
        var config=JSON.readTree(Files.readAllBytes(fixture));Path isolated=Path.of(config.path("root").asString());privatePath(isolated,true);
        require(isolated.getFileName().toString().matches("PAW-SourceProducer-[0-9a-f]{32}"));
        diagnosticRoot=isolated;
        int port=config.path("port").asInt();require(port!=5432);
        var pid=Files.readAllLines(isolated.resolve("pgdata/postmaster.pid"));
        require(pid.get(1).equals(isolated.resolve("pgdata").toString().replace('\\','/'))
            &&pid.get(3).equals(Integer.toString(port))&&Long.parseLong(pid.getFirst())==config.path("processId").asLong());
        var pipe=new DataOutputStream(output);
        byte[] hello=("SYNTHETIC_SOURCE_V1\t"+ProcessHandle.current().pid()).getBytes(StandardCharsets.US_ASCII);
        pipe.writeInt(hello.length);pipe.write(hello);pipe.flush();
        stage="REQUEST";
        var input=new DataInputStream(System.in);int size=input.readInt();require(size>0&&size<=16384);
        byte[] request=input.readNBytes(size);require(request.length==size&&input.read()==-1);
        var call=JSON.readTree(request);require(call.size()==7&&call.path("Version").asInt()==1&&call.path("Action").asString().equals("BACKUP_SYNTHETIC"));
        String backupId=call.path("BackupId").asString(),logical=call.path("DatabaseLogicalIdentity").asString();
        require(backupId.matches("[0-9a-f]{32}")&&UUID.fromString(logical).toString().equals(logical));
        Path root=Path.of(call.path("SourceRoot").asString()),app=root.getParent().getParent();
        require(app.getParent().equals(isolated)&&app.getFileName().toString().matches("paw-source-producer-[0-9a-f]{32}")
            &&root.getParent().getFileName().toString().equals("Profiles")&&root.getFileName().toString().matches("[0-9a-f]{32}"));
        Path encrypted=Path.of(call.path("Encrypted").asString());
        require(encrypted.equals(app.resolve("Recovery/synthetic-payloads").resolve(backupId)));
        Method receive=MaintenanceExclusion.class.getDeclaredMethod("receiveHandles",Path.class,long.class);receive.setAccessible(true);
        Method held=MaintenanceExclusion.class.getDeclaredMethod("requireHandles",Path.class);held.setAccessible(true);
        stage="EXCLUSION";
        try(var exclusion=(MaintenanceExclusion)receive.invoke(null,root,call.path("ExclusionHandle").asLong())){
            stage="FIXTURE_DATABASE";
            var administrator=new Endpoint("postgres",config.path("adminUsername").asString(),config.path("adminPassword").asString(),port);
            String database="personal_ai_workspace_b3_"+backupId.substring(0,12),receipt="workspace-b3:"+UUID.randomUUID();
            try(var db=administrator.dataSource().getConnection()){
                require(value(db,"SHOW data_directory").replace('\\','/').equals(isolated.resolve("pgdata").toString().replace('\\','/'))
                    &&scalar(db,"SELECT inet_server_port() ")==port);
                require(scalar(db,"SELECT count(*) FROM pg_database")==3&&scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname !~ '^pg_'")==1);
                sql(db,"CREATE DATABASE "+database+" TEMPLATE template0 ENCODING 'UTF8'");
                sql(db,"COMMENT ON DATABASE "+database+" IS '"+receipt+"'");
                for(String name:List.of(database,"postgres","template1"))sql(db,"REVOKE ALL ON DATABASE "+name+" FROM PUBLIC");
            }
            var source=new Endpoint(database,administrator.username(),administrator.password(),port);
            try(var db=source.dataSource().getConnection()){
                sql(db,"REVOKE ALL ON SCHEMA public FROM PUBLIC");sql(db,"CREATE SCHEMA workspace");
                sql(db,"CREATE TABLE public.finance_sentinel(value text)");sql(db,"INSERT INTO public.finance_sentinel VALUES('OUTSIDE_WORKSPACE')");
            }
            Flyway.configure().dataSource(source.dataSource()).schemas("workspace").defaultSchema("workspace").createSchemas(false)
                .locations("classpath:db/workspace/migration").cleanDisabled(true).load().migrate();
            Snapshot before;
            try(var db=source.dataSource().getConnection()){
                sql(db,"INSERT INTO workspace.memory_items VALUES('"+UUID.randomUUID()+"','PROJECT_NOTE','Synthetic','Native producer','ACTIVE',1,'MANUAL',1000,1000)");
                before=snapshot(db,true);
            }
            Path knowledge=root.resolve("data/knowledge");directory(knowledge);directory(knowledge.resolve("sources"));
            char[] password=(UUID.randomUUID().toString()+UUID.randomUUID()).toCharArray(),consumed=password.clone();
            try {
                stage="BACKUP";
                held.invoke(exclusion,root);
                var completed=WorkspaceSourceBackup.backupSynthetic(source,Path.of(config.path("tools").asString()),knowledge,encrypted,backupId,logical,true,consumed);
                require(Arrays.equals(consumed,new char[consumed.length]));held.invoke(exclusion,root);
                try(var db=source.dataSource().getConnection()){
                    require(before.equals(snapshot(db,true))&&value(db,"SELECT value FROM public.finance_sentinel").equals("OUTSIDE_WORKSPACE")
                        &&scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname='"+completed.sourceRole()+"'")==0);
                }
                var secure=new PortableEncryptedBackup(Path.of(config.path("tools").asString()),PortableEncryptedBackup.StagingProtection.SYNTHETIC_ONLY);
                stage="AUTHENTICATED_VERIFICATION";
                Path staging=isolated.resolve("verification");
                var decrypted=secure.decryptWorkspace(completed.backup(),staging,password.clone());
                require(digest(decrypted.backup().resolve("manifest.json")).equals(completed.backup().manifestSha256()));
                MaintenanceStaging.clean(staging);held.invoke(exclusion,root);
                // All source work, role cleanup and the extra authenticated verification finished.
                completed.writeNativeResult(output);
                stage="RESULT_SENT";
            } finally {Arrays.fill(password,'\0');Arrays.fill(consumed,'\0');}
        }
    }
}
