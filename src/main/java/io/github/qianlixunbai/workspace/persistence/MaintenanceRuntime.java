package io.github.qianlixunbai.workspace.persistence;

import com.sun.jna.platform.win32.Crypt32Util;
import io.github.qianlixunbai.workspace.PersonalAiWorkspaceApplication;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** One native-owned activation session. Historical records never authorize ordinary startup.
 * Source/configuration pins overlap both children; only the new Runtime's actual DataSource
 * may end its target bootstrap lock, immediately before normal store initialization. */
public final class MaintenanceRuntime implements AutoCloseable {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Intent(int version,String kind,String profile,String rootIdentity,String operation,long runtimePid,
                          String challenge,String configuration,SqliteCombinedMigration.Expected expected,String modelState){}
    record Event(int version,String kind,String operation,long runtimePid,String challenge,String configuration,
                         String previous,String stage,List<Long> backends){}
    private final MaintenanceExclusion exclusion;private final Path root,dir;private final Intent intent;
    private final String session,recordKey;
    private final List<AutoCloseable> pins=new ArrayList<>();
    private MaintenanceConfiguration.ActivationConfiguration configuration;
    private SqliteMaintenanceOwner.RuntimeSource source;private BoundDataSource dataSource;
    private MaintenanceModelState modelState;private io.github.qianlixunbai.workspace.model.ModelStateStore modelStore;
    private boolean modelClaimed;
    private volatile String state="PINNING";private volatile boolean serving;
    private boolean closed;private String previous;private int sequence;
    private MaintenanceRuntime(MaintenanceExclusion exclusion,Path root,String operation,String session,Intent intent)throws IOException {
        this.exclusion=exclusion;this.root=root;this.dir=MaintenanceSessions.directory(root,operation,session);this.intent=intent;
        this.session=session;this.recordKey=MaintenanceSessions.key(operation,session);
    }

    static void prepare(SqliteMaintenanceOwner owner,long pid,String challenge,String configuration)throws IOException {
        Path root=owner.configurationRoot();String operation=owner.operationId();
        if(pid<=0||pid==ProcessHandle.current().pid()||challenge==null||!challenge.matches("[a-f0-9]{64}"))throw stopped();
        Path registry=root.resolve("evidence/sqlite-activation"),dir=registry.resolve(operation);
        WindowsPrivateAcl.directory(registry);SqliteSourceLease.absent(dir);WindowsPrivateAcl.directory(dir);
        try(var registryPin=new SqliteSourceLease.DirectoryPin(registry);var directoryPin=new SqliteSourceLease.DirectoryPin(dir);
                var model=new MaintenanceModelState(root)){
            var intent=new Intent(1,"MAINTENANCE_RUNTIME_INTENT",root.getFileName().toString(),
                    TrustedDatabaseConfiguration.Identity.read(root,true),operation,pid,challenge,configuration,owner.configurationTarget(),model.digest());
            owner.requireHeld();writeRecord(dir.resolve("intent.dpapi"),intent,root,operation);
            owner.requireHeld();registryPin.verify();directoryPin.verify();
        }
    }

