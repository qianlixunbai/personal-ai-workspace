package io.github.qianlixunbai.workspace.capability.ask;

import io.github.qianlixunbai.workspace.memory.MemoryReference;
import jakarta.validation.constraints.*;
import io.github.qianlixunbai.workspace.common.*;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;
import java.util.*;

@JsonDeserialize(using = MemoryAskRequest.Deserializer.class)
public record MemoryAskRequest(@NotBlank @Size(max = 3000) String question,
        @NotNull @Size(min = 1, max = 4) List<MemoryReference> memories,
        @Pattern(regexp = "chat\\.balanced") String profile) {
    public MemoryAskRequest { if (memories != null) memories = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(memories)); }
    @Override public String toString() { return "MemoryAskRequest[redacted]"; }
    /** Endpoint-specific types and field allowlists; never coerce revision or client context. */
    public static final class Deserializer extends StdDeserializer<MemoryAskRequest> {
        public Deserializer() { super(MemoryAskRequest.class); }
        @Override public MemoryAskRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = context.readTree(parser);
            fields(root, Set.of("question", "memories", "profile"));
            JsonNode array = root.path("memories");
            if (!array.isArray() || array.size() < 1 || array.size() > 4) throw invalid();
            List<MemoryReference> references = new ArrayList<>();
            for (JsonNode item : array) {
                fields(item, Set.of("id", "revision"));
                JsonNode revision = item.path("revision");
                String id = string(item, "id", true);
                if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                        || !revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 1) throw invalid();
                references.add(new MemoryReference(UUID.fromString(id), revision.longValue()));
            }
            MemoryReference.validate(references);
            return new MemoryAskRequest(string(root, "question", true), references, string(root, "profile", false));
        }
        private static String string(JsonNode node, String name, boolean required) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) { if (required) throw invalid(); return null; }
            if (!value.isString()) throw invalid();
            return value.asString();
        }
        private static void fields(JsonNode node, Set<String> allowed) {
            if (node == null || !node.isObject()) throw invalid();
            for (String name : node.propertyNames()) if (!allowed.contains(name)) throw invalid();
        }
        private static WorkspaceException invalid() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "HTTP"); }
    }
}
