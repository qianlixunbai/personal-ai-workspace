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
import tools.jackson.databind.JsonNode;

/** Independent local provenance, retained across producer exit. Reading it never proves
 * stopped writers, authorizes personal backup, or admits/activates a recovery target. */
public final class RegisteredRecoverySource implements AutoCloseable {
    private static final int LIMIT=16384;
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path record,encrypted;
    private final String backupId;
    private final boolean synthetic;
    private final Map<Path,String> identities=new LinkedHashMap<>();
    private final List<WinNT.HANDLE> directories=new ArrayList<>();
    private final List<FileChannel> files=new ArrayList<>();
    private JsonNode source;
    private String registrationSha256;
    private boolean closed;

    /** The authority may differ from the registered source; no source Profile/config/DB is
     * opened. The package location is selected by the native owner, never used as provenance. */
    public static RegisteredRecoverySource open(MaintenanceExclusion exclusion,String backupId,Path encrypted) throws IOException {
        Path authority=WindowsNativeGate.maintenanceProfileRoot(exclusion);
        Path app=authority.getParent().getParent();
        hex(backupId,32);
        Path recovery=app.resolve("Recovery"),sources=recovery.resolve("sources"),entry=sources.resolve(backupId);
        for(Path directory:List.of(app,recovery,sources,entry))WindowsPrivateAcl.verify(directory,true);
        WindowsPrivateAcl.verifyRecoverySourceRecord(entry.resolve("source.dpapi"));
        var result=open(app,backupId,encrypted,false);
        try {
            for(Path directory:List.of(app,recovery,sources,entry))WindowsPrivateAcl.verify(directory,true);
            WindowsPrivateAcl.verifyRecoverySourceRecord(entry.resolve("source.dpapi"));
            exclusion.requireHeld(authority);return result;
        }
        catch(IOException|RuntimeException failure){result.close();throw failure;}
    }

    // Private OS seam for the existing isolated native producer probe. It can consume only
    // the distinct synthetic namespace/entropy/kind, and never upgrades personal authority.
    private static RegisteredRecoverySource openSynthetic(Path application,String backupId) throws IOException {
        if(!application.getFileName().toString().matches("paw-source-producer-[0-9a-f]{32}"))throw stopped();
        hex(backupId,32);
        return open(application,backupId,application.resolve("Recovery/synthetic-payloads").resolve(backupId),true);
    }

