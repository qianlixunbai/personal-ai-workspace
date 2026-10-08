package io.github.qianlixunbai.workspace.provider.ollama;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.model.ModelStateStore;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.*;

/** Reviewed subset of v0.40.0 api/types.go, server/routes.go and server/images.go. */
final class OllamaModelAdmission {
    static final String VERSION = "0.40.0";
    private static final Set<String> ARCHITECTURES = Set.of("llama", "qwen2", "qwen3", "qwen35", "qwen35moe");
    private static final Set<String> SHOW_FIELDS = Set.of("thinking", "license", "modelfile", "parameters", "template",
            "system", "renderer", "parser", "details", "messages", "remote_model", "remote_host", "model_info",
            "projector_info", "tensors", "capabilities", "manifests", "modified_at", "requires");
    private static final Set<String> TAG_FIELDS = Set.of("name", "model", "remote_model", "remote_host", "modified_at",
            "size", "digest", "details", "capabilities");
    private static final Set<String> DETAIL_FIELDS = Set.of("parent_model", "format", "family", "families", "parameter_size",
            "quantization_level", "context_length", "embedding_length", "runner");
    private static final Pattern FROM = Pattern.compile("(?m)^FROM ([^\r\n]+)\r?$");
    record Evidence(String model, String digest, String architecture, long contextLimit) {
        @Override public String toString() { return "LocalModelEvidence[private]"; }
    }

