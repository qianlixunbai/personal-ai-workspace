package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.RecoverySource;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;

/** Owns one source-role -> real v3 encrypted stream -> role reclamation lifecycle.
 * Package-private and synthetic-only until native maintenance admission/transport is available.
 * No CLI, HTTP, caller-written completion receipt or personal-source authority is exposed.
 */
final class WorkspaceSourceBackup {
    private WorkspaceSourceBackup() {}
    record SourceIdentity(String systemIdentifier,long databaseOid,int port,String database,String receipt) {
        @Override public String toString(){return "SourceIdentity[private]";}
    }

    /** A live, single-use SQL/file freeze. It is deliberately NOT complete stopped-writer
     * proof: old executables and remote model execution remain outside its coverage. The
     * only factory is synthetic; no boolean, serialized record or UI result can recreate it. */
    static final class FrozenSource implements AutoCloseable {
        private final RecoverySource binding;
        private Endpoint administrator;
        private Endpoint reader;
        private final Connection connection;
        private final SourceIdentity identity;
        private final long backendPid;
        private final List<FileChannel> files=new ArrayList<>();
        private final Path knowledge;
        private final List<FileEntry> entries;
        private final Snapshot before;
        private boolean consumed,closed;

        private FrozenSource(RecoverySource binding,Endpoint administrator,Connection connection) throws Exception {
            this.binding=binding;this.administrator=administrator;this.connection=connection;
            binding.requireMaintenanceHeld();binding.verifyAdministrator(connection);
            identity=identity(connection,administrator);backendPid=scalar(connection,"SELECT pg_backend_pid()");
            require(backendPid>0&&identity.databaseOid()==binding.databaseIdentity().databaseOid()
                &&identity.systemIdentifier().equals(binding.databaseIdentity().systemIdentifier()));
            require(scalar(connection,"SELECT count(*) FROM pg_stat_activity WHERE datid="+identity.databaseOid()+" AND pid<>pg_backend_pid()")==0);
            require(scalar(connection,"SELECT count(*) FROM pg_prepared_xacts WHERE database=current_database()")==0);
            connection.setAutoCommit(false);
            try {
                for(String table:WorkspaceV3Catalog.TABLES)sql(connection,"LOCK TABLE "+table+" IN SHARE MODE NOWAIT");
                WorkspaceV3Catalog.verify(connection);safeJobs(connection);validateWorkspaceData(connection);
                knowledge=binding.profileRoot().resolve("data/knowledge");entries=knowledgeFiles(connection);require(entries.size()<=1000);
                for(var entry:entries){Path file=resolveKnowledge(knowledge,entry.path());privatePath(file,false);files.add(pin(file));}
                verifyKnowledge(connection,knowledge,entries);before=snapshot(connection,true);
                requireHeld();
                require(scalar(connection,"SELECT count(*) FROM pg_stat_activity WHERE datid="+identity.databaseOid()+" AND pid<>pg_backend_pid()")==0);
            }catch(Exception|Error failure){for(var file:files)try{file.close();}catch(IOException ignored){}throw failure;}
        }
        void requireHeld() throws Exception {
            require(!closed&&administrator!=null&&!connection.isClosed()&&!connection.getAutoCommit());
            binding.requireMaintenanceHeld();require(identity.equals(identity(connection,administrator)));
            require(scalar(connection,"SELECT pg_backend_pid()")==backendPid);
            String tables=String.join(",",WorkspaceV3Catalog.TABLES.stream().map(t->"'"+t+"'::regclass").toList());
            require(scalar(connection,"SELECT count(*) FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='relation'"
                +" AND mode='ShareLock' AND granted AND relation IN ("+tables+")")==WorkspaceV3Catalog.TABLES.size());
            for(var file:files)require(file.isOpen());
        }
        private void claim() throws Exception {requireHeld();require(!consumed);consumed=true;}
        void requireSource(Endpoint source,Path root) throws Exception {
            requireHeld();require(consumed&&reader==source&&source.database().equals(identity.database())
                &&source.port()==identity.port()&&knowledge.equals(root.toAbsolutePath().normalize()));
        }
        void verifyContents() throws Exception {
            requireHeld();WorkspaceV3Catalog.verify(connection);safeJobs(connection);
            require(before.equals(snapshot(connection,true)));verifyKnowledge(connection,knowledge,entries);
        }
        // Consumers still reject every other backend. This exclusion is derived from the
        // retained, continuously checked JDBC connection, never a caller-supplied PID.
        String excludeOwnedBackend() throws Exception {requireHeld();return " AND pid<>"+backendPid;}
        @Override public void close() throws IOException {
            if(closed)return;closed=true;Exception failure=null;
            try{connection.rollback();}catch(Exception error){failure=error;}
            try{connection.close();}catch(Exception error){failure=error;}
            for(var file:files)try{file.close();}catch(IOException error){failure=error;}
            administrator=null;reader=null;
            if(failure!=null)throw new IOException("Source freeze close incomplete; no registration");
        }
        @Override public String toString(){return "FrozenSource[synthetic; partial coverage; private]";}
    }

