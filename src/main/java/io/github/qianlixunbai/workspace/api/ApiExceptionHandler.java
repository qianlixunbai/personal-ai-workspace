package io.github.qianlixunbai.workspace.api;

import io.github.qianlixunbai.workspace.common.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(WorkspaceException.class)
    ResponseEntity<ApiError> controlled(WorkspaceException failure) {
        HttpStatus status = switch (failure.error().code()) {
            case WEB_TARGET_INVALID -> HttpStatus.BAD_REQUEST;
            case WEB_DISABLED, WEB_TARGET_NOT_PUBLIC, WEB_REDIRECT_DENIED -> HttpStatus.FORBIDDEN;
            case WEB_FETCH_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case WEB_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case WEB_RESPONSE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case WEB_CONTENT_TYPE_UNSUPPORTED -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case WEB_DNS_FAILED, WEB_TLS_FAILED, WEB_CONTENT_INVALID, WEB_FETCH_FAILED -> HttpStatus.BAD_GATEWAY;
            case KNOWLEDGE_SEARCH_INVALID, KNOWLEDGE_QUERY_TOO_COMPLEX, KNOWLEDGE_INVALID_SOURCE, KNOWLEDGE_UNSUPPORTED_TYPE, KNOWLEDGE_INVALID_UTF8,
                    KNOWLEDGE_BACKUP_INVALID, KNOWLEDGE_BACKUP_UNSUPPORTED -> HttpStatus.BAD_REQUEST;
            case KNOWLEDGE_SOURCE_TOO_LARGE, KNOWLEDGE_BACKUP_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case KNOWLEDGE_INDEX_LIMIT_EXCEEDED, KNOWLEDGE_LIMIT_EXCEEDED, KNOWLEDGE_DUPLICATE_SOURCE, KNOWLEDGE_REVISION_CONFLICT,
                    KNOWLEDGE_DELETE_INCOMPLETE, KNOWLEDGE_BACKUP_CONFLICT, KNOWLEDGE_RESTORE_TARGET_NOT_EMPTY -> HttpStatus.CONFLICT;
            case KNOWLEDGE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case KNOWLEDGE_QUEUE_FULL -> HttpStatus.TOO_MANY_REQUESTS;
            case KNOWLEDGE_INDEX_NOT_READY, KNOWLEDGE_INDEX_UNAVAILABLE, KNOWLEDGE_INDEX_REBUILD_FAILED,
                    KNOWLEDGE_STORAGE_UNAVAILABLE, KNOWLEDGE_SCHEMA_UNSUPPORTED, KNOWLEDGE_INTERRUPTED -> HttpStatus.SERVICE_UNAVAILABLE;
            case WORKSPACE_BACKUP_INVALID, WORKSPACE_BACKUP_UNSUPPORTED, INVALID_REQUEST, MEMORY_INVALID, MEMORY_BACKUP_INVALID, MEMORY_BACKUP_UNSUPPORTED, CONVERSATION_INVALID -> HttpStatus.BAD_REQUEST;
            case WORKSPACE_BACKUP_TOO_LARGE, MEMORY_BACKUP_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case POLICY_DENIED -> HttpStatus.FORBIDDEN;
            case TASK_NOT_FOUND, MEMORY_NOT_FOUND, CONVERSATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case WORKSPACE_BACKUP_CONFLICT, WORKSPACE_RESTORE_TARGET_NOT_EMPTY, MEMORY_REVISION_CONFLICT, MEMORY_LIMIT_EXCEEDED, MEMORY_SELECTION_STALE, MEMORY_RESTORE_TARGET_NOT_EMPTY,
                    CONVERSATION_CONFLICT, CONVERSATION_LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
            case MEMORY_STORAGE_UNAVAILABLE, MEMORY_SCHEMA_UNSUPPORTED, CONVERSATION_STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case QUEUE_FULL -> HttpStatus.TOO_MANY_REQUESTS;
            case PROVIDER_UNAVAILABLE, MODEL_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TASK_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(failure.error());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ApiError> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(ApiError.of(ErrorCode.INVALID_REQUEST, "HTTP"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(Exception ignored) {
        return ResponseEntity.status(404).body(ApiError.of(ErrorCode.INVALID_REQUEST, "HTTP"));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(Exception ignored) {
        return ResponseEntity.status(405).body(ApiError.of(ErrorCode.INVALID_REQUEST, "HTTP"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ignored) {
        return ResponseEntity.internalServerError().body(ApiError.of(ErrorCode.INTERNAL_ERROR, "HTTP"));
    }
}
