package io.github.qianlixunbai.workspace.capability.translate;

import io.github.qianlixunbai.workspace.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;
import java.util.*;

@JsonDeserialize(using = TranslateRequest.Deserializer.class)
public record TranslateRequest(@Size(max = 16000) String text,
                               List<@Valid Item> items,
                               @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}") String sourceLanguage,
                               @NotBlank @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}") String targetLanguage,
                               @Pattern(regexp = "translate\\.fast") String profile) {
    public TranslateRequest { if (items != null) items = List.copyOf(items); }
    public TranslateRequest(String text, String sourceLanguage, String targetLanguage, String profile) {
        this(text, null, sourceLanguage, targetLanguage, profile);
    }
    public record Item(@NotNull @Min(0) Integer id, @NotBlank String text) {
        @Override public String toString() { return "TranslateItem[redacted]"; }
    }
    @AssertTrue public boolean isExactlyOneInput() {
        return (text != null) != (items != null) && (text == null || !text.isBlank());
    }
    @Override public String toString() { return "TranslateRequest[redacted]"; }

    /** No numeric/string coercion for the mapping protocol; explicit null input is invalid. */
    public static final class Deserializer extends StdDeserializer<TranslateRequest> {
        public Deserializer() { super(TranslateRequest.class); }
        @Override public TranslateRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = context.readTree(parser);
            fields(root, Set.of("text", "items", "sourceLanguage", "targetLanguage", "profile"));
            if (root.has("text") == root.has("items")) throw invalid();
            List<Item> items = null;
            if (root.has("items")) {
                JsonNode array = root.get("items");
                if (!array.isArray() || array.isEmpty() || array.size() > TranslateBatch.MAX_ITEMS) throw invalid();
                items = new ArrayList<>();
                for (JsonNode item : array) {
                    fields(item, Set.of("id", "text"));
                    JsonNode id = item.path("id");
                    if (!id.isIntegralNumber() || !id.canConvertToInt() || id.intValue() < 0) throw invalid();
                    items.add(new Item(id.intValue(), string(item, "text", true)));
                }
            }
            return new TranslateRequest(string(root, "text", root.has("text")), items,
                    string(root, "sourceLanguage", false), string(root, "targetLanguage", true), string(root, "profile", false));
        }
        private static String string(JsonNode node, String field, boolean required) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                if (required) throw invalid();
                return null;
            }
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