    static FrozenSource freezeSynthetic(RecoverySource source,String username,char[] password) throws IOException {
        Connection connection=null;
        try {
            require(source!=null);source.requireMaintenanceHeld();
            require(source.database().matches("personal_ai_workspace_b3_[0-9a-f]{12}")&&source.port()!=5432);
            require(source.profileRoot().getParent().getParent().getFileName().toString().matches("paw-source-producer-[0-9a-f]{32}"));
            require(username!=null&&username.matches("[a-z_][a-z0-9_]{0,62}")&&password!=null&&password.length>0&&password.length<=1024);
            var administrator=new Endpoint(source.database(),username,new String(password),source.port());
            connection=administrator.dataSource().getConnection();
            return new FrozenSource(source,administrator,connection);
        }catch(Exception|Error rejected){
            if(connection!=null)try{connection.close();}catch(Exception ignored){}
            throw new IOException("Source freeze unavailable; no role or registration");
        }finally{if(password!=null)Arrays.fill(password,'\0');}
    }

    /** One native response to this live synthetic source, never a personal admission or a
     * reusable administrator credential. The owner retains OS/configuration pins throughout. */
    static NativeAuthorization requestSyntheticAuthorization(RecoverySource source,String backupId,String logicalId,
            java.io.InputStream input,java.io.OutputStream output) throws IOException {
        byte[] bytes=null;char[] password=null,phrase=null;FrozenSource frozen=null;
        try {
            source.requireMaintenanceHeld();
            require(backupId.matches("[0-9a-f]{32}")&&source.database().equals("personal_ai_workspace_b3_"+backupId.substring(0,12))
                &&source.port()!=5432&&source.profileRoot().getParent().getParent().getFileName().toString().matches("paw-source-producer-[0-9a-f]{32}"));
            require(UUID.fromString(logicalId).toString().equals(logicalId));
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);
            String challenge=java.util.HexFormat.of().formatHex(random);Arrays.fill(random,(byte)0);
            String profile=source.profileRoot().getFileName().toString(),configuration=source.configurationSha256();
            var observation=source.inspectApplication();
            var request=new java.util.LinkedHashMap<String,Object>();
            request.put("Version",1);request.put("Kind","SYNTHETIC_SOURCE_AUTHORIZATION_REQUEST");
            request.put("ChildPid",ProcessHandle.current().pid());request.put("BackupId",backupId);
            request.put("DatabaseLogicalIdentity",logicalId);request.put("SourceProfileId",profile);
            request.put("SourceDatabase",source.database());request.put("SourcePort",source.port());
            request.put("SourceConfigurationIdentity",source.configurationIdentity());request.put("SourceConfigurationSha256",configuration);
            request.put("SourceApplicationRoleOid",observation.applicationRoleOid());request.put("Challenge",challenge);
            byte[] frame=JSON.writeValueAsBytes(request);require(frame.length>0&&frame.length<=16384);
            var pipe=new java.io.DataOutputStream(output);pipe.writeInt(frame.length);pipe.write(frame);pipe.flush();
            var response=new java.io.DataInputStream(input);int length=response.readInt();require(length>0&&length<=16384);
            bytes=response.readNBytes(length);require(bytes.length==length&&response.read()==-1);
            var call=JSON.readTree(bytes);
            var fields=java.util.Set.of("Version","Action","ChildPid","BackupId","DatabaseLogicalIdentity","SourceProfileId",
                "SourceConfigurationSha256","Challenge","AdministratorUsername","AdministratorPassword","Passphrase");
            require(call.isObject()&&call.size()==fields.size());
            for(String field:fields)require(call.has(field));
            require(call.path("Version").isIntegralNumber()&&call.path("Version").canConvertToInt()&&call.path("Version").intValue()==1
                &&call.path("ChildPid").isIntegralNumber()&&call.path("ChildPid").canConvertToLong()
                &&call.path("ChildPid").longValue()==ProcessHandle.current().pid());
            for(String field:fields)if(!field.equals("Version")&&!field.equals("ChildPid"))require(call.path(field).isString());
            require(call.path("Action").asString().equals("AUTHORIZE_SYNTHETIC_BACKUP")&&call.path("BackupId").asString().equals(backupId)
                &&call.path("DatabaseLogicalIdentity").asString().equals(logicalId)&&call.path("SourceProfileId").asString().equals(profile)
                &&call.path("SourceConfigurationSha256").asString().equals(configuration)&&call.path("Challenge").asString().equals(challenge));
            String username=call.path("AdministratorUsername").asString();
            password=call.path("AdministratorPassword").asString().toCharArray();phrase=call.path("Passphrase").asString().toCharArray();
            require(username.matches("[a-z_][a-z0-9_]{0,62}")&&password.length>0&&password.length<=1024&&phrase.length>=16&&phrase.length<=1024);
            source.requireMaintenanceHeld();require(configuration.equals(source.configurationSha256()));
            // Only now can the supplied administrator connect; endpoint and scope come from
            // the retained configuration. No path/endpoint/SQL or stop flag came from the UI.
            frozen=freezeSynthetic(source,username,password);
            var authorized=new NativeAuthorization(frozen,backupId,logicalId,phrase);phrase=null;frozen=null;
            return authorized;
        }catch(Exception rejected){throw new IOException("Native synthetic source authorization rejected; no receipt");}
        finally {
            if(bytes!=null)Arrays.fill(bytes,(byte)0);if(password!=null)Arrays.fill(password,'\0');if(phrase!=null)Arrays.fill(phrase,'\0');
            if(frozen!=null)frozen.close();
        }
    }

    static final class NativeAuthorization implements AutoCloseable {
        private final FrozenSource frozen;
        private final String backupId,logicalId;
        private char[] passphrase;
        private boolean consumed,closed;
        private NativeAuthorization(FrozenSource frozen,String backupId,String logicalId,char[] passphrase){
            this.frozen=frozen;this.backupId=backupId;this.logicalId=logicalId;this.passphrase=passphrase;
        }
        FrozenSource frozen() throws IOException {require(!closed&&!consumed);return frozen;}
        Completed backup(Path tools,Path encrypted) throws IOException {
            require(!closed&&!consumed);consumed=true;
            try{return backupSynthetic(frozen,tools,encrypted,backupId,logicalId,passphrase);}
            finally{Arrays.fill(passphrase,'\0');passphrase=null;}
        }
        @Override public void close() throws IOException {
            if(closed)return;closed=true;
            try{frozen.close();}finally{if(passphrase!=null){Arrays.fill(passphrase,'\0');passphrase=null;}}
        }
        @Override public String toString(){return "NativeAuthorization[synthetic; single use; private]";}
    }
    static final class Completed {
        private final String backupId,sourceRole;
        private final SourceIdentity source;
        private final PortableEncryptedBackup.WorkspaceBackup backup;
        private final String configurationIdentity,configurationSha256;
        private final long applicationRoleOid;
        private Completed(String backupId,String sourceRole,SourceIdentity source,PortableEncryptedBackup.WorkspaceBackup backup,
                RecoverySource binding) throws IOException {
            this.backupId=backupId;this.sourceRole=sourceRole;this.source=source;this.backup=backup;
            configurationIdentity=binding==null?null:binding.configurationIdentity();
            configurationSha256=binding==null?null:binding.configurationSha256();
            applicationRoleOid=binding==null?0:binding.databaseIdentity().applicationRoleOid();
        }
        String backupId(){return backupId;}
        String sourceRole(){return sourceRole;}
        SourceIdentity source(){return source;}
        PortableEncryptedBackup.WorkspaceBackup backup(){return backup;}
        /** One bounded result frame for the direct native owner's private stdout. Only the
         * completed execution can emit it; it is explicitly NOT personal SQL-source authority.
         * No endpoint password, passphrase, source content or caller-provided digest is sent. */
        void writeNativeResult(java.io.OutputStream output) throws IOException {
            require(configurationIdentity!=null&&configurationSha256!=null&&applicationRoleOid>0);
            var result=new java.util.LinkedHashMap<String,Object>();
            result.put("Version",2);result.put("Kind","SYNTHETIC_WORKSPACE_BACKUP_RESULT");
            result.put("ChildPid",ProcessHandle.current().pid());result.put("BackupId",backupId);
            result.put("DatabaseLogicalIdentity",backup.manifest().databaseLogicalIdentity());
            result.put("SourceSystemIdentifier",source.systemIdentifier());result.put("SourceDatabaseOid",source.databaseOid());
            result.put("SourcePort",source.port());result.put("SourceDatabase",source.database());result.put("SourceRole",sourceRole);
            result.put("PayloadBytes",backup.completion().bytes());result.put("PayloadSha256",backup.completion().sha256());
            result.put("ManifestSha256",backup.manifestSha256());result.put("CredentialCleanup","RECLAIMED");
            result.put("SourceConfigurationIdentity",configurationIdentity);result.put("SourceConfigurationSha256",configurationSha256);
            result.put("SourceApplicationRoleOid",applicationRoleOid);
            byte[] bytes=JSON.writeValueAsBytes(result);
            require(bytes.length>0&&bytes.length<=16384);
            var pipe=new java.io.DataOutputStream(output);pipe.writeInt(bytes.length);pipe.write(bytes);pipe.flush();
        }
        @Override public String toString(){return "CompletedSourceBackup[private]";}
    }

    /** Administrator material is provided only by the owning synthetic fixture, never loaded
     * from existing service/Profile credentials. The future native owner must replace this
     * synthetic entry with an admitted source context; deleting the restrictions is not enough.
     * Consumes the encryption passphrase on every outcome. Never modifies existing app grants.
     */
    static Completed backupSynthetic(Endpoint administrator,Path tools,Path knowledge,Path encrypted,
            String backupId,String logicalId,boolean writersStopped,char[] passphrase) throws IOException {
        return backupSynthetic(administrator,tools,knowledge,encrypted,backupId,logicalId,writersStopped,passphrase,null);
    }

    /** Bound synthetic execution consumes a live freeze; the caller cannot replace it with a
     * stopped-writers assertion. Closing/reclaiming must succeed before returning Completed.
     * The factory remains synthetic and cannot grant personal maintenance admission. */
    static Completed backupSynthetic(FrozenSource frozen,Path tools,Path encrypted,String backupId,String logicalId,char[] passphrase) throws IOException {
        try(frozen) {
            require(frozen!=null);frozen.claim();
            return backupSynthetic(frozen.administrator,tools,frozen.knowledge,encrypted,
                backupId,logicalId,true,passphrase,frozen.binding,frozen);
        } catch(IOException failure){throw failure;}
        catch(Exception rejected){throw new IOException("Source authorization unavailable; no registration");
        } finally {if(passphrase!=null)Arrays.fill(passphrase,'\0');}
    }

    private static Completed backupSynthetic(Endpoint administrator,Path tools,Path knowledge,Path encrypted,
            String backupId,String logicalId,boolean writersStopped,char[] passphrase,RecoverySource binding) throws IOException {
        return backupSynthetic(administrator,tools,knowledge,encrypted,backupId,logicalId,writersStopped,passphrase,binding,null);
    }

    private static Completed backupSynthetic(Endpoint administrator,Path tools,Path knowledge,Path encrypted,
            String backupId,String logicalId,boolean writersStopped,char[] passphrase,RecoverySource binding,FrozenSource frozen) throws IOException {
        OwnedRole owned=null;PortableEncryptedBackup.WorkspaceBackup backup=null;Exception failure=null;String stage="SOURCE_ADMISSION";
        try {
            require(writersStopped&&administrator.database().matches("personal_ai_workspace_b3_[0-9a-f]{12}")&&administrator.port()!=5432);
            require(backupId!=null&&backupId.matches("[0-9a-f]{32}")&&logicalId!=null
                &&UUID.fromString(logicalId).toString().equals(logicalId)&&!new UUID(0,0).toString().equals(logicalId));
            require(passphrase!=null&&passphrase.length>=16&&passphrase.length<=1024);
            // Reject an unusable destination before granting anything. The encrypted engine
            // still performs its complete CREATE_NEW/path admission and never overwrites.
            PortableEncryptedBackup.stagingParent(encrypted.toAbsolutePath().normalize());
            require(!java.nio.file.Files.exists(encrypted,java.nio.file.LinkOption.NOFOLLOW_LINKS));
            if(frozen!=null)frozen.verifyContents();
            owned=new OwnedRole(administrator,backupId,binding,frozen);
            stage="SOURCE_ROLE_PROVISION";
            Endpoint reader=owned.provision();
            if(frozen!=null)frozen.reader=reader; // Bind the actual scoped role endpoint, not caller metadata.
            stage="ENCRYPTED_BACKUP";
            backup=frozen==null?new PortableEncryptedBackup(tools).backupWorkspace(reader,knowledge,encrypted,writersStopped,passphrase,logicalId)
                :new PortableEncryptedBackup(tools).backupFrozenWorkspace(reader,knowledge,encrypted,passphrase,logicalId,frozen);
            stage="SOURCE_IDENTITY";
            require(backup.sourceDatabaseOid()==owned.source.databaseOid()
                &&backup.sourcePort()==owned.source.port()&&backup.sourceDatabase().equals(owned.source.database()));
        } catch(Exception rejected){failure=rejected;}
        finally {
            if(passphrase!=null)Arrays.fill(passphrase,'\0');
            // Also reclaim when the engine throws an Error. Do not hide cleanup failure behind
            // ciphertext completion. No DROP OWNED, process termination or automatic retry.
            if(owned!=null)try{owned.reclaim();}catch(Exception cleanup){throw new IOException("Source backup stopped: CREDENTIAL_CLEANUP_REQUIRED; retain attempt; no registration");}
        }
        if(failure!=null)throw new IOException("Source backup stopped: BACKUP_INCOMPLETE; stage="+stage+"; kind="+failure.getClass().getSimpleName()+"; no registration");
        require(owned!=null&&backup!=null);
        // Cleanup relies on the already-frozen server/database/role receipt, not continued app
        // login availability. A changed app configuration must prevent publication without
        // preventing reclamation of this attempt's known short-lived credentials.
        if(binding!=null)try(var db=administrator.dataSource().getConnection()){
            binding.verifyAdministrator(db);owned.requireBinding();
            if(frozen!=null){frozen.verifyContents();require(scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE datid="+owned.source.databaseOid()
                +" AND pid<>pg_backend_pid()"+frozen.excludeOwnedBackend())==0);}
        }catch(Exception rejected){throw new IOException("Source backup stopped: SOURCE_BINDING_CHANGED; no registration");}
        return new Completed(backupId,owned.role,owned.source,backup,binding);
    }

    private static final class OwnedRole {
        private final Endpoint administrator;
        private final RecoverySource binding;
        private final FrozenSource frozen;
        private final String role,receipt;
        private SourceIdentity source;
        private long roleOid;
        private boolean creationAttempted;
        OwnedRole(Endpoint administrator,String backupId,RecoverySource binding,FrozenSource frozen){
            this.administrator=administrator;this.binding=binding;this.frozen=frozen;role="workspace_backup_src_"+backupId;receipt="workspace-backup-source:"+backupId;
        }
        Endpoint provision() throws Exception {
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);
            String password;try{password=Base64.getEncoder().encodeToString(random);}finally{Arrays.fill(random,(byte)0);}
            try(var db=administrator.dataSource().getConnection()){
                if(binding!=null)binding.verifyAdministrator(db); // App connection closes before the no-other-client check.
                source=identity(db,administrator);
                requireBinding();
                WorkspaceV3Catalog.verify(db);
                require(scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE datid="+source.databaseOid()+" AND pid<>pg_backend_pid()"
                    +(frozen==null?"":frozen.excludeOwnedBackend()))==0);
                require(scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname='"+role+"'")==0);
                db.setAutoCommit(false);
                try {
                    creationAttempted=true;
                    if(frozen!=null)frozen.requireHeld();
                    sql(db,"CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT");
                    roleOid=scalar(db,"SELECT oid FROM pg_roles WHERE rolname='"+role+"'");
                    sql(db,"COMMENT ON ROLE "+role+" IS '"+receipt+"'");
                    sql(db,"GRANT CONNECT ON DATABASE "+source.database()+" TO "+role);
                    sql(db,"GRANT USAGE ON SCHEMA workspace TO "+role);
                    for(String table:WorkspaceV3Catalog.TABLES)sql(db,"GRANT SELECT,UPDATE ON TABLE "+table+" TO "+role);
                    // PUBLIC grants are inherited even with NOINHERIT. Refuse them; never
                    // revoke broad grants on an existing source/other database to force success.
                    require(scalar(db,"SELECT count(*) FROM pg_database WHERE datallowconn AND oid<>"+source.databaseOid()
                        +" AND has_database_privilege('"+role+"',oid,'CONNECT')")==0);
                    require(scalar(db,"SELECT has_database_privilege('"+role+"',current_database(),'CREATE,TEMP')::int")==0);
                    require(scalar(db,"SELECT count(*) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('workspace','information_schema')"
                        +" AND has_schema_privilege('"+role+"',oid,'USAGE,CREATE')")==0);
                    require(scalar(db,"SELECT has_schema_privilege('"+role+"','workspace','CREATE')::int")==0);
                    require(scalar(db,"SELECT count(*) FROM pg_auth_members WHERE member="+roleOid+" OR roleid="+roleOid+" OR grantor="+roleOid)==0);
                    sql(db,"ALTER ROLE "+role+" LOGIN PASSWORD '"+password+"' VALID UNTIL '"+java.time.Instant.now().plusSeconds(1800)+"'");
                    db.commit();
                }catch(Exception rejected){try{db.rollback();}catch(Exception ignored){}throw rejected;}
            }
            return new Endpoint(administrator.database(),role,password,administrator.port());
        }
        void reclaim() throws Exception {
            if(!creationAttempted)return;
            try(var db=administrator.dataSource().getConnection()){
                require(source.equals(identity(db,administrator)));
                long existing=scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname='"+role+"'");
                if(existing==0)return; // Failed transaction rolled back, verified on the same source.
                require(roleOid!=0&&scalar(db,"SELECT count(*) FROM pg_roles WHERE oid="+roleOid+" AND rolname='"+role
                    +"' AND shobj_description(oid,'pg_authid')='"+receipt+"'")==1);
                // Commit login/password revocation first; an unknown dependency must leave the
                // owned role disabled even if the subsequent narrowly scoped revoke/drop fails.
                sql(db,"ALTER ROLE "+role+" NOLOGIN PASSWORD NULL");
                require(scalar(db,"SELECT count(*) FROM pg_authid WHERE oid="+roleOid+" AND NOT rolcanlogin AND rolpassword IS NULL")==1);
                require(scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE usesysid="+roleOid)==0);
                require(scalar(db,"SELECT count(*) FROM pg_auth_members WHERE member="+roleOid+" OR roleid="+roleOid+" OR grantor="+roleOid)==0);
                db.setAutoCommit(false);
                try {
                    for(String table:WorkspaceV3Catalog.TABLES)sql(db,"REVOKE SELECT,UPDATE ON TABLE "+table+" FROM "+role);
                    sql(db,"REVOKE USAGE ON SCHEMA workspace FROM "+role);
                    sql(db,"REVOKE CONNECT ON DATABASE "+source.database()+" FROM "+role);
                    sql(db,"DROP ROLE "+role); // Server refuses unexpected dependencies; no CASCADE.
                    db.commit();
                }catch(Exception rejected){try{db.rollback();}catch(Exception ignored){}throw rejected;}
                require(scalar(db,"SELECT count(*) FROM pg_roles WHERE oid="+roleOid+" OR rolname='"+role+"'")==0);
            }
        }
        private void requireBinding() throws IOException {
            if(binding==null)return;
            var actual=binding.databaseIdentity();
            require(source.systemIdentifier().equals(actual.systemIdentifier())&&source.databaseOid()==actual.databaseOid()
                &&source.port()==binding.port()&&source.database().equals(binding.database()));
        }
    }
    private static SourceIdentity identity(Connection db,Endpoint expected) throws Exception {
        session(db);sql(db,"SET statement_timeout='15s'");sql(db,"SET lock_timeout='3s'");
        require(scalar(db,"SELECT current_setting('server_version_num')::int/10000")==17);
        require(value(db,"SELECT current_database()").equals(expected.database())&&value(db,"SELECT session_user").equals(expected.username())
            &&value(db,"SELECT current_user").equals(expected.username())
            &&scalar(db,"SELECT inet_server_port()")==expected.port());
        String receipt=value(db,"SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()");
        require(receipt!=null&&receipt.matches("workspace-b3:[0-9a-f-]{36}"));
        String system=value(db,"SELECT system_identifier::text FROM pg_control_system()");
        require(Long.parseUnsignedLong(system)!=0);
        long oid=scalar(db,"SELECT oid FROM pg_database WHERE datname=current_database()");require(oid>0);
        return new SourceIdentity(system,oid,expected.port(),expected.database(),receipt);
    }
}
