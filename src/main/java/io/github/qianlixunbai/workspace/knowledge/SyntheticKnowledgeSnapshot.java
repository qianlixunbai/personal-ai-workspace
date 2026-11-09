package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.security.WindowsNativeGate;
import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeHistorySnapshotRecord.*;

/** Isolated prototype, not a bean/CLI/HTTP route. The only factory creates fresh synthetic sources.
 * No API accepts an existing source database, active Profile, PostgreSQL connection or arbitrary restore target.
 * Fixed plaintext files are synthetic-only; this does not extend any user-facing backup format. */
final class SyntheticKnowledgeSnapshot implements AutoCloseable {
    static final String FORMAT="personal-ai-workspace.synthetic-knowledge-history-snapshot";
    static final long HISTORY_BYTES=4L+KnowledgeAnswerHistory.LIMIT*(4L+MAX_RECORD_BYTES);
    static final Set<String> FILES=Set.of("knowledge.backup","answers.bin","manifest.json");
    record Manifest(String format,int version,String protection,int answers,long sourceBytes,String sourceSha256,
                    long historyBytes,String historySha256) {}
    private final Path root,token;
    private final KnowledgeStore source;
    private final KnowledgeAnswerHistory history;
    private SyntheticKnowledgeSnapshot(Path root,Path token,KnowledgeStore source,KnowledgeAnswerHistory history){
        this.root=root;this.token=token;this.source=source;this.history=history;
    }
    static SyntheticKnowledgeSnapshot create(Path parent,Path token) throws IOException {
        parent=parent.toAbsolutePath().normalize();PrivateKnowledgeDirectory.validate(parent,token);
        // Native admission is unchanged. Windows fixtures live only in the admitted Profile's evidence domain.
        if(WindowsPrivateAcl.isWindows()&&!parent.startsWith(WindowsNativeGate.profileRoot().resolve("evidence")))
            throw new IOException("Synthetic evidence domain required");
        PrivateKnowledgeDirectory.noLinks(parent);require(Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS));
        // Keep synthetic paths short enough for the existing Windows SQLite journal path budget.
        Path root=parent.resolve("ks-"+UUID.randomUUID().toString().replace("-","").substring(0,16));require(!Files.exists(root,LinkOption.NOFOLLOW_LINKS));
        PrivateKnowledgeDirectory.directory(root);KnowledgeStore source=null;
        try{
            source=new KnowledgeStore(root.resolve("source"),token);
            return new SyntheticKnowledgeSnapshot(root,token,source,new KnowledgeAnswerHistory(root.resolve("source"),token));
        }catch(Exception e){if(source!=null)source.close();throw e;}
    }
    Path root(){return root;}
    KnowledgeStore source(){return source;}
    KnowledgeAnswerHistory history(){return history;}
    Path candidate(){return root.resolve("candidate-"+UUID.randomUUID());}
    Path encryptedSnapshot(char[] password){return new SyntheticEncryptedKnowledgeSnapshot(this).snapshot(password);}
    Manifest restoreEncrypted(Path encrypted,Path target,char[] password){return new SyntheticEncryptedKnowledgeSnapshot(this).restore(encrypted,target,password);}
    Path snapshot(){return snapshot(()->{});}
    /** Probe hook is inside both locks, for deterministic writer exclusion verification in this isolated fixture. */
    Path snapshot(Runnable afterFreeze){
        Path pending=root.resolve("snapshot-"+UUID.randomUUID()+".pending");
        Path complete=pending.resolveSibling(pending.getFileName().toString().replace(".pending",""));
        try{
            PrivateKnowledgeDirectory.directory(pending);
            writeEntries((name,maximum,writer)->write(pending.resolve(name),maximum,writer),afterFreeze);
            Files.move(pending,complete);return complete;
        }catch(WorkspaceException e){throw e;}catch(Exception e){throw new WorkspaceException(ErrorCode.KNOWLEDGE_EXPORT_FAILED,"SYNTHETIC_SNAPSHOT");}
        // Failed private staging is deliberately retained as fixture evidence; it is never a completed snapshot.
    }
    /** Same logical writer and frozen source/history boundary for clear and encrypted outputs. */
    void writeEntries(EntrySink sink,Runnable afterFreeze){
        source.frozenSnapshot(()->history.frozenSnapshot(()->{
            afterFreeze.run();int count=(int)history.list(0).total();
            var sourceFile=entry(sink,"knowledge.backup",KnowledgeLimits.BACKUP_BYTES,out->new KnowledgeBackup(source,token).writeSnapshot(out));
            var historyFile=entry(sink,"answers.bin",HISTORY_BYTES,out->{
                var data=new DataOutputStream(out);data.writeInt(count);int[] written={0};
                history.visitSnapshot(row->{byte[] bytes=encode(capture(row,source));data.writeInt(bytes.length);data.write(bytes);written[0]++;});
                require(written[0]==count);data.flush();
            });
            var manifest=new Manifest(FORMAT,1,"UNENCRYPTED_SYNTHETIC_ONLY",count,
                    sourceFile.bytes(),sourceFile.sha256(),historyFile.bytes(),historyFile.sha256());
            sink.write("manifest.json",4096,out->out.write(JSON.writeValueAsBytes(manifest)));return null;
        }));
    }
    @FunctionalInterface interface EntrySink {void write(String name,long maximum,Writer writer)throws Exception;}
    private record EntryDigest(long bytes,String sha256){}
    private static EntryDigest entry(EntrySink sink,String name,long maximum,Writer writer)throws Exception{
        var hash=hash();long[] count={0};
        sink.write(name,maximum,out->{
            var digest=new FilterOutputStream(out){
                @Override public void write(int value)throws IOException{require(++count[0]<=maximum);hash.update((byte)value);out.write(value);}
                @Override public void write(byte[] bytes,int offset,int length)throws IOException{
                    count[0]+=length;require(count[0]<=maximum);hash.update(bytes,offset,length);out.write(bytes,offset,length);
                }
                @Override public void close()throws IOException{flush();}
            };
            writer.write(digest);digest.flush();
        });
        return new EntryDigest(count[0],HexFormat.of().formatHex(hash.digest()));
    }
    Manifest restore(Path snapshot,Path target){return restore(snapshot,target,()->{});}
    Manifest restore(Path snapshot,Path target,Runnable beforePublish){
        Path staging=null;
        try{
            child(snapshot,"snapshot-");child(target,"candidate-");
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new WorkspaceException(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,"SYNTHETIC_SNAPSHOT");
            PrivateKnowledgeDirectory.noLinks(snapshot);require(Files.isDirectory(snapshot,LinkOption.NOFOLLOW_LINKS));
            try(var paths=Files.list(snapshot)){require(paths.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(FILES));}
            for(String name:FILES){var file=snapshot.resolve(name);PrivateKnowledgeDirectory.regular(file);PrivateKnowledgeDirectory.protect(file,false);}
            require(Files.size(snapshot.resolve("manifest.json"))<=4096);
            var manifest=decode(Files.readAllBytes(snapshot.resolve("manifest.json")),Manifest.class);
            if(!FORMAT.equals(manifest.format())||manifest.version()!=1)throw new WorkspaceException(ErrorCode.KNOWLEDGE_BACKUP_UNSUPPORTED,"SYNTHETIC_SNAPSHOT");
            require("UNENCRYPTED_SYNTHETIC_ONLY".equals(manifest.protection())&&manifest.answers()>=0&&manifest.answers()<=KnowledgeAnswerHistory.LIMIT);
            verifyFile(snapshot.resolve("knowledge.backup"),manifest.sourceBytes(),manifest.sourceSha256(),KnowledgeLimits.BACKUP_BYTES);
            verifyFile(snapshot.resolve("answers.bin"),manifest.historyBytes(),manifest.historySha256(),HISTORY_BYTES);
            staging=root.resolve(".r-"+UUID.randomUUID().toString().replace("-","").substring(0,16));
            require(!Files.exists(staging,LinkOption.NOFOLLOW_LINKS));PrivateKnowledgeDirectory.directory(staging);
            var sourceHash=hash();
            try(var input=new DigestInputStream(Files.newInputStream(snapshot.resolve("knowledge.backup")),sourceHash)){
                new KnowledgeBackup(source,token).restore(input,staging.toString());
                require(HexFormat.of().formatHex(sourceHash.digest()).equals(manifest.sourceSha256()));
            }
            try(var restored=new KnowledgeStore(staging,token);var restoredHistory=new KnowledgeAnswerHistory(staging,token)){
                Set<UUID> ids=new HashSet<>();var historyHash=hash();
                try(var input=new DataInputStream(new DigestInputStream(Files.newInputStream(snapshot.resolve("answers.bin")),historyHash))){
                    require(input.readInt()==manifest.answers());
                    for(int i=0;i<manifest.answers();i++){
                        int length=input.readInt();require(length>0&&length<=MAX_RECORD_BYTES);
                        byte[] bytes=input.readNBytes(length);require(bytes.length==length);
                        var row=decode(bytes,Saved.class);var entry=verify(row,restored);require(ids.add(entry.task().taskId()));
                        restoredHistory.restoreTerminal(entry);
                    }
                    require(input.read()==-1&&HexFormat.of().formatHex(historyHash.digest()).equals(manifest.historySha256()));
                }
                require(restoredHistory.list(0).total()==manifest.answers());restored.verifyReconstruction();
            }
            // Publish both stores in one same-volume rename, after every record and original source is verified.
            beforePublish.run();
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new WorkspaceException(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,"SYNTHETIC_SNAPSHOT");
            // Same parent, directory rename, no REPLACE_EXISTING or cross-volume copy fallback (MemoryBackupService pattern).
            PrivateKnowledgeDirectory.noLinks(root);Files.move(staging,target);return manifest;
        }catch(WorkspaceException e){throw e;}
        catch(FileAlreadyExistsException e){throw new WorkspaceException(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY,"SYNTHETIC_SNAPSHOT");}
        catch(Exception e){throw invalid();}
        // Failed candidate directories are never activated or merged; leave them inside the owned fixture for inspection.
    }
    void child(Path path,String prefix) throws IOException {
        require(path!=null&&path.isAbsolute()&&path.normalize().equals(path)&&root.equals(path.getParent()));
        String name=path.getFileName().toString();require(name.startsWith(prefix));
        String id=name.substring(prefix.length());require(UUID.fromString(id).toString().equals(id));PrivateKnowledgeDirectory.noLinks(path);
    }
    private static void verifyFile(Path file,long bytes,String sha,long maximum) throws IOException {
        require(bytes>0&&bytes<=maximum&&sha!=null&&sha.matches("[0-9a-f]{64}")&&Files.size(file)==bytes);
        require(digest(file,maximum).equals(sha));
    }
    private static MessageDigest hash(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw invalid();}}
    static String digest(Path file,long maximum) throws IOException {
        var hash=hash();long count=0;try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];int n;
            while((n=input.read(buffer))!=-1){count+=n;require(count<=maximum);hash.update(buffer,0,n);}}
        return HexFormat.of().formatHex(hash.digest());
    }
    interface Writer {void write(OutputStream output)throws Exception;}
    static void write(Path file,long maximum,Writer writer)throws Exception {
        PrivateKnowledgeDirectory.file(file);
        try(var channel=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            var output=new FilterOutputStream(Channels.newOutputStream(channel)){
                long count;
                @Override public void write(int value)throws IOException{require(++count<=maximum);out.write(value);}
                @Override public void write(byte[] bytes,int offset,int length)throws IOException{count+=length;require(count<=maximum);out.write(bytes,offset,length);}
            };
            writer.write(output);output.flush();channel.force(true);
        }
    }
    @Override public void close(){history.close();source.close();}
}