    /** Gate is the only caller that installs this object as this JVM's activation capability. */
    public static MaintenanceRuntime accept(MaintenanceExclusion exclusion,String operation,String session,String challenge)throws IOException {
        Path root=WindowsNativeGate.maintenanceProfileRoot(exclusion);
        if(operation==null||!operation.matches("[a-f0-9]{32}")||challenge==null||!challenge.matches("[a-f0-9]{64}"))throw stopped();
        Path dir=MaintenanceSessions.directory(root,operation,session);
        var pins=new ArrayList<AutoCloseable>();MaintenanceRuntime runtime=null;
        try {
            if(!session.equals("initial")){
                var history=MaintenanceSessions.open(root,operation,MaintenanceSessions.End.PENDING);pins.add(history);
                if(!history.session.equals(session))throw stopped();
            }
            pins.add(new SqliteSourceLease.DirectoryPin(dir.getParent()));pins.add(new SqliteSourceLease.DirectoryPin(dir));
            try(var entries=Files.list(dir)){if(!entries.map(p->p.getFileName().toString()).toList().equals(List.of("intent.dpapi")))throw stopped();}
            var pin=new SqliteSourceLease.FilePin(dir.resolve("intent.dpapi"));pins.add(pin);
            Intent intent=readRecord(pin,Intent.class,root,MaintenanceSessions.key(operation,session));
            if(intent.version!=1||!"MAINTENANCE_RUNTIME_INTENT".equals(intent.kind)||!operation.equals(intent.operation)
                    ||!root.getFileName().toString().equals(intent.profile)||intent.runtimePid!=ProcessHandle.current().pid()
                    ||!challenge.equals(intent.challenge)||!TrustedDatabaseConfiguration.Identity.read(root,true).equals(intent.rootIdentity)
                    ||intent.configuration==null||!intent.configuration.matches("[a-f0-9]{64}")||intent.expected==null
                    ||intent.expected.binding()==null||!operation.equals(intent.expected.binding().operation()))throw stopped();
            runtime=new MaintenanceRuntime(exclusion,root,operation,session,intent);runtime.pins.addAll(pins);pins.clear();runtime.previous=pin.digest();
            runtime.configuration=session.equals("initial")?MaintenanceConfiguration.ActivationConfiguration.open(root,operation)
                    :MaintenanceConfiguration.ActivationConfiguration.inspectStopped(root,operation);
            if(!runtime.configuration.expected().equals(intent.expected)||!runtime.configuration.digest().equals(intent.configuration))throw stopped();
            runtime.source=SqliteMaintenanceOwner.retainRuntimeSource(exclusion,operation,runtime.configuration);
            runtime.modelState=new MaintenanceModelState(root);
            if(!runtime.modelState.digest().equals(intent.modelState))throw stopped();
            runtime.requireOwned();runtime.append("PINNED",List.of());runtime.state="PINNED";return runtime;
        }catch(Exception failure){if(runtime!=null)runtime.close();throw stopped();}
        finally{for(int i=pins.size()-1;i>=0;i--)try{pins.get(i).close();}catch(Exception ignored){}}
    }

    public synchronized void requireRuntimeAccess()throws IOException {
        if(!Set.of("INITIALIZING","VERIFIED","STOPPING").contains(state))throw stopped();requireOwned();
    }
    private void requireOwned()throws IOException {
        if(closed||configuration==null||source==null)throw stopped();exclusion.requireHeld(root);
        configuration.requireConfiguration(root);source.requireHeld();
        for(var pin:pins)if(pin instanceof SqliteSourceLease.FilePin file)file.verify();
            else if(pin instanceof SqliteSourceLease.DirectoryPin directory)directory.verify();
            else if(pin instanceof MaintenanceSessions history)history.verifyPins();
    }
    private synchronized void beginInitialization()throws IOException {
        if(!state.equals("PINNED"))throw stopped();requireOwned();
        // This cannot succeed while the source child still owns its exclusive owner.lock.
        source.takeOwnerLock();append("INITIALIZING",List.of());state="INITIALIZING";
        modelStore=exclusion.takeModelWriter(modelState);
    }

    public synchronized io.github.qianlixunbai.workspace.model.ModelStateStore claimModelStore(Path directory)throws IOException {
        if(!state.equals("INITIALIZING")||modelClaimed||modelStore==null||!root.resolve("model-state").equals(directory))throw stopped();
        requireOwned();modelState.requireWriter();modelClaimed=true;return modelStore;
    }
    private void verifyModel(ConfigurableApplicationContext context)throws IOException {
        if(!modelClaimed)throw stopped();modelState.requireWriter();
        context.getBean(io.github.qianlixunbai.workspace.model.ActiveModelManager.class).requireMaintenanceQuiescent(modelStore);
    }

