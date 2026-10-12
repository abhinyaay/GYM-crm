package com.gymops.leads;

import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.subscription.SubscriptionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Translates lead events into outbox notifications (inside the publishing transaction). */
@Component
@RequiredArgsConstructor
public class LeadNotificationHandler {

    private final NotificationService notifications;
    private final LeadRepository leadRepository;
    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;

    @EventListener
    public void on(LeadEvents.LeadAssigned e) {
        leadRepository.findById(e.leadId()).ifPresent(l -> notifications.notifyUsers(List.of(e.ownerUserId()),
                NotificationType.LEAD_ASSIGNED, "New lead assigned: " + l.fullName(),
                "%s (%s, %s) has been assigned to you. Lead #%d.".formatted(l.fullName(), l.getSource(),
                        l.getInterest(), l.getId())));
    }

    @EventListener
    public void on(LeadEvents.TrialStarted e) {
        var member = memberRepository.findById(e.memberId()).orElse(null);
        var sub = subscriptionRepository.findById(e.subscriptionId()).orElse(null);
        if (member == null || sub == null) {
            return;
        }
        notifications.notifyMember(member, NotificationType.TRIAL_STARTED, "Your trial is booked",
                "Your %s at %s runs from %s to %s. See you at the gym!".formatted(sub.getPlan().getName(),
                        sub.getBranch().getName(), sub.getStartDate(), sub.getEndDate()),
                "TRIAL_STARTED:" + e.subscriptionId());
    }

    @EventListener
    public void on(LeadEvents.TrialEnding e) {
        memberRepository.findById(e.memberId()).ifPresent(m -> notifications.notifyMember(m,
                NotificationType.TRIAL_ENDING, "Your trial ends soon",
                "Your trial ends on %s. Talk to the front desk to keep training with a membership.".formatted(e.endsOn()),
                "TRIAL_ENDING:" + e.leadId() + ":" + e.endsOn()));
    }

    @EventListener
    public void on(LeadEvents.FollowUpsDue e) {
        String overdue = e.overdue() > 0 ? " (%d overdue)".formatted(e.overdue()) : "";
        notifications.notifyUsers(List.of(e.ownerUserId()), NotificationType.LEAD_FOLLOW_UP_DUE,
                "Lead follow-ups due today",
                "You have %d lead follow-up%s due%s.".formatted(e.due(), e.due() == 1 ? "" : "s", overdue));
    }
}
