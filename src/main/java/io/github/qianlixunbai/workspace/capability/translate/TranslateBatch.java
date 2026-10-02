package io.github.qianlixunbai.workspace.capability.translate;

import io.github.qianlixunbai.workspace.common.*;
import io.github.qianlixunbai.workspace.task.TaskResult;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded input and strict provider mapping for one generation, without item retries. */
public final class TranslateBatch {
    public static final int MAX_ITEMS = 32, MAX_CHARACTERS = 2800, MAX_TEXT_BYTES = 4096;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private TranslateBatch() {}

    public static String input(List<TranslateRequest.Item> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) throw invalidInput();
        Set<Integer> ids = new HashSet<>();
        int chars = 0, bytes = 0;
        for (var item : items) {
            if (item == null || item.id() == null || item.id() < 0 || !ids.add(item.id())
                    || item.text() == null || item.text().isBlank() || item.text().length() > MAX_CHARACTERS)
                throw invalidInput();
            chars += item.text().length();
            bytes += item.text().getBytes(StandardCharsets.UTF_8).length;
            if (chars > MAX_CHARACTERS || bytes > MAX_TEXT_BYTES) throw invalidInput();
        }
        return JSON.writeValueAsString(items);
    }

    public static TaskResult.TranslationBatch result(String output, List<TranslateRequest.Item> requested) {
        JsonNode array;
        try { array = JSON.readTree(output); }
        catch (tools.jackson.core.JacksonException ignored) { throw invalidOutput(); }
        if (array == null || !array.isArray()) throw invalidOutput();
        Set<Integer> ids = new HashSet<>(), seen = new HashSet<>(), duplicates = new HashSet<>();
        requested.forEach(item -> ids.add(item.id()));
        Map<Integer, String> translations = new HashMap<>();
        for (JsonNode item : array) {
            if (!item.isObject()) continue;
            JsonNode id = item.path("id");
            if (!id.isIntegralNumber() || !id.canConvertToInt() || !ids.contains(id.intValue())) continue;
            int key = id.intValue();
            if (!seen.add(key)) { duplicates.add(key); translations.remove(key); continue; }
            JsonNode translation = item.path("translation");
            if (item.size() != 2 || !translation.isString() || translation.asString().isBlank()) continue;
            if (!duplicates.contains(key)) translations.put(key, translation.asString().strip());
        }
        List<TaskResult.Translation> valid = new ArrayList<>();
        for (var item : requested) {
            String value = translations.get(item.id());
            if (value != null) valid.add(new TaskResult.Translation(item.id(), value));
        }
        return new TaskResult.TranslationBatch(valid);
    }
    private static WorkspaceException invalidInput() { return new WorkspaceException(ErrorCode.INVALID_REQUEST, "INPUT_BUDGET"); }
    private static WorkspaceException invalidOutput() { return new WorkspaceException(ErrorCode.PROVIDER_RESPONSE_INVALID, "RESPONSE"); }
}
