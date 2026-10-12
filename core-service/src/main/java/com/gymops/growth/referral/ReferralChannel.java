package com.gymops.growth.referral;

/** How the referee arrived with the code. */
public enum ReferralChannel {
    /** Shared link: public enquiry form ({@code /enquire?ref=CODE}). */
    LINK,
    /** Entered by staff at the front desk. */
    DESK,
    /** Claimed by the member after self-registration. */
    SELF_SIGNUP
}
