package com.gymops.leads;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.leads.dto.LeadDtos.ConvertRequest;
import com.gymops.leads.dto.LeadDtos.LeadDetailResponse;
import com.gymops.leads.dto.LeadDtos.StartTrialRequest;
import com.gymops.leads.dto.LeadDtos.TrialResponse;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.member.MemberService;
import com.gymops.member.dto.MemberRequest;
import com.gymops.plan.MembershipPlan;
import com.gymops.plan.PlanService;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.subscription.SubscriptionService;
import com.gymops.subscription.SubscriptionStatus;
import com.gymops.subscription.dto.SubscriptionRequests;
import com.gymops.subscription.dto.SubscriptionResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns prospects into members: starting a (free or paid) trial and converting a lead. Member
 * registration and the subscription purchase are delegated to {@link MemberService} and
 * {@link SubscriptionService}, so every membership rule (plan must be sellable, no overlap, invoice,
 * zero-amount auto-settlement and activation) applies unchanged. One transaction: if the subscription
 * cannot be created, the member registration and the lead change roll back too.
 */
@Service
@RequiredArgsConstructor
public class LeadTrialService {

    private final LeadService leads;
    private final LeadActivityRepository activityRepository;
    private final MemberService memberService;
    private final MemberRepository memberRepository;
    private final SubscriptionService subscriptionService;
    private final PlanService planService;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public TrialResponse startTrial(Long leadId, StartTrialRequest r) {
        Lead lead = leads.loadForStaff(leadId);
        if (!lead.getStatus().canTransitionTo(LeadStatus.TRIAL_ACTIVE)) {
            throw new InvalidStateTransitionException("lead", lead.getStatus(), "start a trial for");
        }
        MembershipPlan plan = planService.load(r.planId());
        if (!plan.isActive()) {
            throw new BusinessRuleException("Plan " + plan.getCode() + " is not available for sale");
        }
        if (lead.getEmail() == null) {
            throw new BusinessRuleException("Add the prospect's email address before starting a trial");
        }
        if (lead.getLastName() == null) {
            throw new BusinessRuleException("Add the prospect's last name before starting a trial");
        }

        Optional<Member> existing = findMemberByEmail(lead.getEmail());
        Long memberId;
        if (existing.isPresent()) {
            Member m = existing.get();
            access.assertMemberAccess(m.getId(), m.getHomeBranch().getId());
            memberId = m.getId();
        } else {
            memberId = memberService.register(new MemberRequest(lead.getBranch().getId(), lead.getFirstName(),
                    lead.getLastName(), lead.getEmail(), lead.getPhone(), null, null, null, null, null, null,
                    "Registered from lead #" + lead.getId() + " (" + lead.getSource() + ")", List.of())).id();
        }

        SubscriptionResponse sub;
        TrialStartGuard.begin(memberId);
        try {
            sub = subscriptionService.subscribe(new SubscriptionRequests.Create(memberId, plan.getId(), r.startDate(), null));
        } finally {
            TrialStartGuard.end();
        }
        lead.startTrial(sub.id(), memberId, sub.endDate());

        boolean paymentDue = sub.status() == SubscriptionStatus.PENDING_PAYMENT;
        Instant now = Instant.now(clock);
        activityRepository.save(LeadActivity.log(lead, LeadActivityType.STATUS_CHANGE,
                "Trial started: %s, %s to %s%s".formatted(plan.getName(), sub.startDate(), sub.endDate(),
                        paymentDue ? " (awaiting payment)" : ""), CurrentUser.get().userId(), now));
        audit.record(AuditEvent.of("LEAD", lead.getId(), "TRIAL_STARTED").branch(lead.getBranch().getId())
                .member(memberId).with("plan", plan.getCode()).with("subscriptionId", sub.id())
                .with("existingMember", existing.isPresent()).with("paymentDue", paymentDue));
        events.publishEvent(new LeadEvents.TrialStarted(lead.getId(), memberId, sub.id()));
        return new TrialResponse(leads.toResponse(lead, now), memberId, sub.id(), sub.status(), sub.startDate(),
                sub.endDate(), sub.price(), sub.invoiceId(), paymentDue, existing.isPresent());
    }

    /** Manually links a lead to an existing member who joined (e.g. bought at the desk before the lead was linked). */
    @Transactional
    public LeadDetailResponse convert(Long leadId, ConvertRequest r) {
        Lead lead = leads.loadForStaff(leadId);
        Member member = memberService.load(r.memberId());
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        convert(lead, member.getId(), "Converted: linked to member " + member.getMemberCode(), false);
        return leads.detailOf(lead);
    }

    /** Shared by the manual action and the auto-conversion listener (runs inside the caller's transaction). */
    void convert(Lead lead, Long memberId, String reason, boolean automatic) {
        LeadStatus from = lead.getStatus();
        Instant now = Instant.now(clock);
        lead.convert(memberId, now);
        activityRepository.save(LeadActivity.log(lead, LeadActivityType.STATUS_CHANGE,
                from + " → " + LeadStatus.CONVERTED + ": " + reason, CurrentUser.idOrNull(), now));
        audit.record(AuditEvent.of("LEAD", lead.getId(), "CONVERTED").branch(lead.getBranch().getId())
                .member(memberId).with("from", from).with("automatic", automatic));
    }

    private Optional<Member> findMemberByEmail(String email) {
        return memberRepository.findOne((root, q, cb) -> cb.equal(cb.lower(root.get("email")), email.toLowerCase()));
    }
}
