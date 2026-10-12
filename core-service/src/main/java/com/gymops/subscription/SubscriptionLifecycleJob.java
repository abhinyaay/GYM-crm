package com.gymops.subscription;

import com.gymops.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily subscription housekeeping. Each subscription is processed in its own transaction (one bad
 * record never blocks the batch) and every step is idempotent, so a crashed or repeated run is
 * safe. ShedLock ensures a single instance runs it.
 *
 * Order matters: queued renewals start before predecessors are evaluated for expiry, so a renewed
 * member goes straight ACTIVE -> EXPIRED (renewed) instead of into GRACE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionLifecycleJob {

    private final SubscriptionRepository repository;
    private final SubscriptionService service;
    private final AppProperties props;
    private final Clock clock;

    public record RunSummary(int started, int expiredOrGrace, int resumed, int stalePendingCancelled,
                             int renewalReminders, int expiryWarnings, int failures) {
    }

    @Scheduled(cron = "${app.jobs.subscription-lifecycle-cron:0 5 0 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "subscription-lifecycle", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduledRun() {
        RunSummary summary = run();
        log.info("subscription_lifecycle_run {}", summary);
    }

    public RunSummary run() {
        LocalDate today = LocalDate.now(clock);
        int[] failures = {0};
        int started = processEach(repository.findDueToStart(today), service::startIfDue, failures);
        int resumed = processEach(repository.findFreezeEnded(today), service::resumeIfFreezeEnded, failures);
        int expired = processEach(repository.findPastEndDate(today), service::evaluateExpiry, failures);
        Instant cutoff = Instant.now(clock).minus(props.billing().pendingPaymentExpiryDays(), ChronoUnit.DAYS);
        int stale = processEach(repository.findStalePending(cutoff), service::cancelStalePending, failures);

        int reminders = service.queueRenewalReminders(today.plusDays(props.subscription().renewalReminderDays()),
                props.subscription().renewalReminderDays());
        int warnings = service.queueRenewalReminders(today.plusDays(props.subscription().expiryWarningDays()),
                props.subscription().expiryWarningDays());
        return new RunSummary(started, expired, resumed, stale, reminders, warnings, failures[0]);
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
                log.error("Lifecycle step failed for subscription {}: {}", id, e.getMessage(), e);
            }
        }
        return changed;
    }
}
