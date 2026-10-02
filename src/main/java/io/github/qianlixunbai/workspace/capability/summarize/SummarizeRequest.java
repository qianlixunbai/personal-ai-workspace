package io.github.qianlixunbai.workspace.capability.summarize;

import jakarta.validation.constraints.*;

public record SummarizeRequest(@NotBlank @Size(max = 6000) String text,
        @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}") String targetLanguage,
        @Pattern(regexp = "summarize\\.fast") String profile) {
    @Override public String toString() { return "SummarizeRequest[redacted]"; }
}
