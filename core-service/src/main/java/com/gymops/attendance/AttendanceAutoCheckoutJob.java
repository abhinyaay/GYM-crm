package com.gymops.attendance;

import com.gymops.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceAutoCheckoutJob {

    private final AttendanceRepository repository;
    private final AttendanceService service;
    private final AppProperties props;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.jobs.auto-checkout-interval:PT15M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "attendance-auto-checkout", lockAtMostFor = "PT10M")
    public void run() {
        Instant cutoff = Instant.now(clock).minus(props.attendance().autoCheckoutAfter());
        int closed = 0;
        for (Long id : repository.findStaleOpenVisitIds(cutoff)) {
            try {
                if (service.autoCheckOut(id)) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.error("Auto check-out failed for visit {}: {}", id, e.getMessage());
            }
        }
        if (closed > 0) {
            log.info("attendance_auto_checkout closed={}", closed);
        }
    }
}
