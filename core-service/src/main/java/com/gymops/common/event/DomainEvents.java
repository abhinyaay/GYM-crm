package com.gymops.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Domain events published synchronously inside the business transaction. Listeners (notifications,
 * cache eviction) therefore commit or roll back together with the change that caused them.
 */
public final class DomainEvents {

    private DomainEvents() {
    }

    public sealed interface DomainEvent permits SubscriptionActivated, SubscriptionFrozen, SubscriptionResumed,
            SubscriptionCancelled, SubscriptionExpired, RenewalReminderDue, PaymentSucceeded, PaymentFailed,
            RefundProcessed, AttendanceMilestone, VisitAutoClosed, TrainerAssigned, SessionScheduled,
            WorkoutPlanAssigned, WorkoutPlanUpdated, ComplaintUpdated, ComplaintEscalated, MemberChanged {
    }

    public record SubscriptionActivated(Long subscriptionId, Long memberId) implements DomainEvent {
    }

    public record SubscriptionFrozen(Long subscriptionId, Long memberId, LocalDate frozenUntil) implements DomainEvent {
    }

    public record SubscriptionResumed(Long subscriptionId, Long memberId) implements DomainEvent {
    }

    public record SubscriptionCancelled(Long subscriptionId, Long memberId, BigDecimal refundAmount) implements DomainEvent {
    }

    public record SubscriptionExpired(Long subscriptionId, Long memberId) implements DomainEvent {
    }

    public record RenewalReminderDue(Long subscriptionId, Long memberId, LocalDate endDate, int daysLeft)
            implements DomainEvent {
    }

    public record PaymentSucceeded(Long paymentId, Long memberId) implements DomainEvent {
    }

    public record PaymentFailed(Long paymentId, Long memberId) implements DomainEvent {
    }

    public record RefundProcessed(Long refundId, Long memberId, BigDecimal amount) implements DomainEvent {
    }

    public record AttendanceMilestone(Long memberId, long totalVisits) implements DomainEvent {
    }

    public record VisitAutoClosed(Long attendanceId, Long memberId) implements DomainEvent {
    }

    public record TrainerAssigned(Long memberId, Long trainerId) implements DomainEvent {
    }

    public record SessionScheduled(Long sessionId, Long memberId, Long trainerId) implements DomainEvent {
    }

    public record WorkoutPlanAssigned(Long workoutPlanId, Long memberId) implements DomainEvent {
    }

    public record WorkoutPlanUpdated(Long workoutPlanId, Long memberId) implements DomainEvent {
    }

    public record ComplaintUpdated(Long complaintId, String status) implements DomainEvent {
    }

    public record ComplaintEscalated(Long complaintId, Long branchId, int level) implements DomainEvent {
    }

    /** Any change that affects the cached member profile (membership status included). */
    public record MemberChanged(Long memberId) implements DomainEvent {
    }
}
