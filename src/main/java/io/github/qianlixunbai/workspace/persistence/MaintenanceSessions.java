package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.github.qianlixunbai.workspace.persistence.MaintenanceRuntime.*;

/** Append-only native-owned, model-disabled sessions. No ordinary-start capability.
 * A successor intent is persisted BEFORE its Process.Start. Missing/partial successors cannot
 * fall back to an older stopped session. A pipe assertion of exit is accepted only from the
 * admitted native transport holding the live exclusion, never inferred from saved PIDs. */
public final class MaintenanceSessions implements AutoCloseable {
    enum End { PENDING, STOPPED, OBSERVED }
    private record Next(int version,String kind,String profile,String rootIdentity,String operation,
                        String session,String previousExit,String mode){}
    private record Exit(int version,String kind,String operation,String session,long runtimePid,
                        String challenge,String terminal,String configuration,String modelState){}
    private final Path root;private final String operation;
    private final List<AutoCloseable> pins=new ArrayList<>();
    String session="initial",terminal,exitDigest;Intent intent;
    private MaintenanceSessions(Path root,String operation){this.root=root;this.operation=operation;}

    static Path directory(Path root,String operation,String session)throws IOException {
        if(operation==null||!operation.matches("[a-f0-9]{32}")||session==null
                ||!session.equals("initial")&&!session.matches("[0-9]{8}"))throw stopped();
        Path original=root.resolve("evidence/sqlite-activation").resolve(operation);
        return session.equals("initial")?original:original.resolve("sessions").resolve(session);
    }
    static String key(String operation,String session){return session.equals("initial")?operation:operation+"/"+session;}
    private static Set<String> names(Path dir)throws IOException {
        try(var files=Files.list(dir)){var names=files.map(p->p.getFileName().toString()).limit(130).toList();
            if(names.size()>=130)throw stopped();return new HashSet<>(names);}
    }
    private void directoryPin(Path path)throws IOException{pins.add(new SqliteSourceLease.DirectoryPin(path));}
    private SqliteSourceLease.FilePin file(Path path)throws IOException {
        var pin=new SqliteSourceLease.FilePin(path);pins.add(pin);return pin;
    }
    static MaintenanceSessions open(Path root,String operation,End end)throws IOException {
        var result=new MaintenanceSessions(root,operation);
        try{result.read(end);return result;}catch(Exception failure){result.close();throw stopped();}
    }
    private void read(End end)throws IOException {
        Path original=directory(root,operation,"initial");
        directoryPin(original.getParent());directoryPin(root.resolve("evidence/sqlite-configuration"));
        if(!names(original.getParent()).equals(Set.of(operation))
                ||!names(root.resolve("evidence/sqlite-configuration")).equals(Set.of(operation)))throw stopped();
        Intent first=null;Set<String> sessions=new HashSet<>();boolean sessionDirectory=false;
        for(int index=0;index<=128;index++){
            Path dir=directory(root,operation,session);directoryPin(dir);Set<String> actual=names(dir);
            boolean next=actual.contains("next.dpapi");
            var intentPin=file(dir.resolve("intent.dpapi"));intent=readRecord(intentPin,Intent.class,root,key(operation,session));
            validateIntent(intent,root,operation);
            if(first==null)first=intent;
            else if(!intent.configuration().equals(first.configuration())||!intent.expected().equals(first.expected())
                    ||!intent.modelState().equals(first.modelState()))throw stopped();
            var expected=new HashSet<String>();expected.add("intent.dpapi");
            boolean pending=!next&&end==End.PENDING;
            String previous=intentPin.digest();int sequence=0;
            if(!pending)for(String stage:List.of("PINNED","INITIALIZING","VERIFIED","STOPPING","VERIFIED_STOPPED")){
                String name=String.format(Locale.ROOT,"%02d-%s.dpapi",sequence,stage.toLowerCase(Locale.ROOT));expected.add(name);
                var pin=file(dir.resolve(name));Event event=readRecord(pin,Event.class,root,key(operation,session));
                int count=sequence<2?0:sequence<4?3:1;
                if(event.version()!=1||!"MAINTENANCE_RUNTIME_EVENT".equals(event.kind())||!operation.equals(event.operation())
                        ||event.runtimePid()!=intent.runtimePid()||!intent.challenge().equals(event.challenge())
                        ||!intent.configuration().equals(event.configuration())||!previous.equals(event.previous())
                        ||!stage.equals(event.stage())||event.backends()==null||event.backends().size()!=count
                        ||event.backends().stream().anyMatch(p->p==null||p<=0)||new HashSet<>(event.backends()).size()!=count)throw stopped();
                previous=pin.digest();sequence++;
            }
            terminal=previous;exitDigest=null;
            if(next||end==End.OBSERVED){
                expected.add("native-exit.dpapi");var pin=file(dir.resolve("native-exit.dpapi"));
                Exit exit=readRecord(pin,Exit.class,root,key(operation,session));
                if(!exit.equals(new Exit(1,"NATIVE_EXIT_OBSERVED",operation,session,intent.runtimePid(),intent.challenge(),
                        terminal,intent.configuration(),intent.modelState())))throw stopped();exitDigest=pin.digest();
            }
            if(next)expected.add("next.dpapi");
            if(index==0&&actual.contains("sessions")){expected.add("sessions");sessionDirectory=true;directoryPin(dir.resolve("sessions"));}
            if(!actual.equals(expected))throw stopped();
            if(!next){
                if(pending&&index==0)throw stopped();
                if(sessionDirectory&&!names(original.resolve("sessions")).equals(sessions)||!sessionDirectory&&index>0)throw stopped();
                verifyPins();return;
            }
            if(index>=128)throw stopped();
            String following=String.format(Locale.ROOT,"%08d",index+1);
            var nextPin=file(dir.resolve("next.dpapi"));Next record=readRecord(nextPin,Next.class,root,key(operation,session));
            if(!record.equals(new Next(1,"NATIVE_RESTART_INTENT",root.getFileName().toString(),
                    intent.rootIdentity(),operation,following,exitDigest,"MODEL_DISABLED_MAINTENANCE")))throw stopped();
            sessions.add(following);session=following;
        }
        throw stopped();
    }
    static void validateIntent(Intent intent,Path root,String operation)throws IOException {
        if(intent.version()!=1||!"MAINTENANCE_RUNTIME_INTENT".equals(intent.kind())||!operation.equals(intent.operation())
                ||!root.getFileName().toString().equals(intent.profile())||!TrustedDatabaseConfiguration.Identity.read(root,true).equals(intent.rootIdentity())
                ||intent.runtimePid()<=0||intent.challenge()==null||!intent.challenge().matches("[a-f0-9]{64}")
                ||intent.configuration()==null||!intent.configuration().matches("[a-f0-9]{64}")||intent.expected()==null
                ||intent.expected().binding()==null||!operation.equals(intent.expected().binding().operation())
                ||intent.modelState()==null||!intent.modelState().matches("[a-f0-9]{64}"))throw stopped();
    }
    void verifyPins()throws IOException {
        for(var pin:pins)if(pin instanceof SqliteSourceLease.FilePin f)f.verify();
            else if(pin instanceof SqliteSourceLease.DirectoryPin d)d.verify();
    }
    private String prepare()throws IOException {
        if(exitDigest==null)throw stopped();int index=session.equals("initial")?1:Integer.parseInt(session)+1;
        if(index>128)throw stopped();String following=String.format(Locale.ROOT,"%08d",index);
        Path old=directory(root,operation,session),dir=directory(root,operation,following);
        // This marker consumes the predecessor before a successor process can exist. Any
        // failure from this point leaves a blocking partial attempt, with no retry/repair path.
        verifyPins();writeRecord(old.resolve("next.dpapi"),new Next(1,"NATIVE_RESTART_INTENT",root.getFileName().toString(),
                intent.rootIdentity(),operation,following,exitDigest,"MODEL_DISABLED_MAINTENANCE"),root,key(operation,session));
        if(session.equals("initial")){SqliteSourceLease.absent(dir.getParent());WindowsPrivateAcl.directory(dir.getParent());}
        directoryPin(dir.getParent());SqliteSourceLease.absent(dir);WindowsPrivateAcl.directory(dir);directoryPin(dir);
        return following;
    }
    void observeNativeExit(long pid,String challenge)throws IOException {
        if(exitDigest!=null||pid!=intent.runtimePid()||!intent.challenge().equals(challenge))throw stopped();verifyPins();
        Path path=directory(root,operation,session).resolve("native-exit.dpapi");
        var expected=new Exit(1,"NATIVE_EXIT_OBSERVED",operation,session,pid,challenge,terminal,intent.configuration(),intent.modelState());
        writeRecord(path,expected,root,key(operation,session));var pin=file(path);
        if(!expected.equals(readRecord(pin,Exit.class,root,key(operation,session))))throw stopped();exitDigest=pin.digest();
    }
    @Override public void close(){for(int i=pins.size()-1;i>=0;i--)try{pins.get(i).close();}catch(Exception ignored){}}

