package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.persistence.MaintenanceExclusion;
import io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration;
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
            String appRole="workspace_app_"+backupId.substring(0,12),appPassword=UUID.randomUUID().toString();
            Snapshot before;
            try(var db=source.dataSource().getConnection()){
                sql(db,"CREATE ROLE "+appRole+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD '"+appPassword+"'");
                sql(db,"GRANT CONNECT ON DATABASE "+database+" TO "+appRole);
                sql(db,"GRANT USAGE ON SCHEMA workspace TO "+appRole);
                for(String table:WorkspaceV3Catalog.TABLES)sql(db,"GRANT SELECT ON TABLE "+table+" TO "+appRole);
                sql(db,"INSERT INTO workspace.memory_items VALUES('"+UUID.randomUUID()+"','PROJECT_NOTE','Synthetic','Native producer','ACTIVE',1,'MANUAL',1000,1000)");
                before=snapshot(db,true);
            }
            Path knowledge=root.resolve("data/knowledge");directory(knowledge);directory(knowledge.resolve("sources"));
            stage="PROTECTED_CONFIGURATION";
            Method freeze=TrustedDatabaseConfiguration.class.getDeclaredMethod("pinRecoverySource",Path.class);freeze.setAccessible(true);
            try{freeze.invoke(null,root);throw new IllegalStateException("Missing configuration accepted");}
            catch(java.lang.reflect.InvocationTargetException rejected){require(rejected.getCause() instanceof IOException);}
            byte[] configuration=JSON.writeValueAsBytes(Map.of("format",1,"backend","postgresql","host","127.0.0.1",
                "port",port,"database",database,"username",appRole,"password",appPassword));
            createProtectedConfiguration(root.resolve("data/database.dpapi"),configuration);Arrays.fill(configuration,(byte)0);
            char[] password=(UUID.randomUUID().toString()+UUID.randomUUID()).toCharArray(),consumed=password.clone();
            try(var binding=(TrustedDatabaseConfiguration.RecoverySource)freeze.invoke(null,root)) {
                Path configurationFile=root.resolve("data/database.dpapi");String configurationHash=digest(configurationFile);
                try(var ignored=java.nio.channels.FileChannel.open(configurationFile,StandardOpenOption.WRITE)){
                    throw new IllegalStateException("Pinned configuration writable");
                }catch(IOException denied){/* Real Windows sharing denial, no write attempted. */}
                try{Files.move(configurationFile,configurationFile.resolveSibling("moved.dpapi"));throw new IllegalStateException("Configuration moved");}
                catch(IOException denied){/* No-delete sharing retains the exact configuration. */}
                try{Files.move(root.resolve("data"),root.resolve("moved-data"));throw new IllegalStateException("Data directory moved");}
                catch(IOException denied){/* The containing data directory is pinned as well. */}
                try(var wrong=administrator.dataSource().getConnection()){
                    try{binding.verifyAdministrator(wrong);throw new IllegalStateException("Wrong database accepted");}
                    catch(IOException denied){/* No role creation or grant can precede this check. */}
                    require(scalar(wrong,"SELECT count(*) FROM pg_roles WHERE rolname='workspace_backup_src_"+backupId+"'")==0);
                }
                require(configurationHash.equals(binding.configurationSha256())&&binding.database().equals(database)&&binding.port()==port);
                stage="BACKUP";
                held.invoke(exclusion,root);
                var completed=WorkspaceSourceBackup.backupSynthetic(binding,administrator.username(),administrator.password(),
                    Path.of(config.path("tools").asString()),encrypted,backupId,logical,true,consumed);
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
                require(configurationHash.equals(digest(configurationFile)));
                write(isolated.resolve("source-binding-evidence.json"),JSON.writeValueAsBytes(Map.of(
                    "configurationDpapiRead",true,"configurationPinned",true,"configurationSha256",configurationHash,
                    "applicationRoleOid",binding.databaseIdentity().applicationRoleOid(),"wrongAdministratorDatabaseRejected",true,
                    "missingConfigurationRejected",true,"sourceSnapshotUnchanged",true,"nativeGateExercised",false)));
                // All source work, role cleanup and the extra authenticated verification finished.
                binding.close();
                try{binding.requireUnchanged();throw new IllegalStateException("Closed binding reused");}
                catch(IOException denied){/* A closed pin never silently reopens configuration. */}
                completed.writeNativeResult(output);
                stage="RESULT_SENT";
            } finally {Arrays.fill(password,'\0');Arrays.fill(consumed,'\0');}
        }
    }

    private static void createProtectedConfiguration(Path file,byte[] plaintext) throws Exception {
        // Direct private OS primitives on this new synthetic file only. No real Gate state,
        // Profile/configuration lookup or reflective assignment of an admitted root.
        Class<?> protection=Class.forName(TrustedDatabaseConfiguration.class.getName()+"$Protection");
        Method protect=protection.getDeclaredMethod("protect",byte[].class);protect.setAccessible(true);
        byte[] encrypted=(byte[])protect.invoke(null,(Object)plaintext);
        try {
            Class<?> acl=Class.forName("io.github.qianlixunbai.workspace.security.WindowsPrivateAcl$NativeAcl");
            Method create=acl.getDeclaredMethod("operate",Path.class,boolean.class,boolean.class,boolean.class);create.setAccessible(true);
            create.invoke(null,file,false,true,false);
            try(var output=java.nio.channels.FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                var bytes=java.nio.ByteBuffer.wrap(encrypted);while(bytes.hasRemaining())output.write(bytes);output.force(true);
            }
            privatePath(file,false);
        }finally{Arrays.fill(encrypted,(byte)0);}
    }
}
