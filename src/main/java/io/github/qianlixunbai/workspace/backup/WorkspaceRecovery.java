package io.github.qianlixunbai.workspace.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;

/** One authenticated v3 backup -> fresh synthetic database -> verified, disabled target.
 * No CLI, native Profile, product source authority, READY or activation. In particular the
 * in-process Completed capability cannot be reconstructed from a passphrase or a JSON file.
 */
final class WorkspaceRecovery {
    private WorkspaceRecovery() {}
    record Verified(String operationId,String database,long databaseOid,String ownerRole,long ownerRoleOid,
                    String appRole,long appRoleOid,Result restore,String credentials) {}

    static Verified restoreNewSyntheticTarget(WorkspaceSourceBackup.Completed source,Path tools,Path staging,
                                              char[] passphrase,Endpoint administrator) throws IOException {
        String stage="SOURCE_CAPABILITY";boolean decoded=false;
        Verified verified;
        try {
            require(source!=null&&source.backup().manifest().formatVersion()==3);
            require(source.source().database().matches("personal_ai_workspace_b3_[0-9a-f]{12}")
                &&administrator.database().equals(source.source().database())
                &&administrator.port()==source.source().port()&&administrator.port()!=5432);
            var secure=new PortableEncryptedBackup(tools,PortableEncryptedBackup.StagingProtection.SYNTHETIC_ONLY);
            stage="AUTHENTICATION";
            // No administrator connection, role/DB creation, target plan or target credential
            // generation before complete AEAD, manifest, dump TOC and Knowledge validation.
            var clear=secure.decryptWorkspace(source.backup(),staging,passphrase);decoded=true;
            require(digest(clear.backup().resolve("manifest.json")).equals(source.backup().manifestSha256()));
            stage="FRESH_TARGET_PREPARATION";
            var target=new NewTarget(source,administrator,staging);
            try {
                target.prepare();
                stage="RESTORE_AND_VERIFY";
                var result=new UnifiedPostgresqlBackup(tools).restoreWorkspace(clear.backup(),target.owner(),target.app(),
                    target.receipt,staging.resolve("candidate"),source.backup());
                target.verifyDatabase(true);
                verified=new Verified(target.operation,target.database,target.databaseOid,target.ownerRole,target.ownerOid,
                    target.appRole,target.appOid,result,"BOTH_LOGINS_DISABLED_PASSWORDS_REMOVED");
            }finally{target.close();} // Cleanup failure must not hide as a suppressed restore error.
        }catch(Exception failure){
            if(failure instanceof IOException io&&io.getMessage()!=null&&io.getMessage().contains("TARGET_CREDENTIAL_CLEANUP_REQUIRED"))throw io;
            throw new IOException("Synthetic recovery stopped: "+stage+"; no activation; retain target and attempt");
        }finally{
            if(passphrase!=null)Arrays.fill(passphrase,'\0');
            if(decoded)MaintenanceStaging.clean(staging.toAbsolutePath().normalize());
        }
        // A failure/unknown SQL or file outcome retains its original plan and target. This
        // method never drops/reuses a database, repairs a plan or retries an existing staging.
        write(staging.resolve("SYNTHETIC_RECOVERY_VERIFIED.json"),JSON.writeValueAsBytes(verified));
        return verified;
    }

