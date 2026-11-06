package io.github.qianlixunbai.workspace.backup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;

/** One fresh cluster, one successful stream and one rejected stream. No retained credentials,
 * shared PostgreSQL service, real Profile, model process or original Knowledge files. */
@EnabledIfSystemProperty(named="source.backup.test",matches="true")
class WorkspaceSourceBackupIntegrationTest {
    @Test void sourceRoleIsReclaimedBeforeCompletedBackupCanBeReturned() throws Exception {
        Path fixture=Path.of(System.getProperty("source.backup.fixture"));privatePath(fixture,false);
        var config=JSON.readTree(Files.readAllBytes(fixture));Path root=Path.of(config.path("root").asString());privatePath(root,true);
        int port=config.path("port").asInt();assertNotEquals(5432,port);
        var pid=Files.readAllLines(root.resolve("pgdata/postmaster.pid"));
        assertEquals(root.resolve("pgdata").toString().replace('\\','/'),pid.get(1));assertEquals(Integer.toString(port),pid.get(3));
        assertEquals(config.path("processId").asLong(),Long.parseLong(pid.getFirst()));
        var admin=new Endpoint("postgres",config.path("adminUsername").asString(),config.path("adminPassword").asString(),port);
        String database="personal_ai_workspace_b3_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String receipt="workspace-b3:"+UUID.randomUUID();
        try(var db=admin.dataSource().getConnection()){
            assertEquals(root.resolve("pgdata").toString().replace('\\','/'),value(db,"SHOW data_directory").replace('\\','/'));
            assertEquals(port,scalar(db,"SELECT inet_server_port()"));
            // Exact new initdb inventory, before changing even fixture-local PUBLIC privileges.
            assertEquals(3,scalar(db,"SELECT count(*) FROM pg_database"));
            assertEquals(1,scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname !~ '^pg_'"));
            sql(db,"CREATE DATABASE "+database+" TEMPLATE template0 ENCODING 'UTF8'");
            sql(db,"COMMENT ON DATABASE "+database+" IS '"+receipt+"'");
            for(String name:List.of(database,"postgres","template1"))sql(db,"REVOKE ALL ON DATABASE "+name+" FROM PUBLIC");
        }
        var source=new Endpoint(database,admin.username(),admin.password(),port);
        try(var db=source.dataSource().getConnection()){
            sql(db,"REVOKE ALL ON SCHEMA public FROM PUBLIC");sql(db,"CREATE SCHEMA workspace");
            sql(db,"CREATE TABLE public.finance_sentinel(value text)");sql(db,"INSERT INTO public.finance_sentinel VALUES('OUTSIDE_WORKSPACE')");
        }
        Flyway.configure().dataSource(source.dataSource()).schemas("workspace").defaultSchema("workspace").createSchemas(false)
            .locations("classpath:db/workspace/migration").cleanDisabled(true).load().migrate();
        String memory=UUID.randomUUID().toString();Snapshot before;
        try(var db=source.dataSource().getConnection()){
            sql(db,"INSERT INTO workspace.memory_items VALUES('"+memory+"','PROJECT_NOTE','Synthetic','Owned-role backup','ACTIVE',1,'MANUAL',1000,1000)");
            before=snapshot(db,true);
        }
        Path knowledge=root.resolve("knowledge");directory(knowledge);directory(knowledge.resolve("sources"));
        Path encrypted=root.resolve("encrypted"),tools=Path.of(config.path("tools").asString());
        char[] password=(UUID.randomUUID().toString()+UUID.randomUUID()).toCharArray(),input=password.clone();
        String backupId=UUID.randomUUID().toString().replace("-",""),logicalId=UUID.randomUUID().toString();
        var completed=WorkspaceSourceBackup.backupSynthetic(source,tools,knowledge,encrypted,backupId,logicalId,true,input);
        assertArrayEquals(new char[input.length],input);
        assertEquals(backupId,completed.backupId());assertEquals(logicalId,completed.backup().manifest().databaseLogicalIdentity());
        assertEquals(before,completed.backup().manifest().snapshot());assertEquals("CompletedSourceBackup[private]",completed.toString());
        try(var db=source.dataSource().getConnection()){
            assertEquals(value(db,"SELECT system_identifier::text FROM pg_control_system()"),completed.source().systemIdentifier());
            assertEquals(scalar(db,"SELECT oid FROM pg_database WHERE datname=current_database()"),completed.source().databaseOid());
            assertEquals(0,scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname='"+completed.sourceRole()+"'"));
            assertEquals(before,snapshot(db,true));assertEquals("OUTSIDE_WORKSPACE",value(db,"SELECT value FROM public.finance_sentinel"));
        }
        // Authenticate/decrypt the real output and compare the exact bytes written by the stream,
        // not a reserialized manifest or caller-supplied SHA. No SQL restore or personal staging.
        var secure=new PortableEncryptedBackup(tools,PortableEncryptedBackup.StagingProtection.SYNTHETIC_ONLY);
        Path staging=root.resolve("verification");
        var decrypted=secure.decryptWorkspace(completed.backup(),staging,password.clone());
        assertEquals(completed.backup().manifestSha256(),digest(decrypted.backup().resolve("manifest.json")));
        assertEquals(completed.backup().completion().sha256(),digest(encrypted.resolve("backup.pgp")));
        assertEquals(completed.backup().completion().bytes(),Files.size(encrypted.resolve("backup.pgp")));
        MaintenanceStaging.clean(staging);
        String failed=UUID.randomUUID().toString().replace("-","");
        // Catalog validation passes at provision. Actual snapshot validation rejects this row,
        // after role creation; the owner must still remove its privileges/login/role.
        try(var db=source.dataSource().getConnection()){
            sql(db,"INSERT INTO workspace.conversations VALUES('"+memory+"','Synthetic','ACTIVE',1000,1000)");
            sql(db,"INSERT INTO workspace.conversation_turns VALUES('"+memory+"','"+memory+"',1,'PENDING',1000,1000,NULL,NULL)");
        }
        char[] rejectedPassword=password.clone();
        var failure=assertThrows(IOException.class,()->WorkspaceSourceBackup.backupSynthetic(source,tools,knowledge,root.resolve("rejected"),
            failed,UUID.randomUUID().toString(),true,rejectedPassword));
        assertTrue(failure.getMessage().contains("BACKUP_INCOMPLETE"));assertTrue(failure.getMessage().contains("ENCRYPTED_BACKUP"));
        assertArrayEquals(new char[rejectedPassword.length],rejectedPassword);
        assertFalse(Files.exists(root.resolve("rejected/ENCRYPTED_BACKUP_COMPLETE.json")));
        try(var db=source.dataSource().getConnection()){
            assertEquals(0,scalar(db,"SELECT count(*) FROM pg_roles WHERE rolname LIKE 'workspace_backup_src_%'"));
            assertEquals(1,scalar(db,"SELECT count(*) FROM workspace.conversation_turns WHERE status='PENDING'")); // Not repaired by backup.
        }
        Arrays.fill(password,'\0');
        write(root.resolve("source-backup-evidence.json"),JSON.writeValueAsBytes(Map.of("status","SYNTHETIC_SOURCE_LIFECYCLE_VERIFIED",
            "backupId",backupId,"manifestSha256",completed.backup().manifestSha256(),"payloadSha256",completed.backup().completion().sha256(),
            "sourceRoleReclaimed",true,"failureRoleReclaimed",true,"personalSourceRegistration",false)));
    }
}
