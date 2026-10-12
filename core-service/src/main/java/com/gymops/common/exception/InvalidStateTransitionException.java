package com.gymops.common.exception;

public class InvalidStateTransitionException extends ApiException {

    public InvalidStateTransitionException(String entity, Object from, String action) {
        super(ErrorCode.INVALID_STATE_TRANSITION, "Cannot " + action + " " + entity + " in status " + from);
    }
}
