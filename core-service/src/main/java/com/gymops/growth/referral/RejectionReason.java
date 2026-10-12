package com.gymops.growth.referral;

public enum RejectionReason {
    /** Referee is the referrer (same member, email or phone). */
    SELF_REFERRAL,
    /** Referee already held a paid membership before the referral. */
    NOT_NEW
}
