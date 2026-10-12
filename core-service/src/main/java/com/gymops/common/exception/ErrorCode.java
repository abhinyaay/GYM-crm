package com.gymops.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes. Clients branch on {@code code}, never on the message text.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    FEATURE_DISABLED(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    SUBSCRIPTION_OVERLAP(HttpStatus.CONFLICT),
    DUPLICATE_CHECK_IN(HttpStatus.CONFLICT),
    CLASS_FULL(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY),
    MEMBERSHIP_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_ENTITY),
    ACCESS_RESTRICTED(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
