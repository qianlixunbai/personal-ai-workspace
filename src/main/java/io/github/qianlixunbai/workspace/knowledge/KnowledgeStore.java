package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeLimits.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.error;

/** One Knowledge writer per data root; this lock does not change Memory's persistence. */
public final class KnowledgeStore implements AutoCloseable {
    public static final int SCHEMA_VERSION=1;
    private final Path root;
    private Connection db;
    private FileChannel lockChannel;
    private FileLock processLock;
    public record Job(String requestId,String documentId,String state,String errorCode,String sourceRevision) {}
    public record Page(List<KnowledgeDocument> items,long total,int page,int limit) {}
    public record Detail(KnowledgeDocument document,List<KnowledgeDocumentRevision> revisions,Job job) {}
    public record Preview(String documentId,String sourceRevision,int offset,String text,Integer nextOffset,
            List<KnowledgeParser.Locator> locators,String parserVersion,String normalizationVersion) {
        @Override public String toString(){return "Preview[documentId="+documentId+",offset="+offset+"]";}
    }
    public KnowledgeStore(Path data,Path token) {
        Path prepared=null;
        try {
            prepared=PrivateKnowledgeDirectory.prepare(data.toAbsolutePath().normalize(),token);root=prepared;
            Path lock=root.resolve("knowledge.lock");if(!Files.exists(lock))PrivateKnowledgeDirectory.file(lock);
            lockChannel=FileChannel.open(lock,StandardOpenOption.WRITE);processLock=lockChannel.tryLock();
            if(processLock==null)throw error(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);
            Path database=root.resolve("knowledge.db");if(!Files.exists(database))PrivateKnowledgeDirectory.file(database);
            db=DriverManager.getConnection("jdbc:sqlite:"+database);
            exec("PRAGMA busy_timeout=3000");exec("PRAGMA foreign_keys=ON");exec("PRAGMA journal_mode=DELETE");exec("PRAGMA synchronous=FULL");
            if(scalar("PRAGMA foreign_keys")!=1)throw new SQLException();
            integrity();
            transaction(()->{long version=scalar("PRAGMA user_version");
                if(version!=0&&version!=SCHEMA_VERSION)throw error(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);
                if(version==0){if(scalar("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")!=0)
                    throw error(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);schema();exec("PRAGMA user_version=1");}
                verifySchema();return null;});
            reconcile();verifyReady();
        }catch(WorkspaceException e){close();throw e;}
        catch(Exception e){close();throw error(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);}
    }
    private void schema() throws SQLException {
        exec("""
            CREATE TABLE documents(id TEXT PRIMARY KEY, title TEXT NOT NULL,
              status TEXT NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
              version INTEGER NOT NULL CHECK(version>0), current_revision INTEGER,
              created TEXT NOT NULL, updated TEXT NOT NULL, last_job TEXT,
              FOREIGN KEY(id,current_revision) REFERENCES revisions(doc_id,revision) DEFERRABLE INITIALLY DEFERRED)
            """);
        exec("""
            CREATE TABLE revisions(doc_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
              revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 10), digest TEXT NOT NULL,
              filename TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('TXT','MARKDOWN')),
              bytes INTEGER NOT NULL CHECK(bytes>0 AND bytes<=8388608), imported TEXT NOT NULL,
              parser TEXT NOT NULL, normalizer TEXT NOT NULL, representation_digest TEXT NOT NULL,
              text TEXT NOT NULL,locators TEXT NOT NULL,lines INTEGER NOT NULL CHECK(lines BETWEEN 1 AND 100000),
              artifact_bytes INTEGER NOT NULL CHECK(artifact_bytes>0), PRIMARY KEY(doc_id,revision),UNIQUE(doc_id,digest))
            """);
        exec("CREATE INDEX revisions_digest ON revisions(digest)");
        exec("CREATE TRIGGER revision_immutable BEFORE UPDATE ON revisions BEGIN SELECT RAISE(ABORT,'Immutable Knowledge revision'); END");
        exec("""
            CREATE TABLE jobs(id TEXT PRIMARY KEY,doc_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
              state TEXT NOT NULL CHECK(state IN ('PENDING','PARSING','READY','FAILED','CANCELLED','INTERRUPTED')),
              error TEXT,revision INTEGER NOT NULL,filename TEXT NOT NULL,type TEXT NOT NULL,
              size INTEGER NOT NULL CHECK(size>0 AND size<=8388608),expected_version INTEGER NOT NULL,created TEXT NOT NULL)
            """);
        exec("CREATE TABLE deletes(doc_id TEXT PRIMARY KEY,token TEXT NOT NULL UNIQUE,revisions TEXT NOT NULL,expected_version INTEGER NOT NULL)");
    }
    private void verifySchema() throws SQLException {
        Map<String,List<String>> columns=Map.of(
            "documents",List.of("id","title","status","version","current_revision","created","updated","last_job"),
            "revisions",List.of("doc_id","revision","digest","filename","type","bytes","imported","parser","normalizer","representation_digest","text","locators","lines","artifact_bytes"),
            "jobs",List.of("id","doc_id","state","error","revision","filename","type","size","expected_version","created"),
            "deletes",List.of("doc_id","token","revisions","expected_version"));
        Set<String> tables=new HashSet<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
            {while(r.next())tables.add(r.getString(1));}
        if(!tables.equals(columns.keySet()))throw error(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);
        for(var entry:columns.entrySet()){List<String> actual=new ArrayList<>();try(var s=db.createStatement();var r=s.executeQuery("PRAGMA table_info("+entry.getKey()+")"))
            {while(r.next())actual.add(r.getString("name"));}if(!actual.equals(entry.getValue()))throw error(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);}
        if(scalar("SELECT count(*) FROM sqlite_master WHERE type='trigger'")!=1
                ||scalar("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='revision_immutable'")!=1)
            throw error(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);
    }
    public static String id(String value) {
        try{UUID uuid=UUID.fromString(value);if(uuid.equals(new UUID(0,0))||!uuid.toString().equals(value))throw new IllegalArgumentException();return value;}
        catch(Exception e){throw error(ErrorCode.KNOWLEDGE_INVALID_SOURCE);}
    }
    public static long version(String value) {
        try{if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}
        catch(Exception e){throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);}
    }
    public synchronized Page list(String status,int page) {
        if(!Set.of("ACTIVE","ARCHIVED").contains(status)||page<0||page>=25)throw error(ErrorCode.INVALID_REQUEST);
        return sql(()->{List<KnowledgeDocument> items=new ArrayList<>();try(var s=statement("SELECT * FROM documents WHERE status=? ORDER BY updated DESC,id LIMIT 20 OFFSET ?",status,page*20);var r=s.executeQuery())
            {while(r.next())items.add(document(r));}return new Page(List.copyOf(items),scalar("SELECT count(*) FROM documents WHERE status=?",status),page,20);});
    }
    public synchronized KnowledgeDocument get(String value) {id(value);return sql(()->{try(var s=statement("SELECT * FROM documents WHERE id=?",value);var r=s.executeQuery()){
        if(!r.next())throw error(ErrorCode.KNOWLEDGE_NOT_FOUND);return document(r);}});}
    private KnowledgeDocument document(ResultSet r)throws SQLException {
        String request=r.getString("last_job"),state=request==null?"INTERRUPTED":job(request).state();
        String current=r.getString("current_revision");
        return new KnowledgeDocument(r.getString("id"),r.getString("title"),r.getString("status"),r.getString("version"),
                current,r.getString("created"),r.getString("updated"),state,request);
    }
    public synchronized Detail detail(String value) {var doc=get(value);return new Detail(doc,revisions(value),doc.requestId()==null?null:job(doc.requestId()));}
    synchronized List<KnowledgeDocumentRevision> revisions(String value) {
        return sql(()->{List<KnowledgeDocumentRevision> all=new ArrayList<>();try(var s=statement("SELECT * FROM revisions WHERE doc_id=? ORDER BY revision",value);var r=s.executeQuery()){
            while(r.next())all.add(revision(r));}return List.copyOf(all);});
    }
    private KnowledgeDocumentRevision revision(ResultSet r)throws SQLException {
        return new KnowledgeDocumentRevision(r.getString("doc_id"),r.getString("revision"),r.getString("digest"),r.getString("filename"),r.getString("type"),
            r.getLong("bytes"),r.getString("imported"),r.getString("parser"),r.getString("normalizer"),r.getString("representation_digest"),r.getInt("lines"));
    }
    public synchronized Job job(String value) {id(value);return sql(()->{try(var s=statement("SELECT * FROM jobs WHERE id=?",value);var r=s.executeQuery()){
        if(!r.next())throw error(ErrorCode.KNOWLEDGE_NOT_FOUND);return new Job(r.getString("id"),r.getString("doc_id"),r.getString("state"),r.getString("error"),
                r.getString("state").equals("READY")?r.getString("revision"):null);}});}
    public synchronized Job findJob(String value){try{return job(value);}catch(WorkspaceException e){if(e.error().code()==ErrorCode.KNOWLEDGE_NOT_FOUND)return null;throw e;}}
    synchronized Job admit(String request,String existing,String expected,String filename,long size) {
        id(request);KnowledgeParser.filename(filename);String type=KnowledgeParser.type(filename);
        if(size<1)throw error(ErrorCode.KNOWLEDGE_INVALID_SOURCE);if(size>SOURCE_BYTES)throw error(ErrorCode.KNOWLEDGE_SOURCE_TOO_LARGE);
        return transaction(()->{
            if(findJob(request)!=null)throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
            exec("DELETE FROM jobs WHERE state NOT IN ('PENDING','PARSING') AND id NOT IN (SELECT last_job FROM documents WHERE last_job IS NOT NULL)");
            if(scalar("SELECT count(*) FROM jobs")>=DOCUMENTS+5)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
            if(scalar("SELECT count(*) FROM revisions")+scalar("SELECT count(*) FROM jobs WHERE state IN ('PENDING','PARSING')")>=REVISIONS
                    ||scalar("SELECT coalesce(sum(bytes),0) FROM revisions")+scalar("SELECT coalesce(sum(size),0) FROM jobs WHERE state IN ('PENDING','PARSING')")+size>CORPUS_BYTES)
                throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
            String doc=existing==null?UUID.randomUUID().toString():id(existing),now=Instant.now().toString();long metadata=1;
            if(existing==null){if(expected!=null)throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
                if(scalar("SELECT count(*) FROM documents")>=DOCUMENTS)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
                exec("INSERT INTO documents VALUES(?,?,'ACTIVE',1,NULL,?,?,NULL)",doc,filename,now,now);
            }else{var current=get(doc);metadata=version(expected);
                if(version(current.metadataVersion())!=metadata||!current.status().equals("ACTIVE"))throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);}
            if(scalar("SELECT count(*) FROM jobs WHERE doc_id=? AND state IN ('PENDING','PARSING')",doc)>0
                ||scalar("SELECT count(*) FROM deletes WHERE doc_id=?",doc)>0)throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
            long revision=scalar("SELECT coalesce(max(revision),0)+1 FROM revisions WHERE doc_id=?",doc);
            if(revision>REVISIONS_PER_DOCUMENT)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
            exec("INSERT INTO jobs VALUES(?,?,'PENDING',NULL,?,?,?,?,?,?)",request,doc,revision,filename,type,size,metadata,now);
            exec("UPDATE documents SET last_job=?,updated=? WHERE id=?",request,now,doc);return job(request);
        });
    }
    Path upload(String request){return root.resolve("staging").resolve(id(request)+".upload");}
    Path source(String doc,String revision){return root.resolve("sources").resolve(id(doc)).resolve(version(revision)+".source");}
    public Path root(){return root;}
    synchronized boolean cancelled(String request){String state=job(request).state();return !state.equals("PENDING")&&!state.equals("PARSING");}
    synchronized void parsing(String request){if(cancelled(request))throw error(ErrorCode.KNOWLEDGE_CANCELLED);sql(()->{exec("UPDATE jobs SET state='PARSING' WHERE id=?",request);return null;});}
    synchronized void complete(String request,KnowledgeParser.Representation representation,String digest) {
        sql(()->{try(var s=statement("SELECT * FROM jobs WHERE id=?",request);var r=s.executeQuery()){
            if(!r.next()||!r.getString("state").equals("PARSING"))throw error(ErrorCode.KNOWLEDGE_CANCELLED);
            String doc=r.getString("doc_id"),rev=r.getString("revision"),filename=r.getString("filename"),type=r.getString("type"),now=Instant.now().toString();
            long size=r.getLong("size"),expected=r.getLong("expected_version");
            if(version(get(doc).metadataVersion())!=expected)throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
            try(var match=statement("SELECT doc_id,revision FROM revisions WHERE digest=?",digest);var found=match.executeQuery()){
                if(found.next()){
                    if(!found.getString(1).equals(doc))throw error(ErrorCode.KNOWLEDGE_DUPLICATE_SOURCE);
                    exec("UPDATE jobs SET state='READY',revision=? WHERE id=?",found.getLong(2),request);return null;
                }
            }
            long artifacts=(long)representation.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+representation.locatorJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(scalar("SELECT coalesce(sum(artifact_bytes),0) FROM revisions")+artifacts>ARTIFACT_BYTES)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
            Path from=upload(request),to=source(doc,rev);PrivateKnowledgeDirectory.directory(to.getParent());
            // Job's deterministic candidate identity is durable before this filesystem publication.
            if(Files.exists(to,LinkOption.NOFOLLOW_LINKS))throw error(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);
            Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);PrivateKnowledgeDirectory.protect(to,false);
            transaction(()->{
                if(cancelled(request))throw error(ErrorCode.KNOWLEDGE_CANCELLED);
                exec("INSERT INTO revisions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",doc,version(rev),digest,filename,type,size,now,
                    KnowledgeParser.PARSER_VERSION,KnowledgeParser.NORMALIZATION_VERSION,representation.digest(),representation.text(),representation.locatorJson(),representation.lineCount(),artifacts);
                exec("UPDATE documents SET current_revision=?,version=version+1,updated=? WHERE id=? AND version=?",version(rev),now,doc,expected);
                exec("UPDATE jobs SET state='READY' WHERE id=?",request);return null;
            });return null;
        }});
    }
    synchronized void failed(String request,ErrorCode code) {
        sql(()->{exec("UPDATE jobs SET state=?,error=? WHERE id=? AND state IN ('PENDING','PARSING')",
                code==ErrorCode.KNOWLEDGE_CANCELLED?"CANCELLED":"FAILED",code.name(),request);return null;});cleanupCandidate(request);
    }
    public synchronized Job cancel(String request,String document) {
        var current=job(request);if(!current.documentId().equals(id(document)))throw error(ErrorCode.KNOWLEDGE_NOT_FOUND);
        sql(()->{exec("UPDATE jobs SET state='CANCELLED',error='KNOWLEDGE_CANCELLED' WHERE id=? AND state IN ('PENDING','PARSING')",request);return null;});return job(request);
    }
    synchronized void cleanupCandidate(String request) {
        sql(()->{try(var s=statement("SELECT doc_id,revision FROM jobs WHERE id=?",request);var r=s.executeQuery()){
            if(!r.next())return null;Path staged=upload(request),candidate=source(r.getString(1),r.getString(2));
            removeFile(staged);if(scalar("SELECT count(*) FROM revisions WHERE doc_id=? AND revision=?",r.getString(1),r.getLong(2))==0)removeFile(candidate);
            if(Files.isDirectory(candidate.getParent())){try(var entries=Files.list(candidate.getParent())){if(entries.findAny().isEmpty())Files.delete(candidate.getParent());}}
            return null;}});
    }
    private void removeFile(Path path)throws IOException {if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){PrivateKnowledgeDirectory.regular(path);Files.delete(path);}}
    private void reconcile()throws Exception {
        List<String> interrupted=new ArrayList<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM jobs WHERE state IN ('PENDING','PARSING')")){while(r.next())interrupted.add(id(r.getString(1)));}
        transaction(()->{exec("UPDATE jobs SET state='INTERRUPTED',error='KNOWLEDGE_INTERRUPTED' WHERE state IN ('PENDING','PARSING')");return null;});
        // Include terminal candidates whose cleanup was interrupted. Unknown staging is preserved.
        try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM jobs")){while(r.next())cleanupCandidate(r.getString(1));}
        List<String> deletion=new ArrayList<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT doc_id FROM deletes")){while(r.next())deletion.add(id(r.getString(1)));}
        for(String doc:deletion)reconcileDelete(doc);
    }
    private void verifyReady()throws Exception {
        if(scalar("SELECT count(*) FROM documents")>DOCUMENTS||scalar("SELECT count(*) FROM revisions")>REVISIONS
                ||scalar("SELECT coalesce(sum(bytes),0) FROM revisions")>CORPUS_BYTES
                ||scalar("SELECT coalesce(sum(artifact_bytes),0) FROM revisions")>ARTIFACT_BYTES
                ||scalar("SELECT count(*) FROM jobs")>DOCUMENTS+5)throw error(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
        try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM documents")){while(r.next()){
            id(r.getString("id"));KnowledgeParser.filename(r.getString("title"));version(r.getString("version"));
            Instant.parse(r.getString("created"));Instant.parse(r.getString("updated"));}}
        try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM revisions")){while(r.next()){
            var row=revision(r);Path file=source(row.documentId(),row.sourceRevision());PrivateKnowledgeDirectory.regular(file);PrivateKnowledgeDirectory.protect(file,false);
            if(Files.size(file)!=row.byteLength()||!KnowledgeParser.sourceDigest(file).equals(row.sourceDigest()))throw new IOException("Incomplete Knowledge source");
            KnowledgeParser.filename(row.originalFilename());
            if(!KnowledgeParser.type(row.originalFilename()).equals(row.sourceType())||!row.parserVersion().equals(KnowledgeParser.PARSER_VERSION)
                    ||!row.normalizationVersion().equals(KnowledgeParser.NORMALIZATION_VERSION))throw error(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);
            var parsed=KnowledgeParser.parse(file,row.sourceType(),()->false);
            if(!parsed.text().equals(r.getString("text"))||!parsed.locatorJson().equals(r.getString("locators"))||!parsed.digest().equals(row.representationDigest())
                    ||parsed.lineCount()!=row.lineCount()||r.getLong("artifact_bytes")!=(long)parsed.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+parsed.locatorJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                throw new IOException("Invalid Knowledge representation");
        }}integrity();
    }
    public synchronized KnowledgeDocument lifecycle(String doc,String expected,String status) {
        if(!Set.of("ACTIVE","ARCHIVED").contains(status))throw error(ErrorCode.INVALID_REQUEST);
        return transaction(()->{var current=get(doc);if(version(current.metadataVersion())!=version(expected)||busy(doc))throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
            exec("UPDATE documents SET status=?,version=version+1,updated=? WHERE id=?",status,Instant.now().toString(),doc);return get(doc);});
    }
    private boolean busy(String doc)throws SQLException {return scalar("SELECT count(*) FROM jobs WHERE doc_id=? AND state IN ('PENDING','PARSING')",doc)>0||scalar("SELECT count(*) FROM deletes WHERE doc_id=?",doc)>0;}
    public synchronized void delete(String doc,String expected) {
        id(doc);long requested=version(expected);
        sql(()->{if(scalar("SELECT count(*) FROM deletes WHERE doc_id=?",doc)>0){
            if(scalar("SELECT expected_version FROM deletes WHERE doc_id=?",doc)!=requested)throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
            reconcileDelete(doc);if(scalar("SELECT count(*) FROM documents WHERE id=?",doc)==0)return null;
        }
        var current=get(doc);if(version(current.metadataVersion())!=requested||busy(doc))throw error(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
        String token=UUID.randomUUID().toString(),revisions=String.join(",",revisions(doc).stream().map(KnowledgeDocumentRevision::sourceRevision).toList());
        transaction(()->{exec("INSERT INTO deletes VALUES(?,?,?,?)",doc,token,revisions,requested);return null;});
        Path original=root.resolve("sources").resolve(doc),tomb=root.resolve("staging").resolve(token+".delete");
        try {
            if(Files.exists(original)){verifyDeleteFiles(original,revisions);Files.move(original,tomb,StandardCopyOption.ATOMIC_MOVE);}
            transaction(()->{exec("DELETE FROM documents WHERE id=? AND version=?",doc,requested);return null;});
            reconcileDelete(doc);return null;
        } catch(Exception e){reconcileDelete(doc);if(e instanceof WorkspaceException controlled)throw controlled;throw error(ErrorCode.KNOWLEDGE_DELETE_INCOMPLETE);}
        });
    }
    private void verifyDeleteFiles(Path directory,String revisions)throws IOException {
        PrivateKnowledgeDirectory.noLinks(directory);Set<String> allowed=new HashSet<>();if(!revisions.isEmpty())for(String rev:revisions.split(","))allowed.add(version(rev)+".source");
        try(var entries=Files.list(directory)){for(Path file:entries.toList()){if(!allowed.contains(file.getFileName().toString()))throw new IOException("Unknown Knowledge object");PrivateKnowledgeDirectory.regular(file);}}
    }
    private void reconcileDelete(String doc)throws Exception {
        try(var s=statement("SELECT * FROM deletes WHERE doc_id=?",doc);var r=s.executeQuery()){
            if(!r.next())return;String token=id(r.getString("token")),revisions=r.getString("revisions");
            Path original=root.resolve("sources").resolve(id(doc)),tomb=root.resolve("staging").resolve(token+".delete");
            if(scalar("SELECT count(*) FROM documents WHERE id=?",doc)>0){
                if(Files.exists(tomb)){verifyDeleteFiles(tomb,revisions);if(Files.exists(original))throw new IOException("Ambiguous delete");Files.move(tomb,original,StandardCopyOption.ATOMIC_MOVE);}
            }else if(Files.exists(tomb)){
                verifyDeleteFiles(tomb,revisions);if(!revisions.isEmpty())for(String rev:revisions.split(","))removeFile(tomb.resolve(version(rev)+".source"));Files.delete(tomb);
            }
            exec("DELETE FROM deletes WHERE doc_id=?",doc);
        }
    }
    public synchronized Preview preview(String doc,String revision,int offset) {
        get(doc);long rev=version(revision);if(offset<0)throw error(ErrorCode.INVALID_REQUEST);
        return sql(()->{try(var s=statement("SELECT * FROM revisions WHERE doc_id=? AND revision=?",doc,rev);var r=s.executeQuery()){
            if(!r.next())throw error(ErrorCode.KNOWLEDGE_NOT_FOUND);String text=r.getString("text");
            if(offset>text.length()||offset>0&&offset<text.length()&&Character.isLowSurrogate(text.charAt(offset)))throw error(ErrorCode.INVALID_REQUEST);
            // Recreate only bounded structural interpretation of already verified immutable normalized text.
            var representation=KnowledgeParser.represent(text,r.getString("type"),()->false);
            var locations=representation.locators().stream().filter(x->x.startOffset()<=offset&&x.endOffset()>offset).limit(1).toList();
            int end=Math.min(text.length(),offset+PREVIEW_UNITS);
            // A preview range stays inside one structural locator, including heading boundaries.
            if(!locations.isEmpty())end=Math.min(end,locations.getFirst().endOffset());
            if(end<text.length()&&Character.isLowSurrogate(text.charAt(end)))end--;
            return new Preview(doc,revision,offset,text.substring(offset,end),end<text.length()?end:null,locations,r.getString("parser"),r.getString("normalizer"));
        }});
    }
    interface Operation<T>{T run()throws Exception;}
    private <T>T sql(Operation<T> work){try{return work.run();}catch(WorkspaceException e){throw e;}catch(Exception e){throw error(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);}}
    private <T>T transaction(Operation<T> work){return sql(()->{exec("BEGIN IMMEDIATE");try{T result=work.run();exec("COMMIT");return result;}catch(Exception e){try{exec("ROLLBACK");}catch(Exception ignored){}throw e;}});}
    PreparedStatement statement(String sql,Object... args)throws SQLException {var s=db.prepareStatement(sql);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
    void exec(String sql,Object... args)throws SQLException{try(var s=statement(sql,args)){s.execute();}}
    long scalar(String sql,Object... args)throws SQLException{try(var s=statement(sql,args);var r=s.executeQuery()){if(!r.next())throw new SQLException();return r.getLong(1);}}
    private void integrity()throws SQLException{for(String pragma:List.of("PRAGMA quick_check","PRAGMA foreign_key_check")){try(var s=db.createStatement();var r=s.executeQuery(pragma)){
        if(pragma.endsWith("quick_check")){if(!r.next()||!r.getString(1).equals("ok")||r.next())throw new SQLException();}else if(r.next())throw new SQLException();}}}
    synchronized <T>T snapshot(Operation<T> work){return sql(()->{exec("BEGIN");try{T result=work.run();exec("COMMIT");return result;}catch(Exception e){exec("ROLLBACK");throw e;}});}
    synchronized void reconstructDocument(KnowledgeDocument d){transaction(()->{id(d.documentId());KnowledgeParser.filename(d.title());version(d.metadataVersion());
        if(!Set.of("ACTIVE","ARCHIVED").contains(d.status()))throw error(ErrorCode.KNOWLEDGE_BACKUP_INVALID);
        Instant.parse(d.createdAt());Instant.parse(d.updatedAt());
        exec("INSERT INTO documents VALUES(?,?,?,?,NULL,?,?,NULL)",d.documentId(),d.title(),d.status(),version(d.metadataVersion()),d.createdAt(),d.updatedAt());return null;});}
    synchronized void reconstructRevision(KnowledgeDocumentRevision r,KnowledgeParser.Representation p){transaction(()->{
        exec("INSERT INTO revisions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",r.documentId(),version(r.sourceRevision()),r.sourceDigest(),r.originalFilename(),r.sourceType(),r.byteLength(),r.importedAt(),
                r.parserVersion(),r.normalizationVersion(),r.representationDigest(),p.text(),p.locatorJson(),p.lineCount(),(long)p.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+p.locatorJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);return null;});}
    synchronized void reconstructPointer(String doc,String revision){transaction(()->{exec("UPDATE documents SET current_revision=? WHERE id=?",revision==null?null:version(revision),doc);return null;});}
    synchronized void verifyReconstruction(){sql(()->{verifyReady();return null;});}
    public synchronized void close(){try{if(db!=null)db.close();}catch(Exception ignored){}db=null;try{if(processLock!=null)processLock.release();}catch(Exception ignored){}
        try{if(lockChannel!=null)lockChannel.close();}catch(Exception ignored){} }
}
