package com.gymops.attendance;

import com.gymops.common.exception.ErrorCode;

/**
 * Why the shared entry rules refused a member. Used by front-desk check-in (mapped to an API error) and by
 * gate devices (mapped to an access reason code). {@link #metricTag()} keeps the historical
 * {@code gymops.checkins.denied{reason}} tag values stable.
 */
public enum EntryDenial {
    MEMBER_SUSPENDED(ErrorCode.ACCESS_RESTRICTED, "MEMBER_SUSPENDED"),
    MEMBER_INACTIVE(ErrorCode.ACCESS_RESTRICTED, "MEMBER_INACTIVE"),
    ALREADY_INSIDE(ErrorCode.DUPLICATE_CHECK_IN, "DUPLICATE"),
    BRANCH_CLOSED(ErrorCode.ACCESS_RESTRICTED, "BRANCH_CLOSED"),
    NO_ACTIVE_MEMBERSHIP(ErrorCode.MEMBERSHIP_NOT_ACTIVE, "NO_ACTIVE_MEMBERSHIP"),
    MEMBERSHIP_FROZEN(ErrorCode.MEMBERSHIP_NOT_ACTIVE, "NO_ACTIVE_MEMBERSHIP"),
    BRANCH_NOT_INCLUDED(ErrorCode.ACCESS_RESTRICTED, "BRANCH_NOT_INCLUDED"),
    OUTSIDE_PLAN_HOURS(ErrorCode.ACCESS_RESTRICTED, "OUTSIDE_PLAN_HOURS"),
    VISIT_LIMIT(ErrorCode.ACCESS_RESTRICTED, "VISIT_LIMIT"),
    AT_CAPACITY(ErrorCode.ACCESS_RESTRICTED, "AT_CAPACITY");

    private final ErrorCode errorCode;
    private final String metricTag;

    EntryDenial(ErrorCode errorCode, String metricTag) {
        this.errorCode = errorCode;
        this.metricTag = metricTag;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String metricTag() {
        return metricTag;
    }
}
