package io.github.qianlixunbai.workspace.common;

public record ApiError(ErrorCode code, String message, String phase) {
    public static ApiError of(ErrorCode code, String phase) {
        String message = switch (code) {
            case WORKSPACE_BACKUP_INVALID -> "Workspace backup is invalid or damaged.";
            case WORKSPACE_BACKUP_UNSUPPORTED -> "Workspace backup version is unsupported.";
            case WORKSPACE_BACKUP_TOO_LARGE -> "Workspace backup exceeds its size budget.";
            case WORKSPACE_BACKUP_CONFLICT -> "Wait for pending turns to finish or Cancel before backing up.";
            case WORKSPACE_EXPORT_FAILED -> "Workspace export could not be completed.";
            case WORKSPACE_RESTORE_FAILED -> "Workspace restore could not be confirmed.";
            case WORKSPACE_RESTORE_TARGET_NOT_EMPTY -> "Restore requires a new or empty data directory.";
            case CONVERSATION_NOT_FOUND -> "Conversation or turn does not exist.";
            case CONVERSATION_INVALID -> "Conversation request is invalid.";
            case CONVERSATION_CONFLICT -> "Conversation or turn state does not allow this operation.";
            case CONVERSATION_LIMIT_EXCEEDED -> "Conversation capacity or size limit exceeded.";
            case CONVERSATION_STORAGE_UNAVAILABLE -> "Conversation storage is unavailable.";
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
            case MEMORY_BACKUP_INVALID -> "Memory backup is invalid or damaged.";
            case MEMORY_BACKUP_UNSUPPORTED -> "Memory backup version is unsupported.";
            case MEMORY_BACKUP_TOO_LARGE -> "Memory backup exceeds its size budget.";
            case MEMORY_RESTORE_TARGET_NOT_EMPTY -> "Restore requires a new or empty data directory.";
            case MEMORY_EXPORT_FAILED -> "Memory export could not be completed.";
            case MEMORY_RESTORE_FAILED -> "Memory restore could not be completed.";
        };
        return new ApiError(code, message, phase);
    }
}
