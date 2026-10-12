package com.gymops.subscription.dto;

import com.gymops.member.MembershipState;
import java.time.LocalDate;

/** Member-facing snapshot of the membership that currently matters most. */
public record MembershipSummary(MembershipState state, Long subscriptionId, String planCode, String planName,
                                LocalDate startDate, LocalDate endDate, Long daysRemaining) {

    public static MembershipSummary none() {
        return new MembershipSummary(MembershipState.NONE, null, null, null, null, null, null);
    }
}
