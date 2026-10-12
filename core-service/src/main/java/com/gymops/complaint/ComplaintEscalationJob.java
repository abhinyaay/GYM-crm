package com.gymops.complaint;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Escalates unresolved complaints whose SLA has passed and notifies branch managers. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplaintEscalationJob {

    private final ComplaintRepository repository;
    private final ComplaintService service;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.jobs.complaint-sla-interval:PT10M}", initialDelayString = "PT2M")
    @SchedulerLock(name = "complaint-sla-escalation", lockAtMostFor = "PT5M")
    public void run() {
        int escalated = 0;
        for (Long id : repository.findSlaBreachedIds(Instant.now(clock))) {
            try {
                if (service.escalateIfSlaBreached(id)) {
                    escalated++;
                }
            } catch (RuntimeException e) {
                log.error("SLA escalation failed for complaint {}: {}", id, e.getMessage());
            }
        }
        if (escalated > 0) {
            log.info("complaint_sla_escalation escalated={}", escalated);
        }
    }
}
