package com.gymops.access;

import com.gymops.notification.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flags devices that stopped sending heartbeats. A device is flagged (and its branch managers notified) once per
 * outage: {@code offline_since} stays set until the next heartbeat clears it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceMonitorService {

    private final AccessDeviceRepository devices;
    private final AccessAlarmRepository alarms;
    private final AccessNotifier notifier;
    private final AccessProperties props;
    private final Clock clock;

    public List<Long> silentDevices() {
        return devices.findSilentSince(Instant.now(clock).minus(props.heartbeatTimeout()));
    }

    /** Marks one device offline if it is still silent (row-locked, so concurrent runs flag it once). */
    @Transactional
    public boolean flagIfSilent(Long deviceId) {
        AccessDevice device = devices.findByIdForUpdate(deviceId).orElse(null);
        Instant now = Instant.now(clock);
        if (device == null || !device.isActive() || device.getOfflineSince() != null || device.getLastHeartbeatAt() == null
                || !device.getLastHeartbeatAt().isBefore(now.minus(props.heartbeatTimeout()))) {
            return false;
        }
        device.markOffline(now);
        alarms.save(new AccessAlarm(device.getId(), device.getBranch().getId(), "offline-" + now.getEpochSecond(),
                AlarmType.DEVICE_OFFLINE, "No heartbeat since " + device.getLastHeartbeatAt(), now, now));
        notifier.notifyBranchManagers(device.getBranch().getId(), NotificationType.ACCESS_DEVICE_OFFLINE,
                "Gate device offline: " + device.getName(),
                device.getName() + " at " + device.getBranch().getName() + " has not sent a heartbeat since "
                        + device.getLastHeartbeatAt() + ". It keeps admitting members from its offline allowlist; check its "
                        + "network and power.");
        log.warn("access_device_offline device={} lastHeartbeat={}", device.getDeviceUid(), device.getLastHeartbeatAt());
        return true;
    }
}
