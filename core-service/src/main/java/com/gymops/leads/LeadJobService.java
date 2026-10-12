package com.gymops.leads;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-item, idempotent steps of the lead jobs; each runs in its own transaction. */
@Service
@RequiredArgsConstructor
public class LeadJobService {

    static final String STALE_TRIAL_TASK = "Trial ended — call to convert";

    private final LeadRepository repository;
    private final LeadActivityRepository activityRepository;
    private final LeadService leads;
    private final LeadsProperties props;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Notifies every owner with follow-ups due today (or overdue); at most one digest per owner per day. */
    @Transactional
    public int sendFollowUpDigests() {
        LocalDate today = LocalDate.now(clock);
        Instant startOfToday = today.atStartOfDay(clock.getZone()).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        int sent = 0;
        for (LeadActivityRepository.OwnerDue row : activityRepository.countDueByOwner(startOfTomorrow, startOfToday)) {
            if (!repository.digestSentSince(row.getOwnerUserId(), startOfToday)) {
                events.publishEvent(new LeadEvents.FollowUpsDue(row.getOwnerUserId(), row.getDue(), row.getOverdue(), today));
                sent++;
            }
        }
        return sent;
    }

    /** Trial ends in {@code trialEndingNoticeDays}: remind the member and give the owner a call task (once). */
    @Transactional
    public boolean remindTrialEnding(Long leadId) {
        Lead lead = repository.findById(leadId).orElse(null);
        LocalDate noticeDate = LocalDate.now(clock).plusDays(props.trialEndingNoticeDays());
        if (lead == null || lead.getStatus() != LeadStatus.TRIAL_ACTIVE || !noticeDate.equals(lead.getTrialEndsOn())) {
            return false;
        }
        events.publishEvent(new LeadEvents.TrialEnding(lead.getId(), lead.getMemberId(), lead.getTrialEndsOn()));
        String task = "Trial ends on " + lead.getTrialEndsOn() + " — call to offer a membership";
        Instant due = lead.getTrialEndsOn().atTime(LocalTime.of(props.followUpHour(), 0)).atZone(clock.getZone()).toInstant();
        return raiseTaskOnce(lead, task, due, "TRIAL_ENDING_TASK");
    }

    /** Trial ended {@code staleTrialDays} ago without a conversion: keep the status, raise one call task. */
    @Transactional
    public boolean flagStaleTrial(Long leadId) {
        Lead lead = repository.findById(leadId).orElse(null);
        LocalDate cutoff = LocalDate.now(clock).minusDays(props.staleTrialDays());
        if (lead == null || lead.getStatus() != LeadStatus.TRIAL_ACTIVE || lead.getTrialEndsOn() == null
                || lead.getTrialEndsOn().isAfter(cutoff)) {
            return false;
        }
        return raiseTaskOnce(lead, STALE_TRIAL_TASK, Instant.now(clock), "STALE_TRIAL_TASK");
    }

    private boolean raiseTaskOnce(Lead lead, String task, Instant due, String auditAction) {
        if (activityRepository.existsByLeadIdAndTypeAndBody(lead.getId(), LeadActivityType.FOLLOW_UP, task)) {
            return false;
        }
        activityRepository.save(LeadActivity.followUp(lead, task, due, null, Instant.now(clock)));
        leads.refreshNextFollowUp(lead);
        audit.record(AuditEvent.of("LEAD", lead.getId(), auditAction).branch(lead.getBranch().getId())
                .member(lead.getMemberId()).with("dueAt", due));
        return true;
    }
}
