package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.conversation.*;
import io.github.qianlixunbai.workspace.knowledge.*;
import org.bouncycastle.bcpg.*;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.bc.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;
import static io.github.qianlixunbai.workspace.backup.UnifiedPostgresqlBackup.*;
import static io.github.qianlixunbai.workspace.backup.PortableEncryptedBackup.*;

/** One synthetic encryption/authentication flow, followed by one new real PostgreSQL restore.
 * No connection to the original test/dev/retained B3 databases; their saved file collection is read-only.
 */
@EnabledIfSystemProperty(named="encrypted.backup.test",matches="true")
class PortableEncryptedBackupIntegrationTest {
    @Test void streamedAuthenticatedPayloadStillRejectsUnsafePathsAndExpansionBeforeTargetConfig() throws Exception {
        Path config=Path.of(".runtime/workspace/b3e-test-credentials.json");privatePath(config,false);
        var c=JSON.readTree(Files.readAllBytes(config));Path source=Path.of(c.path("backupRoot").asString());
        Path root=Path.of(c.path("maintenanceRoot").asString()).resolve("streaming-"+UUID.randomUUID());directory(root);
        var secure=new PortableEncryptedBackup(Path.of(c.path("tools").asString()));
        var plain=new UnifiedPostgresqlBackup(Path.of(c.path("tools").asString()));c=null;
        char[] password=randomPassword();
        try {
            Path encrypted=root.resolve("encrypted");secure.encrypt(source,encrypted,password.clone());
            var decrypted=secure.decrypt(encrypted,root.resolve("decrypted"),password.clone());
            assertEquals(plain.validate(source),decrypted.manifest());assertTrue(Files.exists(root.resolve("decrypted/DECRYPTION_VERIFIED")));
            for(String attack:List.of("traversal","expansion")) {
                Path hostile=root.resolve(attack);hostileAuthenticatedZip(hostile,password,attack.equals("expansion"));
                Path staging=root.resolve("reject-"+attack);
                var failure=assertThrows(IOException.class,()->secure.restore(hostile,staging,password.clone(),root.resolve("absent-config"),true));
                assertTrue(failure.getMessage().contains("INTERNAL_VALIDATION"),failure::getMessage);
                assertFalse(Files.exists(staging.resolve("DECRYPTION_VERIFIED")));assertFalse(Files.exists(staging.resolve("candidate")));
                assertFalse(Files.exists(root.resolve("escape")));
                if(attack.equals("expansion"))assertTrue(Files.size(staging.resolve("backup/database.dump"))<=Files.size(hostile.resolve(PAYLOAD)));
            }
        }finally{Arrays.fill(password,'\0');}
    }
    /** Standard-library hostile ZIP fixture inside standard BC OpenPGP, with the normal KDF strength. */
    private static void hostileAuthenticatedZip(Path target,char[] password,boolean expansion) throws Exception {
        directory(target);Path payload=target.resolve(PAYLOAD);write(payload,new byte[0]);
        var builder=new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setUseV6AEAD().setWithAEAD(AEADAlgorithmTags.GCM,16);
        var generator=new PGPEncryptedDataGenerator(builder);generator.setForceSessionKey(true);
        generator.addMethod(new BcPBEKeyEncryptionMethodGenerator(password,S2K.Argon2Params.memoryConstrainedParameters()).setSecureRandom(new SecureRandom()));
        try(var output=Files.newOutputStream(payload);var crypt=generator.open(output,new byte[65536]);
            var literal=new PGPLiteralDataGenerator().open(crypt,PGPLiteralData.BINARY,LITERAL,new Date(0),new byte[65536]);
            var zip=new ZipOutputStream(literal)) {
            zip.putNextEntry(new ZipEntry(expansion?"database.dump":"../../escape"));
            byte[] bytes=new byte[65536];for(int i=0;i<(expansion?16:1);i++)zip.write(bytes);zip.closeEntry();
        }
        var completion=new Completion(FORMAT,1,"ENCRYPTED_BACKUP_COMPLETE","RFC9580-AES256-GCM-ARGON2ID",PAYLOAD,Files.size(payload),digest(payload));
        write(target.resolve(COMPLETE),JSON.writeValueAsBytes(completion));
    }
    @Test void authenticatedCollectionBeforeTargetThenRealStoresAndIndependentProcess() throws Exception {
        Path config=Path.of(".runtime/workspace/b3e-test-credentials.json");privatePath(config,false);
        var c=JSON.readTree(Files.readAllBytes(config));
        Path source=Path.of(c.path("backupRoot").asString()),parent=Path.of(c.path("maintenanceRoot").asString());
        Path root=parent.resolve("attempt-"+UUID.randomUUID());directory(root);
        var owner=new Endpoint(c.path("targetDatabase").asString(),c.path("ownerUsername").asString(),c.path("ownerPassword").asString());
        var app=new Endpoint(owner.database(),c.path("appUsername").asString(),c.path("appPassword").asString());
        var plain=new UnifiedPostgresqlBackup(Path.of(c.path("tools").asString()));var secure=new PortableEncryptedBackup(Path.of(c.path("tools").asString()));c=null;
        var original=plain.validate(source);assertEquals(2,original.snapshot().tables().get("public.users").rows());
        assertEquals(1,original.snapshot().tables().get("workspace.conversations").rows());
        char[] password=randomPassword();Path encrypted=root.resolve("encrypted");
        try {
            char[] encryptionPassword=password.clone();var complete=secure.encrypt(source,encrypted,encryptionPassword);cleared(encryptionPassword);
            assertEquals("ENCRYPTED_BACKUP_COMPLETE",complete.status());assertEquals(1,complete.version());
            assertEquals(complete,secure.completion(encrypted));
            try(var pin=pin(encrypted.resolve(PAYLOAD))){PortableEncryptedBackup.profile(pin);}
            assertThrows(IOException.class,()->secure.encrypt(source,encrypted,password.clone()));
            // A missing config is intentional: authentication failures must precede even loading it.
            Path absentConfig=root.resolve("must-never-be-read.json");
            rejectBeforeTarget(secure,encrypted,root.resolve("wrong-password"),randomPassword(),absentConfig,"AUTHENTICATED_DECRYPTION");
            for(String mode:List.of("middle","final-tag","truncated","kdf-cost")) {
                Path altered=root.resolve(mode);cloneEncrypted(encrypted,altered);
                Path payload=altered.resolve(PAYLOAD);
                try(var channel=FileChannel.open(payload,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
                    if(mode.equals("truncated"))channel.truncate(channel.size()-1);
                    else if(mode.equals("kdf-cost")) {
                        var pgp=new BCPGInputStream(Channels.newInputStream(channel));var key=(SymmetricKeyEncSessionPacket)pgp.readPacket();
                        byte[] kdf=key.getS2K().getEncoded(),header=new byte[256];channel.position(0);int size=channel.read(ByteBuffer.wrap(header));int found=-1;
                        for(int i=0;i<=size-kdf.length;i++)if(Arrays.equals(Arrays.copyOfRange(header,i,i+kdf.length),kdf)){found=i;break;}
                        assertTrue(found>=0);channel.position(found+kdf.length-1);channel.write(ByteBuffer.wrap(new byte[]{30}));
                    }else {
                        long offset=mode.equals("middle")?channel.size()/2:channel.size()-1;
                        var byteValue=ByteBuffer.allocate(1);channel.position(offset);channel.read(byteValue);byteValue.flip();byteValue.put(0,(byte)(byteValue.get(0)^1));
                        channel.position(offset);channel.write(byteValue);
                    }
                    channel.force(true);
                }
                // Recalculate the public checksum: the cryptographic tag/profile must reject it.
                var updated=new Completion(complete.format(),complete.version(),complete.status(),complete.profile(),PAYLOAD,Files.size(payload),digest(payload));
                Files.write(altered.resolve(COMPLETE),JSON.writeValueAsBytes(updated));
                rejectBeforeTarget(secure,altered,root.resolve("reject-"+mode),password.clone(),absentConfig,
                    mode.equals("kdf-cost")?"CONTAINER_VALIDATION":"AUTHENTICATED_DECRYPTION");
            }
            Path wrongVersion=root.resolve("wrong-version");cloneEncrypted(encrypted,wrongVersion);
            var future=new Completion(complete.format(),2,complete.status(),complete.profile(),PAYLOAD,complete.bytes(),complete.sha256());
            Files.write(wrongVersion.resolve(COMPLETE),JSON.writeValueAsBytes(future));
            rejectBeforeTarget(secure,wrongVersion,root.resolve("reject-version"),password.clone(),absentConfig,"CONTAINER_VALIDATION");
            interruptedEncryption(secure,source,root,password);
            independentProcess(encrypted,root,password);
            // All negative cases and full decryption finish before this first target DB read/restore.
            try(var db=owner.dataSource().getConnection()){assertTrue(tablesEmpty(db));}
            var result=secure.restore(encrypted,root.resolve("restore"),password.clone(),config,true);
            assertEquals(new Result("RESTORE_VERIFIED","IDENTITY_REBIND_REQUIRED","WINDOWS_ADMISSION_PENDING"),result);
            assertTrue(Files.exists(root.resolve("restore/DECRYPTION_VERIFIED")));
            var decrypted=plain.validate(root.resolve("restore/backup"));assertEquals(original,decrypted);
            Snapshot restored;
            try(var db=owner.dataSource().getConnection()) {
                restored=snapshot(db);histories(db);
                assertEquals("90071992547409.93",value(db,"SELECT balance::text FROM public.accounts WHERE name='B3 precision'"));
                assertEquals("12.34",value(db,"SELECT amount::text FROM public.transactions WHERE description='B3 synthetic transaction'"));
                assertEquals(2,scalar(db,"SELECT count(*) FROM public.users"));assertEquals(2,scalar(db,"SELECT count(*) FROM public.accounts"));
                assertEquals(1,scalar(db,"SELECT count(*) FROM workspace.sqlite_import"));assertEquals(1,scalar(db,"SELECT count(*) FROM workspace.knowledge_sqlite_import"));
            }
            try(var memories=new MemoryStore(app.dataSource(),root);var conversations=new ConversationStore(app.dataSource());
                var knowledge=new KnowledgeStore(app.dataSource());var index=new KnowledgeLexicalIndex(knowledge)) {
                var memory=memories.backupSnapshot().getFirst();assertNotNull(memories.get(memory.id()));
                var conversation=conversations.list(Conversation.Status.ACTIVE,0,10).items().getFirst();
                var turn=conversations.detail(conversation.id(),0,10).turns().getFirst();
                assertEquals("B3 synthetic question",turn.userMessage().content());assertEquals("B3 synthetic answer",turn.assistantMessage().content());
                assertEquals(memory.id(),turn.memories().getFirst().memoryId());assertEquals(memory.revision(),turn.memories().getFirst().revision());
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!index.status().state().equals("READY")&&System.nanoTime()<deadline)Thread.sleep(20);
                assertEquals("READY",index.status().state());var hit=index.search("预算",10).hits().getFirst();
                assertTrue(knowledge.preview(hit.documentId(),hit.sourceRevision(),0).text().contains("预算"));
                assertFalse(knowledge.preview(hit.documentId(),hit.sourceRevision(),0).locators().isEmpty());
            }
            assertThrows(IOException.class,()->secure.restore(encrypted,root.resolve("duplicate"),password.clone(),config,true));
            assertFalse(Files.exists(root.resolve("duplicate/candidate")));
            try(var db=owner.dataSource().getConnection()){assertEquals(restored,snapshot(db));}
            assertEquals(original,plain.validate(source)); // Preserved archive, Manifest and every file unchanged.
            Map<String,Object> evidence=new LinkedHashMap<>();
            evidence.put("backupEncryption","VERIFIED");evidence.put("authenticatedDecryption","VERIFIED");evidence.put("postgresqlKnowledgeRestore","VERIFIED");
            evidence.put("portableProductReadiness","PARTIAL: plaintext staging, Windows admission and Finance identity rebind pending");
            evidence.put("implementation","Bouncy Castle 1.86 / RFC 9580 / AES-256-GCM / Argon2id 64MiB t=3 p=4");
            evidence.put("targetDatabase",owner.database());evidence.put("ciphertextBytes",complete.bytes());evidence.put("knowledgeFiles",original.knowledgeFiles().size());
            evidence.put("negativeAuthenticationCasesBeforeTargetConfiguration",true);evidence.put("interruptedEncryptionNeverPublished",true);
            evidence.put("independentJvmEmptyHomeNoDpapi",true);evidence.put("duplicateRestoreRefused",true);evidence.put("sourceCollectionUnchanged",true);
            evidence.put("testPassphrase","Random in-memory test material; cleared, never persisted, not a retained recovery secret");
            write(root.resolve("evidence.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
            System.out.println("B3E: standard encrypted collection + full authentication + new PostgreSQL/Knowledge/Stores restore VERIFIED; production/Windows/rebind PARTIAL");
            System.out.println("B3E evidence: "+root.resolve("evidence.json"));
        }finally{Arrays.fill(password,'\0');}
    }
    private static boolean tablesEmpty(java.sql.Connection db) throws Exception {
        return scalar(db,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','workspace')")==0;
    }
    private static void rejectBeforeTarget(PortableEncryptedBackup secure,Path encrypted,Path stage,char[] password,Path missingConfig,String expected) throws Exception {
        var error=assertThrows(IOException.class,()->secure.restore(encrypted,stage,password,missingConfig,true));
        assertTrue(error.getMessage().contains(expected),error::getMessage);cleared(password);
        assertFalse(Files.exists(stage.resolve("backup")));assertFalse(Files.exists(stage.resolve("DECRYPTION_VERIFIED")));assertFalse(Files.exists(stage.resolve("candidate")));
    }
    private static void cloneEncrypted(Path from,Path to) throws Exception {
        directory(to);for(String name:List.of(PAYLOAD,COMPLETE,"ENCRYPTED_BACKUP_INCOMPLETE"))copy(from.resolve(name),to.resolve(name));
    }
    private static char[] randomPassword() {
        var random=new SecureRandom();char[] password=new char[48];String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for(int i=0;i<password.length;i++)password[i]=alphabet.charAt(random.nextInt(alphabet.length()));return password;
    }
    private static void cleared(char[] chars){for(char c:chars)assertTrue(c=='\0',"Consumed passphrase must be cleared");}
    private static void interruptedEncryption(PortableEncryptedBackup secure,Path source,Path root,char[] password) throws Exception {
        Path interrupted=root.resolve("interrupted-encryption");var failure=new AtomicReference<Throwable>();char[] secret=password.clone();
        var writer=new Thread(()->{try{secure.encrypt(source,interrupted,secret);}catch(Throwable error){failure.set(error);}},"b3e-interruption");
        writer.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!Files.exists(interrupted.resolve("ENCRYPTED_BACKUP_INCOMPLETE"))&&writer.isAlive()&&System.nanoTime()<deadline)Thread.sleep(1);
        writer.interrupt();writer.join(15000);assertFalse(writer.isAlive());assertNotNull(failure.get());cleared(secret);
        assertTrue(Files.exists(interrupted.resolve("ENCRYPTED_BACKUP_INCOMPLETE")));assertFalse(Files.exists(interrupted.resolve(COMPLETE)));
        assertThrows(IOException.class,()->secure.decrypt(interrupted,root.resolve("reject-interrupted"),password.clone()));
    }
    private static void independentProcess(Path encrypted,Path root,char[] password) throws Exception {
        Path emptyHome=root.resolve("independent-home");directory(emptyHome);
        Path java=Path.of(System.getProperty("java.home"),"bin","java.exe");
        var builder=new ProcessBuilder(java.toString(),"-Xmx256m","-Duser.home="+emptyHome,"-cp",System.getProperty("java.class.path"),
            PortableEncryptedBackupIntegrationTest.class.getName(),encrypted.toString(),root.resolve("independent-decryption").toString());
        builder.environment().keySet().removeIf(k->k.toUpperCase(Locale.ROOT).contains("PASSPHRASE"));builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();
        try {
            // Anonymous pipe only for generated test material; production CLI only accepts Console.readPassword.
            try(var input=new DataOutputStream(process.getOutputStream())){input.writeInt(password.length);for(char c:password)input.writeChar(c);}
            assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
            assertEquals("INDEPENDENT_DECRYPTION_VERIFIED",new String(process.getInputStream().readNBytes(1024),StandardCharsets.UTF_8).strip());
        }finally{if(process.isAlive())process.destroyForcibly();process.getInputStream().close();process.getErrorStream().close();}
    }
    /** Child-JVM test helper, never packaged as a maintenance passphrase-input fallback. */
    public static void main(String[] args) throws Exception {
        char[] password=null;
        try(var input=new DataInputStream(System.in)) {
            int length=input.readInt();if(length<16||length>1024)throw new IOException();password=new char[length];
            for(int i=0;i<length;i++)password[i]=input.readChar();
            var result=new PortableEncryptedBackup(Path.of("D:/PostgreSQL/bin")).decrypt(Path.of(args[0]),Path.of(args[1]),password);
            if(result.manifest().knowledgeFiles().size()!=7)throw new IOException();
            System.out.println("INDEPENDENT_DECRYPTION_VERIFIED");
        }finally{if(password!=null)Arrays.fill(password,'\0');}
    }
}