    public synchronized DataSource bindDataSource(DataSource actual)throws IOException {
        if(!state.equals("INITIALIZING")||dataSource!=null)throw stopped();requireOwned();
        // The actual Spring bean proves its backend while both bootstrap transactions still hold
        // SHARE locks. Only after that proof do we end our own bootstrap transaction. The native
        // parent already observed the old child exit; source.takeOwnerLock enforces that boundary.
        try(var proof=holdTarget(actual)) {
            configuration.releaseBootstrapTarget();verifyTarget(proof);
            dataSource=new BoundDataSource(actual);return dataSource;
        }catch(Exception failure){state="FAILED";throw stopped();}
    }

    /** Invoked under each real store's monitor, on the connection it actually initialized. */
    public synchronized long verifyBackend(Connection db)throws IOException {
        requireRuntimeAccess();
        if(dataSource==null||!dataSource.owns(db))throw stopped();return backend(db);
    }
    private long backend(Connection db)throws IOException {
        try {
            if(db.isClosed()||!db.getAutoCommit()||!MaintenanceTarget.matches(db,intent.expected.binding()))throw stopped();
            PostgresqlWorkspace.verify(db);
            try(var s=db.createStatement();var rows=s.executeQuery("SELECT pg_backend_pid()")){
                if(!rows.next())throw stopped();long pid=rows.getLong(1);if(pid<=0||rows.next())throw stopped();return pid;
            }
        }catch(Exception failure){throw stopped();}
    }
    private List<Long> storeBackends(ConfigurableApplicationContext context)throws IOException {
        if(context.getBean("workspacePostgresqlDataSource")!=dataSource)throw stopped();
        var result=List.of(context.getBean(io.github.qianlixunbai.workspace.memory.MemoryStore.class).maintenanceBackend(this),
                context.getBean(io.github.qianlixunbai.workspace.conversation.ConversationStore.class).maintenanceBackend(this),
                context.getBean(io.github.qianlixunbai.workspace.knowledge.KnowledgeStore.class).maintenanceBackend(this));
        if(new HashSet<>(result).size()!=3)throw stopped();return result;
    }
    private List<Long> verifyStarted(ConfigurableApplicationContext context)throws IOException {
        synchronized(this){if(!state.equals("INITIALIZING")||!context.isActive()||dataSource==null)throw stopped();requireOwned();}
        var backends=storeBackends(context);
        verifyModel(context);
        try(var proof=holdTarget(dataSource.delegate)){
            requireOwned();verifyTarget(proof);append("VERIFIED",backends);state="VERIFIED";return backends;
        }catch(Exception failure){throw stopped();}
    }
    private void finish(ConfigurableApplicationContext context)throws IOException {
        synchronized(this){if(!state.equals("VERIFIED"))throw stopped();requireOwned();serving=false;}
        var backends=storeBackends(context);
        verifyModel(context);
        synchronized(this){append("STOPPING",backends);state="STOPPING";}
        io.github.qianlixunbai.workspace.RuntimeWorkShutdown.closeContext(context);
        modelState.requireClosed();exclusion.retainStoppedModel();
        synchronized(this){
            if(context.isActive()||dataSource==null||dataSource.hasOpenConnections())throw stopped();requireOwned();
            // Later Runtime data is preserved. Finishing checks identity/configuration, not the old
            // import digest, and never deletes rows or rewrites a user's later configuration.
            try(var db=dataSource.delegate.getConnection()){
                long finalBackend=backend(db);append("VERIFIED_STOPPED",List.of(finalBackend));state="VERIFIED_STOPPED";
            }catch(Exception failure){throw stopped();}
        }
    }
    public synchronized boolean requestsAllowed(){
        if(!serving||!state.equals("VERIFIED")||closed)return false;
        try{requireOwned();return true;}catch(IOException failure){serving=false;state="FAILED";return false;}
    }
    private synchronized void serve()throws IOException {if(!state.equals("VERIFIED"))throw stopped();requireOwned();serving=true;}

