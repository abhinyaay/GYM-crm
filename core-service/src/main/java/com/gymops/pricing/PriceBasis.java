package com.gymops.pricing;

/** Why a subscription term costs what it costs. */
public enum PriceBasis {
    /** The plan's list price on the invoice date. */
    LIST,
    /** A manager-approved custom price (corporate / negotiated rate). */
    NEGOTIATED,
    /** Existing-member price protection after a price change ("grandfathered"). */
    PRICE_PROTECTED,
    /** An autopay renewal kept at the member's current price because the rise was not notified in time. */
    NOTICE_HELD
}