    static JsonNode installed(JsonNode tags, String model) {
        if (!ModelStateStore.validModel(model) || !tags.isObject() || tags.size() != 1 || !tags.path("models").isArray()
                || tags.path("models").size() > 256) reject();
        JsonNode match = null;
        for (JsonNode item : tags.path("models")) {
            if (!item.isObject() || !item.path("name").isString() || !item.path("model").isString()) reject();
            if (model.equals(item.path("name").asString()) || model.equals(item.path("model").asString())) {
                if (match != null || !model.equals(item.path("name").asString()) || !model.equals(item.path("model").asString())) reject();
                fields(item, TAG_FIELDS);
                noRemote(item);
                String family = item.path("details").path("family").asString("");
                if (!ARCHITECTURES.contains(family)) reject();
                details(item.path("details"), family);
                digest(item.path("digest"));
                if (!item.path("size").isIntegralNumber() || !item.path("size").canConvertToLong() || item.path("size").asLong() <= 0) reject();
                match = item;
            }
        }
        if (match == null) throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE, "MODEL");
        return match;
    }

    static Evidence verify(String model, JsonNode before, JsonNode show, JsonNode after, int contextBudget) {
        fields(show, SHOW_FIELDS);
        noRemote(show);
        if (show.has("manifests") && !show.path("manifests").isArray()
                || show.has("projector_info") && !show.path("projector_info").isObject()) reject();
        if (show.path("manifests").size() != 0 || show.path("projector_info").size() != 0
                || !show.path("model_info").isObject()
                || !show.path("capabilities").isArray()) reject();
        minimumVersion(show);
        boolean completion = false;
        for (JsonNode capability : show.path("capabilities")) {
            if (!capability.isString() || !Set.of("completion", "tools", "vision", "thinking", "embedding").contains(capability.asString())) reject();
            completion |= "completion".equals(capability.asString());
        }
        if (!completion) reject();
        JsonNode info = show.path("model_info");
        String architecture = info.path("general.architecture").asString("");
        if (!ARCHITECTURES.contains(architecture)) reject();
        details(before.path("details"), architecture);
        details(show.path("details"), architecture);
        details(after.path("details"), architecture);
        JsonNode context = info.path(architecture + ".context_length");
        if (!context.isIntegralNumber() || !context.canConvertToLong() || context.asLong() < contextBudget) reject();
        for (String name : info.propertyNames()) {
            if (name.endsWith(".context_length") && !name.equals(architecture + ".context_length")) reject();
        }
        for (JsonNode detail : List.of(before.path("details"), show.path("details"), after.path("details"))) {
            if (detail.has("context_length") && (!detail.path("context_length").isIntegralNumber()
                    || !detail.path("context_length").canConvertToLong() || detail.path("context_length").asLong() != context.asLong())) reject();
        }
        String a = digest(before.path("digest")), b = digest(after.path("digest"));
        if (!a.equals(b)) throw new WorkspaceException(ErrorCode.MODEL_IDENTITY_CHANGED, "MODEL");
        if (!before.equals(after)) reject();
        String modelfile = show.path("modelfile").asString("");
        // The first generated command must be a single absolute blob FROM. Never open this path.
        String first = modelfile.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).findFirst().orElse("");
        Matcher matcher = FROM.matcher(modelfile);
        if (!matcher.find() || !first.equals("FROM " + matcher.group(1))) reject();
        String source = matcher.group(1);
        if (source.startsWith("\"") && source.endsWith("\"")) source = source.substring(1, source.length() - 1);
        if (!(source.matches("/[A-Za-z0-9._ /-]+/blobs/sha256-[0-9a-f]{64}")
                || source.matches("[A-Za-z]:[\\\\/][A-Za-z0-9._ \\\\/-]+[\\\\/]blobs[\\\\/]sha256-[0-9a-f]{64}"))
                || source.contains("..") || matcher.find()
                || Pattern.compile("(?mi)^(ADAPTER|DRAFT|REQUIRES|RUNNER)\\b").matcher(modelfile).find()) reject();
        return new Evidence(model, a, architecture, context.asLong());
    }
    private static void minimumVersion(JsonNode show) {
        if (!nonempty(show, "requires")) return;
        // v0.40.0 ShowResponse.Requires is a minimum version, not a source/runner directive.
        // Support only canonical stable triples; malformed, prerelease and unknown forms fail closed.
        String required = show.path("requires").asString();
        if (!required.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")) reject();
        String[] minimum = required.split("\\."), supported = VERSION.split("\\.");
        for (int i = 0; i < supported.length; i++) {
            int comparison = Integer.compare(Integer.parseInt(minimum[i]), Integer.parseInt(supported[i]));
            if (comparison > 0) reject();
            if (comparison < 0) return;
        }
    }
    private static void details(JsonNode node, String architecture) {
        fields(node, DETAIL_FIELDS);
        if (!"gguf".equals(node.path("format").asString("")) || !architecture.equals(node.path("family").asString(""))
                || nonempty(node, "parent_model") || !node.path("families").isArray()
                || node.path("families").size() != 1 || !architecture.equals(node.path("families").get(0).asString(""))) reject();
    }
    static String digest(JsonNode node) {
        if (!node.isString()) reject();
        String value = node.asString();
        if (value.startsWith("sha256:")) value = value.substring(7);
        if (!ModelStateStore.validDigest(value)) reject();
        return value;
    }
    static void noRemote(JsonNode node) {
        if (node.isObject()) {
            for (String key : node.propertyNames()) {
                if (key.equals("runner") && (!node.path(key).isString()
                        || !Set.of("", "ggml", "llamacpp").contains(node.path(key).asString()))) reject();
                if (Set.of("remote_host", "remote_model", "source", "cloud", "routing", "manifest").contains(key)
                        && nonempty(node, key)) reject();
                noRemote(node.path(key));
            }
        } else if (node.isArray()) { for (JsonNode item : node) noRemote(item); }
    }
    private static boolean nonempty(JsonNode node, String key) {
        if (!node.has(key)) return false;
        JsonNode value = node.path(key);
        // Explicit null and incorrect types are unknown metadata, not proof of absence.
        if (!value.isString()) reject();
        return !value.asString().isEmpty();
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) reject();
        for (String field : node.propertyNames()) if (!allowed.contains(field)) reject();
    }
    private static void reject() { throw new WorkspaceException(ErrorCode.POLICY_DENIED, "MODEL_METADATA"); }
}
