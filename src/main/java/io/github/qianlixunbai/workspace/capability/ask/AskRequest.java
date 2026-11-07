package io.github.qianlixunbai.workspace.capability.ask;

import jakarta.validation.constraints.*;

public record AskRequest(@NotBlank @Size(max = 3000) String question,
        @Pattern(regexp = "chat\\.balanced") String profile,
        @Pattern(regexp = "memory-candidate-v1") String outputContract) {
    public AskRequest(String question, String profile) { this(question, profile, null); }
    @Override public String toString() { return "AskRequest[redacted]"; }
}
