package com.gymops.pricing;

public enum PriceChangeSource {
    /** Scheduled through the price-change workflow (policy, reason, notice). */
    SCHEDULED,
    /** Immediate edit through PUT /plans/{id}; recorded for the history. */
    DIRECT_EDIT
}
