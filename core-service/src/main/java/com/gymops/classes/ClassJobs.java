package com.gymops.classes;

import com.gymops.classes.dto.ClassDtos.JobRunSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 15 minutes: completes classes that ended (plus a grace period) and reconciles their seats
 * against gym check-ins, then queues reminders for classes starting soon. Each class is reconciled in
 * its own transaction (one bad record never blocks the batch); ShedLock keeps it to one instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassJobs {

    private final ClassSessionRepository sessionRepository;
    private final ClassBookingService bookingService;
    private final ClassesProperties props;
    private final Clock clock;

    @Scheduled(cron = "${app.classes.job-cron:0 */15 * * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "class-sessions", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void scheduledRun() {
        JobRunSummary summary = run();
        if (summary.sessionsCompleted() > 0 || summary.remindersQueued() > 0 || summary.failures() > 0) {
            log.info("class_jobs_run {}", summary);
        }
    }

    public JobRunSummary run() {
        Instant cutoff = Instant.now(clock).minus(props.reconcileAfter());
        List<Long> due = sessionRepository.findIdsDueForCompletion(cutoff);
        int completed = 0;
        int attended = 0;
        int noShows = 0;
        int expired = 0;
        int failures = 0;
        for (Long id : due) {
            try {
                ClassBookingService.Reconciliation r = bookingService.completeAndReconcile(id);
                if (r.completed()) {
                    completed++;
                    attended += r.attended();
                    noShows += r.noShows();
                    expired += r.waitlistExpired();
                }
            } catch (RuntimeException e) {
                failures++;
                log.error("Class reconciliation failed for session {}: {}", id, e.getMessage(), e);
            }
        }
        int reminders = 0;
        try {
            reminders = bookingService.queueReminders();
        } catch (RuntimeException e) {
            failures++;
            log.error("Class reminders failed: {}", e.getMessage(), e);
        }
        return new JobRunSummary(completed, attended, noShows, expired, reminders, failures);
    }
}