    /** Recheck CURRENT source/backup/configuration/target/model while retaining their real owners. */
    static final class Current implements AutoCloseable {
        final MaintenanceConfiguration.ActivationConfiguration configuration;
        private SqliteMaintenanceOwner.RuntimeSource source;private MaintenanceModelState model;
        Current(MaintenanceExclusion exclusion,Path root,String operation,Intent intent)throws IOException {
            configuration=MaintenanceConfiguration.ActivationConfiguration.inspectStopped(root,operation);
            try{
                if(!configuration.digest().equals(intent.configuration())||!configuration.expected().equals(intent.expected()))throw stopped();
                source=SqliteMaintenanceOwner.retainRuntimeSource(exclusion,operation,configuration);source.takeOwnerLock();
                model=new MaintenanceModelState(root);if(!model.digest().equals(intent.modelState()))throw stopped();
                require(exclusion,root);
            }catch(Exception failure){close();throw stopped();}
        }
        void require(MaintenanceExclusion exclusion,Path root)throws IOException {
            exclusion.requireHeld(root);configuration.requireConfiguration(root);source.requireHeld();model.requireSnapshot();
        }
        public void close(){if(model!=null)model.close();if(source!=null)source.close();configuration.close();}
    }

    /** A fresh, live review for the normal desktop entry. It permits only another
     * model-disabled session, never an ordinary Java fence bypass or model execution. */
    private record ResumeReview(int version,String kind,String profile,String operation,String predecessor,
            String previousExit,String nonce,String configuration,String modelState,
            MaintenanceTarget.Binding binding,String database,String role,String address,int port,
            String scope,boolean modelExecutionAllowed){}
    private static void reviewRestart(DataInputStream input,DataOutputStream output,MaintenanceSessions history,
            Current current,MaintenanceExclusion exclusion,Path root)throws Exception {
        long started=System.nanoTime();
        current.require(exclusion,root);history.verifyPins();
        var target=current.configuration.target;
        MaintenanceTarget.verifyCurrentHeld(target,current.configuration.expected());
        ResumeReview review;
        try(var query=target.createStatement();var row=query.executeQuery(
                "SELECT current_database(),current_user,host(inet_server_addr()),inet_server_port()")){
            if(!row.next())throw stopped();
            review=new ResumeReview(1,"LIVE_WORKSPACE_RESUME",root.getFileName().toString(),history.operation,
                    history.session,history.exitDigest,HexFormat.of().formatHex(new java.security.SecureRandom().generateSeed(32)),
                    history.intent.configuration(),history.intent.modelState(),current.configuration.expected().binding(),
                    row.getString(1),row.getString(2),row.getString(3),row.getInt(4),"MODEL_DISABLED_WORKSPACE_SESSION",false);
            if(row.next()||!"127.0.0.1".equals(review.address())||review.port()<1||review.port()>65535)throw stopped();
        }
        String json=new tools.jackson.databind.json.JsonMapper().writeValueAsString(review);
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        reply(output,Map.of("stage","RESTART_REVIEW","pid",ProcessHandle.current().pid(),
                "operation",history.operation,"review",json,"digest",digest));
        var decision=readFrame(input,Set.of("action","operation","digest"));
        if(!history.operation.equals(decision.get("operation"))||!digest.equals(decision.get("digest")))throw stopped();
        if("CANCEL_RESTART".equals(decision.get("action"))){
            reply(output,Map.of("stage","RESTART_CANCELLED","operation",history.operation,"digest",digest));
            throw new ResumeCancelled();
        }
        if(!"CONFIRM_RESTART".equals(decision.get("action"))
                ||System.nanoTime()-started>java.time.Duration.ofSeconds(120).toNanos())throw stopped();
        // The exact live connection and all source/configuration/model pins still exist.
        // Only after this check may prepare() consume the predecessor's native stop receipt.
        history.verifyPins();current.require(exclusion,root);
        MaintenanceTarget.verifyCurrentHeld(target,current.configuration.expected());
    }
    private static final class ResumeCancelled extends Exception {}

