package com.gymops.payment.credit;

public enum CreditEntryType {
    /** Goodwill / promotional credit granted by staff. */
    ISSUED,
    /** A refund paid out to store credit instead of the original instrument. */
    REFUND,
    /** Credit spent on an invoice. */
    PAYMENT,
    /** Manual correction by staff (positive or negative). */
    ADJUSTMENT
}
