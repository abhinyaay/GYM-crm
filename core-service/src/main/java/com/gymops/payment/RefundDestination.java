package com.gymops.payment;

/** Where refunded money goes: back to the original instrument, or onto the member's store credit. */
public enum RefundDestination {
    ORIGINAL_METHOD,
    STORE_CREDIT
}
