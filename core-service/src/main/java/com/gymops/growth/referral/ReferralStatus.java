package com.gymops.growth.referral;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Referral state machine; {@link #canTransitionTo} is the single source of truth.
 *
 * <pre>
 *   PENDING   -> CONVERTED  referee's first paid (non-trial) membership activated; rewards are HELD
 *   PENDING   -> REJECTED   a fraud guard failed (self-referral, referee not new)
 *   CONVERTED -> REVERSED   the qualifying payment was refunded or the membership cancelled within the hold
 * </pre>
 */
public enum ReferralStatus {
    PENDING,
    CONVERTED,
    REVERSED,
    REJECTED;

    private static final Map<ReferralStatus, Set<ReferralStatus>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(CONVERTED, REJECTED),
            CONVERTED, EnumSet.of(REVERSED),
            REVERSED, EnumSet.noneOf(ReferralStatus.class),
            REJECTED, EnumSet.noneOf(ReferralStatus.class));

    public boolean canTransitionTo(ReferralStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }
}
