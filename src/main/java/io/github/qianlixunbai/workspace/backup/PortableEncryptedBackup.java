package io.github.qianlixunbai.workspace.backup;

import org.bouncycastle.bcpg.*;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;

/** Standard RFC 9580 OpenPGP around the complete, unchanged B3 synthetic collection.
 * No crypto protocol implementation, DPAPI, native tool, Runtime bean or HTTP endpoint.
 * Private disk staging remains necessary on decryption; production personal-data readiness is pending.
 */
public final class PortableEncryptedBackup {
    public static final String FORMAT="personal-ai-workspace.openpgp-backup";
    static final String LITERAL="workspace-b3-v1.zip",PAYLOAD="backup.pgp",COMPLETE="ENCRYPTED_BACKUP_COMPLETE.json";
    static final long MAX_BYTES=1L<<40;
    private static final int BUFFER=65536,MAX_ENTRIES=1004;
    private final UnifiedPostgresqlBackup plain;
    public PortableEncryptedBackup(Path postgresqlTools){plain=new UnifiedPostgresqlBackup(postgresqlTools);}
    public record Completion(String format,int version,String status,String profile,String payload,long bytes,String sha256) {}
    public record Decrypted(Path backup,UnifiedPostgresqlBackup.Manifest manifest) {
        @Override public String toString(){return "DecryptedBackup[private]";}
    }

