package io.github.qianlixunbai.workspace.capability.vision;

import io.github.qianlixunbai.workspace.common.*;
import java.nio.charset.StandardCharsets;

/** A declared capability or an embedding count can never manufacture a runner policy proof. */
public final class VisionTokenBudget {
    public static final String POLICY = "v1-png-rgb-white-square-1024-context-8192";
    public record Evidence(String identity, String nativePayloadDigest, String preprocessingDigest,
                           String templateDigest, String policy, int textBound, int templateBound,
                           int imageBound, int safetyMargin) {
        @Override public String toString() { return "VisionTokenEvidence[private]"; }
    }
    private VisionTokenBudget() {}
    public static void verify(Evidence trusted, String currentIdentity, String currentPayloadDigest,
                              String currentPreprocessingDigest, String currentTemplateDigest, String question) {
        if (question == null || question.isBlank() || question.length() > 1000
                || question.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw new WorkspaceException(ErrorCode.INVALID_REQUEST,"VISION_QUESTION");
        }
        if (trusted == null || currentIdentity == null || !currentIdentity.equals(trusted.identity)
                || !hash(currentPayloadDigest) || !currentPayloadDigest.equals(trusted.nativePayloadDigest)
                || !hash(currentPreprocessingDigest) || !currentPreprocessingDigest.equals(trusted.preprocessingDigest)
                || !hash(currentTemplateDigest) || !currentTemplateDigest.equals(trusted.templateDigest)
                || !POLICY.equals(trusted.policy) || trusted.textBound < 4096 || trusted.templateBound <= 0
                || trusted.imageBound <= 0 || trusted.safetyMargin <= 0
                || (long)trusted.textBound + trusted.templateBound + trusted.imageBound + 1024 + trusted.safetyMargin > 8192) {
            throw new WorkspaceException(ErrorCode.MODEL_UNAVAILABLE,"VISION_TOKEN_EVIDENCE");
        }
    }
    private static boolean hash(String s) { return s != null && s.matches("[0-9a-f]{64}"); }
}
