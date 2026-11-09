package io.github.qianlixunbai.workspace.knowledge;

import com.sun.nio.file.ExtendedOpenOption;
import io.github.qianlixunbai.workspace.backup.OpenPgpStreams;
import io.github.qianlixunbai.workspace.common.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeHistorySnapshotRecord.*;
import static io.github.qianlixunbai.workspace.knowledge.SyntheticKnowledgeSnapshot.*;

/** Synthetic-only adapter to the established OpenPGP codec. Not a bean, CLI or user API.
 * Only an existing fresh-fixture capability can construct it; no personal source is accepted.
 * The envelope has its own version and literal name, never a new meaning for PG v1/v2/v3. */
final class SyntheticEncryptedKnowledgeSnapshot {
    static final String ENCRYPTED_FORMAT="personal-ai-workspace.synthetic-knowledge-history-openpgp";
    static final String LITERAL="workspace-synthetic-knowledge-history-v1.zip";
    static final String PROFILE="RFC9580-AES256-GCM-ARGON2ID";
    static final String PAYLOAD="backup.pgp",RECEIPT="complete.json";
    static final long CLEAR_BYTES=KnowledgeLimits.BACKUP_BYTES+HISTORY_BYTES+16384;
    static final long CIPHER_BYTES=CLEAR_BYTES+CLEAR_BYTES/100+1048576;
    record Completion(String format,int version,String profile,long bytes,String sha256){}
    private final SyntheticKnowledgeSnapshot fixture;
    SyntheticEncryptedKnowledgeSnapshot(SyntheticKnowledgeSnapshot fixture){this.fixture=Objects.requireNonNull(fixture);}

