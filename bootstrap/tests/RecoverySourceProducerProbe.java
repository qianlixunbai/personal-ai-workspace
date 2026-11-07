package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.persistence.MaintenanceExclusion;
import io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration;
import io.github.qianlixunbai.workspace.persistence.RegisteredRecoverySource;
import io.github.qianlixunbai.workspace.persistence.PreparedRecoveryTarget;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import org.flywaydb.core.Flyway;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;

/** Explicit fresh-cluster probe. Optional prepared mode uses the real Gate of a newly owned
 * synthetic authority. No fabricated stop proof, existing Profile, shared service credential
 * or product source registration. stdout belongs to the native pipe. */
public final class RecoverySourceProducerProbe {
    private static Path diagnosticRoot;
    private static String stage="FIXTURE";
    public static void main(String[] args) throws Exception {
        try { if(args.length==4)restoreRegistered(args);else if(args.length==2)trustedInspectionFixture(args);else run(args); }
        catch(Exception | LinkageError failure){
            Throwable cause=failure instanceof java.lang.reflect.InvocationTargetException wrapper?wrapper.getCause():failure;
            if(diagnosticRoot!=null)write(diagnosticRoot.resolve("probe-failure.json"),JSON.writeValueAsBytes(Map.of(
                "stage",stage,"type",cause.getClass().getName(),"frames",Arrays.stream(cause.getStackTrace()).limit(8).map(StackTraceElement::toString).toList())));
            System.exit(10); // No exception message/SQL/credential in child output.
        }
    }

