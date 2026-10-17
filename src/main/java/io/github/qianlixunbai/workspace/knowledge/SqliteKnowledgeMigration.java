package io.github.qianlixunbai.workspace.knowledge;

import com.sun.nio.file.ExtendedOpenOption;
import org.sqlite.*;
import javax.sql.DataSource;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Offline maintenance only. Never imports the disposable lexical.db or changes source authority. */
public final class SqliteKnowledgeMigration {
    private SqliteKnowledgeMigration(){}
    private record Table(String name,String columns,String order){}
    private static final List<Table> TABLES=List.of(
        new Table("documents","id,title,status,version,current_revision,created,updated,last_job","id"),
        new Table("revisions","doc_id,revision,digest,filename,type,bytes,imported,parser,normalizer,representation_digest,text,locators,lines,artifact_bytes","doc_id,revision"),
        new Table("jobs","id,doc_id,state,error,revision,filename,type,size,expected_version,created","id"),
        new Table("deletes","doc_id,token,revisions,expected_version","doc_id"));
    public record Result(Map<String,Long> rows,String contentDigest){
        public Result{rows=Map.copyOf(rows);}
        @Override public String toString(){return "KnowledgeMigration[private]";}
    }

    /** The caller must keep the old Runtime stopped through the separately authorized cutover. */
    public static Result migrate(Path sourceRoot,Path backupRoot,DataSource target)throws IOException{
        sourceRoot=sourceRoot.toAbsolutePath().normalize();backupRoot=backupRoot.toAbsolutePath().normalize();
        try{
            if(backupRoot.startsWith(sourceRoot)||sourceRoot.startsWith(backupRoot)||Files.exists(backupRoot,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            PrivateKnowledgeDirectory.noLinks(sourceRoot);verifyPrivate(sourceRoot,true);
            for(String name:List.of("knowledge.db","knowledge.lock")){Path p=sourceRoot.resolve(name);PrivateKnowledgeDirectory.regular(p);verifyPrivate(p,false);}
            PrivateKnowledgeDirectory.noLinks(backupRoot);verifyPrivate(backupRoot.getParent(),true);
            try(var lock=FileChannel.open(sourceRoot.resolve("knowledge.lock"),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                var held=lock.tryLock()){
                if(held==null)throw new IOException();
                var rw=new SQLiteConfig();rw.resetOpenMode(SQLiteOpenMode.CREATE);rw.setBusyTimeout(3000);
                try(var freeze=DriverManager.getConnection("jdbc:sqlite:"+sourceRoot.resolve("knowledge.db"),rw.toProperties())){
                    sql(freeze,"BEGIN IMMEDIATE");
                    try(var reader=sqlite(sourceRoot.resolve("knowledge.db"))){
                        verifySqlite(reader);PrivateKnowledgeDirectory.directory(backupRoot);
                        Path backup=backupRoot.resolve("knowledge.db");PrivateKnowledgeDirectory.file(backup);
                        if(reader.unwrap(SQLiteConnection.class).getDatabase().backup("main",backup.toString(),null)!=0)throw new IOException();
                        try(var snapshot=sqlite(backup);var proof=new FileProof(snapshot,sourceRoot)){
                            if(!summarize(freeze,false).equals(summarize(snapshot,false)))throw new IOException();
                            PrivateKnowledgeDirectory.directory(backupRoot.resolve("sources"));
                            for(Pinned pin:proof.files){
                                Path destination=backupRoot.resolve(sourceRoot.relativize(pin.path));
                                PrivateKnowledgeDirectory.directory(destination.getParent());PrivateKnowledgeDirectory.file(destination);
                                try(var out=FileChannel.open(destination,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                                    pin.channel.position(0);ByteBuffer buffer=ByteBuffer.allocate(65536);
                                    while(pin.channel.read(buffer)!=-1){buffer.flip();while(buffer.hasRemaining())out.write(buffer);buffer.clear();}out.force(true);
                                }
                                if(!KnowledgeParser.sourceDigest(destination).equals(pin.digest))throw new IOException();
                            }
                            proof.verify();return importSnapshot(snapshot,sourceRoot,target);
                        }
                    }finally{sql(freeze,"ROLLBACK");}
                }
            }
        }catch(Exception failure){throw new IOException("Knowledge offline migration stopped; retain source and backup; inspect receipt");}
    }

    /** Import an already isolated snapshot/file tree. This does not grant Windows private-file admission. */
    public static Result importSnapshot(Connection snapshot,Path sourceRoot,DataSource target)throws IOException{
        String stage="SOURCE_VALIDATION";boolean begun=false;
        try{
            if(!snapshot.getAutoCommit()||!snapshot.getMetaData().getDatabaseProductName().equals("SQLite"))throw new SQLException();
            sql(snapshot,"BEGIN");begun=true;verifySqlite(snapshot);Result expected=summarize(snapshot,false);
            try(var proof=new FileProof(snapshot,sourceRoot.toAbsolutePath().normalize())){
                stage="TARGET_ADMISSION";
                try(var db=target.getConnection()){
                KnowledgePostgresql.verify(db);
                try(var lock=db.createStatement();var r=lock.executeQuery("SELECT pg_try_advisory_lock(170017,2)")){if(!r.next()||!r.getBoolean(1))throw new SQLException();}
                KnowledgePostgresql.begin(db,true);
                try{
                    stage="EMPTY_KNOWLEDGE_CHECK";
                    if(count(db,"SELECT count(*) FROM workspace.knowledge_sqlite_import")!=0)throw new SQLException();
                    for(var table:TABLES)if(count(db,"SELECT count(*) FROM workspace.knowledge_"+table.name)!=0)throw new SQLException();
                    stage="COPY_AND_VERIFY";for(var table:TABLES)copy(snapshot,db,table);
                    if(!expected.equals(summarize(db,true)))throw new SQLException();
                    sql(db,"SET CONSTRAINTS ALL IMMEDIATE");
                    stage="REBUILD_INDEX";buildIndex(db);
                    stage="FILE_RECHECK";proof.verify();
                    try(var s=db.prepareStatement("INSERT INTO workspace.knowledge_sqlite_import VALUES(1,?,?)")){
                        s.setString(1,expected.contentDigest);s.setLong(2,System.currentTimeMillis());s.executeUpdate();
                    }
                    stage="COMMIT";sql(db,"COMMIT");
                }catch(Exception e){try{sql(db,"ROLLBACK");}catch(SQLException ignored){}throw e;}
                stage="COMMIT_VISIBILITY";
                try(var committed=target.getConnection()){
                    KnowledgePostgresql.begin(committed,false);
                    if(!expected.equals(summarize(committed,true)))throw new SQLException();
                    try(var s=committed.createStatement();var r=s.executeQuery("SELECT content_digest FROM workspace.knowledge_sqlite_import WHERE singleton=1")){
                        if(!r.next()||!expected.contentDigest.equals(r.getString(1)))throw new SQLException();
                    }
                    sql(committed,"COMMIT");
                }
                stage="STORE_READBACK";
                sql(db,"SELECT pg_advisory_unlock(170017,2)");
                try(var store=new KnowledgeStore(target);var s=snapshot.createStatement();var rows=s.executeQuery("SELECT id,current_revision,created,updated FROM documents ORDER BY id")){
                    while(rows.next()){
                        var document=store.get(rows.getString(1));
                        if(!Objects.equals(document.currentReadyRevision(),rows.getString(2))||!document.createdAt().equals(rows.getString(3))||!document.updatedAt().equals(rows.getString(4)))throw new SQLException();
                        if(document.currentReadyRevision()!=null)store.preview(document.documentId(),document.currentReadyRevision(),0);
                    }
                }
                proof.verify();return expected;
                }
            }
        }catch(Exception e){String state=e instanceof SQLException s&&s.getSQLState()!=null&&s.getSQLState().matches("[A-Z0-9]{5}")?s.getSQLState():"UNAVAILABLE";
            String location=Arrays.stream(e.getStackTrace()).filter(f->f.getClassName().startsWith(SqliteKnowledgeMigration.class.getName())).findFirst().map(f->f.getMethodName()+":"+f.getLineNumber()).orElse("unknown");
            throw new IOException("Knowledge import stopped: "+stage+"; kind="+e.getClass().getSimpleName()+"; at="+location+"; SQLSTATE="+state+"; inspect receipt; no automatic retry/fallback");
        }finally{if(begun)try{sql(snapshot,"ROLLBACK");}catch(SQLException ignored){}}
    }
    private static void verifySqlite(Connection db)throws Exception{
        if(count(db,"PRAGMA user_version")!=1)throw new SQLException();
        try(var s=db.createStatement();var r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!r.getString(1).equals("ok")||r.next())throw new SQLException();}
        try(var s=db.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new SQLException();}
        try(var expected=DriverManager.getConnection("jdbc:sqlite::memory:")){
            for(String ddl:KnowledgeStore.SCHEMA_DDL)sql(expected,ddl);
            if(!definitions(db).equals(definitions(expected)))throw new SQLException();
        }
        if(count(db,"SELECT count(*) FROM documents")>KnowledgeLimits.DOCUMENTS||count(db,"SELECT count(*) FROM revisions")>KnowledgeLimits.REVISIONS
            ||count(db,"SELECT coalesce(sum(bytes),0) FROM revisions")>KnowledgeLimits.CORPUS_BYTES
            ||count(db,"SELECT coalesce(sum(artifact_bytes),0) FROM revisions")>KnowledgeLimits.ARTIFACT_BYTES
            ||count(db,"SELECT count(*) FROM jobs")>KnowledgeLimits.DOCUMENTS+5
            ||count(db,"SELECT count(*) FROM jobs WHERE state IN ('PENDING','PARSING')")!=0||count(db,"SELECT count(*) FROM deletes")!=0
            ||count(db,"SELECT count(*) FROM documents d WHERE d.last_job IS NOT NULL AND NOT EXISTS(SELECT 1 FROM jobs j WHERE j.id=d.last_job AND j.doc_id=d.id)")!=0
            ||count(db,"SELECT count(*) FROM jobs j WHERE j.state='READY' AND NOT EXISTS(SELECT 1 FROM revisions r WHERE r.doc_id=j.doc_id AND r.revision=j.revision)")!=0)throw new SQLException();
        for(var table:TABLES)try(var s=db.createStatement();var r=s.executeQuery("SELECT "+table.columns+" FROM "+table.name)){
            while(r.next())for(String column:table.columns.split(",")){
                String value=r.getString(column);if(value==null)continue;
                if(column.equals("id")||column.equals("doc_id")||column.equals("last_job"))KnowledgeStore.id(value);
                if(column.equals("created")||column.equals("updated")||column.equals("imported"))java.time.Instant.parse(value);
            }
        }
    }
    private static Map<String,String> definitions(Connection db)throws SQLException{
        Map<String,String> values=new TreeMap<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")){
            while(r.next())values.put(r.getString(1)+":"+r.getString(2),r.getString(3).strip());}return values;
    }
    private static void copy(Connection source,Connection target,Table table)throws SQLException{
        int columns=table.columns.split(",").length;
        try(var s=source.createStatement();var rows=s.executeQuery("SELECT "+table.columns+" FROM "+table.name+" ORDER BY "+table.order);
            var insert=target.prepareStatement("INSERT INTO workspace.knowledge_"+table.name+"("+table.columns+") VALUES("+String.join(",",Collections.nCopies(columns,"?"))+")")){
            while(rows.next()){for(int i=1;i<=columns;i++){Object value=rows.getObject(i);if(value==null)insert.setObject(i,null);else insert.setObject(i,value);}insert.executeUpdate();}
        }
    }
    private static Result summarize(Connection db,boolean pg)throws Exception{
        var hash=MessageDigest.getInstance("SHA-256");Map<String,Long> counts=new LinkedHashMap<>();
        try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash))){
            for(var table:TABLES){out.writeUTF(table.name);out.writeUTF(table.columns);long count=0;
                try(var s=db.createStatement();var r=s.executeQuery("SELECT "+table.columns+" FROM "+(pg?"workspace.knowledge_":"")+table.name+" ORDER BY "+table.order)){
                    while(r.next()){count++;out.writeByte(1);for(int i=1;i<=r.getMetaData().getColumnCount();i++){String value=r.getString(i);byte[] bytes=value==null?null:value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        out.writeInt(bytes==null?-1:bytes.length);if(bytes!=null)out.write(bytes);}}
                }out.writeByte(0);counts.put(table.name,count);
            }
        }return new Result(counts,HexFormat.of().formatHex(hash.digest()));
    }
    private static void buildIndex(Connection db)throws SQLException{
        List<KnowledgeStore.SearchRef> refs=new ArrayList<>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT d.id,d.current_revision,r.representation_digest,d.title,r.type FROM workspace.knowledge_documents d JOIN workspace.knowledge_revisions r ON r.doc_id=d.id AND r.revision=d.current_revision WHERE d.status='ACTIVE' ORDER BY d.id")){
            while(r.next())refs.add(new KnowledgeStore.SearchRef(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)));
        }
        PostgresqlKnowledgeIndex.build(db,refs,ref->{try(var s=db.prepareStatement("SELECT text,type FROM workspace.knowledge_revisions WHERE doc_id=? AND revision=?")){
            s.setString(1,ref.documentId());s.setLong(2,KnowledgeStore.version(ref.sourceRevision()));try(var r=s.executeQuery()){if(!r.next())throw new SQLException();return KnowledgeParser.represent(r.getString(1),r.getString(2),()->false);}
        }catch(SQLException e){throw KnowledgeParser.error(io.github.qianlixunbai.workspace.common.ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE);}},LexicalChunker.MAX_CHUNKS,KnowledgeLexicalIndex.MAX_BYTES);
    }
    private record Pinned(Path path,FileChannel channel,Object key,String digest,long bytes,boolean windowsSharePin){
        void verify()throws IOException{
            PrivateKnowledgeDirectory.regular(path);
            // The Windows JDK may not expose a fileKey. Its real no-share-write/delete handle
            // remains held through commit; do not invent a path-derived identity as a replacement.
            if(!channel.isOpen()||key==null&&!windowsSharePin||key!=null&&!Objects.equals(key,Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey())||Files.size(path)!=bytes||!KnowledgeParser.sourceDigest(path).equals(digest))throw new IOException();
        }
    }
    private static final class FileProof implements AutoCloseable{
        final List<Pinned> files=new ArrayList<>();
        FileProof(Connection snapshot,Path root)throws Exception{
            try(var s=snapshot.createStatement();var r=s.executeQuery("SELECT * FROM revisions ORDER BY doc_id,revision")){
                while(r.next()){
                    String doc=KnowledgeStore.id(r.getString("doc_id"));long revision=KnowledgeStore.version(r.getString("revision"));
                    Path file=root.resolve("sources").resolve(doc).resolve(revision+".source");PrivateKnowledgeDirectory.regular(file);
                    Set<OpenOption> options=new HashSet<>(Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
                    boolean windows=file.getFileSystem().getClass().getName().equals("sun.nio.fs.WindowsFileSystem");
                    if(windows){options.add(ExtendedOpenOption.NOSHARE_WRITE);options.add(ExtendedOpenOption.NOSHARE_DELETE);}
                    var channel=FileChannel.open(file,options);Object key=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
                    var pin=new Pinned(file,channel,key,r.getString("digest"),r.getLong("bytes"),windows);files.add(pin);pin.verify();
                    KnowledgeParser.filename(r.getString("filename"));String type=KnowledgeParser.type(r.getString("filename"));
                    if(!type.equals(r.getString("type"))||!KnowledgeParser.PARSER_VERSION.equals(r.getString("parser"))||!KnowledgeParser.NORMALIZATION_VERSION.equals(r.getString("normalizer")))throw new IOException();
                    var parsed=KnowledgeParser.parse(file,type,()->false);
                    if(!parsed.text().equals(r.getString("text"))||!parsed.locatorJson().equals(r.getString("locators"))||!parsed.digest().equals(r.getString("representation_digest"))||parsed.lineCount()!=r.getInt("lines")
                        ||parsed.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+parsed.locatorJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length!=r.getLong("artifact_bytes"))throw new IOException();
                }
            }catch(Exception e){close();throw e;}
        }
        void verify()throws IOException{for(var file:files)file.verify();}
        public void close()throws IOException{IOException failed=null;for(var file:files)try{file.channel.close();}catch(IOException e){failed=e;}if(failed!=null)throw failed;}
    }
    private static Connection sqlite(Path file)throws SQLException{var config=new SQLiteConfig();config.setReadOnly(true);config.setBusyTimeout(3000);return DriverManager.getConnection("jdbc:sqlite:"+file,config.toProperties());}
    private static void verifyPrivate(Path path,boolean directory)throws IOException{
        if(io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.isWindows()){PrivateKnowledgeDirectory.protect(path,directory);return;}
        var attributes=Files.readAttributes(path,java.nio.file.attribute.PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        var user=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if(attributes.isDirectory()!=directory||!attributes.owner().equals(user)||!attributes.permissions().equals(java.nio.file.attribute.PosixFilePermissions.fromString(directory?"rwx------":"rw-------")))throw new IOException("Private migration source required");
    }
    private static long count(Connection db,String query)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery(query)){if(!r.next())throw new SQLException();return r.getLong(1);}}
    private static void sql(Connection db,String query)throws SQLException{try(var s=db.createStatement()){s.execute(query);}}
}
