package io.github.qianlixunbai.workspace.knowledge;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import io.github.qianlixunbai.workspace.task.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Profile-local execution history, independent of the Knowledge source backend and source backup format. */
public final class KnowledgeAnswerHistory implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final int LIMIT = 1000, PAGE_SIZE = 20;
    private static final String DDL = """
        CREATE TABLE answers(id TEXT PRIMARY KEY,question TEXT NOT NULL,query TEXT NOT NULL,
          profile TEXT NOT NULL,prompt TEXT NOT NULL,created TEXT NOT NULL,finished TEXT,
          status TEXT NOT NULL CHECK(status IN ('QUEUED','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
          result TEXT,error_code TEXT,error_phase TEXT,
          CHECK((status='QUEUED')=(finished IS NULL)),
          CHECK((status='SUCCEEDED')=(result IS NOT NULL)),
          CHECK((status IN ('FAILED','CANCELLED','TIMED_OUT'))=(error_code IS NOT NULL)))
        """;
    private Connection db;
    private static final String SCOPE_DDL="CREATE TABLE answer_scopes(id TEXT PRIMARY KEY REFERENCES answers(id),document_id TEXT NOT NULL,source_revision TEXT NOT NULL)";
    private FileChannel lockChannel;
    private FileLock processLock;
    public record Entry(String question, String query, TaskView task, KnowledgeScope scope) {
        public Entry(String question,String query,TaskView task){this(question,query,task,null);}
        @Override public String toString() { return "KnowledgeAnswerHistoryEntry[redacted]"; }
    }
    public record Summary(UUID taskId, String question, TaskStatus status, Instant createdAt) {
        @Override public String toString() { return "KnowledgeAnswerHistorySummary[redacted]"; }
    }
    public record Page(List<Summary> items, long total, int page, int limit) {}

    public KnowledgeAnswerHistory(Path data, Path token) {
        try {
            PrivateKnowledgeDirectory.validate(data.toAbsolutePath().normalize(), token);
            PrivateKnowledgeDirectory.directory(data.toAbsolutePath().normalize());
            Path root = data.toAbsolutePath().normalize().resolve("knowledge-answer-history");
            PrivateKnowledgeDirectory.directory(root);
            for (String name : List.of("history.db", "history.db-journal", "history.db-wal", "history.db-shm", "history.lock")) {
                Path file = root.resolve(name);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    PrivateKnowledgeDirectory.regular(file);
                    if (name.startsWith("history.db-") && io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.isWindows())
                        io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.verifySqliteSidecar(file);
                    else PrivateKnowledgeDirectory.protect(file, false);
                }
            }
            Path lock = root.resolve("history.lock");
            if (!Files.exists(lock)) PrivateKnowledgeDirectory.file(lock);
            lockChannel = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            processLock = lockChannel.tryLock();
            if (processLock == null) throw new SQLException();
            Path file = root.resolve("history.db");
            if (!Files.exists(file)) PrivateKnowledgeDirectory.file(file);
            db = DriverManager.getConnection("jdbc:sqlite:" + file);
            execute("PRAGMA busy_timeout=3000"); execute("PRAGMA journal_mode=DELETE"); execute("PRAGMA synchronous=FULL");
            try (var s = db.createStatement(); var r = s.executeQuery("PRAGMA quick_check")) {
                if (!r.next() || !"ok".equals(r.getString(1)) || r.next()) throw new SQLException();
            }
            transaction(() -> {
                long version = count("PRAGMA user_version");
                if (version == 0 && count("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") == 0) {
                    execute(DDL); execute("PRAGMA user_version=1");
                    version=1;
                } else if (version != 1 && version != 2) throw failure(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);
                Map<String,String> schema=new HashMap<>();
                try (var s = db.createStatement(); var r = s.executeQuery("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                    while(r.next())schema.put(r.getString(1),r.getString(2).strip());
                }
                if(!schema.equals(version==1?Map.of("answers",DDL.strip()):Map.of("answers",DDL.strip(),"answer_scopes",SCOPE_DDL)))
                    throw failure(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);
                if(version==1){execute(SCOPE_DDL);execute("PRAGMA user_version=2");}
                if(count("SELECT count(*) FROM answer_scopes s LEFT JOIN answers a ON a.id=s.id WHERE a.id IS NULL")!=0)
                    throw failure(ErrorCode.KNOWLEDGE_SCHEMA_UNSUPPORTED);
                if (count("SELECT count(*) FROM answers") > LIMIT) throw failure(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
                // Startup owns the file lock. Interrupted executions are never replayed.
                execute("UPDATE answers SET status='FAILED',finished=created,error_code='KNOWLEDGE_INTERRUPTED',error_phase='RESTART' WHERE status='QUEUED'");
                return null;
            });
        } catch (Exception e) { close(); if (e instanceof WorkspaceException w) throw w; throw failure(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE); }
    }
    public synchronized void begin(UUID id, String question, String query, ModelProfile.PublicProfile profile, String prompt) {
        begin(id,question,query,profile,prompt,null);
    }
    public synchronized void begin(UUID id, String question, String query, ModelProfile.PublicProfile profile, String prompt, KnowledgeScope scope) {
        if (question == null || question.isBlank() || question.length() > 3000 || query == null || query.length() > 256)
            throw failure(ErrorCode.INVALID_REQUEST);
        transaction(() -> {
            if (count("SELECT count(*) FROM answers") >= LIMIT) throw failure(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
            execute("INSERT INTO answers VALUES(?,?,?,?,?,?,NULL,'QUEUED',NULL,NULL,NULL)", id.toString(), question, query,
                    JSON.writeValueAsString(profile), prompt, Instant.now().toString());
            saveScope(id,scope);
            return null;
        });
    }
    public synchronized void finish(UUID id, TaskStatus status, Object result, ApiError error) {
        if (status == TaskStatus.QUEUED || status == TaskStatus.RUNNING || (status == TaskStatus.SUCCEEDED) != (result instanceof TaskResult.KnowledgeAnswer)
                || (status == TaskStatus.SUCCEEDED) != (error == null)) throw failure(ErrorCode.INTERNAL_ERROR);
        String serialized = result == null ? null : JSON.writeValueAsString(result);
        if (serialized != null && serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) throw failure(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
        transaction(() -> {
            try (var s = prepare("UPDATE answers SET status=?,result=?,error_code=?,error_phase=?,finished=? WHERE id=? AND status='QUEUED'",
                    status.name(), serialized, error == null ? null : error.code().name(), error == null ? null : error.phase(), Instant.now().toString(), id.toString())) {
                if (s.executeUpdate() != 1) throw failure(ErrorCode.TASK_NOT_FOUND);
            }
            return null;
        });
    }
    public synchronized Entry get(UUID id) {
        return read(() -> {
            try (var s = prepare("SELECT * FROM answers WHERE id=?", id.toString()); var r = s.executeQuery()) {
                if (!r.next()) throw failure(ErrorCode.TASK_NOT_FOUND);
                TaskStatus status = TaskStatus.valueOf(r.getString("status"));
                var result = r.getString("result") == null ? null : JSON.readValue(r.getString("result"), TaskResult.KnowledgeAnswer.class);
                var error = r.getString("error_code") == null ? null : ApiError.of(ErrorCode.valueOf(r.getString("error_code")), r.getString("error_phase"));
                var task = new TaskView(id, "knowledge-answer", status, JSON.readValue(r.getString("profile"), ModelProfile.PublicProfile.class),
                        r.getString("prompt"), Instant.parse(r.getString("created")), r.getString("finished") == null ? null : Instant.parse(r.getString("finished")), result, error);
                return new Entry(r.getString("question"), r.getString("query"), task,scope(id));
            }
        });
    }
    public synchronized Page list(int page) {
        if (page < 0 || page >= LIMIT / PAGE_SIZE) throw failure(ErrorCode.INVALID_REQUEST);
        return read(() -> {
            List<Summary> items = new ArrayList<>();
            try (var s = prepare("SELECT id,question,status,created FROM answers ORDER BY created DESC,id DESC LIMIT ? OFFSET ?", PAGE_SIZE, page * PAGE_SIZE); var r = s.executeQuery()) {
                while (r.next()) {
                    String question = r.getString("question").strip(); int end = question.offsetByCodePoints(0, Math.min(120, question.codePointCount(0, question.length())));
                    items.add(new Summary(UUID.fromString(r.getString("id")), question.substring(0, end), TaskStatus.valueOf(r.getString("status")), Instant.parse(r.getString("created"))));
                }
            }
            return new Page(List.copyOf(items), count("SELECT count(*) FROM answers"), page, PAGE_SIZE);
        });
    }
    public synchronized void delete(UUID id) {
        transaction(() -> {
            if (get(id).task().status() == TaskStatus.QUEUED) throw failure(ErrorCode.KNOWLEDGE_REVISION_CONFLICT);
            execute("DELETE FROM answer_scopes WHERE id=?",id.toString());
            execute("DELETE FROM answers WHERE id=?", id.toString()); return null;
        });
    }
    /** Fixed joint lock order: KnowledgeStore then history. Never reconcile or edit source records here. */
    synchronized <T> T frozenSnapshot(KnowledgeStore.Operation<T> work) {
        return transaction(() -> {
            if(count("SELECT count(*) FROM answers WHERE status='QUEUED'")!=0)throw failure(ErrorCode.KNOWLEDGE_BACKUP_CONFLICT);
            return work.run();
        });
    }
    interface EntryVisitor { void accept(Entry entry) throws Exception; }
    void visitSnapshot(EntryVisitor visitor) throws Exception {
        if(!Thread.holdsLock(this))throw failure(ErrorCode.KNOWLEDGE_BACKUP_CONFLICT);
        try(var s=prepare("SELECT id FROM answers ORDER BY id");var r=s.executeQuery()) {
            while(r.next())visitor.accept(get(UUID.fromString(r.getString(1))));
        }
    }
    /** Only the isolated joint restore uses this; no source constructor is opened by snapshot inspection. */
    synchronized void restoreTerminal(Entry entry) {
        KnowledgeHistorySnapshotRecord.validate(entry);
        transaction(() -> {
            if(count("SELECT count(*) FROM answers")>=LIMIT)throw failure(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
            var t=entry.task();var error=t.error();
            execute("INSERT INTO answers VALUES(?,?,?,?,?,?,?,?,?,?,?)",t.taskId().toString(),entry.question(),entry.query(),
                    JSON.writeValueAsString(t.profile()),t.promptVersion(),t.createdAt().toString(),t.finishedAt().toString(),t.status().name(),
                    t.result()==null?null:JSON.writeValueAsString(t.result()),error==null?null:error.code().name(),error==null?null:error.phase());
            saveScope(t.taskId(),entry.scope());
            return null;
        });
    }
    private KnowledgeScope scope(UUID id)throws SQLException {
        try(var s=prepare("SELECT document_id,source_revision FROM answer_scopes WHERE id=?",id.toString());var r=s.executeQuery()){
            return r.next()?new KnowledgeScope(r.getString(1),r.getString(2)):null;
        }
    }
    private void saveScope(UUID id,KnowledgeScope scope)throws SQLException {
        if(scope!=null)execute("INSERT INTO answer_scopes VALUES(?,?,?)",id.toString(),scope.documentId(),scope.sourceRevision());
    }
    private interface Operation<T> { T run() throws Exception; }
    private <T> T read(Operation<T> work) {
        try { return work.run(); } catch (WorkspaceException e) { throw e; } catch (Exception e) { throw failure(ErrorCode.KNOWLEDGE_STORAGE_UNAVAILABLE); }
    }
    private <T> T transaction(Operation<T> work) {
        return read(() -> { execute("BEGIN IMMEDIATE"); try { T value = work.run(); execute("COMMIT"); return value; }
            catch (Exception e) { try { execute("ROLLBACK"); } catch (Exception ignored) { close(); } throw e; } });
    }
    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        var s = db.prepareStatement(sql); for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]); return s;
    }
    private void execute(String sql, Object... args) throws SQLException { try (var s = prepare(sql, args)) { s.execute(); } }
    private long count(String sql) throws SQLException { try (var s = prepare(sql); var r = s.executeQuery()) { if (!r.next()) throw new SQLException(); return r.getLong(1); } }
    private static WorkspaceException failure(ErrorCode code) { return new WorkspaceException(code, "KNOWLEDGE_HISTORY"); }
    public synchronized void close() {
        try { if (db != null) db.close(); } catch (Exception ignored) {} db = null;
        try { if (processLock != null) processLock.release(); } catch (Exception ignored) {}
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) {}
    }
}