    private static final class NewTarget implements AutoCloseable {
        private final WorkspaceSourceBackup.Completed source;
        private final Endpoint administrator;
        private final Path staging;
        private final String operation=UUID.randomUUID().toString().replace("-","");
        private final String suffix=operation.substring(0,12),database="personal_ai_workspace_b3_"+suffix;
        private final String ownerRole="workspace_b3_owner_"+suffix,appRole="workspace_b3_app_"+suffix;
        private final String receipt="workspace-b3:"+UUID.randomUUID(),roleReceipt="workspace-recovery-synthetic:"+operation;
        private final String ownerPassword=password(),appPassword=password();
        private Connection db;
        private long ownerOid,appOid,databaseOid;
        NewTarget(WorkspaceSourceBackup.Completed source,Endpoint administrator,Path staging){
            this.source=source;this.administrator=administrator;this.staging=staging;
        }
        Endpoint owner(){return new Endpoint(database,ownerRole,ownerPassword,administrator.port());}
        Endpoint app(){return new Endpoint(database,appRole,appPassword,administrator.port());}
        void prepare() throws Exception {
            db=administrator.dataSource().getConnection();session(db);
            sql(db,"SET statement_timeout='15s'");sql(db,"SET lock_timeout='3s'");
            var expected=source.source();
            require(scalar(db,"SELECT current_setting('server_version_num')::int/10000")==17
                &&value(db,"SELECT current_database()").equals(expected.database())
                &&value(db,"SELECT session_user").equals(administrator.username())
                &&scalar(db,"SELECT inet_server_port()")==expected.port()
                &&value(db,"SELECT system_identifier::text FROM pg_control_system()").equals(expected.systemIdentifier())
                &&scalar(db,"SELECT oid FROM pg_database WHERE datname=current_database()")==expected.databaseOid()
                &&value(db,"SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()").equals(expected.receipt()));
            require(!database.equals(expected.database())&&scalar(db,"SELECT count(*) FROM pg_database WHERE datname='"+database+"'")==0
                &&scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname IN ('"+ownerRole+"','"+appRole+"')")==0);
            write(staging.resolve("SYNTHETIC_RESTORE_PLAN.json"),JSON.writeValueAsBytes(Map.of(
                "operationId",operation,"backupId",source.backupId(),"source",expected,"targetDatabase",database,
                "ownerRole",ownerRole,"appRole",appRole,"ownershipReceipt",receipt,
                "payloadSha256",source.backup().completion().sha256(),"manifestSha256",source.backup().manifestSha256(),
                "state","PREPARING_NOT_REPLAYABLE")));
            // Roles start disabled. The transaction cannot leave a usable partial credential.
            db.setAutoCommit(false);
            try {
                for(String role:List.of(ownerRole,appRole)){
                    sql(db,"CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT");
                    sql(db,"COMMENT ON ROLE "+role+" IS '"+roleReceipt+"'");
                }
                ownerOid=scalar(db,"SELECT oid FROM pg_roles WHERE rolname='"+ownerRole+"'");
                appOid=scalar(db,"SELECT oid FROM pg_roles WHERE rolname='"+appRole+"'");db.commit();
            }catch(Exception failure){try{db.rollback();}catch(Exception ignored){}throw failure;}
            finally{db.setAutoCommit(true);}
            // PostgreSQL CREATE DATABASE cannot run inside a transaction. Durable intent is
            // already present; any unknown result stops here and leaves evidence for inspection.
            sql(db,"CREATE DATABASE "+database+" OWNER "+ownerRole+" TEMPLATE template0 ENCODING 'UTF8'");
            databaseOid=scalar(db,"SELECT oid FROM pg_database WHERE datname='"+database+"'");
            sql(db,"COMMENT ON DATABASE "+database+" IS '"+receipt+"'");
            sql(db,"REVOKE ALL ON DATABASE "+database+" FROM PUBLIC");
            sql(db,"GRANT CONNECT ON DATABASE "+database+" TO "+appRole);
            verifyDatabase(false);
            for(String role:List.of(ownerRole,appRole)){
                require(scalar(db,"SELECT count(*) FROM pg_database WHERE datallowconn AND oid<>"+databaseOid
                    +" AND has_database_privilege('"+role+"',oid,'CONNECT')")==0);
                require(scalar(db,"SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='"+role+"')")==0);
            }
            require(scalar(db,"SELECT has_database_privilege('"+appRole+"','"+database+"','CREATE,TEMP')::int")==0);
            write(staging.resolve("SYNTHETIC_TARGET_PREPARED.json"),JSON.writeValueAsBytes(Map.of(
                "operationId",operation,"databaseOid",databaseOid,"ownerRoleOid",ownerOid,"appRoleOid",appOid)));
            sql(db,"ALTER ROLE "+ownerRole+" LOGIN PASSWORD '"+ownerPassword+"' VALID UNTIL '"+java.time.Instant.now().plusSeconds(1800)+"'");
            sql(db,"ALTER ROLE "+appRole+" LOGIN PASSWORD '"+appPassword+"' VALID UNTIL '"+java.time.Instant.now().plusSeconds(1800)+"'");
        }
        void verifyDatabase(boolean attempted) throws Exception {
            require(databaseOid>0&&scalar(db,"SELECT count(*) FROM pg_database WHERE oid="+databaseOid
                +" AND datname='"+database+"' AND datdba="+ownerOid)==1);
            String actual=value(db,"SELECT shobj_description("+databaseOid+",'pg_database')");
            if(attempted)require(actual!=null&&actual.startsWith(receipt+":restore-attempted:")
                &&UUID.fromString(actual.substring((receipt+":restore-attempted:").length())).toString()
                    .equals(actual.substring((receipt+":restore-attempted:").length())));
            else require(receipt.equals(actual));
        }
        @Override public void close() throws IOException {
            if(db==null)return;
            try {
                if(!db.getAutoCommit()){db.rollback();db.setAutoCommit(true);}
                Exception cleanupFailure=null;
                for(int i=0;i<2;i++)try{
                    String role=i==0?ownerRole:appRole;long oid=i==0?ownerOid:appOid;
                    if(oid==0)continue; // No credentials were enabled before both OIDs were known.
                    long count=scalar(db,"SELECT count(*) FROM pg_roles WHERE oid="+oid+" OR rolname='"+role+"'");
                    if(count==0)continue; // Verified rollback of role creation on this connection.
                    require(count==1&&scalar(db,"SELECT count(*) FROM pg_roles WHERE oid="+oid+" AND rolname='"+role
                        +"' AND shobj_description(oid,'pg_authid')='"+roleReceipt+"'")==1);
                    sql(db,"ALTER ROLE "+role+" NOLOGIN PASSWORD NULL");
                    require(scalar(db,"SELECT count(*) FROM pg_authid WHERE oid="+oid+" AND NOT rolcanlogin AND rolpassword IS NULL")==1
                        &&scalar(db,"SELECT count(*) FROM pg_stat_activity WHERE usesysid="+oid)==0);
                }catch(Exception failure){cleanupFailure=failure;} // Attempt both known roles.
                if(cleanupFailure!=null)throw cleanupFailure;
            }catch(Exception failure){throw new IOException("Synthetic recovery stopped: TARGET_CREDENTIAL_CLEANUP_REQUIRED; retain target and attempt");}
            finally{try{db.close();}catch(Exception failure){throw new IOException("Synthetic recovery stopped: TARGET_CREDENTIAL_CLEANUP_REQUIRED; connection close unknown");}}
        }
        private static String password(){
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            try{return Base64.getEncoder().encodeToString(bytes);}finally{Arrays.fill(bytes,(byte)0);}
        }
    }
}
