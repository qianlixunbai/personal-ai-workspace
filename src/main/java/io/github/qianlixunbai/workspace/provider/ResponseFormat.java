package io.github.qianlixunbai.workspace.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed application-owned contracts only. No caller-supplied schemas or grammar. */
public enum ResponseFormat {
    TEXT, MEMORY_CANDIDATE;

    private static final byte[] MEMORY_SCHEMA = loadSchema();
    private static byte[] loadSchema() {
        try (var input = ResponseFormat.class.getResourceAsStream("/contracts/memory-candidate.schema.json")) {
            if (input == null) throw new IllegalStateException("Missing response contract");
            byte[] bytes = input.readNBytes(2049);
            if (bytes.length > 2048) throw new IllegalStateException("Response contract too large");
            return bytes;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    public JsonNode schema() {
        // Each request receives its own tree; consumers cannot mutate the contract.
        return this == TEXT ? null : JsonMapper.builder().build().readTree(MEMORY_SCHEMA);
    }
}
