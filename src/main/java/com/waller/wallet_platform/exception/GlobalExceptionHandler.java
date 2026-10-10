package com.waller.wallet_platform.exception;

import java.util.LinkedHashMap;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import com.waller.wallet_platform.model.response.ApiResponse;

import lombok.extern.slf4j.Slf4j;

// Returns every controller error in the ApiResponse shape; Spring Boot's default error body omits exception messages
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String VALIDATION_FAILED = "Validation failed";
    private static final String MALFORMED_BODY = "Malformed request body";
    private static final String INTERNAL_ERROR = "Internal server error";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<?>> handleResponseStatus(ResponseStatusException e) {
        String message = e.getReason() != null ? e.getReason() : defaultMessage(e.getStatusCode());
        return build(e.getStatusCode(), ApiResponse.error(message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException e) {
        LinkedHashMap<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, ApiResponse.error(VALIDATION_FAILED, fieldErrors));
    }

    // @Min/@Max etc. on @RequestParam / @PathVariable
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<?>> handleParameterValidation(HandlerMethodValidationException e) {
        LinkedHashMap<String, String> parameterErrors = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(result -> parameterErrors.putIfAbsent(
                parameterName(result.getMethodParameter()),
                result.getResolvableErrors().getFirst().getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, ApiResponse.error(VALIDATION_FAILED, parameterErrors));
    }

    // Prefers the annotation's name: reflection names are null when compiled without -parameters (e.g. VS Code's compiler)
    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.name().isEmpty()) {
            return pathVariable.name();
        }
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }

    // e.g. /posting/abc where a number is expected
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, ApiResponse.error("Invalid value for parameter '" + e.getName() + "'"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, ApiResponse.error(MALFORMED_BODY));
    }

    @ExceptionHandler(DataExistException.class)
    public ResponseEntity<ApiResponse<?>> handleDataExist(DataExistException e) {
        return build(HttpStatus.CONFLICT, ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler({ InvalidDataException.class, InvalidPasswordException.class })
    public ResponseEntity<ApiResponse<?>> handleInvalidData(RuntimeException e) {
        return build(HttpStatus.BAD_REQUEST, ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, ApiResponse.error(e.getMessage()));
    }

    // Method security (@PreAuthorize) throws these inside controllers; without handlers the catch-all would turn them into 500s
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccessDenied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, ApiResponse.error(defaultMessage(HttpStatus.FORBIDDEN)));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<?>> handleAuthentication(AuthenticationException e) {
        return build(HttpStatus.UNAUTHORIZED, ApiResponse.error(defaultMessage(HttpStatus.UNAUTHORIZED)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleOther(Exception e) {
        // Spring MVC's own exceptions (405, 404 for unknown paths, 415, ...) carry their status
        if (e instanceof ErrorResponse errorResponse) {
            return build(errorResponse.getStatusCode(), ApiResponse.error(defaultMessage(errorResponse.getStatusCode())));
        }
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ApiResponse.error(INTERNAL_ERROR));
    }

    private static ResponseEntity<ApiResponse<?>> build(HttpStatusCode status, ApiResponse<?> body) {
        return ResponseEntity.status(status).body(body);
    }

    private static String defaultMessage(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.getReasonPhrase() : String.valueOf(status.value());
    }

}
