package io.github.qianlixunbai.workspace.persistence;

import com.sun.jna.platform.win32.*;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** An unpublished canonical target, not a Runtime identity or a personal recovery permit.
 * Only the explicitly synthetic registered-source path is implemented. No arbitrary data path. */
public final class PreparedRecoveryTarget implements AutoCloseable {
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private record Plan(int Version,String Kind,String State,String OperationId,String TargetProfileId,String BackupId,
        String SourceRegistrationSha256,String PayloadSha256,String ManifestSha256,String DatabaseName,String OwnerRole,
        String AppRole,String OwnershipReceipt) {}
    private record Identity(int Version,String Kind,String OperationId,String TargetProfileId,String PlanSha256,
        String RootIdentity,String DataIdentity) {}
    private final MaintenanceExclusion exclusion;
    private final Path authority,root,data,entry;
    private final String targetId;
    private final WindowsNativeGate.PreparedIdentity nativeIdentity;
    private final int sourcePort;
    private final String sourceSystemIdentifier;
    private final long sourceDatabaseOid;
    private final List<WinNT.HANDLE> directories=new ArrayList<>();
    private final List<FileChannel> files=new ArrayList<>();
    private final Map<Path,String> identities=new LinkedHashMap<>();
    private Plan plan;private Identity identity;private boolean closed,claimed,restored,offline;
    private String preparedHash,attemptHash;

