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
            case INVALID_REQUEST, MEMORY_INVALID -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case POLICY_DENIED -> HttpStatus.FORBIDDEN;
            case TASK_NOT_FOUND, MEMORY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MEMORY_REVISION_CONFLICT, MEMORY_LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
            case MEMORY_STORAGE_UNAVAILABLE, MEMORY_SCHEMA_UNSUPPORTED -> HttpStatus.SERVICE_UNAVAILABLE;
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
