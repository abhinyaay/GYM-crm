package com.gymops.pricing;

/** What a plan price change means for members who already hold the plan. */
public enum ExistingMemberPolicy {
    /** Existing members renew at the new price from their next renewal on/after the effective date (with notice). */
    NEW_PRICE_AT_RENEWAL,
    /** Existing members keep their current price until a protection date; new sales pay the new price at once. */
    PROTECT_EXISTING
}
