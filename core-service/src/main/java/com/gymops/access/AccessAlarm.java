package com.gymops.access;

import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Door forced / held open / tailgating / tamper reported by a device, or "device offline" raised by the server. */
@Getter
@Entity
@Table(name = "access_alarms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessAlarm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, updatable = false)
    private Long deviceId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "device_event_id", nullable = false, updatable = false)
    private String deviceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alarm_type", nullable = false, updatable = false)
    private AlarmType alarmType;

    @Column(name = "details", updatable = false)
    private String details;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public AccessAlarm(Long deviceId, Long branchId, String deviceEventId, AlarmType type, String details,
                       Instant occurredAt, Instant receivedAt) {
        this.deviceId = deviceId;
        this.branchId = branchId;
        this.deviceEventId = deviceEventId;
        this.alarmType = type;
        this.details = details == null || details.length() <= 300 ? details : details.substring(0, 300);
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    public void acknowledge(Long by, Instant now) {
        if (acknowledgedAt != null) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Alarm " + id + " is already acknowledged");
        }
        acknowledgedAt = now;
        acknowledgedBy = by;
    }
}
