package com.gymops.member;

/** Derived, member-facing view of whether the member currently holds a valid membership. */
public enum MembershipState {
    ACTIVE,
    FROZEN,
    GRACE,
    PENDING_PAYMENT,
    SCHEDULED,
    NONE
}
