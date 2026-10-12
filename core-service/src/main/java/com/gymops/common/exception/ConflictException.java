package com.gymops.common.exception;

public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String message) {
        super(code, message);
    }

    public ConflictException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE, message);
    }
}
