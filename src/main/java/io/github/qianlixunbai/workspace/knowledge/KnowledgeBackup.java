package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeLimits.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.error;

/** v1: no archive paths, compression, extraction filenames or whole-corpus in-memory object. */
public final class KnowledgeBackup {
    static final byte[] MAGIC="personal-ai-workspace.knowledge-backup\n".getBytes(StandardCharsets.US_ASCII);
    private static final JsonMapper JSON=JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(4096).maxNameLength(64).maxNumberLength(20).maxNestingDepth(3).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final KnowledgeStore active;private final Path token;private final Object maintenance=new Object();
    public KnowledgeBackup(KnowledgeStore active,Path token){this.active=active;this.token=token;}
    public record Metadata(int formatVersion,int schemaVersion,String createdAt,int documentCount,int revisionCount,long sourceBytes,long artifactBytes,String contentDigest) {}
    private record Document(String documentId,String title,String status,String metadataVersion,String currentReadyRevision,String createdAt,String updatedAt){}
    static void require(boolean condition){if(!condition)throw error(ErrorCode.KNOWLEDGE_BACKUP_INVALID);}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException();}}
    public Metadata export(OutputStream output) {
        try{return active.snapshot(()->{
            if(active.scalar("SELECT count(*) FROM jobs WHERE state IN ('PENDING','PARSING')")>0||active.scalar("SELECT count(*) FROM deletes")>0)
                throw error(ErrorCode.KNOWLEDGE_BACKUP_CONFLICT);
            int docs=(int)active.scalar("SELECT count(*) FROM documents"),revs=(int)active.scalar("SELECT count(*) FROM revisions");long created=System.currentTimeMillis();
            long sourceBytes=active.scalar("SELECT coalesce(sum(bytes),0) FROM revisions"),artifactBytes=active.scalar("SELECT coalesce(sum(artifact_bytes),0) FROM revisions");
            var hash=digest();var hashed=new DigestOutputStream(output,hash);var out=new DataOutputStream(hashed);
            out.write(MAGIC);out.writeInt(1);out.writeInt(SCHEMA());out.writeLong(created);out.writeInt(docs);out.writeInt(revs);out.writeLong(sourceBytes);out.writeLong(artifactBytes);
            try(var s=active.statement("SELECT * FROM documents ORDER BY id");var r=s.executeQuery()){while(r.next()){
                var d=new Document(r.getString("id"),r.getString("title"),r.getString("status"),r.getString("version"),r.getString("current_revision"),r.getString("created"),r.getString("updated"));
                out.writeByte('D');json(out,d);var rows=active.revisions(d.documentId());out.writeInt(rows.size());
                for(var revision:rows){out.writeByte('R');json(out,revision);Path source=active.source(d.documentId(),revision.sourceRevision());PrivateKnowledgeDirectory.regular(source);
                    require(Files.size(source)==revision.byteLength());out.writeLong(revision.byteLength());
                    var sourceHash=digest();try(var input=new DigestInputStream(Files.newInputStream(source),sourceHash)){copy(input,out,revision.byteLength());require(input.read()==-1);}
                    require(HexFormat.of().formatHex(sourceHash.digest()).equals(revision.sourceDigest()));
                    try(var text=active.statement("SELECT text,locators FROM revisions WHERE doc_id=? AND revision=?",d.documentId(),KnowledgeStore.version(revision.sourceRevision()));var values=text.executeQuery()){
                        require(values.next());blob(out,values.getString(1));blob(out,values.getString(2));}
                }
            }}out.writeByte('E');out.flush();String checksum=HexFormat.of().formatHex(hash.digest());hashed.on(false);out.write(HexFormat.of().parseHex(checksum));out.flush();
            return new Metadata(1,SCHEMA(),Instant.ofEpochMilli(created).toString(),docs,revs,sourceBytes,artifactBytes,checksum);
        });}catch(WorkspaceException e){throw e;}catch(Exception e){throw error(ErrorCode.KNOWLEDGE_EXPORT_FAILED);}
    }
    private static int SCHEMA(){return KnowledgeStore.SCHEMA_VERSION;}
    private static void json(DataOutputStream out,Object value)throws IOException {byte[] bytes=JSON.writeValueAsBytes(value);require(bytes.length<=4096);out.writeInt(bytes.length);out.write(bytes);}
    private static void blob(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static void copy(InputStream input,OutputStream output,long length)throws IOException {
        byte[] buffer=new byte[65536];while(length>0){int n=input.read(buffer,0,(int)Math.min(length,buffer.length));if(n<0)throw new EOFException();if(n==0)continue;output.write(buffer,0,n);length-=n;}
    }
    public Metadata validate(InputStream input){synchronized(maintenance){return reconstruct(input,null);}}
    public Metadata restore(InputStream input,String target){synchronized(maintenance){return reconstruct(input,target);}}
    private Metadata reconstruct(InputStream input,String targetText) {
        Path staging=null,target=null;boolean createdTarget=false;Set<Path> owned=new HashSet<>();
        try {
            if(targetText!=null){if(targetText.length()>8192)throw error(ErrorCode.KNOWLEDGE_RESTORE_FAILED);target=Path.of(targetText);
                if(!target.isAbsolute()||!target.normalize().equals(target))throw error(ErrorCode.KNOWLEDGE_RESTORE_FAILED);
                PrivateKnowledgeDirectory.validate(target,token);
                Path activeData=active.root().getParent();if(target.startsWith(activeData)||activeData.startsWith(target))throw error(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY);
                empty(target);createdTarget=!Files.exists(target);PrivateKnowledgeDirectory.directory(target);
            }
            // Restore staging is inside the explicitly selected empty target, so publication stays on its volume.
            staging=(target==null?active.root().resolve("staging"):target).resolve(UUID.randomUUID()+".backup");Files.createDirectory(staging);PrivateKnowledgeDirectory.protect(staging,true);owned.add(staging);
            Path knowledge=staging.resolve("knowledge");
            Metadata result;
            try(var store=new KnowledgeStore(staging,token)){
                owned.add(knowledge);owned.add(knowledge.resolve("sources"));owned.add(knowledge.resolve("staging"));owned.add(knowledge.resolve("knowledge.db"));owned.add(knowledge.resolve("knowledge.lock"));owned.add(knowledge.resolve("knowledge.db-journal"));
                result=read(input,store,owned);store.verifyReconstruction();
            }
            if(target!=null){PrivateKnowledgeDirectory.noLinks(target);
                try(var children=Files.list(target)){var entries=children.toList();require(entries.size()==1&&entries.getFirst().equals(staging));}
                Path destination=target.resolve("knowledge");
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw error(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY);
                Files.move(knowledge,destination,StandardCopyOption.ATOMIC_MOVE);
                owned.removeIf(p->p.startsWith(knowledge));
            }
            return result;
        }catch(WorkspaceException e){
            if(Set.of(ErrorCode.KNOWLEDGE_BACKUP_TOO_LARGE,ErrorCode.KNOWLEDGE_BACKUP_INVALID,ErrorCode.KNOWLEDGE_BACKUP_UNSUPPORTED,ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY).contains(e.error().code()))throw e;
            throw error(targetText==null?ErrorCode.KNOWLEDGE_BACKUP_INVALID:ErrorCode.KNOWLEDGE_RESTORE_FAILED);
        }catch(Exception e){throw error(targetText==null?ErrorCode.KNOWLEDGE_BACKUP_INVALID:ErrorCode.KNOWLEDGE_RESTORE_FAILED);}
        finally{
            if(staging!=null){try{cleanupOwned(owned);}catch(Exception ignored){/* Preserve any unexpected object; never recursively delete it. */}}
            if(createdTarget&&target!=null){try{if(Files.isDirectory(target)){try(var files=Files.list(target)){if(files.findAny().isEmpty())Files.delete(target);}}}catch(Exception ignored){}}
        }
    }
    private static void empty(Path target)throws IOException{
        PrivateKnowledgeDirectory.noLinks(target);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isDirectory(target))throw error(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY);
            try(var children=Files.list(target)){if(children.findAny().isPresent())throw error(ErrorCode.KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY);}
        }
    }
    static void cleanupOwned(Set<Path> paths)throws IOException{
        for(Path path:paths.stream().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList())if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
            PrivateKnowledgeDirectory.noLinks(path);Files.delete(path);
        }
    }
    private Metadata read(InputStream raw,KnowledgeStore store,Set<Path> owned)throws Exception {
        var hash=digest();var hashed=new DigestInputStream(new BoundedInput(raw),hash);var in=new DataInputStream(hashed);
        require(Arrays.equals(in.readNBytes(MAGIC.length),MAGIC));if(in.readInt()!=1||in.readInt()!=SCHEMA())throw error(ErrorCode.KNOWLEDGE_BACKUP_UNSUPPORTED);
        long created=in.readLong();require(created>=0);int documents=in.readInt(),revisions=in.readInt();long expectedSource=in.readLong(),expectedArtifacts=in.readLong();
        require(documents>=0&&documents<=DOCUMENTS&&revisions>=0&&revisions<=REVISIONS&&revisions<=documents*REVISIONS_PER_DOCUMENT);
        if(expectedSource<0||expectedSource>CORPUS_BYTES||expectedArtifacts<0||expectedArtifacts>ARTIFACT_BYTES)throw error(ErrorCode.KNOWLEDGE_BACKUP_TOO_LARGE);
        Set<String> ids=new HashSet<>(),digests=new HashSet<>();int seen=0;long sources=0,artifacts=0;
        for(int d=0;d<documents;d++){
            require(in.readUnsignedByte()=='D');JsonNode doc=small(in);fields(doc,"documentId","title","status","metadataVersion","currentReadyRevision","createdAt","updatedAt");
            String id=KnowledgeStore.id(text(doc,"documentId")),filename=KnowledgeParser.filename(text(doc,"title")),status=text(doc,"status"),version=text(doc,"metadataVersion"),current=nullable(doc,"currentReadyRevision");
            require(ids.add(id));KnowledgeStore.version(version);require(Set.of("ACTIVE","ARCHIVED").contains(status));if(current!=null)require(KnowledgeStore.version(current)<=10);
            String createdAt=text(doc,"createdAt"),updated=text(doc,"updatedAt");require(!Instant.parse(updated).isBefore(Instant.parse(createdAt)));
            store.reconstructDocument(new KnowledgeDocument(id,filename,status,version,null,createdAt,updated,"INTERRUPTED",null));
            int count=in.readInt();require(count>=0&&count<=REVISIONS_PER_DOCUMENT&&seen+count<=revisions);Set<String> numbers=new HashSet<>();
            for(int n=0;n<count;n++){
                require(in.readUnsignedByte()=='R');JsonNode metadata=small(in);fields(metadata,"documentId","sourceRevision","sourceDigest","originalFilename","sourceType","byteLength","importedAt","parserVersion","normalizationVersion","representationDigest","lineCount");
                String rev=text(metadata,"sourceRevision"),digest=text(metadata,"sourceDigest"),original=KnowledgeParser.filename(text(metadata,"originalFilename")),type=text(metadata,"sourceType"),imported=text(metadata,"importedAt"),representationDigest=text(metadata,"representationDigest");
                require(text(metadata,"documentId").equals(id)&&KnowledgeStore.version(rev)==n+1&&numbers.add(rev)&&digest.matches("[0-9a-f]{64}")&&representationDigest.matches("[0-9a-f]{64}"));
                // Same digest cannot cross Document ownership, but software reprocessing is not a new revision.
                require(digests.add(digest));require(type.equals(KnowledgeParser.type(original)));Instant.parse(imported);
                if(!text(metadata,"parserVersion").equals(KnowledgeParser.PARSER_VERSION)||!text(metadata,"normalizationVersion").equals(KnowledgeParser.NORMALIZATION_VERSION))throw error(ErrorCode.KNOWLEDGE_BACKUP_UNSUPPORTED);
                long bytes=number(metadata,"byteLength",1,SOURCE_BYTES);int lines=(int)number(metadata,"lineCount",1,LINES);require(in.readLong()==bytes);
                sources+=bytes;if(sources>CORPUS_BYTES)throw error(ErrorCode.KNOWLEDGE_BACKUP_TOO_LARGE);
                Path source=store.source(id,rev);PrivateKnowledgeDirectory.directory(source.getParent());owned.add(source.getParent());PrivateKnowledgeDirectory.file(source);owned.add(source);
                try(var output=Files.newOutputStream(source)){copy(in,output,bytes);}
                require(KnowledgeParser.sourceDigest(source).equals(digest));String normalized=utf8(in,TEXT_BYTES),locators=utf8(in,LOCATOR_BYTES);
                artifacts+=(long)normalized.getBytes(StandardCharsets.UTF_8).length+locators.getBytes(StandardCharsets.UTF_8).length;
                if(artifacts>ARTIFACT_BYTES)throw error(ErrorCode.KNOWLEDGE_BACKUP_TOO_LARGE);
                var parsed=KnowledgeParser.parse(source,type,()->false);
                require(parsed.text().equals(normalized)&&parsed.locatorJson().equals(locators)&&parsed.digest().equals(representationDigest)&&parsed.lineCount()==lines);
                store.reconstructRevision(new KnowledgeDocumentRevision(id,rev,digest,original,type,bytes,imported,KnowledgeParser.PARSER_VERSION,KnowledgeParser.NORMALIZATION_VERSION,representationDigest,lines),parsed);seen++;
            }
            require(current==null?count==0:current.equals(Integer.toString(count)));store.reconstructPointer(id,current);
        }
        require(seen==revisions&&sources==expectedSource&&artifacts==expectedArtifacts&&in.readUnsignedByte()=='E');
        byte[] checksum=hash.digest();hashed.on(false);require(Arrays.equals(checksum,in.readNBytes(32))&&in.read()==-1);
        return new Metadata(1,SCHEMA(),Instant.ofEpochMilli(created).toString(),documents,revisions,sources,artifacts,HexFormat.of().formatHex(checksum));
    }
    private static JsonNode small(DataInputStream in)throws IOException {
        int size=in.readInt();require(size>0&&size<=4096);byte[] bytes=in.readNBytes(size);require(bytes.length==size);return JSON.readTree(bytes);
    }
    private static String utf8(DataInputStream in,int limit)throws IOException {
        int size=in.readInt();if(size<0||size>limit)throw error(ErrorCode.KNOWLEDGE_BACKUP_TOO_LARGE);byte[] bytes=in.readNBytes(size);require(bytes.length==size);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void fields(JsonNode value,String... expected){require(value!=null&&value.isObject()&&value.size()==expected.length);for(String name:expected)require(value.has(name));}
    private static String text(JsonNode value,String name){var node=value.get(name);require(node!=null&&node.isString());return node.asString();}
    private static String nullable(JsonNode value,String name){return value.get(name).isNull()?null:text(value,name);}
    private static long number(JsonNode value,String name,long min,long max){var n=value.get(name);require(n!=null&&n.isIntegralNumber()&&n.canConvertToLong());long v=n.asLong();require(v>=min&&v<=max);return v;}
    private static final class BoundedInput extends FilterInputStream{
        BoundedInput(InputStream input){super(input);}
        long total;private void count(int n){if(n>0&&(total+=n)>BACKUP_BYTES)throw error(ErrorCode.KNOWLEDGE_BACKUP_TOO_LARGE);}
        @Override public int read()throws IOException{int n=in.read();if(n>=0)count(1);return n;}
        @Override public int read(byte[] bytes,int off,int len)throws IOException{int n=in.read(bytes,off,len);count(n);return n;}
    }
}
