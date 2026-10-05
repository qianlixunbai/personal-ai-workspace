package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.github.qianlixunbai.workspace.knowledge.KnowledgeParser.error;

/** Disposable local derived index. No connection, queue or AI dependency is shared with ingestion. */
public final class KnowledgeLexicalIndex implements AutoCloseable {
    public static final int INDEX_SCHEMA_VERSION=1;
    public static final String RANKING_VERSION="lexical-rank-1";
    public static final long MAX_BYTES=1L<<30;
    private final KnowledgeStore store;
    private final Path directory,staging,database;
    private final long maxBytes;private final int maxChunks;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"knowledge-index");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean running=new AtomicBoolean(),rerun=new AtomicBoolean();
    private final Object publication=new Object();
    private volatile boolean closed;
    private volatile String state="STALE",publishedFingerprint,sqliteVersion;
    private volatile ErrorCode failure;
    private volatile int documentCount,chunkCount;
    public record Status(String state,int indexedDocuments,int indexedChunks) {}
    public record Hit(String documentId,String title,String sourceRevision,String sourceType,int startOffset,int endOffset,
            int startLine,int endLine,String heading,String snippet,List<LexicalSnippet.Range> highlightRanges) {
        @Override public String toString(){return "LexicalHit[documentId="+documentId+"]";}
    }
    public record Results(List<Hit> hits) {
        @Override public String toString(){return "LexicalResults[count="+hits.size()+"]";}
    }
    private record Row(String id,String revision,int ordinal,int start,int end,int firstLine,int lastLine,String heading) {}
    private static final List<String> DDL=List.of(
        "CREATE TABLE metadata(k TEXT PRIMARY KEY,v TEXT NOT NULL)",
        "CREATE TABLE chunks(id INTEGER PRIMARY KEY,document TEXT NOT NULL,revision INTEGER NOT NULL,ordinal INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,first_line INTEGER NOT NULL,last_line INTEGER NOT NULL,heading TEXT,UNIQUE(document,revision,ordinal))",
        "CREATE VIRTUAL TABLE lexical USING fts5(title,heading,body,tokenize='ascii')");
    public KnowledgeLexicalIndex(KnowledgeStore store){this(store,MAX_BYTES,LexicalChunker.MAX_CHUNKS);}
    KnowledgeLexicalIndex(KnowledgeStore store,long maxBytes,int maxChunks){
        this.store=store;this.maxBytes=maxBytes;this.maxChunks=maxChunks;
        directory=store.root().resolve("index");staging=directory.resolve("staging");database=directory.resolve("lexical.db");
        store.onCorpusChanged(this::invalidate);
        try {
            prepare();sqliteVersion=engine();var refs=store.searchCorpus();String current=fingerprint(refs,sqliteVersion);
            if(Files.exists(database,LinkOption.NOFOLLOW_LINKS)){
                try(var db=DriverManager.getConnection("jdbc:sqlite:"+database)){var meta=verify(db);exec(db,"INSERT INTO lexical(lexical) VALUES('integrity-check')");if(!current.equals(meta.get("fingerprint")))throw new SQLException();
                    documentCount=Integer.parseInt(meta.get("documents"));chunkCount=Integer.parseInt(meta.get("chunks"));publishedFingerprint=current;state="READY";}
            }
        }catch(Exception ignored){state="STALE";}
        if(!state.equals("READY"))requestRebuild();
    }
    private void prepare()throws Exception {
        PrivateKnowledgeDirectory.directory(directory);PrivateKnowledgeDirectory.directory(staging);
        if(Files.exists(database,LinkOption.NOFOLLOW_LINKS)){PrivateKnowledgeDirectory.regular(database);PrivateKnowledgeDirectory.protect(database,false);}
    }
    static String engine()throws SQLException {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")){exec(db,"CREATE VIRTUAL TABLE capability USING fts5(value)");
            exec(db,"INSERT INTO capability VALUES('probe')");
            try(var s=db.createStatement();var r=s.executeQuery("SELECT bm25(capability) FROM capability WHERE capability MATCH '\"probe\"'")){
                if(!r.next()||!Double.isFinite(r.getDouble(1))||r.getDouble(1)>=0)throw new SQLException();}
            try(var s=db.createStatement();var r=s.executeQuery("SELECT sqlite_version()")){r.next();return r.getString(1);}
        }
    }
    static String fingerprint(List<KnowledgeStore.SearchRef> refs,String engine) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for(String value:List.of("knowledge-lexical",Integer.toString(INDEX_SCHEMA_VERSION),LexicalChunker.VERSION,LexicalAnalyzer.VERSION,RANKING_VERSION,engine))frame(hash,value);
            for(var ref:refs){frame(hash,ref.documentId());frame(hash,ref.sourceRevision());frame(hash,ref.digest());}
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException();}
    }
    private static void frame(MessageDigest digest,String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(8).putLong(bytes.length).array());digest.update(bytes);}
    private void invalidate(){state="STALE";requestRebuild();}
    public Status status() {
        if(state.equals("READY")&&!Objects.equals(publishedFingerprint,fingerprint(store.searchCorpus(),sqliteVersion)))invalidate();
        return new Status(state,state.equals("READY")?documentCount:0,state.equals("READY")?chunkCount:0);
    }
    public Status rebuild(){invalidate();return status();}
    private void requestRebuild() {
        if(closed)return;rerun.set(true);
        if(running.compareAndSet(false,true))try{executor.execute(this::run);}catch(RejectedExecutionException ignored){running.set(false);}
    }
    private void run() {
        try{do {
            rerun.set(false);if(closed)return;state="BUILDING";
            try{build();}catch(WorkspaceException e){failure=e.error().code();state="FAILED";}
            catch(Exception ignored){failure=ErrorCode.KNOWLEDGE_INDEX_REBUILD_FAILED;state="FAILED";}
        }while(rerun.get()&&!closed);}
        finally{running.set(false);if(rerun.get()&&!closed)requestRebuild();}
    }
    private void build()throws Exception {
        prepare();if(sqliteVersion==null)sqliteVersion=engine();
        var refs=store.searchCorpus();String identity=fingerprint(refs,sqliteVersion);
        Path candidate=staging.resolve("k2-"+UUID.randomUUID()+".candidate");PrivateKnowledgeDirectory.file(candidate);
        int[] count={0};
        try {
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+candidate)) {
                exec(db,"PRAGMA journal_mode=MEMORY");exec(db,"PRAGMA temp_store=MEMORY");exec(db,"PRAGMA synchronous=FULL");
                exec(db,"PRAGMA max_page_count="+Math.max(1,maxBytes/4096));
                for(String ddl:DDL)exec(db,ddl);exec(db,"PRAGMA user_version=1");db.setAutoCommit(false);
                try(var chunks=db.prepareStatement("INSERT INTO chunks VALUES(?,?,?,?,?,?,?,?,?)");var tokens=db.prepareStatement("INSERT INTO lexical(rowid,title,heading,body) VALUES(?,?,?,?)")) {
                    for(var ref:refs) {
                        if(closed||Thread.currentThread().isInterrupted())return;
                        KnowledgeParser.Representation source;
                        try{source=store.searchSource(ref);}catch(WorkspaceException e){if(e.error().code()==ErrorCode.KNOWLEDGE_INDEX_NOT_READY){state="STALE";rerun.set(true);return;}throw e;}
                        LexicalChunker.chunks(source,chunk->{
                            if(++count[0]>maxChunks)throw error(ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED);
                            try {
                                Object[] values={count[0],ref.documentId(),KnowledgeStore.version(ref.sourceRevision()),chunk.ordinal(),chunk.startOffset(),chunk.endOffset(),chunk.startLine(),chunk.endLine(),chunk.heading()};
                                for(int i=0;i<values.length;i++)chunks.setObject(i+1,values[i]);chunks.executeUpdate();
                                tokens.setInt(1,count[0]);tokens.setString(2,LexicalAnalyzer.stream(ref.title()));
                                tokens.setString(3,LexicalAnalyzer.stream(chunk.heading()==null?"":chunk.heading()));
                                tokens.setString(4,LexicalAnalyzer.stream(source.text().substring(chunk.startOffset(),chunk.endOffset())));tokens.executeUpdate();
                            }catch(SQLException e){throw sqlFailure(e);}
                        });
                    }
                }
                Map<String,String> meta=versions();meta.put("fingerprint",identity);meta.put("documents",Integer.toString(refs.size()));meta.put("chunks",Integer.toString(count[0]));
                try(var s=db.prepareStatement("INSERT INTO metadata VALUES(?,?)")){for(var item:meta.entrySet()){s.setString(1,item.getKey());s.setString(2,item.getValue());s.executeUpdate();}}
                db.commit();db.setAutoCommit(true);exec(db,"INSERT INTO lexical(lexical) VALUES('integrity-check')");verify(db);
            }
            if(Files.size(candidate)>maxBytes)throw error(ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED);
            // Store -> publication is the only nested lock order. Publication and truth are separate commits.
            synchronized(store){if(!identity.equals(fingerprint(store.searchCorpus(),sqliteVersion))){state="STALE";rerun.set(true);return;}
                synchronized(publication){if(closed)return;PrivateKnowledgeDirectory.noLinks(database);
                    Files.move(candidate,database,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    PrivateKnowledgeDirectory.protect(database,false);publishedFingerprint=identity;documentCount=refs.size();chunkCount=count[0];failure=null;state="READY";}}
        }catch(SQLException e){throw sqlFailure(e);}
        finally{if(Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)){PrivateKnowledgeDirectory.regular(candidate);Files.delete(candidate);}}
    }
    private static WorkspaceException sqlFailure(SQLException e){return error(e.getErrorCode()==13?ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED:ErrorCode.KNOWLEDGE_INDEX_REBUILD_FAILED);}
    private Map<String,String> versions(){Map<String,String> v=new TreeMap<>();v.put("schema","1");v.put("chunker",LexicalChunker.VERSION);v.put("analyzer",LexicalAnalyzer.VERSION);v.put("ranking",RANKING_VERSION);v.put("sqlite",sqliteVersion);return v;}
    private Map<String,String> verify(Connection db)throws Exception {
        Map<String,String> meta=new TreeMap<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT k,v FROM metadata")){while(r.next())meta.put(r.getString(1),r.getString(2));}
        var expected=versions();if(meta.size()!=8||!meta.entrySet().containsAll(expected.entrySet())||!meta.getOrDefault("fingerprint","").matches("[0-9a-f]{64}"))throw new SQLException();
        long documents=Long.parseLong(meta.get("documents")),chunks=Long.parseLong(meta.get("chunks"));
        if(documents<0||documents>KnowledgeLimits.DOCUMENTS||chunks<0||chunks>maxChunks)throw new SQLException();
        if(scalar(db,"PRAGMA user_version")!=1||scalar(db,"SELECT count(*) FROM chunks")!=chunks||scalar(db,"SELECT count(*) FROM lexical")!=chunks
            ||scalar(db,"SELECT count(DISTINCT document) FROM chunks")!=documents||scalar(db,"PRAGMA page_count")*scalar(db,"PRAGMA page_size")>maxBytes)throw new SQLException();
        try(var expectedDb=DriverManager.getConnection("jdbc:sqlite::memory:")){for(String ddl:DDL)exec(expectedDb,ddl);if(!definitions(db).equals(definitions(expectedDb)))throw new SQLException();}
        try(var s=db.createStatement();var r=s.executeQuery("PRAGMA quick_check")){if(!r.next()||!r.getString(1).equals("ok")||r.next())throw new SQLException();}
        return meta;
    }
    private static Map<String,String> definitions(Connection db)throws SQLException {
        Map<String,String> result=new TreeMap<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")){
            while(r.next())result.put(r.getString(1)+":"+r.getString(2),r.getString(3));}return result;
    }
    private Connection read(Path file)throws Exception {
        PrivateKnowledgeDirectory.regular(file);if(Files.size(file)>maxBytes)throw error(ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED);
        var config=new org.sqlite.SQLiteConfig();config.setReadOnly(true);return DriverManager.getConnection("jdbc:sqlite:"+file,config.toProperties());
    }
    private static void exec(Connection db,String sql)throws SQLException{try(var s=db.createStatement()){s.execute(sql);}}
    private static long scalar(Connection db,String sql)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery(sql)){if(!r.next())throw new SQLException();return r.getLong(1);}}
    public Results search(String raw,Integer requestedLimit) {
        var query=LexicalAnalyzer.query(raw);int limit=requestedLimit==null?10:requestedLimit;
        if(limit<1||limit>10)throw error(ErrorCode.KNOWLEDGE_SEARCH_INVALID);
        var refs=store.searchCorpus();String current=sqliteVersion==null?null:fingerprint(refs,sqliteVersion);
        if(!state.equals("READY")||!Objects.equals(current,publishedFingerprint)){
            if(state.equals("FAILED"))throw error(failure==ErrorCode.KNOWLEDGE_INDEX_LIMIT_EXCEEDED?failure:ErrorCode.KNOWLEDGE_INDEX_UNAVAILABLE);
            requestRebuild();throw error(ErrorCode.KNOWLEDGE_INDEX_NOT_READY);
        }
        List<Row> rows=new ArrayList<>();
        try {
            synchronized(publication){if(!state.equals("READY")||!Objects.equals(current,publishedFingerprint))throw error(ErrorCode.KNOWLEDGE_INDEX_NOT_READY);
                try(var db=read(database);var s=db.prepareStatement("SELECT c.document,c.revision,c.ordinal,c.start,c.end,c.first_line,c.last_line,c.heading FROM lexical JOIN chunks c ON c.id=lexical.rowid WHERE lexical MATCH ? ORDER BY bm25(lexical,4.0,2.0,1.0),c.document COLLATE BINARY,c.revision,c.ordinal LIMIT ?")){
                    try(var identity=db.prepareStatement("SELECT v FROM metadata WHERE k='fingerprint'");var value=identity.executeQuery()){
                        if(!value.next()||!current.equals(value.getString(1)))throw error(ErrorCode.KNOWLEDGE_INDEX_NOT_READY);}
                    s.setString(1,query.expression());s.setInt(2,limit);
                    try(var r=s.executeQuery()){while(r.next())rows.add(new Row(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getInt(7),r.getString(8)));}
                }
            }
            List<Hit> hits=new ArrayList<>();Map<String,KnowledgeParser.Representation> sources=new HashMap<>();
            for(var row:rows){var ref=refs.stream().filter(x->x.documentId().equals(row.id())&&x.sourceRevision().equals(row.revision())).findFirst().orElseThrow(()->error(ErrorCode.KNOWLEDGE_INDEX_NOT_READY));
                var source=sources.computeIfAbsent(ref.documentId(),key->store.searchSource(ref));
                var snippet=LexicalSnippet.create(source.text().substring(row.start(),row.end()),query.tokens());
                hits.add(new Hit(ref.documentId(),LexicalSnippet.bounded(ref.title(),160),ref.sourceRevision(),ref.sourceType(),row.start(),row.end(),row.firstLine(),row.lastLine(),LexicalSnippet.bounded(row.heading(),96),snippet.text(),snippet.ranges()));
            }
            if(!current.equals(fingerprint(store.searchCorpus(),sqliteVersion))||!state.equals("READY")){
                invalidate();throw error(ErrorCode.KNOWLEDGE_INDEX_NOT_READY);}
            return new Results(List.copyOf(hits));
        }catch(WorkspaceException e){if(e.error().code()==ErrorCode.KNOWLEDGE_INDEX_NOT_READY)invalidate();throw e;}
        catch(Exception ignored){invalidate();throw error(ErrorCode.KNOWLEDGE_INDEX_UNAVAILABLE);}
    }
    public void close(){closed=true;store.onCorpusChanged(()->{});executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