    /** Consumes/clears the caller's passphrase array, including failures. Never overwrites a collection. */
    public Completion encrypt(Path backup,Path encrypted,char[] passphrase) throws IOException {
        List<FileChannel> held=new ArrayList<>();String stage="SOURCE_VALIDATION";
        try {
            password(passphrase);backup=backup.toAbsolutePath().normalize();encrypted=encrypted.toAbsolutePath().normalize();
            require(!backup.startsWith(encrypted)&&!encrypted.startsWith(backup));stagingParent(encrypted);
            var manifest=plain.validate(backup);var paths=entries(manifest,backup);
            for(String relative:paths)held.add(pin(backup.resolve(relative)));
            require(manifest.equals(plain.validate(backup)));
            long total=0;for(var channel:held)total=Math.addExact(total,channel.size());
            require(total<=MAX_BYTES-1048576&&Files.getFileStore(encrypted.getParent()).getUsableSpace()>total+total/100+1048576);
            directory(encrypted);write(encrypted.resolve("ENCRYPTED_BACKUP_INCOMPLETE"),new byte[0]);
            Path pending=encrypted.resolve(PAYLOAD+".pending");write(pending,new byte[0]);stage="ENCRYPTION";
            try(var file=FileChannel.open(pending,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                encryptBundle(paths,held,Channels.newOutputStream(file),passphrase);file.force(true);
            }
            require(manifest.equals(plain.validate(backup)));
            Files.move(pending,encrypted.resolve(PAYLOAD),StandardCopyOption.ATOMIC_MOVE);
            var result=new Completion(FORMAT,1,"ENCRYPTED_BACKUP_COMPLETE","RFC9580-AES256-GCM-ARGON2ID",PAYLOAD,
                Files.size(encrypted.resolve(PAYLOAD)),digest(encrypted.resolve(PAYLOAD)));
            stage="COMPLETION_PUBLICATION";publish(encrypted.resolve(COMPLETE),UnifiedPostgresqlBackup.JSON.writeValueAsBytes(result));return result;
        }catch(Exception failure){throw rejected(stage,failure);}
        finally{clear(passphrase);for(var channel:held)try{channel.close();}catch(IOException ignored){}}
    }
    private static List<String> entries(UnifiedPostgresqlBackup.Manifest manifest,Path root) {
        List<String> names=new ArrayList<>(List.of("manifest.json","database.dump","BACKUP_COMPLETE"));
        if(Files.exists(root.resolve("BACKUP_INCOMPLETE"),LinkOption.NOFOLLOW_LINKS))names.add("BACKUP_INCOMPLETE");
        for(var file:manifest.knowledgeFiles())names.add("knowledge/"+file.path());return List.copyOf(names);
    }
    private static void encryptBundle(List<String> names,List<FileChannel> sources,OutputStream output,char[] password) throws Exception {
        var random=new SecureRandom();var builder=new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
            .setUseV6AEAD().setWithAEAD(AEADAlgorithmTags.GCM,16).setSecureRandom(random);
        var generator=new PGPEncryptedDataGenerator(builder);generator.setForceSessionKey(true);
        generator.addMethod(new BcPBEKeyEncryptionMethodGenerator(password,S2K.Argon2Params.memoryConstrainedParameters()).setSecureRandom(random));
        // The channel owner, rather than a nested ZIP/PGP stream, flushes and closes the file.
        var shield=new FilterOutputStream(output){
            @Override public void write(byte[] bytes,int offset,int length) throws IOException{out.write(bytes,offset,length);}
            @Override public void close() throws IOException{flush();}
        };
        try(var crypt=generator.open(shield,new byte[BUFFER]);
            var literal=new PGPLiteralDataGenerator().open(crypt,PGPLiteralData.BINARY,LITERAL,new Date(0),new byte[BUFFER]);
            var zip=new ZipOutputStream(literal,StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.NO_COMPRESSION);byte[] buffer=new byte[BUFFER];
            try {
                for(int i=0;i<names.size();i++) {
                    var entry=new ZipEntry(names.get(i));entry.setTime(0);zip.putNextEntry(entry);
                    var source=sources.get(i);source.position(0);var input=Channels.newInputStream(source);int n;
                    while((n=input.read(buffer))!=-1)zip.write(buffer,0,n);zip.closeEntry();
                }
            }finally{Arrays.fill(buffer,(byte)0);}
        }
    }

    /** Entire message and final AEAD tag must authenticate before any ZIP entry is extracted.
     * A failure keeps only an explicitly incomplete, private staging directory; no DB API is called.
     */
    public Decrypted decrypt(Path encrypted,Path staging,char[] passphrase) throws IOException {
        String stage="CONTAINER_VALIDATION";
        try {
            password(passphrase);encrypted=encrypted.toAbsolutePath().normalize();staging=staging.toAbsolutePath().normalize();
            require(!staging.startsWith(encrypted)&&!encrypted.startsWith(staging));stagingParent(staging);
            var completion=completion(encrypted);
            Decrypted result;
            try(var receipt=pin(encrypted.resolve(COMPLETE));var payload=pin(encrypted.resolve(PAYLOAD))) {
                require(completion.equals(completion(encrypted)));profile(payload);payload.position(0);
                long budget=Math.min(MAX_BYTES,completion.bytes);
                require(Files.getFileStore(staging.getParent()).getUsableSpace()>Math.multiplyExact(budget,3)+1048576);
                directory(staging);write(staging.resolve("DECRYPTION_INCOMPLETE"),new byte[0]);
                Path pending=staging.resolve("bundle.zip.pending");write(pending,new byte[0]);stage="AUTHENTICATED_DECRYPTION";
                try(var file=FileChannel.open(pending,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    decryptBundle(Channels.newInputStream(payload),Channels.newOutputStream(file),passphrase,budget);file.force(true);
                }
                Files.move(pending,staging.resolve("bundle.zip"),StandardCopyOption.ATOMIC_MOVE);
                stage="INTERNAL_VALIDATION";Path backup=staging.resolve("backup");extract(staging.resolve("bundle.zip"),backup,budget);
                var manifest=plain.validate(backup);
                result=new Decrypted(backup,manifest);
            }
            // All input handles close successfully before the final durable verification receipt.
            stage="DECRYPTION_PUBLICATION";
            publish(staging.resolve("DECRYPTION_VERIFIED"),digest(result.backup.resolve("manifest.json")).getBytes(StandardCharsets.US_ASCII));return result;
        }catch(Exception failure){throw rejected(stage,failure);}
        finally{clear(passphrase);}
    }
    private static void decryptBundle(InputStream input,OutputStream output,char[] password,long budget) throws Exception {
        var objects=new BcPGPObjectFactory(input);Object first=objects.nextObject();
        require(first instanceof PGPEncryptedDataList);
        var list=(PGPEncryptedDataList)first;require(list.size()==1&&list.get(0) instanceof PGPPBEEncryptedData);
        var data=(PGPPBEEncryptedData)list.get(0);require(data.getVersion()==6&&data.isAEAD());
        var decryptor=new BcPBEDataDecryptorFactory(password,new BcPGPDigestCalculatorProvider());
        try(var clear=data.getDataStream(decryptor)) {
            var inner=new BcPGPObjectFactory(clear);Object literal=inner.nextObject();require(literal instanceof PGPLiteralData);
            var file=(PGPLiteralData)literal;require(file.getFormat()==PGPLiteralData.BINARY&&file.getFileName().equals(LITERAL));
            transfer(file.getDataStream(),output,budget);
            require(inner.nextObject()==null);require(clear.read()==-1);require(data.verify());
            require(objects.nextObject()==null); // No concatenated unauthenticated/trailing packets.
        }
    }
    /** Check the unauthenticated KDF/chunk parameters BEFORE allocating KDF or AEAD work memory.
     * Only this fixed profile is supported; a hostile packet cannot request arbitrary Argon2 costs.
     */
    static void profile(FileChannel payload) throws Exception {
        var header=new BCPGInputStream(new LimitedInputStream(Channels.newInputStream(payload),65536));
        var first=header.readPacket();require(first instanceof SymmetricKeyEncSessionPacket);
        var key=(SymmetricKeyEncSessionPacket)first;
        require(key.getVersion()==6&&key.getEncAlgorithm()==SymmetricKeyAlgorithmTags.AES_256&&key.getAeadAlgorithm()==AEADAlgorithmTags.GCM);
        var kdf=key.getS2K();require(kdf.getType()==S2K.ARGON_2&&kdf.getMemorySizeExponent()==16&&kdf.getPasses()==3&&kdf.getParallelism()==4);
        var second=header.readPacket();require(second instanceof SymmetricEncIntegrityPacket);
        var data=(SymmetricEncIntegrityPacket)second;
        require(data.getVersion()==2&&data.getCipherAlgorithm()==SymmetricKeyAlgorithmTags.AES_256&&data.getAeadAlgorithm()==AEADAlgorithmTags.GCM&&data.getChunkSize()==10);
    }
    private static void extract(Path zipPath,Path backup,long budget) throws Exception {
        directory(backup);long total=0;Set<String> paths=new HashSet<>();
        // ZipFile eagerly allocates its central directory before size() can enforce an entry cap.
        // Streaming local entries keeps authenticated-but-hostile ZIP metadata bounded as well.
        try(var zip=new ZipInputStream(Files.newInputStream(zipPath),StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();require(paths.size()<MAX_ENTRIES);
                require(!entry.isDirectory()&&paths.add(name)&&allowed(name));
                long limit=name.equals("manifest.json")?4*1024*1024L:name.equals("database.dump")?MAX_BYTES:name.endsWith(".source")?8388608:1024;
                require(entry.getSize()<=limit);limit=Math.min(limit,budget-total);
                Path file=backup.resolve(name).normalize();require(file.startsWith(backup));
                List<Path> parents=new ArrayList<>();for(Path parent=file.getParent();!parent.equals(backup);parent=parent.getParent())parents.add(parent);
                Collections.reverse(parents);for(Path parent:parents)if(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS))directory(parent);
                write(file,new byte[0]);
                try(var output=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    long count=transfer(zip,Channels.newOutputStream(output),limit);zip.closeEntry();
                    require(count==entry.getSize());total=Math.addExact(total,count);output.force(true);
                }
            }
        }require(paths.size()>=3);
    }
    private static boolean allowed(String path) {
        return Set.of("manifest.json","database.dump","BACKUP_COMPLETE","BACKUP_INCOMPLETE").contains(path)||
            path.matches("knowledge/sources/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:[1-9]|10)\\.source");
    }
    Completion completion(Path directory) throws Exception {
        privatePath(directory,true);Path marker=directory.resolve(COMPLETE);privatePath(marker,false);require(Files.size(marker)<=2048);
        var result=UnifiedPostgresqlBackup.JSON.readValue(Files.readAllBytes(marker),Completion.class);
        require(FORMAT.equals(result.format)&&result.version==1&&result.status.equals("ENCRYPTED_BACKUP_COMPLETE")&&result.profile.equals("RFC9580-AES256-GCM-ARGON2ID")&&result.payload.equals(PAYLOAD));
        require(result.bytes>0&&result.bytes<=MAX_BYTES+MAX_BYTES/100&&result.sha256.matches("[0-9a-f]{64}"));
        privatePath(directory.resolve(PAYLOAD),false);require(Files.size(directory.resolve(PAYLOAD))==result.bytes&&digest(directory.resolve(PAYLOAD)).equals(result.sha256));
        try(var files=Files.list(directory)) {
            var iterator=files.iterator();while(iterator.hasNext()) {
                Path file=iterator.next();require(Set.of(PAYLOAD,COMPLETE,"ENCRYPTED_BACKUP_INCOMPLETE").contains(file.getFileName().toString()));privatePath(file,false);
            }
        }
        return result;
    }

    /** SQL trust is a separate explicit decision; passphrase authentication does not imply trust.
     * Config is not even opened until authentication + every B3 static validation has completed.
     */
    public UnifiedPostgresqlBackup.Result restore(Path encrypted,Path staging,char[] passphrase,Path targetConfig,boolean trustedSqlSource) throws IOException {
        String stage="SQL_SOURCE_TRUST";
        try {
            require(trustedSqlSource);stage="AUTHENTICATION";var decrypted=decrypt(encrypted,staging,passphrase);
            stage="TARGET_CONFIGURATION_AND_RESTORE";
            privatePath(targetConfig,false);require(Files.size(targetConfig)<16384);
            var c=UnifiedPostgresqlBackup.JSON.readTree(Files.readAllBytes(targetConfig));
            var owner=new UnifiedPostgresqlBackup.Endpoint(c.path("targetDatabase").asString(),c.path("ownerUsername").asString(),c.path("ownerPassword").asString());
            var app=new UnifiedPostgresqlBackup.Endpoint(owner.database(),c.path("appUsername").asString(),c.path("appPassword").asString());
            // Tool installation belongs to the destination computer, never to the saved source paths.
            var targetMaintenance=new UnifiedPostgresqlBackup(Path.of(c.path("tools").asString()));
            return targetMaintenance.restore(decrypted.backup,owner,app,c.path("ownershipReceipt").asString(),staging.resolve("candidate"));
        }catch(Exception failure){
            if(stage.equals("AUTHENTICATION")&&failure instanceof IOException io)throw io;
            throw new IOException("Encrypted restore stopped: "+stage+"; no activation; retain private evidence");
        }finally{clear(passphrase);}
    }
    private static long transfer(InputStream input,OutputStream output,long limit) throws IOException {
        byte[] buffer=new byte[BUFFER];long count=0;int n;
        try {while((n=input.read(buffer))!=-1){count=Math.addExact(count,n);require(count<=limit);output.write(buffer,0,n);}return count;}
        finally{Arrays.fill(buffer,(byte)0);}
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private long left;
        LimitedInputStream(InputStream input,long limit){super(input);left=limit;}
        @Override public int read() throws IOException{require(left>0);int value=super.read();if(value>=0)left--;return value;}
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            if(length==0)return 0;require(left>0);int n=in.read(bytes,offset,(int)Math.min(length,left));if(n>0)left-=n;return n;
        }
    }
    static void stagingParent(Path path) throws IOException {
        path=path.toAbsolutePath().normalize();privatePath(path.getParent(),true);
        require(!path.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()));
        for(String name:List.of("OneDrive","OneDriveConsumer","OneDriveCommercial")) {
            String value=System.getenv(name);if(value!=null&&!value.isBlank())require(!path.startsWith(Path.of(value).toAbsolutePath().normalize()));
        }
        for(Path part=path;part!=null;part=part.getParent())if(part.getFileName()!=null)
            require(!part.getFileName().toString().toLowerCase(Locale.ROOT).matches("onedrive.*|dropbox|google drive|iclouddrive"));
    }
    private static void password(char[] password) throws IOException {require(password!=null&&password.length>=16&&password.length<=1024);}
    private static void clear(char[] password){if(password!=null)Arrays.fill(password,'\0');}
    private static IOException rejected(String stage,Exception failure){
        String location=Arrays.stream(failure.getStackTrace()).filter(f->f.getClassName().equals(PortableEncryptedBackup.class.getName()))
            .findFirst().map(f->f.getMethodName()+":"+f.getLineNumber()).orElse("unknown");
        return new IOException("Encrypted backup rejected: "+stage+"; kind="+failure.getClass().getSimpleName()+"; at="+location+"; no target access; incomplete private staging may remain");
    }
}