    public static int runOwner(String[] args,InputStream rawInput,PrintStream rawOutput){
        var input=new DataInputStream(rawInput);var output=new DataOutputStream(rawOutput);
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));System.setErr(System.out);
        try{
            WindowsNativeGate.admit();reply(output,Map.of("stage","RESTART_HELLO","pid",ProcessHandle.current().pid(),
                    "profile",System.getProperty("workspace.native.development-profile","")));
            var request=readFrame(input,Set.of("action","operation","handle"));
            boolean review="REVIEW_RESTART".equals(request.get("action"));
            if(!review&&!"PREPARE_RESTART".equals(request.get("action")))throw stopped();String operation=(String)request.get("operation");
            try(var exclusion=WindowsNativeGate.receiveMaintenanceExclusion((Long)request.get("handle"))){
                WindowsNativeGate.requireMaintenanceArguments(args,exclusion);Path root=WindowsNativeGate.maintenanceProfileRoot(exclusion);
                String following,challenge;
                try(var history=open(root,operation,End.OBSERVED);var current=new Current(exclusion,root,operation,history.intent)){
                    if(review)reviewRestart(input,output,history,current,exclusion,root);
                    following=history.prepare();
                    reply(output,Map.of("stage","RESTART_PREPARED","operation",operation,"session",following,"pid",ProcessHandle.current().pid()));
                    var bind=readFrame(input,Set.of("action","operation","session","pid","challenge"));
                    challenge=(String)bind.get("challenge");long pid=(Long)bind.get("pid");
                    command(bind,"BIND_RESTART",operation,challenge);
                    if(!following.equals(bind.get("session"))||pid<=0||pid==ProcessHandle.current().pid()
                            ||!challenge.matches("[a-f0-9]{64}"))throw stopped();
                    history.verifyPins();current.require(exclusion,root);Intent old=history.intent;
                    writeRecord(directory(root,operation,following).resolve("intent.dpapi"),new Intent(1,"MAINTENANCE_RUNTIME_INTENT",
                            old.profile(),old.rootIdentity(),operation,pid,challenge,old.configuration(),old.expected(),old.modelState()),root,key(operation,following));
                    reply(output,Map.of("stage","OFFERED","operation",operation,"session",following,"challenge",challenge));
                    command(readFrame(input,Set.of("action","operation","challenge")),"RELEASE",operation,challenge);
                    current.require(exclusion,root);history.verifyPins();
                }
                reply(output,Map.of("stage","RELEASED","operation",operation,"session",following,"challenge",challenge));return 0;
            }
        }catch(ResumeCancelled cancelled){return 0;}
        catch(Exception|LinkageError failure){try{reply(output,failureFrame("RESTART_OWNER",failure));}catch(Exception ignored){}return 10;}
    }
}
