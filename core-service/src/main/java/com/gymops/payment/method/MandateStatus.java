package com.gymops.payment.method;

/** State of the recurring-debit (e-mandate) authorisation attached to a saved method. */
public enum MandateStatus {
    NONE,
    PENDING,
    ACTIVE,
    PAUSED,
    REVOKED
}
