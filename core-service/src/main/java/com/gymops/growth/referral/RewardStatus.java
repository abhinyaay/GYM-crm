package com.gymops.growth.referral;

/**
 * Reward lifecycle: HELD (inside the anti-fraud hold) -> AVAILABLE (credit posted / code issued) or
 * HELD -> REVERSED (qualifying payment refunded or membership cancelled during the hold). Both end states are final.
 */
public enum RewardStatus {
    HELD,
    AVAILABLE,
    REVERSED
}
