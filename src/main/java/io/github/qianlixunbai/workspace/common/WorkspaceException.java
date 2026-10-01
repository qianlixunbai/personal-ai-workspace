package io.github.qianlixunbai.workspace.common;

public final class WorkspaceException extends RuntimeException {
    private final ApiError error;

    public WorkspaceException(ErrorCode code, String phase) {
        super(code.name()); // Never retain a provider body, prompt or exception cause.
        error = ApiError.of(code, phase);
    }

    public ApiError error() { return error; }
}
