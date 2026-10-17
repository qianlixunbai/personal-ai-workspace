package io.github.qianlixunbai.workspace.provider.ollama;

import io.github.qianlixunbai.workspace.common.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Only the child and complete component closure approved by V1 P1 Final Guard. */
final class OllamaVisionAdmission {
    static final String MODEL="qwen3.5:4b", RUNNER="llamacpp";
    static final String LIST="430d3fc72add72bb7a641e14ee28e60be312b6c9f93a144a22ec4583bdbdf846";
    static final String CHILD="96f38c742e0a10e194b237d2ffb8b55925ebfeed9d27df72688f565e773569e7";
    static final String CONFIG="57c5e44ea71e1b7ad15c8c081b1ad23c66ac5f995a0883e153de7972100e3676";
    static final String LANGUAGE="d324d8d025284ff9faed6c97b03869f4a928bb14a4d83a6cc2c713039c3b3b4f";
    static final String PROJECTOR="db3830bf825a26fcfed35416378696a47e0cbcf49c5b652a6fe9db90541f86d9";
    static final String GGML="3c3adf637187c95955232acd3ed05b9ea45aeb2a5f953e631c38174a46b5b56a";
    static boolean candidate(String model,String runner,String digest) {
        return MODEL.equals(model) && RUNNER.equals(runner) && CHILD.equals(digest);
    }
    static void details(JsonNode d) {
        Set<String> allowed=Set.of("parent_model","format","family","families","parameter_size","quantization_level","context_length","embedding_length","runner");
        fields(d,allowed);
        if (!"".equals(d.path("parent_model").asString("?")) || !"gguf".equals(d.path("format").asString())
                || !"qwen35".equals(d.path("family").asString()) || !"Q4_K_M".equals(d.path("quantization_level").asString())
                || !"4.7B".equals(d.path("parameter_size").asString()) || !RUNNER.equals(d.path("runner").asString())
                || !d.path("families").isArray() || d.path("families").size()!=2
                || !"qwen35".equals(d.path("families").path(0).asString()) || !"clip".equals(d.path("families").path(1).asString())
                || d.has("context_length") && (!d.path("context_length").isIntegralNumber() || d.path("context_length").asLong(-1)!=262144)
                || d.has("embedding_length") && (!d.path("embedding_length").isIntegralNumber() || d.path("embedding_length").asLong(-1)!=2560)) reject();
    }
    static void verify(JsonNode before,JsonNode show,JsonNode after,String version,int context) {
        if (!"0.40.1".equals(version) || context>8192 || !before.equals(after)
                || !CHILD.equals(OllamaModelAdmission.digest(before.path("digest")))) reject();
        fields(show,Set.of("thinking","license","modelfile","parameters","template","system","renderer","parser","details","messages",
                "remote_model","remote_host","model_info","projector_info","tensors","capabilities","manifests","modified_at","requires"));
        OllamaModelAdmission.noRemote(show); details(before.path("details")); details(show.path("details")); details(after.path("details"));
        if (!"0.17.1".equals(show.path("requires").asString()) || !"qwen35".equals(show.path("model_info").path("general.architecture").asString())
                || !show.path("model_info").path("qwen35.context_length").isIntegralNumber() || show.path("model_info").path("qwen35.context_length").asLong(-1)!=262144
                || !"clip".equals(show.path("projector_info").path("general.architecture").asString())
                || !show.path("capabilities").isArray()) reject();
        Set<String> caps=new HashSet<>();
        for(JsonNode cap:show.path("capabilities")) if(!cap.isString() || !Set.of("completion","vision","tools","thinking").contains(cap.asString()) || !caps.add(cap.asString())) reject();
        if(!caps.containsAll(Set.of("completion","vision"))) reject();
        if(show.path("manifests").size()!=2) reject();
        Set<String> runners=new HashSet<>();
        for(JsonNode child:show.path("manifests")) {
            fields(child,Set.of("digest","runner","format","selected")); String r=child.path("runner").asString();
            if(!runners.add(r) || !"gguf".equals(child.path("format").asString())
                    || !(r.equals(RUNNER)?CHILD:GGML).equals(OllamaModelAdmission.digest(child.path("digest")))
                    || child.has("selected") && !child.path("selected").isBoolean()
                    || child.path("selected").asBoolean(false)!=r.equals(RUNNER)) reject();
        }
        if(!runners.equals(Set.of("ggml",RUNNER))) reject();
    }
    private static void fields(JsonNode n,Set<String> fields) { if(!n.isObject()) reject(); for(String f:n.propertyNames()) if(!fields.contains(f)) reject(); }
    private static void reject() { throw new WorkspaceException(ErrorCode.POLICY_DENIED,"VISION_IDENTITY"); }
}
