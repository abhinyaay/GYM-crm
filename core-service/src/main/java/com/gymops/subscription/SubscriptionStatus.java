package com.gymops.subscription;

import java.util.EnumSet;
import java.util.Set;

/**
 * Subscription lifecycle.
 *
 * <pre>
 *  PENDING_PAYMENT --pay--> ACTIVE (start <= today) | SCHEDULED (future start)
 *  SCHEDULED --start date reached--> ACTIVE
 *  ACTIVE --freeze--> FROZEN --resume / freeze ends--> ACTIVE
 *  ACTIVE --end date passed--> GRACE (plan grace days) --grace over--> EXPIRED
 *  ACTIVE --end date passed, renewal in place / no grace--> EXPIRED
 *  ACTIVE|FROZEN --upgrade paid--> SUPERSEDED
 *  PENDING_PAYMENT|SCHEDULED|ACTIVE|FROZEN|GRACE --cancel--> CANCELLED
 *  PENDING_PAYMENT --unpaid for N days--> CANCELLED
 * </pre>
 */
public enum SubscriptionStatus {
    PENDING_PAYMENT,
    SCHEDULED,
    ACTIVE,
    FROZEN,
    GRACE,
    EXPIRED,
    CANCELLED,
    SUPERSEDED;

    /** Statuses covered by the database no-overlap exclusion constraint. */
    public static final Set<SubscriptionStatus> COMMITTED = EnumSet.of(SCHEDULED, ACTIVE, FROZEN, GRACE);

    /** Statuses that may let a member through the door (subject to dates and plan rules). */
    public static final Set<SubscriptionStatus> ACCESS_GRANTING = EnumSet.of(ACTIVE, GRACE);

    public static final Set<SubscriptionStatus> TERMINAL = EnumSet.of(EXPIRED, CANCELLED, SUPERSEDED);
}
