package io.github.qianlixunbai.workspace.memory;

import io.github.qianlixunbai.workspace.common.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static io.github.qianlixunbai.workspace.memory.MemoryLimits.*;

/** v1 logical source contract. No DB/index/auth/task/provider data enters this document. */
public record MemoryBackup(Instant createdAt, List<MemoryItem> items, String contentDigest) {
    public static final String FORMAT = "personal-ai-workspace.memory-backup";
    public static final int FORMAT_VERSION = 1;
    // JSON worst case: 2000 UTF-16 units * 6 escaped bytes + 160 title code points * 12
    // + 1024 field/UUID/enum/revision/time overhead per record, plus 4096 document overhead.
    public static final int MAX_BYTES = TOTAL_ITEMS * (CONTENT_UTF16 * 6 + TITLE_CODE_POINTS * 12 + 1024) + 4096;
    public static final int MAX_RESTORE_BYTES = MAX_BYTES + 64 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public MemoryBackup { items = List.copyOf(items); }
    @Override public String toString() { return "MemoryBackup[formatVersion=1,schemaVersion=1,itemCount=" + items.size() + "]"; }
    public record Metadata(int formatVersion, int schemaVersion, int itemCount, String contentDigest) {}
    public Metadata metadata() { return new Metadata(FORMAT_VERSION, MemoryStore.SCHEMA_VERSION, items.size(), contentDigest); }
    public static MemoryBackup export(MemoryStore store) {
        try {
            var rows = store.backupSnapshot();
            for (var row : rows) validateRow(row);
            Instant time = Instant.ofEpochMilli(System.currentTimeMillis());
            return new MemoryBackup(time, rows, digest(time, rows));
        } catch (Exception ignored) { throw error(ErrorCode.MEMORY_EXPORT_FAILED); }
    }
    public byte[] bytes() {
        try {
            var document = JSON.createObjectNode();
            document.put("format", FORMAT).put("formatVersion", FORMAT_VERSION).put("schemaVersion", MemoryStore.SCHEMA_VERSION)
                    .put("createdAt", createdAt.toString()).put("itemCount", items.size()).put("contentDigest", contentDigest);
            var array = document.putArray("items");
            for (var row : items) {
                array.addObject().put("id", row.id().toString()).put("type", row.type().name()).put("title", row.title())
                        .put("content", row.content()).put("status", row.status().name()).put("revision", row.revision())
                        .put("source", row.source().name()).put("createdAt", row.createdAt().toString()).put("updatedAt", row.updatedAt().toString());
            }
            byte[] result = JSON.writeValueAsBytes(document);
            if (result.length > MAX_BYTES) throw error(ErrorCode.MEMORY_BACKUP_TOO_LARGE);
            return result;
        } catch (WorkspaceException controlled) { throw controlled; }
        catch (Exception ignored) { throw error(ErrorCode.MEMORY_EXPORT_FAILED); }
    }
    public static JsonNode parse(byte[] bytes, int maximum) {
        if (bytes == null) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
        if (bytes.length > maximum) throw error(ErrorCode.MEMORY_BACKUP_TOO_LARGE);
        try {
            // Reject malformed UTF-8 instead of the decoder silently replacing bytes.
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return JSON.readTree(bytes);
        } catch (Exception ignored) { throw error(ErrorCode.MEMORY_BACKUP_INVALID); }
    }
    public static MemoryBackup read(byte[] bytes) { return read(parse(bytes, MAX_BYTES)); }
    public record RestoreRequest(MemoryBackup backup, String targetDirectory) {
        @Override public String toString() { return "MemoryRestoreRequest[redacted]"; }
    }
    public static RestoreRequest readRestore(byte[] bytes) {
        var envelope = parse(bytes, MAX_RESTORE_BYTES);
        fields(envelope, "backup", "targetDirectory");
        try (var parser = JSON.createParser(bytes)) {
            parser.nextToken();
            while (parser.nextToken() != tools.jackson.core.JsonToken.END_OBJECT) {
                String name = parser.currentName(); parser.nextToken();
                long begin = parser.currentTokenLocation().getByteOffset();
                parser.skipChildren();
                if (name.equals("backup") && parser.currentLocation().getByteOffset() - begin > MAX_BYTES)
                    throw error(ErrorCode.MEMORY_BACKUP_TOO_LARGE);
            }
        } catch (WorkspaceException controlled) { throw controlled; }
        catch (Exception ignored) { throw error(ErrorCode.MEMORY_BACKUP_INVALID); }
        return new RestoreRequest(read(envelope.get("backup")), string(envelope, "targetDirectory"));
    }
    public static MemoryBackup read(JsonNode root) {
        try {
            fields(root, "format", "formatVersion", "schemaVersion", "createdAt", "itemCount", "contentDigest", "items");
            if (!FORMAT.equals(string(root, "format"))) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
            if (integer(root, "formatVersion") != FORMAT_VERSION || integer(root, "schemaVersion") != MemoryStore.SCHEMA_VERSION)
                throw error(ErrorCode.MEMORY_BACKUP_UNSUPPORTED);
            Instant time = time(string(root, "createdAt"));
            JsonNode array = root.get("items");
            if (!array.isArray() || array.size() > TOTAL_ITEMS || integer(root, "itemCount") != array.size())
                throw error(ErrorCode.MEMORY_BACKUP_INVALID);
            Set<UUID> ids = new HashSet<>(); List<MemoryItem> rows = new ArrayList<>();
            for (JsonNode node : array) {
                var row = readRow(node);
                if (!ids.add(row.id())) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
                rows.add(row);
            }
            rows.sort(Comparator.comparing(row -> row.id().toString()));
            String checksum = string(root, "contentDigest");
            if (!checksum.matches("[0-9a-f]{64}") || !MessageDigest.isEqual(checksum.getBytes(StandardCharsets.US_ASCII),
                    digest(time, rows).getBytes(StandardCharsets.US_ASCII))) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
            return new MemoryBackup(time, rows, checksum);
        } catch (WorkspaceException controlled) {
            if (controlled.error().code() == ErrorCode.MEMORY_BACKUP_UNSUPPORTED) throw controlled;
            throw error(ErrorCode.MEMORY_BACKUP_INVALID);
        } catch (Exception ignored) { throw error(ErrorCode.MEMORY_BACKUP_INVALID); }
    }
    public static MemoryItem readRow(JsonNode node) {
        fields(node, "id", "type", "title", "content", "status", "revision", "source", "createdAt", "updatedAt");
        String id = string(node, "id");
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
        var row = new MemoryItem(UUID.fromString(id), MemoryItem.Type.valueOf(string(node, "type")), string(node, "title"),
                string(node, "content"), MemoryItem.Status.valueOf(string(node, "status")), integer(node, "revision"),
                MemoryItem.Source.valueOf(string(node, "source")), time(string(node, "createdAt")), time(string(node, "updatedAt")));
        validateRow(row);
        return row;
    }
    public static void fields(JsonNode root, String... expected) {
        if (root == null || !root.isObject() || root.size() != expected.length) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
        for (String name : expected) if (!root.has(name)) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
    }
    public static String string(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isString()) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
        return node.asString();
    }
    public static long integer(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
        return node.asLong();
    }
    public static Instant time(String value) {
        Instant time = Instant.parse(value);
        // SQLite v1 stores milliseconds. Reject any timestamp that reconstruction would truncate.
        if (!time.equals(Instant.ofEpochMilli(time.toEpochMilli())) || !time.toString().equals(value))
            throw error(ErrorCode.MEMORY_BACKUP_INVALID);
        return time;
    }
    public static void validateRow(MemoryItem row) {
        text(row.type(), row.title(), row.content()); revision(row.revision());
        if (row.id() == null || row.id().equals(new UUID(0, 0)) || row.status() == null || row.source() != MemoryItem.Source.MANUAL
                || row.updatedAt().isBefore(row.createdAt())) throw error(ErrorCode.MEMORY_BACKUP_INVALID);
        time(row.createdAt().toString()); time(row.updatedAt().toString());
    }
    /** Canonical binary serialization: each UTF-8 string prefixed by a 4-byte BE byte length. */
    private static String digest(Instant time, List<MemoryItem> rows) throws Exception {
        var checksum = MessageDigest.getInstance("SHA-256");
        try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), checksum))) {
            strings(out, FORMAT, "1", "1", time.toString(), Integer.toString(rows.size()));
            for (var row : rows) strings(out, row.id().toString(), row.type().name(), row.title(), row.content(), row.status().name(),
                    Long.toString(row.revision()), row.source().name(), row.createdAt().toString(), row.updatedAt().toString());
        }
        return HexFormat.of().formatHex(checksum.digest());
    }
    public static void strings(DataOutputStream out, String... values) throws IOException {
        for (String value : values) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
    }
}
