package com.gymops.leads;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily sales-pipeline housekeeping. Every step is idempotent (dedup keys / existence checks) and
 * each lead is processed in its own transaction, so re-runs and partial failures are safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadJobs {

    private final LeadRepository repository;
    private final LeadJobService service;
    private final LeadsProperties props;
    private final Clock clock;

    public record RunSummary(int digestsSent, int trialEndingReminders, int staleTrialTasks, int failures) {
    }

    @Scheduled(cron = "${app.leads.follow-up-digest-cron:0 0 8 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "leads-follow-up-digest", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void scheduledDigest() {
        log.info("leads_follow_up_digest sent={}", runFollowUpDigest());
    }

    @Scheduled(cron = "${app.leads.trial-jobs-cron:0 30 8 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "leads-trial-checks", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void scheduledTrialChecks() {
        int[] failures = {0};
        int reminders = runTrialEndingReminders(failures);
        int stale = runStaleTrialChecks(failures);
        log.info("leads_trial_checks trialEnding={} staleTrials={} failures={}", reminders, stale, failures[0]);
    }

    /** Runs every lead job once (used by the admin endpoint and tests). */
    public RunSummary runAll() {
        int[] failures = {0};
        int digests = runFollowUpDigest();
        return new RunSummary(digests, runTrialEndingReminders(failures), runStaleTrialChecks(failures), failures[0]);
    }

    public int runFollowUpDigest() {
        return service.sendFollowUpDigests();
    }

    public int runTrialEndingReminders(int[] failures) {
        LocalDate noticeDate = LocalDate.now(clock).plusDays(props.trialEndingNoticeDays());
        return processEach(repository.findTrialsEndingOn(noticeDate), service::remindTrialEnding, failures);
    }

    public int runStaleTrialChecks(int[] failures) {
        LocalDate cutoff = LocalDate.now(clock).minusDays(props.staleTrialDays());
        return processEach(repository.findTrialsEndedOnOrBefore(cutoff), service::flagStaleTrial, failures);
    }

    private int processEach(List<Long> ids, Predicate<Long> action, int[] failures) {
        int changed = 0;
        for (Long id : ids) {
            try {
                if (action.test(id)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                failures[0]++;
                log.error("Lead job step failed for lead {}: {}", id, e.getMessage(), e);
            }
        }
        return changed;
    }
}
