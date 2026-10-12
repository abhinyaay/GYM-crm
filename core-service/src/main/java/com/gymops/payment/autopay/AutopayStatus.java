package com.gymops.payment.autopay;

public enum AutopayStatus {
    /** Pre-debit notice sent; waiting for the debit time. */
    SCHEDULED,
    /** Debit submitted; the provider has not reported the outcome yet (settled by webhook). */
    PROCESSING,
    SUCCEEDED,
    FAILED,
    /** Invoice was settled another way before the debit time. */
    SKIPPED,
    /** Autopay switched off, method removed or renewal cancelled before the debit. */
    CANCELLED
}