    private Connection holdTarget(DataSource target)throws IOException {
        return session.equals("initial")?MaintenanceTarget.holdVerified(target,intent.expected)
                :MaintenanceTarget.holdCurrentBinding(target,intent.expected);
    }
    private void verifyTarget(Connection target)throws Exception {
        if(session.equals("initial"))MaintenanceTarget.verifyHeld(target,intent.expected);
        else MaintenanceTarget.verifyCurrentHeld(target,intent.expected);
    }

    private final class BoundDataSource extends org.springframework.jdbc.datasource.AbstractDataSource {
        final DataSource delegate;private final Set<Connection> connections=Collections.newSetFromMap(new IdentityHashMap<>());
        BoundDataSource(DataSource delegate){this.delegate=delegate;}
        public Connection getConnection()throws SQLException {
            Connection db=null;
            try {
                synchronized(MaintenanceRuntime.this){requireRuntimeAccess();}
                db=delegate.getConnection();backend(db);
                synchronized(this){connections.removeIf(c->{try{return c.isClosed();}catch(SQLException e){return false;}});
                    if(connections.size()>=64)throw stopped();connections.add(db);}
                return db;
            }catch(Exception failure){if(db!=null)try{db.close();}catch(Exception ignored){}throw new SQLException("Maintenance backend unavailable");}
        }
        public Connection getConnection(String username,String password)throws SQLException {throw new SQLException("Alternate credentials forbidden");}
        synchronized boolean owns(Connection db){return connections.contains(db);}
        synchronized boolean hasOpenConnections()throws IOException {
            try{for(var db:connections)if(!db.isClosed())return true;return false;}catch(SQLException failure){throw stopped();}
        }
    }

