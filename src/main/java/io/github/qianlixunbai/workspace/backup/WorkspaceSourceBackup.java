package io.github.qianlixunbai.workspace.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
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
    static final class Completed {
        private final String backupId,sourceRole;
        private final SourceIdentity source;
        private final PortableEncryptedBackup.WorkspaceBackup backup;
        private Completed(String backupId,String sourceRole,SourceIdentity source,PortableEncryptedBackup.WorkspaceBackup backup){
            this.backupId=backupId;this.sourceRole=sourceRole;this.source=source;this.backup=backup;
        }
        String backupId(){return backupId;}
        String sourceRole(){return sourceRole;}
        SourceIdentity source(){return source;}
        PortableEncryptedBackup.WorkspaceBackup backup(){return backup;}
        @Override public String toString(){return "CompletedSourceBackup[private]";}
    }

    /** Administrator material is provided only by the owning synthetic fixture, never loaded
     * from existing service/Profile credentials. The future native owner must replace this
     * synthetic entry with an admitted source context; deleting the restrictions is not enough.
     * Consumes the encryption passphrase on every outcome. Never modifies existing app grants.
     */
    static Completed backupSynthetic(Endpoint administrator,Path tools,Path knowledge,Path encrypted,
            String backupId,String logicalId,boolean writersStopped,char[] passphrase) throws IOException {
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
            owned=new OwnedRole(administrator,backupId);
            stage="SOURCE_ROLE_PROVISION";
            Endpoint reader=owned.provision();
            stage="ENCRYPTED_BACKUP";
            backup=new PortableEncryptedBackup(tools).backupWorkspace(reader,knowledge,encrypted,writersStopped,passphrase,logicalId);
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
        return new Completed(backupId,owned.role,owned.source,backup);
    }

    private static final class OwnedRole {
        private final Endpoint administrator;
        private final String role,receipt;
        private SourceIdentity source;
        private long roleOid;
        private boolean creationAttempted;
        OwnedRole(Endpoint administrator,String backupId){
            this.administrator=administrator;role="workspace_backup_src_"+backupId;receipt="workspace-backup-source:"+backupId;
        }
        Endpoint provision() throws Exception {
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);
            String password;try{password=Base64.getEncoder().encodeToString(random);}finally{Arrays.fill(random,(byte)0);}
            try(var db=administrator.dataSource().getConnection()){
                source=identity(db,administrator);
                WorkspaceV3Catalog.verify(db);
                require(scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE datid="+source.databaseOid()+" AND pid<>pg_backend_pid()")==0);
                require(scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname='"+role+"'")==0);
                db.setAutoCommit(false);
                try {
                    creationAttempted=true;
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
    }
    private static SourceIdentity identity(Connection db,Endpoint expected) throws Exception {
        session(db);sql(db,"SET statement_timeout='15s'");sql(db,"SET lock_timeout='3s'");
        require(scalar(db,"SELECT current_setting('server_version_num')::int/10000")==17);
        require(value(db,"SELECT current_database()").equals(expected.database())&&value(db,"SELECT session_user").equals(expected.username())
            &&scalar(db,"SELECT inet_server_port()")==expected.port());
        String receipt=value(db,"SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()");
        require(receipt!=null&&receipt.matches("workspace-b3:[0-9a-f-]{36}"));
        String system=value(db,"SELECT system_identifier::text FROM pg_control_system()");
        require(Long.parseUnsignedLong(system)!=0);
        long oid=scalar(db,"SELECT oid FROM pg_database WHERE datname=current_database()");require(oid>0);
        return new SourceIdentity(system,oid,expected.port(),expected.database(),receipt);
    }
}