    public static PreparedRecoveryTarget openSynthetic(MaintenanceExclusion exclusion,String targetId,RegisteredRecoverySource source) throws IOException {
        require(source!=null&&source.synthetic());source.requireHeld();
        Path authority=WindowsNativeGate.maintenanceProfileRoot(exclusion);
        var nativeIdentity=WindowsNativeGate.verifyPreparedRecoveryTarget(exclusion,targetId);
        var target=new PreparedRecoveryTarget(exclusion,authority,targetId,nativeIdentity,source);
        try {
            target.open(source,nativeIdentity);return target;
        }catch(Exception|LinkageError failure){
            target.close();var stopped=new IOException("Prepared recovery target unavailable");
            stopped.setStackTrace(failure.getStackTrace());throw stopped; // Frames only; no native/path/DPAPI message.
        }
    }
    private PreparedRecoveryTarget(MaintenanceExclusion exclusion,Path authority,String targetId,WindowsNativeGate.PreparedIdentity nativeIdentity,RegisteredRecoverySource source){
        this.exclusion=exclusion;this.authority=authority;this.targetId=targetId;this.nativeIdentity=nativeIdentity;
        sourcePort=source.port();sourceSystemIdentifier=source.systemIdentifier();sourceDatabaseOid=source.databaseOid();
        root=authority.getParent().resolve(targetId);data=root.resolve("data");
        entry=authority.getParent().getParent().resolve("Recovery/synthetic-targets").resolve(targetId);
    }
    private void hold(Path path) throws IOException {
        if(identities.containsKey(path))return;
        if(path.getParent()!=null)hold(path.getParent());
        var handle=Kernel32.INSTANCE.CreateFile("\\\\?\\"+path,0x20081,3,null,3,0x02200000,null);
        require(!WinBase.INVALID_HANDLE_VALUE.equals(handle));directories.add(handle);
        identities.put(path,TrustedDatabaseConfiguration.Identity.read(path,true));
    }
    private byte[] readRecord(String name) throws IOException {
        Path file=entry.resolve(name+".dpapi");WindowsPrivateAcl.verifyRecoveryTargetRecord(this,file);
        var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,
            com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
        files.add(channel);require(channel.size()>0&&channel.size()<=16384);
        byte[] encrypted=new byte[(int)channel.size()];ByteBuffer buffer=ByteBuffer.wrap(encrypted);
        while(buffer.hasRemaining())if(channel.read(buffer)<0)throw new IOException();
        if(name.equals("plan"))planHash=hash(encrypted);
        if(name.equals("prepared"))preparedHash=hash(encrypted);
        try{return Crypt32Util.cryptUnprotectData(encrypted,entropy(name),1,null);}
        finally{Arrays.fill(encrypted,(byte)0);}
    }
    private String planHash;
    private void open(RegisteredRecoverySource source,WindowsNativeGate.PreparedIdentity nativeIdentity) throws IOException {
        hold(entry);hold(root);hold(data);
        for(String name:List.of("credentials","model-state","webview2-profile","chrome-profile","browser-test-extension","evidence","logs")){
            Path directory=root.resolve(name);hold(directory);WindowsPrivateAcl.verifyPreparedDirectory(this,directory);
            try(var entries=Files.list(directory)){require(entries.findAny().isEmpty());}
        }
        for(Path path:List.of(entry.getParent().getParent().getParent(),entry.getParent().getParent(),entry.getParent(),entry,root,data))
            WindowsPrivateAcl.verifyPreparedDirectory(this,path);
        entries(false);
        byte[] bytes=readRecord("plan");
        try{require(bytes.length<=16384);var node=JSON.readTree(bytes);require(node.isObject()&&node.size()==13);plan=JSON.treeToValue(node,Plan.class);}
        finally{Arrays.fill(bytes,(byte)0);}
        bytes=readRecord("prepared");
        try{require(bytes.length<=16384);var node=JSON.readTree(bytes);require(node.isObject()&&node.size()==7);identity=JSON.treeToValue(node,Identity.class);}
        finally{Arrays.fill(bytes,(byte)0);}
        require(plan.Version==1&&"SYNTHETIC_RECOVERY_TARGET".equals(plan.Kind)&&"PREPARING".equals(plan.State)
            &&targetId.equals(plan.TargetProfileId)&&plan.OperationId.matches("[0-9a-f]{32}")
            &&source.backupId().equals(plan.BackupId)&&source.registrationSha256().equals(plan.SourceRegistrationSha256)
            &&source.payloadSha256().equals(plan.PayloadSha256)&&source.manifestSha256().equals(plan.ManifestSha256));
        String suffix=plan.OperationId.substring(0,12);
        require(plan.DatabaseName.equals("personal_ai_workspace_b3_"+suffix)&&!plan.DatabaseName.equals(source.database())
            &&plan.OwnerRole.equals("workspace_b3_owner_"+suffix)&&plan.AppRole.equals("workspace_b3_app_"+suffix)
            &&plan.OwnershipReceipt.startsWith("workspace-b3:")
            &&UUID.fromString(plan.OwnershipReceipt.substring(13)).toString().equals(plan.OwnershipReceipt.substring(13)));
        require(identity.Version==1&&"SYNTHETIC_PREPARED_TARGET".equals(identity.Kind)&&plan.OperationId.equals(identity.OperationId)
            &&targetId.equals(identity.TargetProfileId)&&planHash.equals(identity.PlanSha256)
            &&root.equals(nativeIdentity.root())&&nativeIdentity.rootIdentity().equals(identity.RootIdentity)
            &&nativeIdentity.dataIdentity().equals(identity.DataIdentity));
        requireSource(source);requireEmptyData();
    }
    public void requireSource(RegisteredRecoverySource source) throws IOException {
        requireHeld();source.requireHeld();
        require(source.synthetic()&&!targetId.equals(source.sourceProfileId())&&source.backupId().equals(plan.BackupId)
            &&source.registrationSha256().equals(plan.SourceRegistrationSha256)&&source.payloadSha256().equals(plan.PayloadSha256)
            &&source.manifestSha256().equals(plan.ManifestSha256)&&!source.database().equals(plan.DatabaseName));
    }
    private void entries(boolean attempted) throws IOException {
        try(var list=Files.list(entry)){
            require(list.limit(5).map(p->p.getFileName().toString()).sorted().toList().equals(offline
                ?List.of("attempt.dpapi","offline.dpapi","plan.dpapi","prepared.dpapi"):attempted
                ?List.of("attempt.dpapi","plan.dpapi","prepared.dpapi"):List.of("plan.dpapi","prepared.dpapi")));
        }
    }
    /** Narrow ACL capability for the security package. Before records can be read, the
     * reviewed helper identity, maintenance exclusion and retained ancestor pins must match.
     * This object has no public constructor; it is not a Runtime or personal recovery permit. */
    public Path requirePinnedPath(Path path) throws IOException {
        Path app=authority.getParent().getParent();
        require(!closed&&path.equals(path.toAbsolutePath().normalize())
            &&(path.startsWith(root)||path.startsWith(entry)||path.equals(entry.getParent())
                ||path.equals(entry.getParent().getParent())||path.equals(app))
            &&identities.containsKey(root)&&identities.containsKey(data)&&identities.containsKey(entry)
            &&root.equals(nativeIdentity.root()));
        exclusion.requireHeld(authority);
        for(var item:identities.entrySet())require(item.getValue().equals(TrustedDatabaseConfiguration.Identity.read(item.getKey(),true)));
        require(nativeIdentity.rootIdentity().equals(identities.get(root))&&nativeIdentity.dataIdentity().equals(identities.get(data)));
        return app;
    }
    public void requireHeld() throws IOException {
        require(!closed&&plan!=null&&identity!=null&&files.stream().allMatch(FileChannel::isOpen));
        exclusion.requireHeld(authority);entries(claimed);
        for(var item:identities.entrySet())require(item.getValue().equals(TrustedDatabaseConfiguration.Identity.read(item.getKey(),true)));
        require(identity.RootIdentity.equals(TrustedDatabaseConfiguration.Identity.read(root,true))
            &&identity.DataIdentity.equals(TrustedDatabaseConfiguration.Identity.read(data,true)));
        try(var list=Files.list(root)){
            require(list.limit(9).map(p->p.getFileName().toString()).sorted().toList().equals(List.of(
                "browser-test-extension","chrome-profile","credentials","data","evidence","logs","model-state","webview2-profile")));
        }
    }
    public void requireEmptyData() throws IOException {
        requireHeld();try(var list=Files.list(data)){require(list.findAny().isEmpty());}
    }
    /** Consume before target SQL/file writes. Any interruption leaves this target non-replayable. */
    public void claim() throws IOException {
        require(!claimed);requireEmptyData();
        byte[] plain=JSON.writeValueAsBytes(Map.of("OperationId",plan.OperationId,"TargetProfileId",targetId,"PlanSha256",planHash,"State","RESTORING"));
        byte[] encrypted;
        try{encrypted=Crypt32Util.cryptProtectData(plain,entropy("attempt"),1,"",null);}
        finally{Arrays.fill(plain,(byte)0);}
        Path pending=entry.resolve("attempt.pending"),finalFile=entry.resolve("attempt.dpapi");
        writePrivate(pending,encrypted);Files.move(pending,finalFile);claimed=true;attemptHash=hash(encrypted);
        files.add(FileChannel.open(finalFile,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,
            com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE));
        requireHeld();
    }
    public void beginFiles() throws IOException {require(claimed);requireEmptyData();writeMarker("RESTORE_INCOMPLETE","Unpublished recovery candidate".getBytes(StandardCharsets.US_ASCII));}
    public void createKnowledge() throws IOException {
        require(claimed);requireHeld();Path knowledge=data.resolve("knowledge");
        try{Files.readAttributes(knowledge,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);throw new IOException();}
        catch(NoSuchFileException absent){/* CREATE_NEW below; no existing tree is accepted. */}
        WindowsPrivateAcl.createPreparedDirectory(this,knowledge);
        WindowsPrivateAcl.createPreparedDirectory(this,knowledge.resolve("sources"));requireHeld();
    }
    /** Authenticated manifest paths only; create the final Runtime ACL at birth. Ordinary
     * Files.createDirectory/copy would inherit an unprotected DACL accepted by maintenance
     * but rejected by KnowledgeStore. Never repair an existing failed candidate. */
    public void copyKnowledgeSource(Path authenticatedSource,String relative) throws IOException {
        require(claimed&&relative.matches("sources/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[1-9][0-9]*\\.source"));
        requireHeld();Path destination=data.resolve("knowledge").resolve(relative),parent=destination.getParent();
        try{Files.readAttributes(parent,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);WindowsPrivateAcl.verifyPreparedDirectory(this,parent);}
        catch(NoSuchFileException absent){WindowsPrivateAcl.createPreparedDirectory(this,parent);}
        try(var input=FileChannel.open(authenticatedSource,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
            require(input.size()>0&&input.size()<=8*1024*1024);
            WindowsPrivateAcl.createPreparedFile(this,destination);
            try(var output=FileChannel.open(destination,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.allocate(65536);
                while(input.read(buffer)!=-1){buffer.flip();while(buffer.hasRemaining())output.write(buffer);buffer.clear();}
                output.force(true);
            }
        }
        requireHeld();
    }
    public void writeMarker(String name,byte[] bytes) throws IOException {
        require(claimed&&Set.of("RESTORE_INCOMPLETE","RESTORE_VERIFIED.json").contains(name));requireHeld();writePrivate(data.resolve(name),bytes);requireHeld();
        if(name.equals("RESTORE_VERIFIED.json"))restored=true;
    }
    // A marker found on disk alone never recreates this in-process transition.
    void requireRestored() throws IOException {requireHeld();require(claimed&&restored);}
    /** Synthetic terminal evidence only. Native publication still requires successful child
     * exit, fresh identity checks and a separate explicit activation request. */
    public void publishSyntheticOffline(TrustedDatabaseConfiguration.PreparedDatabase.Observation configuration) throws IOException {
        requireRestored();require(!offline&&configuration!=null&&configuration.permissionContract().equals("WORKSPACE_APP_V1"));
        Path file=data.resolve("database.dpapi");WindowsPrivateAcl.verifyPreparedConfiguration(this);
        try(var pin=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,
            com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)){
            require(pin.size()>0&&pin.size()<=16384&&configuration.configurationIdentity().equals(TrustedDatabaseConfiguration.Identity.read(file,false))
                &&configuration.configurationSha256().equals(hash(Files.readAllBytes(file)))
                &&configuration.systemIdentifier().equals(sourceSystemIdentifier)&&configuration.databaseOid()!=sourceDatabaseOid);
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("Version",1);result.put("Kind","SYNTHETIC_RECOVERY_OFFLINE");result.put("State","OFFLINE_VERIFIED");
        result.put("OperationId",plan.OperationId);result.put("TargetProfileId",targetId);
        result.put("PlanSha256",planHash);result.put("PreparedSha256",preparedHash);result.put("AttemptSha256",attemptHash);
        result.put("SourceRegistrationSha256",plan.SourceRegistrationSha256);result.put("PayloadSha256",plan.PayloadSha256);result.put("ManifestSha256",plan.ManifestSha256);
        result.put("RootIdentity",identity.RootIdentity);result.put("DataIdentity",identity.DataIdentity);
        result.put("DatabaseName",plan.DatabaseName);result.put("OwnerRole",plan.OwnerRole);result.put("AppRole",plan.AppRole);
        result.put("SystemIdentifier",configuration.systemIdentifier());result.put("DatabaseOid",configuration.databaseOid());
        result.put("OwnerRoleOid",configuration.ownerRoleOid());result.put("ApplicationRoleOid",configuration.applicationRoleOid());result.put("Port",sourcePort);
        result.put("ConfigurationIdentity",configuration.configurationIdentity());result.put("ConfigurationSha256",configuration.configurationSha256());
        result.put("PermissionContract",configuration.permissionContract());result.put("ValidationContract","WORKSPACE_OFFLINE_V1");
        result.put("CredentialState","OWNER_DISABLED_APP_RETAINED_SYNTHETIC_ONLY");result.put("PlaintextCleanup","COMPLETE");result.put("NativeGate","PENDING_EXPLICIT_ACTIVATION");
        byte[] plain=JSON.writeValueAsBytes(result),encrypted;
        try{encrypted=Crypt32Util.cryptProtectData(plain,entropy("offline"),1,"",null);}
        finally{Arrays.fill(plain,(byte)0);}
        requireHeld();Path pending=entry.resolve("offline.pending"),destination=entry.resolve("offline.dpapi");
        writePrivate(pending,encrypted);Files.move(pending,destination);offline=true;
        files.add(FileChannel.open(destination,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,
            com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE));requireHeld();
    }
    int sourcePort(){return sourcePort;}
    String sourceSystemIdentifier(){return sourceSystemIdentifier;}
    long sourceDatabaseOid(){return sourceDatabaseOid;}
    private void writePrivate(Path path,byte[] bytes) throws IOException {
        WindowsPrivateAcl.createPreparedFile(this,path);
        try(var output=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
        }
    }
    public Path data(){return data;}
    public String operationId(){return plan.OperationId;}
    public String database(){return plan.DatabaseName;}
    public String ownerRole(){return plan.OwnerRole;}
    public String appRole(){return plan.AppRole;}
    public String receipt(){return plan.OwnershipReceipt;}
    private byte[] entropy(String name){return ("PersonalAIWorkspace/Recovery/synthetic-target/v1/"+targetId+"/"+name).getBytes(StandardCharsets.US_ASCII);}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void require(boolean allowed) throws IOException {if(!allowed)throw new IOException("Prepared recovery target rejected");}
    @Override public void close() throws IOException {
        if(closed)return;closed=true;IOException error=null;
        for(var file:files)try{file.close();}catch(IOException failure){error=failure;}
        for(int i=directories.size()-1;i>=0;i--)if(!Kernel32.INSTANCE.CloseHandle(directories.get(i)))error=new IOException("Target close unknown");
        if(error!=null)throw error;
    }
}
