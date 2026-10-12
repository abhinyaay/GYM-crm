package com.gymops.access;

import com.gymops.attendance.EntryDenial;

/** Machine-readable reason attached to a gate decision (shown on the gate display and in the event log). */
public enum AccessReason {
    OK,
    UNKNOWN_CREDENTIAL,
    CREDENTIAL_PENDING,
    CREDENTIAL_REVOKED,
    CREDENTIAL_SUSPENDED,
    CONSENT_WITHDRAWN,
    BIOMETRICS_DISABLED,
    QR_INVALID,
    QR_EXPIRED,
    QR_REUSED,
    MEMBER_SUSPENDED,
    MEMBER_INACTIVE,
    NO_ACTIVE_MEMBERSHIP,
    FROZEN,
    OUTSIDE_PLAN_HOURS,
    BRANCH_NOT_ALLOWED,
    VISIT_LIMIT,
    DAILY_ENTRY_LIMIT,
    AT_CAPACITY,
    BRANCH_CLOSED,
    ANTI_PASSBACK,
    DIRECTION_NOT_ALLOWED,
    /** The device itself refused entry while offline (reported in a buffered batch). */
    OFFLINE_DENIED;

    /** Maps the shared attendance rule outcome to the gate reason code. */
    public static AccessReason from(EntryDenial denial) {
        return switch (denial) {
            case MEMBER_SUSPENDED -> MEMBER_SUSPENDED;
            case MEMBER_INACTIVE -> MEMBER_INACTIVE;
            case ALREADY_INSIDE -> ANTI_PASSBACK;
            case BRANCH_CLOSED -> BRANCH_CLOSED;
            case NO_ACTIVE_MEMBERSHIP -> NO_ACTIVE_MEMBERSHIP;
            case MEMBERSHIP_FROZEN -> FROZEN;
            case BRANCH_NOT_INCLUDED -> BRANCH_NOT_ALLOWED;
            case OUTSIDE_PLAN_HOURS -> OUTSIDE_PLAN_HOURS;
            case VISIT_LIMIT -> VISIT_LIMIT;
            case DAILY_ENTRY_LIMIT -> DAILY_ENTRY_LIMIT;
            case AT_CAPACITY -> AT_CAPACITY;
        };
    }
}
