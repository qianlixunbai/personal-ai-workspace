package io.github.qianlixunbai.workspace.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Native-only read-only inspection. Never initializes, migrates, binds identities or switches stores. */
@Service
public final class StorageStatusService {
    private static final String PREFIX="workspace.persistence.postgresql.";
    private final Environment environment;
    private final ObjectProvider<DataSource> finance;
    public StorageStatusService(Environment environment,@Qualifier("financeDataSource") ObjectProvider<DataSource> finance) {
        this.environment=environment;this.finance=finance;
    }
    public record Status(String checkedAt,String backend,boolean postgresqlConfigured,String connection,
        String workspaceSchema,String knowledgeSchema,boolean financeEnabled,String financeSchema,
        String migration,String encryptedBackup,String restore,List<String> securityRequirements,String errorCode) {}
    private record Check(String connection,String workspace,String knowledge,String error) {}

    public Status inspect() {
        String backend="UNSUPPORTED",financeSchema="DISABLED";boolean configured=false,financeEnabled=false;
        Check check=new Check("NOT_CONFIGURED","NOT_CHECKED","NOT_CHECKED","NONE");
        try {
            backend=switch(environment.getProperty("workspace.persistence.backend","sqlite")) {
                case "sqlite"->"SQLITE";case "postgresql"->"POSTGRESQL";default->"UNSUPPORTED";
            };
            financeEnabled=environment.getProperty("workspace.finance.enabled",Boolean.class,false);
            int present=0;for(String field:List.of("url","username","password"))if(!environment.getProperty(PREFIX+field,"").isBlank())present++;
            configured=present==3;
            if(present>0&&present<3)check=new Check("CONFIGURATION_INVALID","NOT_CHECKED","NOT_CHECKED","CONFIGURATION_INCOMPLETE");
            else if(configured) {
                if(environment.getProperty(PREFIX+"username","").equalsIgnoreCase("postgres"))
                    check=new Check("ROLE_REJECTED","NOT_CHECKED","NOT_CHECKED","ROLE_REJECTED");
                else {
                    // Same reviewed URL/options contract as the real PostgreSQL Store wiring.
                    var source=new WorkspacePostgresqlConfiguration().workspacePostgresqlDataSource(environment);
                    source.setConnectTimeout(2);source.setSocketTimeout(3);check=inspect(source,false);
                }
            }
            if(financeEnabled) {
                if(environment.getProperty("workspace.finance.datasource.username","").equalsIgnoreCase("postgres"))financeSchema="ROLE_REJECTED";
                else {
                    DataSource source=finance.getIfAvailable();
                    if(source==null)financeSchema="NOT_CONFIGURED";
                    else {var result=inspect(source,true);financeSchema=result.connection.equals("CONNECTED")?result.workspace:result.connection;}
                }
            }
        }catch(RuntimeException invalid){
            // Configuration/provider exception messages can contain secrets or paths. Never propagate them.
            check=new Check("CONFIGURATION_INVALID","NOT_CHECKED","NOT_CHECKED","CONFIGURATION_INVALID");
            if(financeEnabled)financeSchema="NOT_CHECKED";
        }
        return new Status(Instant.now().toString(),backend,configured,check.connection,check.workspace,check.knowledge,
            financeEnabled,financeSchema,"EXPLICIT_APPROVAL_REQUIRED","MAINTENANCE_ONLY","BLOCKED_SECURITY_REQUIREMENTS",
            List.of("WINDOWS_PROFILE_APPROVAL_REQUIRED","VOLUME_PROTECTION_REQUIRED","FINANCE_IDENTITY_CONFIRMATION_REQUIRED","CROSS_DEVICE_VERIFICATION_REQUIRED"),check.error);
    }
    private static Check inspect(DataSource source,boolean finance) {
        boolean connected=false;
        try(var db=source.getConnection()) {
            connected=true;db.setReadOnly(true);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);db.setAutoCommit(false);
            try(var statement=db.createStatement()){statement.execute("SET LOCAL statement_timeout='2s'");statement.execute("SET LOCAL lock_timeout='1s'");}
            if(!flag(db,"SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname=current_user"))
                return new Check("ROLE_REJECTED","NOT_CHECKED","NOT_CHECKED","ROLE_REJECTED");
            String schema=finance?"public":"workspace";
            if(!flag(db,"SELECT to_regclass('"+schema+".flyway_schema_history') IS NOT NULL"))
                return new Check("CONNECTED","INITIALIZATION_REQUIRED","INITIALIZATION_REQUIRED","NONE");
            var versions=new ArrayList<String>();boolean failed=false;
            try(var statement=db.createStatement();var rows=statement.executeQuery("SELECT version,success FROM "+schema+".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank LIMIT 32")) {
                while(rows.next()){versions.add(rows.getString(1));failed|=!rows.getBoolean(2);}
            }
            var expected=new ArrayList<String>();for(int i=1;i<=(finance?21:2);i++)expected.add(Integer.toString(i));
            boolean workspaceV1=!finance&&versions.equals(List.of("1"));
            if(failed||!versions.equals(expected)&&!workspaceV1)return new Check("CONNECTED","INCOMPATIBLE","INCOMPATIBLE","SCHEMA_INCOMPATIBLE");
            if(finance) {
                try(var statement=db.createStatement()) {
                    statement.executeQuery("SELECT id FROM public.users LIMIT 0").close();
                    statement.executeQuery("SELECT id,user_id,currency,balance FROM public.accounts LIMIT 0").close();
                }
                return new Check("CONNECTED","READY","NOT_CHECKED","NONE");
            }
            PostgresqlWorkspace.verify(db);
            if(workspaceV1)return new Check("CONNECTED","V1_READY","INITIALIZATION_REQUIRED","NONE");
            if(!flag(db,"SELECT format=1 FROM workspace.knowledge_control WHERE singleton=1"))return new Check("CONNECTED","V1_V2_READY","INCOMPATIBLE","SCHEMA_INCOMPATIBLE");
            try(var statement=db.createStatement()) {
                statement.executeQuery("SELECT id,current_revision FROM workspace.knowledge_documents LIMIT 0").close();
                statement.executeQuery("SELECT doc_id,revision,digest,representation_digest FROM workspace.knowledge_revisions LIMIT 0").close();
                statement.executeQuery("SELECT k,v FROM workspace.knowledge_index_metadata LIMIT 0").close();
            }
            return new Check("CONNECTED","V1_V2_READY","READY","NONE");
        }catch(SQLException failure) {
            String state=failure.getSQLState()==null?"":failure.getSQLState();
            if(state.startsWith("28"))return new Check("AUTHENTICATION_FAILED","NOT_CHECKED","NOT_CHECKED","AUTHENTICATION_FAILED");
            if(state.equals("42501"))return new Check(connected?"CONNECTED":"ACCESS_DENIED","ACCESS_DENIED","ACCESS_DENIED","PERMISSION_DENIED");
            if(connected&&(state.isEmpty()||state.startsWith("42")||state.startsWith("22")))return new Check("CONNECTED","INCOMPATIBLE","INCOMPATIBLE","SCHEMA_INCOMPATIBLE");
            return new Check("CONNECTION_FAILED","NOT_CHECKED","NOT_CHECKED","CONNECTION_FAILED");
        }
    }
    private static boolean flag(Connection db,String sql) throws SQLException {
        try(var statement=db.createStatement();var rows=statement.executeQuery(sql)){return rows.next()&&rows.getBoolean(1);}
    }
}
