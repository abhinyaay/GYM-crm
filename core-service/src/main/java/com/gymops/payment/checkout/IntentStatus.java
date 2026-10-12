package com.gymops.payment.checkout;

public enum IntentStatus {
    CREATED,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    /** Paid after the invoice was settled another way; the duplicate was refunded. */
    CANCELLED
}
