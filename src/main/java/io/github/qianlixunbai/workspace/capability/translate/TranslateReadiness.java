package io.github.qianlixunbai.workspace.capability.translate;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.qianlixunbai.workspace.common.ErrorCode;

/** Capability availability only; neither provider identity nor model diagnostics cross this boundary. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranslateReadiness(boolean available, Error error) {
    public record Error(ErrorCode code) {}
}
