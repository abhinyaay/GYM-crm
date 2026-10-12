package com.gymops.notification;

import com.gymops.branch.StaffAssignmentRepository;
import com.gymops.common.event.DomainEvents;
import com.gymops.complaint.Complaint;
import com.gymops.complaint.ComplaintRepository;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.payment.Payment;
import com.gymops.payment.PaymentRepository;
import com.gymops.subscription.Subscription;
import com.gymops.subscription.SubscriptionRepository;
import com.gymops.trainer.TrainerRepository;
import com.gymops.trainer.TrainingSessionRepository;
import com.gymops.user.RoleName;
import com.gymops.user.UserRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Translates domain events into outbox notifications. Listeners run synchronously inside the
 * publishing transaction, so notifications are committed atomically with the change.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventHandler {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");

    private final NotificationService notifications;
    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final com.gymops.payment.RefundRepository refundRepository;
    private final TrainerRepository trainerRepository;
    private final TrainingSessionRepository sessionRepository;
    private final ComplaintRepository complaintRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final UserRepository userRepository;

    @EventListener
    public void on(DomainEvents.SubscriptionActivated e) {
        Subscription s = subscriptionRepository.findById(e.subscriptionId()).orElseThrow();
        notifyMember(e.memberId(), NotificationType.SUBSCRIPTION_ACTIVATED, "Your membership is confirmed",
                "%s membership runs from %s to %s.".formatted(s.getPlan().getName(), s.getStartDate(), s.getEndDate()),
                "SUB_ACTIVATED:" + s.getId());
    }

    @EventListener
    public void on(DomainEvents.SubscriptionFrozen e) {
        notifyMember(e.memberId(), NotificationType.SUBSCRIPTION_FROZEN, "Membership frozen",
                "Your membership is frozen until %s; your end date has been extended accordingly.".formatted(e.frozenUntil()),
                null);
    }

    @EventListener
    public void on(DomainEvents.SubscriptionResumed e) {
        notifyMember(e.memberId(), NotificationType.SUBSCRIPTION_RESUMED, "Welcome back",
                "Your membership is active again.", null);
    }

    @EventListener
    public void on(DomainEvents.SubscriptionCancelled e) {
        String refund = e.refundAmount().signum() > 0 ? " A refund of " + e.refundAmount() + " has been issued." : "";
        notifyMember(e.memberId(), NotificationType.SUBSCRIPTION_CANCELLED, "Membership cancelled",
                "Your membership has been cancelled." + refund, "SUB_CANCELLED:" + e.subscriptionId());
    }

    @EventListener
    public void on(DomainEvents.SubscriptionExpired e) {
        notifyMember(e.memberId(), NotificationType.MEMBERSHIP_EXPIRED, "Your membership has expired",
                "Renew any time at the front desk or in the app to continue training.", "SUB_EXPIRED:" + e.subscriptionId());
    }

    @EventListener
    public void on(DomainEvents.RenewalReminderDue e) {
        boolean lastDay = e.daysLeft() <= 1;
        notifyMember(e.memberId(), lastDay ? NotificationType.MEMBERSHIP_EXPIRY_WARNING : NotificationType.RENEWAL_REMINDER,
                lastDay ? "Your membership ends tomorrow" : "Time to renew",
                "Your membership ends on %s. Renew now to keep training without interruption.".formatted(e.endDate()),
                "RENEWAL_REMINDER:" + e.subscriptionId() + ":" + e.daysLeft());
    }

    @EventListener
    public void on(DomainEvents.PaymentSucceeded e) {
        Payment p = paymentRepository.findById(e.paymentId()).orElseThrow();
        notifyMember(e.memberId(), NotificationType.PAYMENT_CONFIRMATION, "Payment received",
                "We received %s %s for invoice %s (ref %s).".formatted(p.getCurrency(), p.getAmount(),
                        p.getInvoice().getInvoiceNumber(), p.getGatewayReference()),
                "PAYMENT_OK:" + p.getId());
    }

    @EventListener
    public void on(DomainEvents.PaymentFailed e) {
        Payment p = paymentRepository.findById(e.paymentId()).orElseThrow();
        if (p.isAutopay()) {
            return;   // autopay sends its own notice, including when the debit will be retried
        }
        notifyMember(e.memberId(), NotificationType.PAYMENT_FAILED, "Payment failed",
                "Your payment for invoice %s could not be processed (%s). Please try again."
                        .formatted(p.getInvoice().getInvoiceNumber(), p.getFailureReason()),
                "PAYMENT_FAILED:" + p.getId());
    }

    @EventListener
    public void on(DomainEvents.RefundProcessed e) {
        boolean toCredit = refundRepository.findById(e.refundId())
                .map(r -> r.getDestination() == com.gymops.payment.RefundDestination.STORE_CREDIT).orElse(false);
        notifyMember(e.memberId(), NotificationType.REFUND_PROCESSED, "Refund processed",
                "A refund of %s has been issued to your %s.".formatted(e.amount(),
                        toCredit ? "store credit" : "original payment method"),
                "REFUND:" + e.refundId());
    }

    @EventListener
    public void on(DomainEvents.AttendanceMilestone e) {
        notifyMember(e.memberId(), NotificationType.ATTENDANCE_MILESTONE, "Milestone unlocked",
                "Congratulations on your %d%s visit!".formatted(e.totalVisits(), suffix(e.totalVisits())),
                "MILESTONE:" + e.memberId() + ":" + e.totalVisits());
    }

    @EventListener
    public void on(DomainEvents.VisitAutoClosed e) {
        notifyMember(e.memberId(), NotificationType.VISIT_AUTO_CLOSED, "Remember to check out",
                "Your last visit was closed automatically because no check-out was recorded.", "AUTO_CLOSE:" + e.attendanceId());
    }

    @EventListener
    public void on(DomainEvents.TrainerAssigned e) {
        String trainer = trainerRepository.findById(e.trainerId()).map(t -> t.getUser().getFullName()).orElse("your trainer");
        notifyMember(e.memberId(), NotificationType.TRAINER_ASSIGNED, "Meet your trainer",
                trainer + " is now your personal trainer.", null);
        trainerRepository.findById(e.trainerId()).ifPresent(t -> notifications.notifyUsers(List.of(t.getUser().getId()),
                NotificationType.TRAINER_ASSIGNED, "New client assigned", "A new member has been assigned to you."));
    }

    @EventListener
    public void on(DomainEvents.SessionScheduled e) {
        sessionRepository.findById(e.sessionId()).ifPresent(s -> notifyMember(e.memberId(), NotificationType.SESSION_SCHEDULED,
                "Training session booked", "Session with %s on %s.".formatted(s.getTrainer().getUser().getFullName(),
                        TIME.format(s.getStartsAt().atZone(ZoneId.of(s.getBranch().getTimezone())))),
                "SESSION:" + s.getId()));
    }

    @EventListener
    public void on(DomainEvents.WorkoutPlanAssigned e) {
        notifyMember(e.memberId(), NotificationType.WORKOUT_PLAN_ASSIGNED, "New workout plan",
                "Your trainer has assigned you a new workout plan.", "WORKOUT_ASSIGNED:" + e.workoutPlanId());
    }

    @EventListener
    public void on(DomainEvents.WorkoutPlanUpdated e) {
        notifyMember(e.memberId(), NotificationType.WORKOUT_PLAN_UPDATED, "Workout plan updated",
                "Your trainer has updated your workout plan.", null);
    }

    @EventListener
    public void on(DomainEvents.ComplaintUpdated e) {
        Complaint c = complaintRepository.findById(e.complaintId()).orElseThrow();
        notifications.notifyUsers(List.of(c.getRaisedBy()), NotificationType.COMPLAINT_UPDATED,
                "Update on " + c.getTicketNumber(), "Your complaint is now " + e.status() + ".");
    }

    @EventListener
    public void on(DomainEvents.ComplaintEscalated e) {
        Complaint c = complaintRepository.findById(e.complaintId()).orElseThrow();
        List<Long> managers = staffAssignmentRepository.findUserIdsByBranchId(e.branchId()).stream()
                .filter(id -> userRepository.findById(id).map(u -> u.hasRole(RoleName.MANAGER)).orElse(false))
                .toList();
        notifications.notifyUsers(managers, NotificationType.COMPLAINT_ESCALATED,
                "Escalated: " + c.getTicketNumber(),
                "Complaint '%s' escalated to level %d (priority %s).".formatted(c.getSubject(), e.level(), c.getPriority()));
    }

    private void notifyMember(Long memberId, NotificationType type, String title, String body, String dedupKey) {
        Member member = memberRepository.findById(memberId).orElse(null);
        if (member != null) {
            notifications.notifyMember(member, type, title, body, dedupKey);
        }
    }

    private static String suffix(long n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return "th";
        }
        return switch ((int) (n % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }
}
