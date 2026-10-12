package com.gymops.common.exception;

public class BusinessRuleException extends ApiException {

    public BusinessRuleException(ErrorCode code, String message) {
        super(code, message);
    }

    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }
}
