package io.github.qianlixunbai.workspace.model;

import jakarta.validation.constraints.*;

public record ModelProfile(@NotBlank String id, @NotBlank String provider,
                           @NotBlank String model, @NotNull Locality locality,
                           @NotBlank String version, @Min(1024) @Max(32768) int contextBudget,
                           @Min(64) @Max(8192) int outputBudget,
                           @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
                           @Min(1) @Max(16000) int maxTextCharacters) {
    public enum Locality { LOCAL, CLOUD }
    public record PublicProfile(String id, String version, Locality locality) {}
    public PublicProfile publicInfo() { return new PublicProfile(id, version, locality); }
}
