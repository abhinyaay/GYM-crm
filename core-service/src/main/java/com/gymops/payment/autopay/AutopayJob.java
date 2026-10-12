package com.gymops.payment.autopay;

import com.gymops.payment.autopay.AutopayDtos.RunSummary;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly autopay run: raise renewals that are due, then execute debits whose pre-debit notice has
 * elapsed. Each item is its own transaction; ShedLock keeps it to one instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutopayJob {

    private final AutopayService service;

    @Scheduled(cron = "${app.payments.autopay.cron:0 15 * * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "autopay", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduledRun() {
        log.info("autopay_run {}", run());
    }

    public RunSummary run() {
        int[] failures = {0};
        int scheduled = 0;
        for (Long id : service.dueForRenewal()) {
            if (Boolean.TRUE.equals(safely(id, service::scheduleRenewal, failures, "schedule renewal for subscription"))) {
                scheduled++;
            }
        }
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        for (Long id : service.dueDebits()) {
            AutopayStatus status = safely(id, service::executeAttempt, failures, "execute autopay attempt");
            if (status == null) {
                continue;
            }
            attempted++;
            if (status == AutopayStatus.SUCCEEDED) {
                succeeded++;
            } else if (status == AutopayStatus.FAILED) {
                failed++;
            }
        }
        return new RunSummary(scheduled, attempted, succeeded, failed, failures[0]);
    }

    private static <T> T safely(Long id, Function<Long, T> action, int[] failures, String what) {
        try {
            return action.apply(id);
        } catch (RuntimeException e) {
            failures[0]++;
            log.error("Autopay: failed to {} {}: {}", what, id, e.getMessage(), e);
            return null;
        }
    }
}
