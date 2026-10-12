package com.gymops.leads;

import java.time.LocalDate;

/**
 * Events of the leads module. Like the core domain events they are published synchronously inside
 * the business transaction, so the notifications they cause commit or roll back with the change.
 */
public final class LeadEvents {

    private LeadEvents() {
    }

    public record LeadAssigned(Long leadId, Long ownerUserId) {
    }

    public record TrialStarted(Long leadId, Long memberId, Long subscriptionId) {
    }

    public record TrialEnding(Long leadId, Long memberId, LocalDate endsOn) {
    }

    public record FollowUpsDue(Long ownerUserId, long due, long overdue, LocalDate date) {
    }

    /** A public enquiry carried a member's referral code (handled by the growth module after commit). */
    public record ReferralCodeSubmitted(Long leadId, String referralCode) {
    }
}
