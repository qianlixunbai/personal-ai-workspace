package io.github.qianlixunbai.workspace.backup;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Manual Windows-only experiment on one newly created, owned cluster. This is NOT a
 * personal admission API: no Profile, runtime lock, history or source registration is created. */
public final class LegacySourceFenceProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static String stage = "ISOLATION";
    private static void require(boolean value) throws IOException { if (!value) throw new IOException(); }
    private static void sql(Connection db,String query) throws SQLException { try(var s=db.createStatement()){s.execute(query);} }
    private static String value(Connection db,String query) throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery(query)){if(!r.next())throw new SQLException();return r.getString(1);}
    }
    private static Connection connect(int port,String database,String user,String password) throws SQLException {
        var ds=new PGSimpleDataSource();ds.setURL("jdbc:postgresql://127.0.0.1:"+port+"/"+database);
        ds.setUser(user);ds.setPassword(password);ds.setConnectTimeout(3);ds.setSocketTimeout(10);return ds.getConnection();
    }
    private static void denied(int port,String database,String user,String password) throws Exception {
        try(var unexpected=connect(port,database,user,password)){throw new IOException("Fence failed");}
        catch(SQLException rejected){require("28000".equals(rejected.getSQLState()));}
    }
    private static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void replaceOwned(Path hba,byte[] expected,byte[] next,String suffix) throws Exception {
        require(Arrays.equals(Files.readAllBytes(hba),expected));
        Path temporary=hba.resolveSibling("pg_hba."+suffix);
        try(var output=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var bytes=java.nio.ByteBuffer.wrap(next);while(bytes.hasRemaining())output.write(bytes);output.force(true);
        }
        require(Arrays.equals(Files.readAllBytes(hba),expected));
        Files.move(temporary,hba,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        require(Arrays.equals(Files.readAllBytes(hba),next));
    }
    public static void main(String[] args) {
        try { run(args); }
        catch(Exception failure){System.out.println("BLOCKED: isolated fence; stage="+stage+"; type="+failure.getClass().getSimpleName());System.exit(10);}
    }
    private static void run(String[] args) throws Exception {
        require(args.length==1&&System.getProperty("os.name").startsWith("Windows"));
        Path fixture=Path.of(args[0]).toAbsolutePath().normalize();
        var input=JSON.readTree(Files.readAllBytes(fixture));Path root=Path.of(input.path("root").asString());
        require(root.equals(root.toRealPath())&&root.getFileName().toString().matches("paw-legacy-fence-[0-9a-f]{32}")
            &&fixture.equals(root.resolve("fixture.json")));
        Path data=root.resolve("pgdata"),tools=Path.of(input.path("tools").asString());
        int port=input.path("port").asInt();require(port>1024&&port!=5432);
        var pid=Files.readAllLines(data.resolve("postmaster.pid"));
        require(pid.get(1).equals(data.toString().replace('\\','/'))&&pid.get(3).equals(Integer.toString(port))
            &&Long.parseLong(pid.getFirst())==input.path("processId").asLong());
        String admin=input.path("adminUsername").asString(),secret=input.path("adminPassword").asString();
        require(admin.equals("legacy_fixture_admin"));
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        String source="personal_ai_workspace_b3_"+suffix,unrelated="legacy_other_"+suffix;
        String app="legacy_app_"+suffix,reader="legacy_reader_"+suffix;
        String appPassword=UUID.randomUUID().toString(),readerPassword=UUID.randomUUID().toString();
        Path hba=data.resolve("pg_hba.conf");byte[] original=Files.readAllBytes(hba),installed=null;
        boolean restored=false,legacyStayedConnected=false;
        try(var control=connect(port,"postgres",admin,secret)){
            require(value(control,"SHOW data_directory").replace('\\','/').equals(data.toString().replace('\\','/'))
                &&value(control,"SHOW hba_file").replace('\\','/').equals(hba.toString().replace('\\','/'))
                &&value(control,"SHOW server_version_num").startsWith("17")
                &&value(control,"SELECT count(*) FROM pg_database").equals("3")
                &&value(control,"SELECT count(*) FROM pg_roles WHERE rolname !~ '^pg_'").equals("1"));
            stage="NEW_SYNTHETIC_DATABASES";
            for(String name:List.of(source,unrelated))sql(control,"CREATE DATABASE "+name+" TEMPLATE template0");
            sql(control,"CREATE ROLE "+app+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+appPassword+"'");
            sql(control,"CREATE ROLE "+reader+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+readerPassword+"'");
            try(var setup=connect(port,source,admin,secret)){
                sql(setup,"CREATE SCHEMA workspace");sql(setup,"CREATE TABLE workspace.synthetic_source(id integer PRIMARY KEY,value text NOT NULL)");
                sql(setup,"INSERT INTO workspace.synthetic_source VALUES(1,'INITIAL')");
                sql(setup,"GRANT USAGE ON SCHEMA workspace TO "+app+","+reader);
                sql(setup,"GRANT SELECT,UPDATE ON workspace.synthetic_source TO "+app+","+reader);
            }
            try(var setup=connect(port,unrelated,admin,secret)){
                sql(setup,"CREATE TABLE public.sentinel(value text)");sql(setup,"INSERT INTO public.sentinel VALUES('UNCHANGED')");
            }
            try(var legacy=connect(port,source,app,appPassword);var owner=connect(port,source,reader,readerPassword)){
                stage="INSTALL_EXACT_DATABASE_FENCE";
                String rules="host "+source+" "+reader+" 127.0.0.1/32 scram-sha-256\r\n"
                    +"host "+source+" all 0.0.0.0/0 reject\r\n"+"host "+source+" all ::0/0 reject\r\n";
                installed=(rules+new String(original,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                Files.write(root.resolve("hba-original.retained"),original,StandardOpenOption.CREATE_NEW);
                Files.writeString(root.resolve("fence-intent.json"),JSON.writeValueAsString(Map.of("source",source,"originalHbaSha256",sha(original),
                    "installedHbaSha256",sha(installed),"legacyHistory","UNKNOWN","personalAdmission",false)),StandardOpenOption.CREATE_NEW);
                replaceOwned(hba,original,installed,"install");
                try(var hbaPin=FileChannel.open(hba,StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE)){
                    require(value(control,"SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL").equals("0"));
                    denied(port,source,app,appPassword);denied(port,source,admin,secret);
                    try(var other=connect(port,unrelated,admin,secret)){require(value(other,"SELECT value FROM public.sentinel").equals("UNCHANGED"));}
                    stage="EXISTING_SESSION_MUST_DRAIN";
                    // HBA cannot terminate or neutralize an existing session. Demonstrate this
                    // explicitly; a gate without a subsequent drain is not writer exclusion.
                    require(value(owner,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()").equals("1"));
                    sql(legacy,"UPDATE workspace.synthetic_source SET value='LEGACY_DRAINED' WHERE id=1");
                    legacyStayedConnected=true;legacy.close(); // Only the probe voluntarily closes its own connection.
                    boolean drained=false;
                    for(int attempt=0;attempt<100&&!drained;attempt++){
                        drained=value(owner,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()").equals("0");
                        if(!drained)Thread.sleep(50);
                    }
                    require(drained);
                    require(value(owner,"SELECT count(*) FROM pg_prepared_xacts WHERE database=current_database()").equals("0"));
                    stage="LIVE_SNAPSHOT_AND_FILE_PIN";
                    owner.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);owner.setAutoCommit(false);
                    sql(owner,"SET LOCAL statement_timeout='10s'");sql(owner,"LOCK TABLE workspace.synthetic_source IN SHARE MODE NOWAIT");
                    require(value(owner,"SELECT value FROM workspace.synthetic_source WHERE id=1").equals("LEGACY_DRAINED"));
                    String snapshot=value(owner,"SELECT pg_export_snapshot()");
                    Path file=root.resolve("synthetic.source"),passfile=root.resolve("dump.pgpass"),dump=root.resolve("workspace.dump");
                    byte[] contents="SYNTHETIC_SOURCE_ONLY".getBytes(StandardCharsets.UTF_8);Files.write(file,contents,StandardOpenOption.CREATE_NEW);
                    try(var pin=FileChannel.open(file,StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE)){
                        try(var writer=FileChannel.open(file,StandardOpenOption.WRITE)){throw new IllegalStateException("Write unexpectedly allowed");}
                        catch(IOException expected) { }
                        Files.writeString(passfile,"127.0.0.1:"+port+":"+source+":"+reader+":"+readerPassword+"\n",StandardOpenOption.CREATE_NEW);
                        try{
                            var launch=new ProcessBuilder(tools.resolve("pg_dump.exe").toString(),"-h","127.0.0.1","-p",Integer.toString(port),"-U",reader,
                                "-d",source,"-w","--format=custom","--schema=workspace","--snapshot="+snapshot,"--file="+dump);
                            launch.environment().keySet().removeIf(key->key.toUpperCase(Locale.ROOT).startsWith("PG"));
                            launch.environment().put("PGPASSFILE",passfile.toString());
                            launch.redirectOutput(ProcessBuilder.Redirect.DISCARD);launch.redirectError(ProcessBuilder.Redirect.DISCARD);
                            Process child=launch.start();try{require(child.waitFor(20,TimeUnit.SECONDS)&&child.exitValue()==0);}
                            finally{if(child.isAlive()){child.destroyForcibly();require(child.waitFor(5,TimeUnit.SECONDS));}}
                        }finally{Files.delete(passfile);}
                        require(Files.size(dump)>0&&Arrays.equals(Files.readAllBytes(file),contents));
                        denied(port,source,app,appPassword);
                    }
                    owner.rollback();owner.setAutoCommit(true);
                }
            }
            stage="RESTORE_ONLY_OUR_HBA_CHANGE";
            replaceOwned(hba,installed,original,"restore");restored=true;
            try(var reopened=connect(port,source,app,appPassword)){
                require(value(reopened,"SELECT value FROM workspace.synthetic_source WHERE id=1").equals("LEGACY_DRAINED"));
            }
            require(Arrays.equals(Files.readAllBytes(hba),original));
            var evidence=new LinkedHashMap<String,Object>();
            evidence.put("status","SYNTHETIC_DATABASE_FENCE_VERIFIED_MODEL_UNPROVEN");
            evidence.put("newLegacyAndAdminConnectionsDenied",true);evidence.put("existingLegacySessionRemainedLive",legacyStayedConnected);
            evidence.put("existingSessionVoluntarilyClosed",true);evidence.put("unrelatedDatabaseAccessibleAndUnchanged",true);
            evidence.put("freshRestrictedPgDumpImportedHeldSnapshot",true);evidence.put("sourceFileWriteDenied",true);
            evidence.put("hbaOriginalBytesRestored",true);evidence.put("originalAppPermissionsUnchanged",true);
            evidence.put("runtimeHistoryOrLockCreated",false);evidence.put("modelStopProven",false);evidence.put("personalAdmission",false);
            Files.writeString(root.resolve("probe-result.json"),JSON.writeValueAsString(evidence),StandardOpenOption.CREATE_NEW);
            System.out.println(JSON.writeValueAsString(evidence));
        }finally{
            // No broad rollback or replay: restore only when the exact installed bytes remain.
            if(installed!=null&&!restored&&Arrays.equals(Files.readAllBytes(hba),installed))replaceOwned(hba,installed,original,"failure-restore");
        }
    }
}
