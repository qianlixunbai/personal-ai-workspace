package io.github.qianlixunbai.workspace.backup;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.memory.*;
import io.github.qianlixunbai.workspace.conversation.*;
import io.github.qianlixunbai.workspace.persistence.WorkspaceSchema;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.qianlixunbai.workspace.memory.MemoryBackup.*;

/** Independent portable source contract. All large arrays go through SQLite/cursors, never a DOM. */
public final class WorkspaceBackupService {
    public static final String FORMAT = "personal-ai-workspace.workspace-backup";
    public static final long MAX_TURNS = (long) ConversationLimits.TOTAL_CONVERSATIONS * ConversationLimits.TURNS_PER_CONVERSATION;
    public static final long MAX_BYTES = MemoryBackup.MAX_BYTES + 4096L + ConversationLimits.TOTAL_CONVERSATIONS *
            (160L * 12 + 1024 + ConversationLimits.TURNS_PER_CONVERSATION * (2L * 8192 * 6 + 4L * 256 + 2048));
    public static final JsonMapper JSON = JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(16384).maxNameLength(64)
                    .maxNumberLength(20).maxNestingDepth(12).build()).build()).disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final MemoryStore active;
    private final Path token;
    private final MemoryBackupService publication;
    public WorkspaceBackupService(MemoryStore active, Path token) {
        this(active, token, new MemoryBackupService(active.databaseFile().getParent(), token));
    }
    public WorkspaceBackupService(MemoryStore active, Path token, MemoryBackupService publication) {
        this.active = active; this.token = token; this.publication = publication;
    }
    public record Metadata(int formatVersion, int memorySchemaVersion, int conversationSchemaVersion, String createdAt,
                           long memoryCount, long conversationCount, long turnCount, long messageCount, String contentDigest) {}
    static WorkspaceException error(ErrorCode code) { return new WorkspaceException(code, "WORKSPACE_BACKUP"); }
    private static void require(boolean condition) { if (!condition) throw error(ErrorCode.WORKSPACE_BACKUP_INVALID); }
    private static void version(long value) { if (value != 1) throw error(ErrorCode.WORKSPACE_BACKUP_UNSUPPORTED); }

    /** The first SELECT pins the read snapshot; digest and response both use that same transaction. */
    public Metadata export(OutputStream output) {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + active.databaseFile())) {
            sql(db, "PRAGMA busy_timeout=3000"); sql(db, "PRAGMA query_only=ON"); sql(db, "BEGIN");
            if (count(db, "SELECT count(*) FROM conversation_turns WHERE status='PENDING'") != 0)
                throw error(ErrorCode.WORKSPACE_BACKUP_CONFLICT);
            var metadata = metadata(db, Instant.ofEpochMilli(System.currentTimeMillis()).toString(), "");
            validateDatabase(db, metadata);
            String digest = digest(db, metadata);
            metadata = new Metadata(1, 1, 1, metadata.createdAt(), metadata.memoryCount(), metadata.conversationCount(),
                    metadata.turnCount(), metadata.messageCount(), digest);
            try (var generator = JSON.createGenerator(new LimitedOutput(output, MAX_BYTES))) {
                generator.writeStartObject(); generator.writeStringProperty("format", FORMAT); generator.writeNumberProperty("formatVersion", 1);
                generator.writeStringProperty("createdAt", metadata.createdAt()); generator.writeStringProperty("contentDigest", digest);
                generator.writeObjectPropertyStart("memory"); generator.writeNumberProperty("schemaVersion", 1);
                generator.writeNumberProperty("itemCount", metadata.memoryCount()); generator.writeArrayPropertyStart("items");
                each(db, "SELECT * FROM memory_items ORDER BY id", r -> generator.writeTree(memory(r)));
                generator.writeEndArray(); generator.writeEndObject(); generator.writeObjectPropertyStart("conversations");
                generator.writeNumberProperty("schemaVersion", 1); generator.writeNumberProperty("conversationCount", metadata.conversationCount());
                generator.writeNumberProperty("turnCount", metadata.turnCount()); generator.writeNumberProperty("messageCount", metadata.messageCount());
                generator.writeArrayPropertyStart("items");
                each(db, "SELECT * FROM conversations ORDER BY id", r -> generator.writeTree(conversation(r)));
                generator.writeEndArray(); generator.writeArrayPropertyStart("turns");
                each(db, "SELECT * FROM conversation_turns ORDER BY conversation_id,sequence", r -> generator.writeTree(turn(db, r)));
                generator.writeEndArray(); generator.writeEndObject(); generator.writeEndObject();
            }
            sql(db, "COMMIT"); return metadata;
        } catch (WorkspaceException controlled) { throw controlled; }
        catch (Exception ignored) { throw error(ErrorCode.WORKSPACE_EXPORT_FAILED); }
    }

    /** Validate-only builds and verifies an account-private disposable staging DB, without publication. */
    public Metadata validate(InputStream input) {
        Path staging = null;
        try {
            staging = Files.createTempDirectory(".workspace-validation-");
            return construct(input, staging);
        } catch (WorkspaceException controlled) { throw controlled; }
        catch (Exception ignored) { throw error(ErrorCode.WORKSPACE_BACKUP_INVALID); }
        finally { if (staging != null) cleanup(staging); }
    }
    public Metadata restore(InputStream input, String targetDirectory) {
        var result = new AtomicReference<Metadata>();
        try {
            publication.restoreWorkspaceDatabase(targetDirectory, staging -> result.set(construct(input, staging)));
            return result.get();
        } catch (WorkspaceException controlled) {
            // Publication's legacy Memory error vocabulary is translated only on the new API.
            throw error(switch (controlled.error().code()) {
                case MEMORY_RESTORE_TARGET_NOT_EMPTY -> ErrorCode.WORKSPACE_RESTORE_TARGET_NOT_EMPTY;
                case WORKSPACE_BACKUP_INVALID, WORKSPACE_BACKUP_UNSUPPORTED, WORKSPACE_BACKUP_TOO_LARGE -> controlled.error().code();
                default -> ErrorCode.WORKSPACE_RESTORE_FAILED;
            });
        }
    }
    private Metadata construct(InputStream input, Path staging) {
        var result = new AtomicReference<Metadata>();
        try (var store = new MemoryStore(staging, token)) {
            store.reconstructWorkspace(db -> {
                try (var reader = new InputStreamReader(new LimitedInput(input, MAX_BYTES), StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
                     var parser = JSON.createParser(reader)) {
                    sql(db, "PRAGMA defer_foreign_keys=ON");
                    var top = JSON.createObjectNode(); var mem = JSON.createObjectNode(); var conv = JSON.createObjectNode();
                    List<MemoryItem> rows = new ArrayList<>();
                    require(parser.nextToken() == JsonToken.START_OBJECT);
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        require(parser.currentToken() == JsonToken.PROPERTY_NAME); String name = parser.currentName(); parser.nextToken();
                        switch (name) {
                            case "memory" -> section(parser, mem, (field, node) -> {
                                require(field.equals("items") && rows.size() < 1000);
                                var row = readRow(node); rows.add(row); insertMemory(db, row);
                            }, Set.of("schemaVersion", "itemCount"), Set.of("items"));
                            case "conversations" -> section(parser, conv, (field, node) -> {
                                if (field.equals("items")) { conversationValid(node); insertConversation(db, node); }
                                else { turnValid(node); insertTurn(db, node); }
                            }, Set.of("schemaVersion", "conversationCount", "turnCount", "messageCount"), Set.of("items", "turns"));
                            case "format", "formatVersion", "createdAt", "contentDigest" -> top.set(name, small(parser, 0));
                            default -> throw error(ErrorCode.WORKSPACE_BACKUP_INVALID);
                        }
                    }
                    require(parser.nextToken() == null);
                    fields(top, "format", "formatVersion", "createdAt", "contentDigest"); require(FORMAT.equals(string(top, "format")));
                    version(integer(top, "formatVersion")); version(integer(mem, "schemaVersion")); version(integer(conv, "schemaVersion"));
                    String created = string(top, "createdAt"); time(created);
                    var metadata = new Metadata(1, 1, 1, created, integer(mem, "itemCount"), integer(conv, "conversationCount"),
                            integer(conv, "turnCount"), integer(conv, "messageCount"), string(top, "contentDigest"));
                    validateDatabase(db, metadata);
                    require(metadata.equals(metadata(db, created, metadata.contentDigest())));
                    require(metadata.contentDigest().matches("[0-9a-f]{64}") && MessageDigest.isEqual(
                            metadata.contentDigest().getBytes(StandardCharsets.US_ASCII), digest(db, metadata).getBytes(StandardCharsets.US_ASCII)));
                    result.set(metadata); rows.sort(Comparator.comparing(r -> r.id().toString())); return rows;
                } catch (WorkspaceException controlled) { throw controlled; }
                catch (Exception ignored) { throw error(ErrorCode.WORKSPACE_BACKUP_INVALID); }
            });
            return result.get();
        } catch (WorkspaceException controlled) {
            if (Set.of(ErrorCode.WORKSPACE_BACKUP_INVALID, ErrorCode.WORKSPACE_BACKUP_UNSUPPORTED,
                    ErrorCode.WORKSPACE_BACKUP_TOO_LARGE, ErrorCode.WORKSPACE_RESTORE_FAILED).contains(controlled.error().code())) throw controlled;
            if (controlled.error().code().name().startsWith("MEMORY_BACKUP_") || controlled.error().code() == ErrorCode.MEMORY_INVALID
                    || controlled.error().code() == ErrorCode.MEMORY_LIMIT_EXCEEDED) throw error(ErrorCode.WORKSPACE_BACKUP_INVALID);
            throw error(ErrorCode.WORKSPACE_RESTORE_FAILED);
        }
    }
    @FunctionalInterface private interface RowConsumer { void accept(String field, JsonNode row) throws Exception; }
    private static void section(JsonParser parser, ObjectNode metadata, RowConsumer consumer, Set<String> scalars, Set<String> arrays) throws Exception {
        require(parser.currentToken() == JsonToken.START_OBJECT); Set<String> seen = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.PROPERTY_NAME); String name = parser.currentName(); require(seen.add(name)); parser.nextToken();
            if (arrays.contains(name)) {
                require(parser.currentToken() == JsonToken.START_ARRAY); long count = 0;
                long max = name.equals("turns") ? MAX_TURNS : 1000;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    require(++count <= max); consumer.accept(name, small(parser, 0));
                }
            } else { require(scalars.contains(name)); metadata.set(name, small(parser, 0)); }
        }
        require(seen.size() == scalars.size() + arrays.size());
    }
    /** Bound malicious nested records before allocating a tree (two messages, four selections). */
    private static JsonNode small(JsonParser p, int depth) throws Exception {
        require(depth <= 4);
        if (p.currentToken() == JsonToken.START_OBJECT) {
            var node = JSON.createObjectNode(); int count = 0;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                require(p.currentToken() == JsonToken.PROPERTY_NAME && ++count <= 12);
                String name = p.currentName(); p.nextToken(); node.set(name, small(p, depth + 1));
            }
            return node;
        }
        if (p.currentToken() == JsonToken.START_ARRAY) {
            var node = JSON.createArrayNode();
            while (p.nextToken() != JsonToken.END_ARRAY) { require(node.size() < 4); node.add(small(p, depth + 1)); }
            return node;
        }
        require(p.currentToken() == JsonToken.VALUE_STRING || p.currentToken() == JsonToken.VALUE_NUMBER_INT || p.currentToken() == JsonToken.VALUE_NULL);
        return JSON.readTree(p);
    }
    private static UUID uuid(String value) {
        require(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        UUID id = UUID.fromString(value); require(!id.equals(new UUID(0, 0))); return id;
    }
    private static void chronology(JsonNode n) { require(!time(string(n, "updatedAt")).isBefore(time(string(n, "createdAt")))); }
    private static void conversationValid(JsonNode n) {
        fields(n, "id", "title", "status", "createdAt", "updatedAt"); uuid(string(n, "id")); chronology(n);
        String title = string(n, "title"); ConversationLimits.content(title);
        require(title.equals(title.strip()) && title.codePointCount(0, title.length()) <= 160);
        Conversation.Status.valueOf(string(n, "status"));
    }
    private static void turnValid(JsonNode n) {
        fields(n, "id", "conversationId", "sequence", "status", "failureCode", "createdAt", "updatedAt", "messages", "memories");
        uuid(string(n, "id")); uuid(string(n, "conversationId")); chronology(n);
        require(integer(n, "sequence") >= 1 && integer(n, "sequence") <= 1000);
        var status = Conversation.TurnStatus.valueOf(string(n, "status")); require(status != Conversation.TurnStatus.PENDING);
        JsonNode failure = n.get("failureCode");
        require(failure.isNull() || failure.isString() && status == Conversation.TurnStatus.FAILED);
        if (!failure.isNull()) Conversation.FailureCode.valueOf(failure.asString());
        JsonNode messages = n.get("messages"), selections = n.get("memories");
        require(messages.isArray() && messages.size() == (status == Conversation.TurnStatus.SUCCEEDED ? 2 : 1));
        Set<String> roles = new HashSet<>();
        for (var message : messages) {
            fields(message, "id", "role", "content", "createdAt"); uuid(string(message, "id"));
            String role = string(message, "role"); require(Set.of("USER", "ASSISTANT").contains(role) && roles.add(role));
            ConversationLimits.content(string(message, "content")); time(string(message, "createdAt"));
        }
        require(roles.contains("USER") && (status == Conversation.TurnStatus.SUCCEEDED) == roles.contains("ASSISTANT"));
        require(selections.isArray() && selections.size() <= 4); Set<Long> positions = new HashSet<>(); Set<UUID> ids = new HashSet<>();
        for (var selection : selections) {
            fields(selection, "position", "memoryId", "revision"); long position = integer(selection, "position");
            require(position >= 0 && position < selections.size() && positions.add(position));
            require(ids.add(uuid(string(selection, "memoryId"))) && integer(selection, "revision") > 0);
        }
    }
    private static void insertMemory(Connection db, MemoryItem r) throws SQLException {
        update(db, "INSERT INTO memory_items VALUES(?,?,?,?,?,?,?,?,?)", r.id().toString(), r.type().name(), r.title(), r.content(),
                r.status().name(), r.revision(), r.source().name(), r.createdAt().toEpochMilli(), r.updatedAt().toEpochMilli());
    }
    private static void insertConversation(Connection db, JsonNode n) throws SQLException {
        update(db, "INSERT INTO conversations VALUES(?,?,?,?,?)", string(n,"id"), string(n,"title"), string(n,"status"),
                time(string(n,"createdAt")).toEpochMilli(), time(string(n,"updatedAt")).toEpochMilli());
    }
    private static void insertTurn(Connection db, JsonNode n) throws SQLException {
        update(db, "INSERT INTO conversation_turns(id,conversation_id,sequence,status,created_at,updated_at,failure_code) VALUES(?,?,?,?,?,?,?)",
                string(n,"id"), string(n,"conversationId"), integer(n,"sequence"), string(n,"status"), time(string(n,"createdAt")).toEpochMilli(),
                time(string(n,"updatedAt")).toEpochMilli(), n.get("failureCode").isNull() ? null : string(n,"failureCode"));
        for (var m : n.get("messages")) update(db, "INSERT INTO conversation_messages VALUES(?,?,?,?,?)", string(m,"id"), string(n,"id"),
                string(m,"role"), string(m,"content"), time(string(m,"createdAt")).toEpochMilli());
        for (var s : n.get("memories")) update(db, "INSERT INTO conversation_memory_selections VALUES(?,?,?,?)", string(n,"id"),
                integer(s,"position"), string(s,"memoryId"), integer(s,"revision"));
    }
    private static Metadata metadata(Connection db, String created, String digest) throws SQLException {
        return new Metadata(1,1,1,created,count(db,"SELECT count(*) FROM memory_items"),count(db,"SELECT count(*) FROM conversations"),
                count(db,"SELECT count(*) FROM conversation_turns"),count(db,"SELECT count(*) FROM conversation_messages"),digest);
    }
    private static void validateDatabase(Connection db, Metadata m) throws Exception {
        require(m.memoryCount() >= 0 && m.memoryCount() <= 1000 && m.conversationCount() >= 0 && m.conversationCount() <= 1000
                && m.turnCount() >= 0 && m.turnCount() <= MAX_TURNS && m.messageCount() >= 0 && m.messageCount() <= 2 * MAX_TURNS);
        require(count(db,"SELECT count(*) FROM (SELECT conversation_id FROM conversation_turns GROUP BY conversation_id HAVING count(*)>1000 OR min(sequence)!=1 OR max(sequence)!=count(*))") == 0);
        try (var s = db.createStatement(); var r = s.executeQuery("PRAGMA foreign_key_check")) { require(!r.next()); }
        WorkspaceSchema.verify(db);
    }
    private static String digest(Connection db, Metadata m) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
            strings(out, FORMAT,"1",m.createdAt(),"1",Long.toString(m.memoryCount()),"1",Long.toString(m.conversationCount()),
                    Long.toString(m.turnCount()),Long.toString(m.messageCount()));
            each(db,"SELECT * FROM memory_items ORDER BY id", r -> {
                var n = memory(r); readRow(n); canonical(out,n,"id","type","title","content","status","revision","source","createdAt","updatedAt");
            });
            each(db,"SELECT * FROM conversations ORDER BY id", r -> {
                var n = conversation(r); conversationValid(n); canonical(out,n,"id","title","status","createdAt","updatedAt");
            });
            each(db,"SELECT * FROM conversation_turns ORDER BY conversation_id,sequence", r -> {
                var n = turn(db,r); turnValid(n); canonical(out,n,"id","conversationId","sequence","status","failureCode","createdAt","updatedAt");
                strings(out,Integer.toString(n.get("messages").size()),Integer.toString(n.get("memories").size()));
                for (var msg : n.get("messages")) canonical(out,msg,"id","role","content","createdAt");
                for (var sel : n.get("memories")) canonical(out,sel,"position","memoryId","revision");
            });
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static void canonical(DataOutputStream out, JsonNode n, String... fields) throws IOException {
        for (String field : fields) { var value = n.get(field); strings(out, value.isNull() ? "" : value.asString()); }
    }
    private static ObjectNode memory(ResultSet r) throws SQLException {
        return JSON.createObjectNode().put("id",r.getString("id")).put("type",r.getString("type")).put("title",r.getString("title"))
                .put("content",r.getString("content")).put("status",r.getString("status")).put("revision",r.getLong("revision"))
                .put("source",r.getString("source")).put("createdAt", instant(r,"created_at")).put("updatedAt",instant(r,"updated_at"));
    }
    private static ObjectNode conversation(ResultSet r) throws SQLException {
        return JSON.createObjectNode().put("id",r.getString("id")).put("title",r.getString("title")).put("status",r.getString("status"))
                .put("createdAt",instant(r,"created_at")).put("updatedAt",instant(r,"updated_at"));
    }
    private static ObjectNode turn(Connection db, ResultSet r) throws Exception {
        var n = JSON.createObjectNode().put("id",r.getString("id")).put("conversationId",r.getString("conversation_id"))
                .put("sequence",r.getLong("sequence")).put("status",r.getString("status")).put("failureCode",r.getString("failure_code"))
                .put("createdAt",instant(r,"created_at")).put("updatedAt",instant(r,"updated_at"));
        var messages = n.putArray("messages"); var selections = n.putArray("memories");
        each(db,"SELECT * FROM conversation_messages WHERE turn_id=? ORDER BY CASE role WHEN 'USER' THEN 0 ELSE 1 END", row ->
                messages.addObject().put("id",row.getString("id")).put("role",row.getString("role"))
                        .put("content",row.getString("content")).put("createdAt",instant(row,"created_at")),r.getString("id"));
        each(db,"SELECT * FROM conversation_memory_selections WHERE turn_id=? ORDER BY position", row ->
                selections.addObject().put("position",row.getInt("position")).put("memoryId",row.getString("memory_id"))
                        .put("revision",row.getLong("revision")),r.getString("id"));
        return n;
    }
    private static String instant(ResultSet r, String column) throws SQLException { return Instant.ofEpochMilli(r.getLong(column)).toString(); }
    @FunctionalInterface private interface Row { void accept(ResultSet r) throws Exception; }
    private static void each(Connection db, String sql, Row row, Object... args) throws Exception {
        try (var s = db.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
            try (var r = s.executeQuery()) { while (r.next()) row.accept(r); }
        }
    }
    private static long count(Connection db, String sql) throws SQLException {
        try (var s=db.createStatement();var r=s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
    private static void sql(Connection db, String sql) throws SQLException { try(var s=db.createStatement()) { s.execute(sql); } }
    private static void update(Connection db, String sql, Object... args) throws SQLException {
        try(var s=db.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); s.executeUpdate(); }
    }
    private static void cleanup(Path dir) {
        boolean failed=false;
        for(String name:List.of("memory.db","memory.db-journal","memory.db-wal","memory.db-shm")) {
            try { Files.deleteIfExists(dir.resolve(name)); } catch(Exception ignored) { failed=true; }
        }
        try { Files.delete(dir); } catch(Exception ignored) { failed=true; }
        if(failed) throw error(ErrorCode.WORKSPACE_RESTORE_FAILED);
    }
    public static final class LimitedInput extends FilterInputStream {
        private long count; private final long maximum;
        public LimitedInput(InputStream input,long maximum) { super(input); this.maximum=maximum; }
        private void add(long n) { if(n>0 && (count+=n)>maximum) throw error(ErrorCode.WORKSPACE_BACKUP_TOO_LARGE); }
        public int read() throws IOException { int value=in.read(); if(value!=-1) add(1); return value; }
        public int read(byte[] b,int offset,int length) throws IOException { int n=in.read(b,offset,(int)Math.min(length,Math.max(1,maximum-count+1))); add(n); return n; }
    }
    private static final class LimitedOutput extends FilterOutputStream {
        private long count; private final long maximum;
        LimitedOutput(OutputStream out,long maximum) { super(out); this.maximum=maximum; }
        public void write(int b) throws IOException { if(++count>maximum) throw error(ErrorCode.WORKSPACE_BACKUP_TOO_LARGE); out.write(b); }
        public void write(byte[] b,int off,int length) throws IOException { if((count+=length)>maximum) throw error(ErrorCode.WORKSPACE_BACKUP_TOO_LARGE); out.write(b,off,length); }
    }
}
