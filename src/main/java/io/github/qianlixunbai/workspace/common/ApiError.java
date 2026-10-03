package io.github.qianlixunbai.workspace.common;

public record ApiError(ErrorCode code, String message, String phase) {
    public static ApiError of(ErrorCode code, String phase) {
        String message = switch (code) {
            case PROVIDER_UNAVAILABLE -> "Local provider is unavailable.";
            case MODEL_UNAVAILABLE -> "The configured local model is not installed.";
            case TASK_CANCELLED -> "Task was cancelled.";
            case TASK_TIMEOUT -> "Task exceeded its time budget.";
            case QUEUE_FULL -> "Task capacity is full. Retry later.";
            case INVALID_REQUEST -> "Request is invalid or exceeds its budget.";
            case POLICY_DENIED -> "Execution is denied by local policy.";
            case PROVIDER_RESPONSE_INVALID -> "Local provider returned an invalid response.";
            case INTERNAL_ERROR -> "Runtime could not complete the request.";
            case UNAUTHORIZED -> "Local client authentication is required.";
            case TASK_NOT_FOUND -> "Task does not exist or has expired.";
            case MEMORY_NOT_FOUND -> "Memory item does not exist.";
            case MEMORY_REVISION_CONFLICT -> "Memory revision has changed. Read the item again.";
            case MEMORY_LIMIT_EXCEEDED -> "Memory capacity or size limit exceeded.";
            case MEMORY_INVALID -> "Memory request is invalid.";
            case MEMORY_STORAGE_UNAVAILABLE -> "Memory storage is unavailable.";
            case MEMORY_SCHEMA_UNSUPPORTED -> "Memory schema version is unsupported.";
            case MEMORY_SELECTION_STALE -> "Selected Memory changed. Review and select Memory again.";
        };
        return new ApiError(code, message, phase);
    }
}
