package io.github.qianlixunbai.workspace.persistence;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import org.postgresql.ds.PGSimpleDataSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** One-way absent-to-owned publication only. Never restores/deletes/replaces a configuration.
 * A durable attempt precedes CREATE_NEW; an uncertain attempt is observed, never replayed.
 * This API has no Runtime capability. Native maintenance and ordinary startup stay blocked. */
public final class MaintenanceConfiguration {
    private MaintenanceConfiguration() {}
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private record Intent(int version,String profile,String rootIdentity,String operation,String before,String after,
                          SqliteCombinedMigration.Expected target) {}
    @FunctionalInterface interface Held { void check() throws IOException; }

    static void prepare(SqliteMaintenanceOwner owner,byte[] configuration) throws IOException {
        Path root=owner.configurationRoot(); String operation=owner.operationId();
        var expected=owner.configurationTarget(); byte[] encrypted=null,plain=null;
        try(var target=MaintenanceTarget.holdVerified(dataSource(configuration),expected)) {
            owner.requireHeld();
            Path registry=root.resolve("evidence/sqlite-configuration");
            WindowsPrivateAcl.directory(registry);
            try(var registryPin=new SqliteSourceLease.DirectoryPin(registry)) {
                Path dir=registry.resolve(operation); SqliteSourceLease.absent(dir); WindowsPrivateAcl.directory(dir);
                try(var dirPin=new SqliteSourceLease.DirectoryPin(dir)) {
                    encrypted=TrustedDatabaseConfiguration.Protection.protect(configuration);
                    var intent=new Intent(1,root.getFileName().toString(),TrustedDatabaseConfiguration.Identity.read(root,true),
                            operation,"ABSENT",hash(encrypted),expected);
                    writeNew(dir.resolve("configuration.dpapi"),encrypted);
                    plain=JSON.writeValueAsBytes(intent);
                    protectedRecord(dir.resolve("intent.dpapi"),plain,root,operation);
                    owner.requireHeld(); registryPin.verify(); dirPin.verify(); MaintenanceTarget.verifyHeld(target,expected);
                    try(var prepared=open(root,operation)){ if(!prepared.intent.equals(intent)) throw stopped(); }
                }
            }
        }catch(Exception failure){throw stopped();}
        finally{if(encrypted!=null)Arrays.fill(encrypted,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
    }

    static String publish(Path root,String operation,SqliteCombinedMigration.Expected expected,Held held) throws IOException {
        try(var publication=holdPublication(root,operation,expected)) { return publication.publish(held); }
    }

    /** Retained by the live source owner BEFORE attempting publication. Failure retains this
     * object's handles until explicit owner close; it never grants Runtime admission. */
    static Publication holdPublication(Path root,String operation,SqliteCombinedMigration.Expected expected)throws IOException {
        var publication=new Publication(root,operation,expected);
        try{publication.open();return publication;}catch(Exception failure){publication.close();throw stopped();}
    }

    static final class Publication implements AutoCloseable {
        private final Path root;private final String operation;private final SqliteCombinedMigration.Expected expected;
        private Prepared prepared;private FileChannel gate;private String gateIdentity;
        private java.sql.Connection target;private SqliteSourceLease.FilePin configuration;
        private boolean attempted,published,closed;
        Publication(Path root,String operation,SqliteCombinedMigration.Expected expected){this.root=root;this.operation=operation;this.expected=expected;}
        private void open()throws IOException {
            prepared=MaintenanceConfiguration.open(root,operation);
            if(!prepared.intent.target.equals(expected))throw stopped();
            Path lock=prepared.dir.resolve("publication.lock");
            try{WindowsPrivateAcl.createFile(lock);}catch(FileAlreadyExistsException existing){WindowsPrivateAcl.verify(lock,false);}
            gateIdentity=TrustedDatabaseConfiguration.Identity.read(lock,false);
            gate=FileChannel.open(lock,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,
                    ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE);
            verifyGate();
        }
        String publish(Held held)throws IOException {
            if(closed||attempted)throw stopped();attempted=true;
            try {
                verifyGate();Path config=root.resolve("data/database.dpapi");
                if(exists(config))return observe(config,prepared);
                if(exists(prepared.dir.resolve("attempt.dpapi")))return "PUBLICATION_UNCONFIRMED";
                held.check();target=MaintenanceTarget.holdVerified(dataSource(prepared.plain),expected);
                held.check();prepared.verify();MaintenanceTarget.verifyHeld(target,expected);SqliteSourceLease.absent(config);
                protectedRecord(prepared.dir.resolve("attempt.dpapi"),JSON.writeValueAsBytes(prepared.intent),root,operation);
                held.check();prepared.verify();MaintenanceTarget.verifyHeld(target,expected);writeNew(config,prepared.encrypted);
                // Keep the exact published file and target transaction, rather than retaining only
                // a success string after closing both. A partial/uncertain write is never retried.
                configuration=new SqliteSourceLease.FilePin(config);
                published=true;requireHeld(root);
                return "RECORDED_CONFIGURATION_PRESENT_ACTIVATION_BLOCKED";
            }catch(Exception failure){throw stopped();}
            finally{
                // Retaining ownership must not retain the temporary decrypted configuration.
                // Subsequent checks use pinned files, the ciphertext digest and the live connection.
                if(prepared.plain!=null)Arrays.fill(prepared.plain,(byte)0);
                if(prepared.encrypted!=null)Arrays.fill(prepared.encrypted,(byte)0);
            }
        }
        private void verifyGate()throws IOException {
            if(closed||gate==null||!gate.isOpen()||gate.size()!=0
                    ||!gateIdentity.equals(TrustedDatabaseConfiguration.Identity.read(prepared.dir.resolve("publication.lock"),false)))throw stopped();
            prepared.verify();
        }
        void requireConfiguration(Path expectedRoot)throws IOException {
            if(!root.equals(expectedRoot)||!published||configuration==null)throw stopped();
            verifyGate();configuration.verify();
            if(!configuration.digest().equals(prepared.intent.after))throw stopped();
        }
        void requireHeld(Path expectedRoot)throws IOException {
            requireConfiguration(expectedRoot);
            try{MaintenanceTarget.verifyHeld(target,expected);}catch(Exception failure){throw stopped();}
        }
        public void close(){
            if(closed)return;closed=true;
            // Closing is suspension, not successful activation. All durable records remain fenced.
            if(target!=null)try{target.close();}catch(Exception ignored){}
            if(configuration!=null)try{configuration.close();}catch(IOException ignored){}
            if(gate!=null)try{gate.close();}catch(IOException ignored){}
            if(prepared!=null)prepared.close();
        }
    }

    /** Fresh Gate/exclusion, protected intent and unchanged source/backup/target are all needed.
     * Existing bytes (including partial/later writes) are read-only. A previous attempt is never retried. */
    public static String recover(MaintenanceExclusion exclusion,String operation) throws IOException {
        Path root=WindowsNativeGate.maintenanceProfileRoot(exclusion);
        try(var prepared=open(root,operation)) {
            Path config=root.resolve("data/database.dpapi");
            if(exists(config))return observe(config,prepared);
            if(exists(prepared.dir.resolve("attempt.dpapi")))return "PUBLICATION_UNCONFIRMED";
            var target=dataSource(prepared.plain);
            var recovery=SqliteMaintenanceOwner.inspect(exclusion,operation,target);
            if(recovery.lastStage()!=SqliteMaintenanceOwner.Stage.TARGET_VERIFIED
                    ||recovery.target()!=SqliteCombinedMigration.Inspection.MATCHING_DATA_AND_RECEIPTS)
                return "SOURCE_OR_TARGET_CHANGED_PRESERVED";
            // Reacquire and retain the source proof across publication; inspect alone is not a lease.
            try(var source=SqliteSourceLease.acquire(exclusion)) {
                // Repeat the complete protected source/backup check while source handles are held.
                var recheck=SqliteMaintenanceOwner.inspect(exclusion,operation,target);
                if(recheck.lastStage()!=SqliteMaintenanceOwner.Stage.TARGET_VERIFIED
                        ||recheck.target()!=SqliteCombinedMigration.Inspection.MATCHING_DATA_AND_RECEIPTS)
                    return "SOURCE_OR_TARGET_CHANGED_PRESERVED";
                return publish(root,operation,prepared.intent.target,source::requireHeld);
            }
        }catch(Exception failure){throw stopped();}
    }

    private static String observe(Path config,Prepared prepared)throws IOException {
        try(var pin=new SqliteSourceLease.FilePin(config)) {
            return pin.digest().equals(prepared.intent.after) ? "RECORDED_CONFIGURATION_PRESENT_ACTIVATION_BLOCKED"
                    : "LATER_OR_PARTIAL_CONFIGURATION_PRESERVED";
        }
    }
    private static boolean exists(Path path)throws IOException {
        try{Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}
        catch(NoSuchFileException absent){return false;}
    }
    private static PGSimpleDataSource dataSource(byte[] plain)throws IOException {
        var config=TrustedDatabaseConfiguration.parse(plain);
        var ds=new PGSimpleDataSource();
        ds.setURL((String)config.getProperty("workspace.persistence.postgresql.url"));
        ds.setUser((String)config.getProperty("workspace.persistence.postgresql.username"));
        ds.setPassword((String)config.getProperty("workspace.persistence.postgresql.password"));
        ds.setCurrentSchema("workspace,pg_catalog");ds.setConnectTimeout(3);ds.setSocketTimeout(30);return ds;
    }
    private static Prepared open(Path root,String operation)throws IOException {
        if(operation==null||!operation.matches("[0-9a-f]{32}"))throw stopped();
        var prepared=new Prepared(root,operation);
        try{prepared.open();return prepared;}catch(Exception failure){prepared.close();throw stopped();}
    }
    private static final class Prepared implements AutoCloseable {
        final Path root,dir;final String operation;final List<AutoCloseable> pins=new ArrayList<>();
        Intent intent;byte[] encrypted,plain;SqliteSourceLease.FilePin configuration,record;
        Prepared(Path root,String operation){this.root=root;this.operation=operation;dir=root.resolve("evidence/sqlite-configuration").resolve(operation);}
        void open()throws Exception {
            pins.add(new SqliteSourceLease.DirectoryPin(root));pins.add(new SqliteSourceLease.DirectoryPin(root.resolve("data")));
            pins.add(new SqliteSourceLease.DirectoryPin(dir.getParent()));pins.add(new SqliteSourceLease.DirectoryPin(dir));
            record=new SqliteSourceLease.FilePin(dir.resolve("intent.dpapi"));pins.add(record);
            byte[] encoded=read(record),decoded=null;
            try{decoded=Crypt32Util.cryptUnprotectData(encoded,entropy(root,operation),1,null);intent=JSON.readValue(decoded,Intent.class);}
            finally{Arrays.fill(encoded,(byte)0);if(decoded!=null)Arrays.fill(decoded,(byte)0);}
            if(intent.version!=1||!root.getFileName().toString().equals(intent.profile)||!operation.equals(intent.operation)
                    ||!TrustedDatabaseConfiguration.Identity.read(root,true).equals(intent.rootIdentity)||!"ABSENT".equals(intent.before)
                    ||intent.after==null||!intent.after.matches("[a-f0-9]{64}")||intent.target==null||intent.target.binding()==null
                    ||!operation.equals(intent.target.binding().operation()))throw stopped();
            configuration=new SqliteSourceLease.FilePin(dir.resolve("configuration.dpapi"));pins.add(configuration);
            encrypted=read(configuration);if(!hash(encrypted).equals(intent.after))throw stopped();
            plain=TrustedDatabaseConfiguration.Protection.unprotect(encrypted);TrustedDatabaseConfiguration.parse(plain);verify();
        }
        void verify()throws IOException {
            record.verify();configuration.verify();
            if(!TrustedDatabaseConfiguration.Identity.read(root,true).equals(intent.rootIdentity))throw stopped();
            try(var files=Files.list(dir)) {
                var names=files.map(p->p.getFileName().toString()).limit(6).toList();
                if(!Set.of("intent.dpapi","configuration.dpapi","publication.lock","attempt.dpapi").containsAll(names))throw stopped();
            }
        }
        public void close(){for(int i=pins.size()-1;i>=0;i--)try{pins.get(i).close();}catch(Exception ignored){}
            if(encrypted!=null)Arrays.fill(encrypted,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    private static byte[] read(SqliteSourceLease.FilePin pin)throws IOException {
        if(pin.channel.size()<1||pin.channel.size()>16384)throw stopped();
        var bytes=ByteBuffer.allocate((int)pin.channel.size());long at=0;
        while(bytes.hasRemaining()){int n=pin.channel.read(bytes,at);if(n<1)throw stopped();at+=n;}pin.verify();return bytes.array();
    }
    private static void writeNew(Path file,byte[] bytes)throws IOException {
        if(bytes.length<1||bytes.length>16384)throw stopped();WindowsPrivateAcl.createFile(file);
        try(var pin=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE)) {
            var data=ByteBuffer.wrap(bytes);while(data.hasRemaining())pin.write(data);pin.force(true);
        }
        try(var pin=new SqliteSourceLease.FilePin(file)){if(!pin.digest().equals(hash(bytes)))throw stopped();}
    }
    private static void protectedRecord(Path file,byte[] plain,Path root,String operation)throws IOException {
        byte[] encrypted=null;
        try{encrypted=Crypt32Util.cryptProtectData(plain,entropy(root,operation),1,"Maintenance configuration intent",null);writeNew(file,encrypted);}
        finally{Arrays.fill(plain,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
    }
    private static byte[] entropy(Path root,String operation){return ("PersonalAIWorkspace/MaintenanceConfiguration/v1/"+root.getFileName()+"/"+operation).getBytes(StandardCharsets.US_ASCII);}
    private static String hash(byte[] bytes)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception failure){throw stopped();}
    }
    private static IOException stopped(){return new IOException("Maintenance configuration incomplete; preserve all state; activation blocked");}
}