    private RegisteredRecoverySource(Path app,String backupId,Path encrypted,boolean synthetic) {
        this.backupId=backupId;this.encrypted=encrypted;this.synthetic=synthetic;
        record=app.resolve("Recovery").resolve(synthetic?"synthetic-sources":"sources").resolve(backupId).resolve("source.dpapi");
    }
    private static RegisteredRecoverySource open(Path app,String backupId,Path encrypted,boolean synthetic) throws IOException {
        var result=new RegisteredRecoverySource(app,backupId,encrypted,synthetic);
        try {result.read();return result;}
        catch(Exception|LinkageError failure){result.close();throw stopped();}
    }
    private void holdDirectory(Path directory) throws IOException {
        if(identities.containsKey(directory))return;
        if(!directory.equals(directory.toAbsolutePath().normalize()))throw stopped();
        if(directory.getParent()!=null)holdDirectory(directory.getParent());
        var handle=Kernel32.INSTANCE.CreateFile("\\\\?\\"+directory,0x20081,3,null,3,0x02200000,null);
        if(WinBase.INVALID_HANDLE_VALUE.equals(handle))throw stopped();
        directories.add(handle);
        identities.put(directory,TrustedDatabaseConfiguration.Identity.read(directory,true));
    }
    private FileChannel holdFile(Path file) throws IOException {
        holdDirectory(file.getParent());
        var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,
            com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
        files.add(channel);identities.put(file,TrustedDatabaseConfiguration.Identity.read(file,false));return channel;
    }
    private void read() throws IOException {
        FileChannel receipt=holdFile(record);
        try(var entries=Files.list(record.getParent())) {
            if(!entries.limit(2).map(p->p.getFileName().toString()).toList().equals(List.of("source.dpapi")))throw stopped();
        }
        if(receipt.size()<1||receipt.size()>LIMIT)throw stopped();
        byte[] bytes=new byte[(int)receipt.size()],plaintext=null;
        try {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())if(receipt.read(buffer)<0)throw stopped();
            registrationSha256=sha256(bytes);
            String entropy="PersonalAIWorkspace/Recovery/"+(synthetic?"synthetic-source/v1/":"source/v1/")+backupId;
            plaintext=Crypt32Util.cryptUnprotectData(bytes,entropy.getBytes(StandardCharsets.US_ASCII),1,null);
            if(plaintext.length<1||plaintext.length>LIMIT)throw stopped();
            var recordNode=JSON.readTree(plaintext);
            if(synthetic) {
                fields(recordNode,"Version Source SourceConfigurationIdentity SourceConfigurationSha256 SourceApplicationRoleOid");
                equal(number(recordNode,"Version"),2);hex(text(recordNode,"SourceConfigurationIdentity"),48);
                hex(text(recordNode,"SourceConfigurationSha256"),64);oid(number(recordNode,"SourceApplicationRoleOid"));
                source=recordNode.path("Source");
            }else source=recordNode;
            fields(source,"Version Kind State BackupId SourceProfileId DatabaseLogicalIdentity SourceRootIdentity SourceDataIdentity SourceSystemIdentifier SourceDatabaseOid SourcePort SourceDatabase SourceRole Format FormatVersion PostgresqlMajor Scope PayloadBytes PayloadSha256 ManifestSha256 CredentialCleanup");
            equal(number(source,"Version"),1);
            equal(text(source,"Kind"),synthetic?"SYNTHETIC_WORKSPACE_BACKUP_RESULT":"WORKSPACE_BACKUP_SOURCE");
            equal(text(source,"State"),synthetic?"SYNTHETIC_BACKUP_RECORDED":"BACKUP_REGISTERED");
            equal(text(source,"BackupId"),backupId);hex(text(source,"SourceProfileId"),32);
            hex(text(source,"SourceRootIdentity"),48);hex(text(source,"SourceDataIdentity"),48);
            if(text(source,"SourceRootIdentity").equals(text(source,"SourceDataIdentity")))throw stopped();
            String logical=text(source,"DatabaseLogicalIdentity");
            if(!UUID.fromString(logical).toString().equals(logical)||new UUID(0,0).toString().equals(logical))throw stopped();
            if(!source.path("SourceSystemIdentifier").isIntegralNumber()
                ||Long.parseUnsignedLong(source.path("SourceSystemIdentifier").asString())==0)throw stopped();
            oid(number(source,"SourceDatabaseOid"));long port=number(source,"SourcePort");
            if(port<1||port>65535||(synthetic&&port==5432))throw stopped();
            for(String key:List.of("SourceDatabase","SourceRole")){
                String name=text(source,key);
                if(name.isBlank()||name.getBytes(StandardCharsets.UTF_8).length>63||name.chars().anyMatch(Character::isISOControl))throw stopped();
            }
            if(synthetic&&!text(source,"SourceDatabase").matches("personal_ai_workspace_b3_[0-9a-f]{12}"))throw stopped();
            equal(text(source,"SourceRole"),"workspace_backup_src_"+backupId);
            equal(text(source,"Format"),"personal-ai-workspace.postgresql-encrypted-stream");
            equal(number(source,"FormatVersion"),3);equal(number(source,"PostgresqlMajor"),17);
            equal(text(source,"Scope"),"WORKSPACE_KNOWLEDGE_ONLY");equal(text(source,"CredentialCleanup"),"RECLAIMED");
            long size=number(source,"PayloadBytes");if(size<1||size>(1L<<40)+(1L<<40)/100)throw stopped();
            hex(text(source,"PayloadSha256"),64);hex(text(source,"ManifestSha256"),64);
        }finally{Arrays.fill(bytes,(byte)0);if(plaintext!=null)Arrays.fill(plaintext,(byte)0);}
        // The independent receipt authorizes these exact ciphertext bytes; a recomputed package
        // completion or a valid password cannot replace it. Retain all pins through restoration.
        holdFile(encrypted.resolve("ENCRYPTED_BACKUP_COMPLETE.json"));
        FileChannel payload=holdFile(encrypted.resolve("backup.pgp"));
        if(payload.size()!=payloadBytes())throw stopped();
        var hash=hash();ByteBuffer buffer=ByteBuffer.allocate(65536);
        while(payload.read(buffer)!=-1){buffer.flip();hash.update(buffer);buffer.clear();}
        equal(HexFormat.of().formatHex(hash.digest()),payloadSha256());requireHeld();
    }
    public void requireHeld() throws IOException {
        if(closed||source==null||files.stream().anyMatch(f->!f.isOpen()))throw stopped();
        try(var entries=Files.list(record.getParent())) {
            if(!entries.limit(2).map(p->p.getFileName().toString()).toList().equals(List.of("source.dpapi")))throw stopped();
        }
        for(var entry:identities.entrySet()) {
            boolean directory=!entry.getKey().equals(record)&&!entry.getKey().equals(encrypted.resolve("backup.pgp"))
                &&!entry.getKey().equals(encrypted.resolve("ENCRYPTED_BACKUP_COMPLETE.json"));
            equal(entry.getValue(),TrustedDatabaseConfiguration.Identity.read(entry.getKey(),directory));
        }
    }
    public boolean synthetic(){return synthetic;}
    public String backupId(){return backupId;}
    public Path encrypted(){return encrypted;}
    public String registrationSha256(){return registrationSha256;}
    public String database(){return source.path("SourceDatabase").asString();}
    public long databaseOid(){return source.path("SourceDatabaseOid").longValue();}
    public String systemIdentifier(){return source.path("SourceSystemIdentifier").asString();}
    public int port(){return source.path("SourcePort").intValue();}
    public long payloadBytes(){return source.path("PayloadBytes").longValue();}
    public String payloadSha256(){return source.path("PayloadSha256").asString();}
    public String manifestSha256(){return source.path("ManifestSha256").asString();}
    public String logicalIdentity(){return source.path("DatabaseLogicalIdentity").asString();}
    private static void fields(JsonNode node,String names) throws IOException {
        if(!node.isObject()||!node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
            .equals(Set.of(names.split(" "))))throw stopped();
    }
    private static String text(JsonNode node,String key) throws IOException {if(!node.path(key).isString())throw stopped();return node.path(key).asString();}
    private static long number(JsonNode node,String key) throws IOException {if(!node.path(key).isIntegralNumber()||!node.path(key).canConvertToLong())throw stopped();return node.path(key).longValue();}
    private static void oid(long value) throws IOException {if(value<1||value>0xffffffffL)throw stopped();}
    private static void hex(String value,int length) throws IOException {if(value==null||!value.matches("[0-9a-f]{"+length+"}"))throw stopped();}
    private static void equal(Object actual,Object expected) throws IOException {if(!Objects.equals(actual,expected))throw stopped();}
    private static void equal(long actual,long expected) throws IOException {if(actual!=expected)throw stopped();}
    private static java.security.MessageDigest hash() {try{return java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static String sha256(byte[] bytes){return HexFormat.of().formatHex(hash().digest(bytes));}
    private static IOException stopped(){return new IOException("Registered recovery source unavailable; no target authorized");}
    @Override public String toString(){return "RegisteredRecoverySource[private]";}
    @Override public void close() throws IOException {
        if(closed)return;closed=true;IOException failure=null;
        for(var file:files)try{file.close();}catch(IOException e){failure=e;}
        for(int i=directories.size()-1;i>=0;i--)if(!Kernel32.INSTANCE.CloseHandle(directories.get(i)))failure=stopped();
        if(failure!=null)throw failure;
    }
}
