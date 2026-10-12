package com.gymops.payment;

public enum InvoiceLineType {
    PLAN_FEE,
    JOINING_FEE,
    PRORATION_CREDIT,
    /** Negotiated price, price protection or notice hold: list price minus what the member pays (negative). */
    PRICE_ADJUSTMENT
}
