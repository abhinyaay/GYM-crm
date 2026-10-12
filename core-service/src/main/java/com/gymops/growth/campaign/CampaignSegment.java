package com.gymops.growth.campaign;

/** Audience segments a retention campaign can target. */
public enum CampaignSegment {
    /** Members whose rule-based churn-risk band (analytics service) is at or above {@code churnBand}. */
    CHURN_RISK,
    /** Active memberships ending within {@code segmentDays} days, autopay off and no renewal bought yet. */
    EXPIRING_NO_AUTOPAY,
    /** Memberships that expired between {@code segmentDays} and {@code segmentDaysMax} days ago, nothing current. */
    LAPSED,
    /** Active members with no check-in in the last {@code segmentDays} days. */
    NO_RECENT_VISIT,
    /** Trials that ended within the last {@code segmentDays} days without a membership purchase. */
    TRIAL_NOT_CONVERTED
}