    private void append(String stage,List<Long> backends)throws IOException {
        requireOwned();
        String name=String.format(Locale.ROOT,"%02d-%s.dpapi",sequence,stage.toLowerCase(Locale.ROOT));
        var event=new Event(1,"MAINTENANCE_RUNTIME_EVENT",intent.operation,intent.runtimePid,intent.challenge,
                intent.configuration,previous,stage,List.copyOf(backends));
        Path file=dir.resolve(name);writeRecord(file,event,root,recordKey);
        var pin=new SqliteSourceLease.FilePin(file);pins.add(pin);
        if(!event.equals(readRecord(pin,Event.class,root,recordKey)))throw stopped();previous=pin.digest();sequence++;
    }
    static void writeRecord(Path file,Object record,Path root,String operation)throws IOException {
        byte[] plain=null,encrypted=null;
        try{plain=JSON.writeValueAsBytes(record);if(plain.length>16384)throw stopped();
            encrypted=Crypt32Util.cryptProtectData(plain,entropy(root,operation,file.getFileName().toString()),1,"Maintenance Runtime stage",null);
            if(encrypted.length>16384)throw stopped();WindowsPrivateAcl.createFile(file);
            try(var output=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                var bytes=ByteBuffer.wrap(encrypted);while(bytes.hasRemaining())output.write(bytes);output.force(true);}
        }catch(Exception failure){throw stopped();}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
    }
    static <T>T readRecord(SqliteSourceLease.FilePin pin,Class<T> type,Path root,String operation)throws IOException {
        byte[] plain=null;try{
            if(pin.channel.size()<1||pin.channel.size()>16384)throw stopped();var bytes=ByteBuffer.allocate((int)pin.channel.size());long at=0;
            while(bytes.hasRemaining()){int n=pin.channel.read(bytes,at);if(n<1)throw stopped();at+=n;}
            plain=Crypt32Util.cryptUnprotectData(bytes.array(),entropy(root,operation,pin.path.getFileName().toString()),1,null);
            T value=JSON.readValue(plain,type);pin.verify();return value;
        }catch(Exception failure){throw stopped();}finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    private static byte[] entropy(Path root,String operation,String name){return ("PersonalAIWorkspace/MaintenanceRuntime/v1/"+root.getFileName()+"/"+operation+"/"+name).getBytes(StandardCharsets.US_ASCII);}
    static IOException stopped(){return new IOException("Maintenance Runtime incomplete; preserve operation and block ordinary startup");}
    static Map<String,Object> failureFrame(String boundary,Throwable failure){
        var types=new ArrayList<String>();var frames=new ArrayList<String>();
        for(Throwable cause=failure;cause!=null&&types.size()<6;cause=cause.getCause()){
            types.add(cause.getClass().getName());for(var frame:Arrays.stream(cause.getStackTrace()).limit(4).toList())frames.add(frame.toString());
        }
        // Code locations only: never exception messages, SQL, credentials, request bodies or paths.
        return Map.of("stage","STOP","boundary",boundary,"types",types,"code",frames);
    }
    @Override public synchronized void close(){
        if(closed)return;serving=false;closed=true;
        if(source!=null)source.close();if(configuration!=null)configuration.close();
        if(modelStore!=null)try{modelStore.close();}catch(Exception ignored){}
        if(modelState!=null)modelState.close();
        for(int i=pins.size()-1;i>=0;i--)try{pins.get(i).close();}catch(Exception ignored){}
        try{exclusion.close();}catch(IOException ignored){}
    }

    static Map<String,Object> readFrame(DataInputStream input,Set<String> fields)throws IOException {
        int size=input.readInt();if(size<1||size>16384)throw stopped();byte[] bytes=input.readNBytes(size);
        try{
            if(bytes.length!=size)throw stopped();var node=JSON.readTree(bytes);
            if(!node.isObject()||node.size()!=fields.size()||!node.propertyNames().equals(fields))throw stopped();
            Map<String,Object> result=new HashMap<>();for(String name:fields){var value=node.get(name);
                if(value.isString())result.put(name,value.asString());else if(value.isIntegralNumber()&&value.canConvertToLong())result.put(name,value.asLong());else throw stopped();}
            return result;
        }finally{Arrays.fill(bytes,(byte)0);}
    }
    static void reply(DataOutputStream output,Map<String,?> value)throws IOException {
        byte[] bytes=JSON.writeValueAsBytes(value);if(bytes.length>16384)throw stopped();output.writeInt(bytes.length);output.write(bytes);output.flush();
    }
    static void command(Map<String,Object> frame,String action,String operation,String challenge)throws IOException {
        if(!action.equals(frame.get("action"))||!operation.equals(frame.get("operation"))||!challenge.equals(frame.get("challenge")))throw stopped();
    }
    private Map<String,Object> response(String stage){return Map.of("stage",stage,"operation",intent.operation,"challenge",intent.challenge,
            "pid",ProcessHandle.current().pid(),"configuration",intent.configuration,"binding",intent.expected.binding(),"modelState",intent.modelState,"session",session);}

    /** Real product entry; no alternate classpath, fake backend or privileged environment flag. */
    public static int runRuntime(String[] args,InputStream rawInput,PrintStream rawOutput){
        var input=new DataInputStream(rawInput);var output=new DataOutputStream(rawOutput);
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(System.out);
        MaintenanceRuntime runtime=null;ConfigurableApplicationContext context=null;String stage="GATE";
        try {
            WindowsNativeGate.admit();String profile=System.getProperty("workspace.native.development-profile","");
            String challenge=HexFormat.of().formatHex(new java.security.SecureRandom().generateSeed(32));
            reply(output,Map.of("stage","HELLO","pid",ProcessHandle.current().pid(),"profile",profile,"challenge",challenge));
            var accepted=readFrame(input,Set.of("action","operation","session","handle"));
            if(!"ACCEPT".equals(accepted.get("action")))throw stopped();stage="PINNING";
            runtime=WindowsNativeGate.receiveMaintenanceRuntime((Long)accepted.get("handle"),(String)accepted.get("operation"),(String)accepted.get("session"),challenge);
            WindowsNativeGate.requireMaintenanceRuntimeArguments(args,runtime);reply(output,runtime.response("PINNED"));
            command(readFrame(input,Set.of("action","operation","challenge")),"INITIALIZE",runtime.intent.operation,challenge);
            stage="INITIALIZING";runtime.beginInitialization();
            context=PersonalAiWorkspaceApplication.startRuntime(args);
            stage="BACKEND_VERIFICATION";var backends=runtime.verifyStarted(context);
            int port=((WebServerApplicationContext)context).getWebServer().getPort();if(port<1||port==18765)throw stopped();
            var verified=new HashMap<String,Object>(runtime.response("VERIFIED"));verified.put("port",port);verified.put("backends",backends);
            reply(output,verified);
            command(readFrame(input,Set.of("action","operation","challenge")),"SERVE",runtime.intent.operation,challenge);
            runtime.serve();reply(output,runtime.response("SERVING"));stage="SERVING";
            command(readFrame(input,Set.of("action","operation","challenge")),"FINISH",runtime.intent.operation,challenge);
            stage="FINISH";runtime.finish(context);reply(output,runtime.response("VERIFIED_STOPPED"));return 0;
        }catch(Exception|LinkageError failure){
            if(runtime!=null){runtime.serving=false;runtime.state="FAILED";}
            try{reply(output,failureFrame(stage,failure));}catch(Exception ignored){}return 10;
        }finally{
            if(context!=null)try{context.close();}catch(Exception ignored){}
            if(runtime!=null)runtime.close();
        }
    }

    /** Internal native source child. The generic personal maintenance UI remains BLOCKED;
     * its future caller must own explicit source/target choices before launching this transport. */
    public static int runOwner(String[] args,InputStream rawInput,PrintStream rawOutput){
        var input=new DataInputStream(rawInput);var output=new DataOutputStream(rawOutput);
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(System.out);
        String stage="GATE";byte[] plain=null;
        try{
            WindowsNativeGate.admit();reply(output,Map.of("stage","OWNER_HELLO","pid",ProcessHandle.current().pid(),
                    "profile",System.getProperty("workspace.native.development-profile","")));
            var request=readFrame(input,Set.of("action","handle","configuration"));if(!"PREPARE".equals(request.get("action")))throw stopped();
            try(var exclusion=WindowsNativeGate.receiveMaintenanceExclusion((Long)request.get("handle"))){
                WindowsNativeGate.requireMaintenanceArguments(args,exclusion);stage="SOURCE";
                plain=Base64.getDecoder().decode((String)request.get("configuration"));var config=TrustedDatabaseConfiguration.parse(plain);
                var target=new org.postgresql.ds.PGSimpleDataSource();target.setURL((String)config.getProperty("workspace.persistence.postgresql.url"));
                target.setUser((String)config.getProperty("workspace.persistence.postgresql.username"));target.setPassword((String)config.getProperty("workspace.persistence.postgresql.password"));
                target.setCurrentSchema("workspace,pg_catalog");target.setConnectTimeout(3);target.setSocketTimeout(30);
                String operation,challenge;
                try(var model=new MaintenanceModelState(WindowsNativeGate.maintenanceProfileRoot(exclusion));
                        var review=new MaintenanceMigrationReview(exclusion,target,model)){
                    stage="NATIVE_CONFIRMATION";
                    reply(output,Map.of("stage","REVIEW_PREPARED","pid",ProcessHandle.current().pid(),"operation",review.operation(),
                            "review",review.json,"digest",review.digest));
                    var decision=readFrame(input,Set.of("action","operation","digest"));
                    if(!review.operation().equals(decision.get("operation"))||!review.digest.equals(decision.get("digest")))throw stopped();
                    if("CANCEL".equals(decision.get("action"))){
                        reply(output,Map.of("stage","CANCELLED","operation",review.operation(),"digest",review.digest));return 0;
                    }
                    if(!"CONFIRM".equals(decision.get("action")))throw stopped();
                    try(var owner=review.confirm((String)decision.get("operation"),(String)decision.get("digest"))){
                    stage="CONFIRMED_MIGRATION";
                    owner.createBackup();owner.importTarget(review.target);owner.prepareConfiguration(plain);Arrays.fill(plain,(byte)0);
                    owner.publishConfiguration();operation=owner.operationId();owner.requireHeld();
                    reply(output,Map.of("stage","PUBLISHED","operation",operation,"pid",ProcessHandle.current().pid()));
                    stage="HANDOFF_INTENT";var handoff=readFrame(input,Set.of("action","operation","pid","challenge"));
                    challenge=(String)handoff.get("challenge");command(handoff,"HANDOFF",operation,challenge);
                    owner.prepareRuntimeHandoff((Long)handoff.get("pid"),challenge);
                    reply(output,Map.of("stage","OFFERED","operation",operation,"challenge",challenge));
                    command(readFrame(input,Set.of("action","operation","challenge")),"RELEASE",operation,challenge);
                    stage="RELEASE";owner.requireHeld();
                    }
                }
                reply(output,Map.of("stage","RELEASED","operation",operation,"challenge",challenge));return 0;
            }
        }catch(Exception|LinkageError failure){try{reply(output,failureFrame(stage,failure));}catch(Exception ignored){}return 10;}
        finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }

    /** Only the live native owner may attest the exact Process exit, after FINISH and EOF.
     * A new inspector rechecks the entire chain and current data before persisting that witness.
     * Historical terminal records without this witness cannot bootstrap a restart. */
    public static int runInspection(String[] args,InputStream rawInput,PrintStream rawOutput){
        var input=new DataInputStream(rawInput);var output=new DataOutputStream(rawOutput);
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(System.out);
        try{
            WindowsNativeGate.admit();reply(output,Map.of("stage","INSPECTION_HELLO","pid",ProcessHandle.current().pid(),
                    "profile",System.getProperty("workspace.native.development-profile","")));
            var request=readFrame(input,Set.of("action","operation","session","handle","runtimePid","challenge"));
            String operation=(String)request.get("operation");
            if(!"INSPECT_STOPPED".equals(request.get("action")))throw stopped();
            try(var exclusion=WindowsNativeGate.receiveMaintenanceExclusion((Long)request.get("handle"))){
                WindowsNativeGate.requireMaintenanceArguments(args,exclusion);Path root=WindowsNativeGate.maintenanceProfileRoot(exclusion);
                try(var history=MaintenanceSessions.open(root,operation,MaintenanceSessions.End.STOPPED);
                        var current=new MaintenanceSessions.Current(exclusion,root,operation,history.intent)){
                    Intent intent=history.intent;
                    if(!history.session.equals(request.get("session")))throw stopped();
                    current.require(exclusion,root);
                    history.observeNativeExit((Long)request.get("runtimePid"),(String)request.get("challenge"));
                    var review=new HashMap<String,Object>(Map.of("stage","STOPPED_HISTORY_TARGET_BOUND","operation",operation,"challenge",intent.challenge,
                            "configuration",intent.configuration,"binding",intent.expected.binding(),"pid",ProcessHandle.current().pid(),
                            "pastRuntimePid",intent.runtimePid,"ordinaryStartupAuthorized",false));
                    review.put("session",history.session);review.put("nativeExitObserved",true);
                    review.put("modelState",intent.modelState);review.put("sourceAndBackupVerified",true);
                    review.put("restartBlockers",List.of("ORDINARY_NATIVE_RESTART_REQUIRED","EXTERNAL_MODEL_EXECUTION_UNPROVEN",
                            "UNREGISTERED_WRITERS_UNPROVEN","USER_CONFIRMATION_REQUIRED"));
                    reply(output,review);return 0;
                }
            }
        }catch(Exception|LinkageError failure){try{reply(output,failureFrame("STOPPED_INSPECTION",failure));}catch(Exception ignored){}return 10;}
    }
}
