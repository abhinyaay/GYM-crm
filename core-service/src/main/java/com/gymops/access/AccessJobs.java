package com.gymops.access;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background jobs of the access module (single instance via ShedLock; one transaction per device). */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessJobs {

    private final DeviceMonitorService monitor;
    private final ReplayGuard replayGuard;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.access.monitor-interval:PT1M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "access-device-monitor", lockAtMostFor = "PT5M")
    public void monitorHeartbeats() {
        int flagged = 0;
        for (Long id : monitor.silentDevices()) {
            try {
                if (monitor.flagIfSilent(id)) {
                    flagged++;
                }
            } catch (RuntimeException e) {
                log.error("Heartbeat monitor failed for device {}: {}", id, e.getMessage());
            }
        }
        if (flagged > 0) {
            log.info("access_devices_flagged_offline count={}", flagged);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @SchedulerLock(name = "access-replay-guard-purge", lockAtMostFor = "PT10M")
    public void purgeReplayGuard() {
        int purged = replayGuard.purgeExpired(Instant.now(clock));
        if (purged > 0) {
            log.info("access_replay_guard_purged rows={}", purged);
        }
    }
}