    Path snapshot(char[] password){return snapshot(password,()->{});}
    Path snapshot(char[] password,Runnable afterFreeze){
        Path complete=fixture.root().resolve("encrypted-"+UUID.randomUUID());
        Path pending=complete.resolveSibling(complete.getFileName()+".pending");
        try{
            password(password);require(!Files.exists(pending,LinkOption.NOFOLLOW_LINKS)&&!Files.exists(complete,LinkOption.NOFOLLOW_LINKS));
            PrivateKnowledgeDirectory.directory(pending);
            write(pending.resolve(PAYLOAD),CIPHER_BYTES,out->OpenPgpStreams.encryptZip(out,password,LITERAL,CLEAR_BYTES,zip->
                fixture.writeEntries((name,maximum,writer)->{
                    require(!Thread.currentThread().isInterrupted());
                    var entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);writer.write(zip);zip.closeEntry();
                },afterFreeze)));
            require(!Thread.currentThread().isInterrupted());
            var completion=new Completion(ENCRYPTED_FORMAT,1,PROFILE,Files.size(pending.resolve(PAYLOAD)),digest(pending.resolve(PAYLOAD),CIPHER_BYTES));
            write(pending.resolve(RECEIPT),4096,out->out.write(JSON.writeValueAsBytes(completion)));
            Files.move(pending,complete);return complete;
        }catch(WorkspaceException e){throw e;}
        catch(Exception e){throw new WorkspaceException(ErrorCode.KNOWLEDGE_EXPORT_FAILED,"SYNTHETIC_ENCRYPTED_SNAPSHOT");}
        finally{clear(password);}
        // Incomplete ciphertext may remain for diagnostics; it is never published as complete.
    }

    Manifest restore(Path encrypted,Path target,char[] password){
        Path decoded=null;
        try{
            password(password);fixture.child(encrypted,"encrypted-");fixture.child(target,"candidate-");
            PrivateKnowledgeDirectory.protect(encrypted,true);exactFiles(encrypted,Set.of(PAYLOAD,RECEIPT));
            try(var receipt=pin(encrypted.resolve(RECEIPT));var payload=pin(encrypted.resolve(PAYLOAD))){
                require(receipt.size()>0&&receipt.size()<=4096);
                var completion=decode(Channels.newInputStream(receipt).readNBytes(4097),Completion.class);
                require(ENCRYPTED_FORMAT.equals(completion.format())&&completion.version()==1&&PROFILE.equals(completion.profile()));
                require(completion.bytes()>0&&completion.bytes()<=CIPHER_BYTES&&completion.bytes()==payload.size());
                require(completion.sha256()!=null&&completion.sha256().matches("[0-9a-f]{64}")
                        &&completion.sha256().equals(digest(encrypted.resolve(PAYLOAD),CIPHER_BYTES)));
                OpenPgpStreams.profile(payload);payload.position(0);
                long budget=Math.min(CLEAR_BYTES,completion.bytes());
                // First pass drains and authenticates ALL packets before creating any plaintext files.
                OpenPgpStreams.decryptBundle(Channels.newInputStream(payload),password,LITERAL,
                        input->OpenPgpStreams.transfer(input,OutputStream.nullOutputStream(),budget));
                payload.position(0);
                decoded=fixture.root().resolve("snapshot-"+UUID.randomUUID());
                require(!Files.exists(decoded,LinkOption.NOFOLLOW_LINKS));PrivateKnowledgeDirectory.directory(decoded);
                Path archive=decoded;
                OpenPgpStreams.decryptBundle(Channels.newInputStream(payload),password,LITERAL,input->{
                    extract(input,archive,budget);OpenPgpStreams.transfer(input,OutputStream.nullOutputStream(),budget);
                });
            }
            Path archive=decoded;
            // Trusted schema/source reconstruction is unchanged. Remove decoded transport files
            // after validation, before candidate publication; this is not secure disk erasure.
            return fixture.restore(archive,target,()->{
                try{clean(archive);}catch(IOException e){throw new UncheckedIOException(e);}
            });
        }catch(WorkspaceException e){throw e;}
        catch(Exception e){throw invalid();}
        finally{
            clear(password);
            if(decoded!=null&&Files.exists(decoded,LinkOption.NOFOLLOW_LINKS))try{clean(decoded);}
            catch(IOException e){throw new WorkspaceException(ErrorCode.KNOWLEDGE_BACKUP_INVALID,"SYNTHETIC_PLAINTEXT_CLEANUP_REQUIRED");}
        }
    }
    private static void extract(InputStream input,Path archive,long budget)throws Exception{
        Set<String> names=new HashSet<>();long total=0;
        var shield=new FilterInputStream(input){@Override public void close(){}};
        try(var zip=new ZipInputStream(shield,StandardCharsets.UTF_8)){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();require(names.size()<3&&!entry.isDirectory()&&FILES.contains(name)&&names.add(name));
                long maximum=switch(name){case "knowledge.backup"->KnowledgeLimits.BACKUP_BYTES;case "answers.bin"->HISTORY_BYTES;default->4096;};
                long limit=Math.min(maximum,budget-total);require(entry.getSize()<=limit);
                long[] size={0};write(archive.resolve(name),limit,out->size[0]=OpenPgpStreams.transfer(zip,out,limit));
                zip.closeEntry();require(entry.getSize()==size[0]);total=Math.addExact(total,size[0]);
            }
        }
        require(names.equals(FILES));
    }
    private static FileChannel pin(Path file)throws IOException{
        PrivateKnowledgeDirectory.regular(file);PrivateKnowledgeDirectory.protect(file,false);
        Set<OpenOption> options=new HashSet<>(Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
        if(System.getProperty("os.name","").startsWith("Windows")){
            options.add(ExtendedOpenOption.NOSHARE_WRITE);options.add(ExtendedOpenOption.NOSHARE_DELETE);
        }
        return FileChannel.open(file,options);
    }
    private static void exactFiles(Path directory,Set<String> expected)throws IOException{
        PrivateKnowledgeDirectory.noLinks(directory);
        try(var files=Files.list(directory)){require(files.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(expected));}
    }
    private static void clean(Path archive)throws IOException{
        PrivateKnowledgeDirectory.noLinks(archive);PrivateKnowledgeDirectory.protect(archive,true);
        List<Path> files;
        try(var paths=Files.list(archive)){files=paths.toList();}
        // Never recursively remove a directory or delete a file outside this fixed transport schema.
        for(Path file:files){require(FILES.contains(file.getFileName().toString()));PrivateKnowledgeDirectory.regular(file);PrivateKnowledgeDirectory.protect(file,false);}
        for(Path file:files)Files.delete(file);
        Files.delete(archive);
    }
    private static void password(char[] value){require(value!=null&&value.length>=16&&value.length<=1024);}
    private static void clear(char[] value){if(value!=null)Arrays.fill(value,'\0');}
}
