package com.gymops.common.exception;

import com.gymops.common.api.ApiError;
import com.gymops.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates every exception into the standard {@link ApiError} envelope. Expected business
 * failures are logged at INFO/WARN without stack traces; unexpected failures at ERROR.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        log.info("Request rejected: code={} message={}", ex.code(), ex.getMessage());
        return build(ex.code(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        List<ApiError.FieldViolation> global = ex.getBindingResult().getGlobalErrors().stream()
                .map(e -> new ApiError.FieldViolation(e.getObjectName(), e.getDefaultMessage(), null))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", request,
                java.util.stream.Stream.concat(violations.stream(), global.stream()).toList());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new ApiError.FieldViolation(
                                r.getMethodParameter().getParameterName(), e.getDefaultMessage(), r.getArgument())))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage(), v.getInvalidValue()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", request, violations);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        String message = switch (ex) {
            case MissingRequestHeaderException m -> "Missing required header '" + m.getHeaderName() + "'";
            case MissingServletRequestParameterException m -> "Missing required parameter '" + m.getParameterName() + "'";
            case MethodArgumentTypeMismatchException m -> "Invalid value for parameter '" + m.getName() + "'";
            default -> "Malformed request body";
        };
        return build(ErrorCode.MALFORMED_REQUEST, message, request, List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Optimistic lock conflict on {}", ex.getPersistentClassName());
        return build(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified by another request. Reload and retry.", request, List.of());
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> handlePessimisticLock(PessimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Lock acquisition failed: {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource is being modified by another request. Retry shortly.", request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return DatabaseConstraints.lookup(ex)
                .map(m -> {
                    log.info("Constraint violation mapped to {}: {}", m.code(), DatabaseConstraints.constraintName(ex).orElse("?"));
                    return build(m.code(), m.message(), request, List.<ApiError.FieldViolation>of());
                })
                .orElseGet(() -> {
                    log.warn("Unmapped data integrity violation: {}", ex.getMostSpecificCause().getMessage());
                    return build(ErrorCode.DUPLICATE_RESOURCE, "Request conflicts with existing data", request, List.of());
                });
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ApiError> handleAccessDenied(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action", request, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(ErrorCode.UNAUTHORIZED, "Authentication required", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, "No endpoint " + request.getMethod() + " " + request.getRequestURI(),
                request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ApiError body = error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage(), request, List.of());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        ApiError body = error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type '" + ex.getContentType() + "' is not supported; use application/json", request, List.of());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** The client does not accept JSON: nothing can be rendered, so answer 406 without a body. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ApiError.FieldViolation toViolation(FieldError fe) {
        Object rejected = fe.getField().toLowerCase().contains("password") ? null : fe.getRejectedValue();
        return new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage(), rejected);
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest request,
                                           List<ApiError.FieldViolation> violations) {
        return ResponseEntity.status(code.status())
                .body(error(code.status(), code.name(), message, request, violations));
    }

    public static ApiError error(HttpStatus status, String code, String message, HttpServletRequest request,
                                 List<ApiError.FieldViolation> violations) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), code, message,
                request.getRequestURI(), MDC.get(CorrelationIdFilter.MDC_KEY), violations);
    }
}
