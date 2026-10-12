package com.gymops.pricing;

import com.gymops.pricing.dto.PricingDtos.RunSummary;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly: apply scheduled price changes whose effective date has arrived, then send renewal price-rise
 * notices to autopay members who have not had one. Each item is its own transaction and idempotent;
 * ShedLock keeps the run to one instance. Sales between midnight and the run already use the new
 * price (lazy evaluation), so the run only makes the catalogue and history catch up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceChangeJob {

    private final PriceChangeService service;
    private final PricingProperties props;

    @Scheduled(cron = "${app.pricing.job-cron:0 1 * * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "pricing", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduledRun() {
        if (props.enabled()) {
            log.info("pricing_run {}", run());
        }
    }

    public RunSummary run() {
        int[] failures = {0};
        int applied = each(service.dueChangeIds(), service::applyIfDue, failures, "apply price change");
        int notices = each(service.sweepCandidates(), service::noticeIfRenewalPriceRises, failures,
                "send price notice for subscription");
        return new RunSummary(applied, notices, failures[0]);
    }

    private static int each(List<Long> ids, Predicate<Long> action, int[] failures, String what) {
        int done = 0;
        for (Long id : ids) {
            try {
                if (action.test(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                failures[0]++;
                log.error("Pricing: failed to {} {}: {}", what, id, e.getMessage(), e);
            }
        }
        return done;
    }
}
