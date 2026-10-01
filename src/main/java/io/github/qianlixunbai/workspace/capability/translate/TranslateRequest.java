package io.github.qianlixunbai.workspace.capability.translate;

import jakarta.validation.constraints.*;

public record TranslateRequest(@NotBlank @Size(max = 16000) String text,
                               @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}") String sourceLanguage,
                               @NotBlank @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}") String targetLanguage,
                               @Pattern(regexp = "translate\\.fast") String profile) {
    @Override public String toString() { return "TranslateRequest[redacted]"; }
}