    /** Fixture preparation/verification for the real pinned-JAR maintenance entry. This
     * helper never admits a Profile; only NativeDatabaseSetup writes its new configuration. */
    private static void trustedInspectionFixture(String[] args) throws Exception {
        require(Set.of("prepare-native-inspection","verify-native-inspection").contains(args[0]));
        var output=System.out;System.setOut(System.err);
        Path fixture=Path.of(args[1]);privatePath(fixture,false);
        var config=JSON.readTree(Files.readAllBytes(fixture));Path isolated=Path.of(config.path("root").asString());privatePath(isolated,true);
        require(isolated.getFileName().toString().matches("PAW-SourceProducer-[0-9a-f]{32}"));diagnosticRoot=isolated;
        int port=config.path("port").asInt();require(port!=5432);
        var pid=Files.readAllLines(isolated.resolve("pgdata/postmaster.pid"));
        require(pid.get(1).equals(isolated.resolve("pgdata").toString().replace('\\','/'))
            &&pid.get(3).equals(Integer.toString(port))&&Long.parseLong(pid.getFirst())==config.path("processId").asLong());
        var administrator=new Endpoint("postgres",config.path("adminUsername").asString(),config.path("adminPassword").asString(),port);
        Path evidence=isolated.resolve("trusted-inspection-database.json");
        if(args[0].equals("verify-native-inspection")){
            privatePath(evidence,false);var expected=JSON.readTree(Files.readAllBytes(evidence));
            var source=new Endpoint(expected.path("database").asString(),administrator.username(),administrator.password(),port);
            try(var db=source.dataSource().getConnection()){
                require(value(db,"SHOW data_directory").replace('\\','/').equals(isolated.resolve("pgdata").toString().replace('\\','/')));
                // JSON tree integer widths change on parse (e.g. long row count 0 ->
                // IntNode). Compare the typed snapshot, not the serialization node types.
                require(snapshot(db,true).equals(JSON.treeToValue(expected.path("snapshot"),Snapshot.class))
                    &&scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname LIKE 'workspace_backup_src_%'")==0
                    &&value(db,"SELECT value FROM public.finance_sentinel").equals("OUTSIDE_WORKSPACE"));
            }
            output.print("VERIFIED_UNCHANGED");return;
        }
        require(!Files.exists(evidence));
        String id=UUID.randomUUID().toString().replace("-","").substring(0,12);
        String database="personal_ai_workspace_b3_"+id,appRole="workspace_app_"+id,password=UUID.randomUUID().toString();
        try(var db=administrator.dataSource().getConnection()){
            require(value(db,"SHOW data_directory").replace('\\','/').equals(isolated.resolve("pgdata").toString().replace('\\','/')));
            require(scalar(db,"SELECT count(*) FROM pg_database")==3&&scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname !~ '^pg_'")==1);
            sql(db,"CREATE DATABASE "+database+" TEMPLATE template0 ENCODING 'UTF8'");
            sql(db,"COMMENT ON DATABASE "+database+" IS 'workspace-b3:"+UUID.randomUUID()+"'");
            for(String name:List.of(database,"postgres","template1"))sql(db,"REVOKE ALL ON DATABASE "+name+" FROM PUBLIC");
        }
        var source=new Endpoint(database,administrator.username(),administrator.password(),port);
        try(var db=source.dataSource().getConnection()){
            sql(db,"REVOKE ALL ON SCHEMA public FROM PUBLIC");sql(db,"CREATE SCHEMA workspace");
            sql(db,"CREATE TABLE public.finance_sentinel(value text)");sql(db,"INSERT INTO public.finance_sentinel VALUES('OUTSIDE_WORKSPACE')");
        }
        Flyway.configure().dataSource(source.dataSource()).schemas("workspace").defaultSchema("workspace").createSchemas(false)
            .locations("classpath:db/workspace/migration").cleanDisabled(true).load().migrate();
        try(var db=source.dataSource().getConnection()){
            sql(db,"CREATE ROLE "+appRole+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD '"+password+"'");
            sql(db,"GRANT CONNECT ON DATABASE "+database+" TO "+appRole);sql(db,"GRANT USAGE ON SCHEMA workspace TO "+appRole);
            for(String table:WorkspaceV3Catalog.TABLES)sql(db,"GRANT SELECT ON TABLE "+table+" TO "+appRole);
            sql(db,"INSERT INTO workspace.memory_items VALUES('"+UUID.randomUUID()+"','PROJECT_NOTE','Synthetic','Trusted inspection','ACTIVE',1,'MANUAL',1000,1000)");
            write(evidence,JSON.writeValueAsBytes(Map.of("database",database,"snapshot",snapshot(db,true))));
        }
        // Captured only by the owning C# probe and passed to the real setup child's stdin;
        // never tee this stdout to a log. It contains newly generated synthetic credentials.
        output.write(JSON.writeValueAsBytes(Map.of("format",1,"backend","postgresql","host","127.0.0.1",
            "port",port,"database",database,"username",appRole,"password",password)));output.flush();
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
            }
            Path knowledge=root.resolve("data/knowledge");directory(knowledge);directory(knowledge.resolve("sources"));
            seedSyntheticHistory(source,knowledge);
            try(var db=source.dataSource().getConnection()){before=snapshot(db,true);}
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
                // Only the isolated fixture persists its generated passphrase, under DPAPI.
                // Product code has no password persistence or metadata -> Completed constructor.
                byte[] passwordBytes=new String(password).getBytes(StandardCharsets.UTF_8);
                try{createProtectedConfiguration(isolated.resolve("fixture-passphrase.dpapi"),passwordBytes);}
                finally{Arrays.fill(passwordBytes,(byte)0);}
                write(isolated.resolve("registered-fixture.json"),JSON.writeValueAsBytes(Map.of(
                    "database",database,"snapshot",before,"sourceProfileId",root.getFileName().toString(),
                    "producerPid",ProcessHandle.current().pid(),"manifest",completed.backup().manifest())));
                held.invoke(exclusion,root);require(configurationHash.equals(digest(configurationFile)));
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

    private static void restoreRegistered(String[] args) throws Exception {
        require(args[0].equals("restore-registered"));
        var output=System.out;System.setOut(System.err);
        MaintenanceExclusion nativeExclusion=null;
        if(!System.getProperty("workspace.native.development-profile","").isEmpty()){
            stage="TARGET_AUTHORITY_NATIVE_GATE";WindowsNativeGate.admit();
            String profile=System.getProperty("workspace.native.development-profile");
            writeFrame(output,"SYNTHETIC_TARGET_NATIVE_V1\t"+ProcessHandle.current().pid()+"\t"+profile);
            long handle=Long.parseLong(readFrame(System.in));
            nativeExclusion=WindowsNativeGate.receiveMaintenanceExclusion(handle);
            Path authority=WindowsNativeGate.maintenanceProfileRoot(nativeExclusion);
            WindowsNativeGate.requireMaintenanceArguments(new String[]{"--server.address=127.0.0.1","--server.port=18765",
                "--workspace.security.token-file="+authority.resolve("credentials/client-token"),"--workspace.data-directory="+authority.resolve("data"),
                "--workspace.model-state-directory="+authority.resolve("model-state")},nativeExclusion);
        }
        final MaintenanceExclusion authorityExclusion=nativeExclusion;
        Path fixture=Path.of(args[1]);privatePath(fixture,false);
        var config=JSON.readTree(Files.readAllBytes(fixture));Path isolated=Path.of(config.path("root").asString());privatePath(isolated,true);
        require(isolated.getFileName().toString().matches("PAW-SourceProducer-[0-9a-f]{32}"));diagnosticRoot=isolated;
        Path app=Path.of(args[2]);require(app.getParent().equals(isolated));String backupId=args[3];
        int port=config.path("port").asInt();require(port!=5432);
        var pid=Files.readAllLines(isolated.resolve("pgdata/postmaster.pid"));
        require(pid.get(1).equals(isolated.resolve("pgdata").toString().replace('\\','/'))
            &&pid.get(3).equals(Integer.toString(port))&&Long.parseLong(pid.getFirst())==config.path("processId").asLong());
        var expected=JSON.readTree(Files.readAllBytes(isolated.resolve("registered-fixture.json")));
        require(expected.path("producerPid").asLong()!=ProcessHandle.current().pid());
        // Native fixture retired its source Profile after the producer exited. Its original
        // configuration/data path is now absent; restoration must use only the independent record.
        require(!Files.exists(app.resolve("Profiles").resolve(expected.path("sourceProfileId").asString())));
        Method open=RegisteredRecoverySource.class.getDeclaredMethod("openSynthetic",Path.class,String.class);open.setAccessible(true);
        stage="MISSING_REGISTRATION";
        try{open.invoke(null,app,UUID.randomUUID().toString().replace("-",""));throw new IllegalStateException("Missing source accepted");}
        catch(java.lang.reflect.InvocationTargetException rejected){require(rejected.getCause() instanceof IOException);}
        stage="PAYLOAD_RECEIPT_MISMATCH";
        Path altered=isolated.resolve("paw-source-producer-"+UUID.randomUUID().toString().replace("-",""));
        directory(altered);directory(altered.resolve("Recovery"));
        for(String name:List.of("synthetic-sources","synthetic-payloads")){
            directory(altered.resolve("Recovery").resolve(name));directory(altered.resolve("Recovery").resolve(name).resolve(backupId));
        }
        copy(app.resolve("Recovery/synthetic-sources").resolve(backupId).resolve("source.dpapi"),
            altered.resolve("Recovery/synthetic-sources").resolve(backupId).resolve("source.dpapi"));
        Path originalPayload=app.resolve("Recovery/synthetic-payloads").resolve(backupId),changedPayload=altered.resolve("Recovery/synthetic-payloads").resolve(backupId);
        require(Files.size(originalPayload.resolve("backup.pgp"))<1048576);
        byte[] tampered=Files.readAllBytes(originalPayload.resolve("backup.pgp"));tampered[tampered.length-1]^=1;
        write(changedPayload.resolve("backup.pgp"),tampered);Arrays.fill(tampered,(byte)0);
        var completion=JSON.readValue(Files.readAllBytes(originalPayload.resolve("ENCRYPTED_BACKUP_COMPLETE.json")),PortableEncryptedBackup.Completion.class);
        write(changedPayload.resolve("ENCRYPTED_BACKUP_COMPLETE.json"),JSON.writeValueAsBytes(new PortableEncryptedBackup.Completion(
            completion.format(),completion.version(),completion.status(),completion.profile(),completion.payload(),completion.bytes(),digest(changedPayload.resolve("backup.pgp")))));
        try{open.invoke(null,altered,backupId);throw new IllegalStateException("Recomputed companion hash authorized changed payload");}
        catch(java.lang.reflect.InvocationTargetException rejected){require(rejected.getCause() instanceof IOException);}
        stage="REGISTERED_SOURCE_READ";
        try(var registered=(RegisteredRecoverySource)open.invoke(null,app,backupId)){
            registered.requireHeld();
            Path receipt=app.resolve("Recovery/synthetic-sources").resolve(backupId).resolve("source.dpapi");
            for(Path locked:List.of(receipt,registered.encrypted().resolve("backup.pgp"))) {
                try(var ignored=java.nio.channels.FileChannel.open(locked,StandardOpenOption.WRITE)){
                    throw new IllegalStateException("Registered input writable");
                }catch(IOException denied){/* Writer denied by retained real OS handles. */}
            }
            var administrator=new Endpoint("postgres",config.path("adminUsername").asString(),config.path("adminPassword").asString(),port);
            Path tools=Path.of(config.path("tools").asString());
            byte[] protectedPassword=Files.readAllBytes(isolated.resolve("fixture-passphrase.dpapi"));
            byte[] clear=com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(protectedPassword,null,1,null);
            char[] password=new String(clear,StandardCharsets.UTF_8).toCharArray();Arrays.fill(clear,(byte)0);Arrays.fill(protectedPassword,(byte)0);
            String database=expected.path("database").asString();require(database.equals(registered.database()));
            boolean disabled=false;
            try(var db=administrator.dataSource().getConnection()){
                require(value(db,"SHOW data_directory").replace('\\','/').equals(isolated.resolve("pgdata").toString().replace('\\','/'))
                    &&value(db,"SELECT system_identifier::text FROM pg_control_system()").equals(registered.systemIdentifier())
                    &&scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE datid="+registered.databaseOid())==0);
                long databases=scalar(db,"SELECT count(*) FROM pg_database"),roles=scalar(db,"SELECT count(*) FROM pg_roles");
                var sourceAdmin=new Endpoint(database,administrator.username(),administrator.password(),port);
                try {
                    stage="SOURCE_OFFLINE_FIXTURE";
                    sql(db,"ALTER DATABASE "+database+" ALLOW_CONNECTIONS false");disabled=true;
                    require(scalar(db,"SELECT count(*) FROM pg_database WHERE oid="+registered.databaseOid()+" AND NOT datallowconn")==1);
                    try(var ignored=sourceAdmin.dataSource().getConnection()){throw new IllegalStateException("Source still connectable");}
                    catch(java.sql.SQLException rejected){require("55000".equals(rejected.getSQLState()));}
                    stage="WRONG_PASSWORD_BEFORE_TARGET";
                    Path rejected=isolated.resolve("wrong-recovery-password");char[] wrong=UUID.randomUUID().toString().toCharArray();
                    try{WorkspaceRecovery.restoreRegisteredSyntheticTarget(registered,tools,rejected,wrong,administrator);throw new IllegalStateException("Wrong password accepted");}
                    catch(IOException denied){require(denied.getMessage().contains("AUTHENTICATION"));}
                    require(Arrays.equals(wrong,new char[wrong.length])&&!Files.exists(rejected.resolve("SYNTHETIC_RESTORE_PLAN.json"))
                        &&databases==scalar(db,"SELECT count(*) FROM pg_database")&&roles==scalar(db,"SELECT count(*) FROM pg_roles"));
                    stage="REGISTERED_RECOVERY";
                    Path restored=isolated.resolve("restored-target");
                    Path[] targetData={restored.resolve("candidate")};String[] preparedId={null};
                    var recovery=WorkspaceRecovery.restoreRegisteredSyntheticTarget(registered,tools,restored,password.clone(),administrator,
                        authorityExclusion==null?null:()->{
                            writeFrame(output,"SYNTHETIC_SOURCE_AUTHENTICATED_V1\t"+backupId+"\t"+registered.payloadSha256()+"\t"+registered.manifestSha256());
                            String target=readFrame(System.in);require(target.matches("[0-9a-f]{32}")&&System.in.read()==-1);
                            var prepared=PreparedRecoveryTarget.openSynthetic(authorityExclusion,target,registered);
                            preparedId[0]=target;targetData[0]=prepared.data();return prepared;
                        });
                    require(recovery.restore().equals(new Result("RESTORE_VERIFIED","IDENTITY_REBIND_REQUIRED","WINDOWS_ADMISSION_PENDING"))
                        &&Files.exists(restored.resolve("SYNTHETIC_RECOVERY_VERIFIED.json"))&&!Files.exists(restored.resolve("backup"))
                        &&databases+1==scalar(db,"SELECT count(*) FROM pg_database")&&roles+2==scalar(db,"SELECT count(*) FROM pg_roles")
                        &&scalar(db,"SELECT count(*) FROM pg_authid WHERE oid IN ("+recovery.ownerRoleOid()+","+recovery.appRoleOid()+") AND NOT rolcanlogin AND rolpassword IS NULL")==2);
                    var restoredAdmin=new Endpoint(recovery.database(),administrator.username(),administrator.password(),port);
                    try(var target=restoredAdmin.dataSource().getConnection()){
                        require(scalar(target,"SELECT count(*) FROM workspace.memory_items")==1&&scalar(target,"SELECT count(*) FROM workspace.conversations")==1
                            &&scalar(target,"SELECT count(*) FROM workspace.knowledge_revisions")==1&&scalar(target,"SELECT count(*) FROM workspace.knowledge_chunks")>0
                            &&scalar(target,"SELECT count(*) FROM pg_tables WHERE schemaname='public'")==0);
                        verifyKnowledge(target,targetData[0].resolve("knowledge"),JSON.treeToValue(expected.path("manifest"),Manifest.class).knowledgeFiles());
                    }
                    String planHash=digest(restored.resolve("SYNTHETIC_RESTORE_PLAN.json"));
                    require(JSON.readTree(Files.readAllBytes(restored.resolve("SYNTHETIC_RESTORE_PLAN.json"))).path("sourceRegistrationSha256").asString().equals(registered.registrationSha256()));
                    try{WorkspaceRecovery.restoreRegisteredSyntheticTarget(registered,tools,restored,password.clone(),administrator);throw new IllegalStateException("Attempt replayed");}
                    catch(IOException denied){/* No replacement plan or existing DB restore. */}
                    require(planHash.equals(digest(restored.resolve("SYNTHETIC_RESTORE_PLAN.json")))
                        &&scalar(db,"SELECT count(*) FROM pg_database WHERE oid="+registered.databaseOid()+" AND NOT datallowconn")==1);
                    sql(db,"ALTER DATABASE "+database+" ALLOW_CONNECTIONS true");disabled=false;
                    try(var original=sourceAdmin.dataSource().getConnection()){
                        require(snapshot(original,true).equals(JSON.treeToValue(expected.path("snapshot"),Snapshot.class))
                            &&value(original,"SELECT value FROM public.finance_sentinel").equals("OUTSIDE_WORKSPACE"));
                    }
                    var evidence=new LinkedHashMap<String,Object>();
                    evidence.put("target",recovery);evidence.put("registrationSha256",registered.registrationSha256());
                    evidence.put("producerPid",expected.path("producerPid").asLong());evidence.put("consumerPid",ProcessHandle.current().pid());
                    for(String name:List.of("authenticatedFreshTarget","wrongPasswordCreatedNoDatabaseOrRoles","memoryConversationKnowledgeVerified",
                        "financeExcluded","sourceSnapshotUnchanged","existingAttemptRejected","plaintextBackupCleaned","durableRegistrationConsumed",
                        "sourceDatabaseUnavailableDuringRestore","sourceProfileAbsent","missingRegistrationRejected","registrationAndPayloadPinned",
                        "recomputedCompletionCannotReplaceRegistration"))evidence.put(name,true);
                    if(authorityExclusion!=null){
                        require(!Files.exists(restored.resolve("candidate"))&&Files.exists(targetData[0].resolve("RESTORE_VERIFIED.json"))
                            &&!Files.exists(targetData[0].getParent().resolve("jna-5.17.0-win32-x86-64")));
                        try{io.github.qianlixunbai.workspace.persistence.NativeProfileRecovery.requireOrdinaryProfile(targetData[0].getParent());throw new IllegalStateException("Candidate started");}
                        catch(IOException denied){}
                        try{PreparedRecoveryTarget.openSynthetic(authorityExclusion,preparedId[0],registered);throw new IllegalStateException("Claimed prepared target reopened");}
                        catch(IOException denied){}
                        evidence.put("preparedTargetProfile",preparedId[0]);evidence.put("preparedTargetData",targetData[0].toString());
                        evidence.put("preparedTargetConsumedOnce",true);evidence.put("candidateStartupRejected",true);evidence.put("targetJnaAbsent",true);
                    }
                    evidence.put("nativeGateExercised",authorityExclusion!=null);evidence.put("activation",false);
                    write(isolated.resolve("synthetic-recovery-evidence.json"),JSON.writeValueAsBytes(evidence));
                    registered.close();
                    try{registered.requireHeld();throw new IllegalStateException("Closed source reused");}catch(IOException denied){}
                    if(authorityExclusion==null)output.print("REGISTERED_SYNTHETIC_RECOVERY_VERIFIED");
                    else writeFrame(output,"PREPARED_SYNTHETIC_RECOVERY_VERIFIED");
                }finally{
                    if(disabled)sql(db,"ALTER DATABASE "+database+" ALLOW_CONNECTIONS true");
                }
            }finally{Arrays.fill(password,'\0');}
        }
    }

    private static void writeFrame(OutputStream output,String message) throws IOException {
        byte[] bytes=message.getBytes(StandardCharsets.US_ASCII);require(bytes.length>0&&bytes.length<=16384);
        var stream=new DataOutputStream(output);stream.writeInt(bytes.length);stream.write(bytes);stream.flush();
    }
    private static String readFrame(InputStream input) throws IOException {
        var stream=new DataInputStream(input);int size=stream.readInt();require(size>0&&size<=16384);
        byte[] bytes=stream.readNBytes(size);require(bytes.length==size);return new String(bytes,StandardCharsets.US_ASCII);
    }

    private static void seedSyntheticHistory(Endpoint source,Path knowledge) throws Exception {
        String conversation=UUID.randomUUID().toString(),turn=UUID.randomUUID().toString();
        String doc=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();
        directory(knowledge.resolve("sources/"+doc));Path file=knowledge.resolve("sources/"+doc+"/1.source");
        write(file,"synthetic recovery budget\n".getBytes(StandardCharsets.UTF_8));
        var parsed=io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.parse(file,"TXT",()->false);
        try(var db=source.dataSource().getConnection()){
            sql(db,"INSERT INTO workspace.conversations VALUES('"+conversation+"','Synthetic recovery','ACTIVE',1000,2000)");
            sql(db,"INSERT INTO workspace.conversation_turns VALUES('"+turn+"','"+conversation+"',1,'SUCCEEDED',1000,2000,'"+UUID.randomUUID()+"',NULL)");
            sql(db,"INSERT INTO workspace.conversation_messages VALUES('"+UUID.randomUUID()+"','"+turn+"','USER','synthetic question',1000)");
            sql(db,"INSERT INTO workspace.conversation_messages VALUES('"+UUID.randomUUID()+"','"+turn+"','ASSISTANT','synthetic answer',2000)");
            db.setAutoCommit(false);
            sql(db,"INSERT INTO workspace.knowledge_documents VALUES('"+doc+"','budget.txt','ACTIVE',2,1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','"+job+"')");
            try(var statement=db.prepareStatement("INSERT INTO workspace.knowledge_revisions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
                Object[] values={doc,1,digest(file),"budget.txt","TXT",Files.size(file),"2026-01-01T00:00:00Z",
                    io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.PARSER_VERSION,
                    io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.NORMALIZATION_VERSION,
                    parsed.digest(),parsed.text(),parsed.locatorJson(),parsed.lineCount(),
                    (long)parsed.text().getBytes(StandardCharsets.UTF_8).length+parsed.locatorJson().getBytes(StandardCharsets.UTF_8).length};
                for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);statement.executeUpdate();
            }
            sql(db,"INSERT INTO workspace.knowledge_jobs VALUES('"+job+"','"+doc+"','READY',NULL,1,'budget.txt','TXT',"+Files.size(file)+",1,'2026-01-01T00:00:00Z')");
            db.commit();
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
