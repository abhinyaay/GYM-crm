package com.gymops.access;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A gate device at a branch. Its shared secret is derived from the master key (see {@link AccessKeys}). */
@Getter
@Entity
@Table(name = "access_devices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessDevice extends BaseEntity {

    @Column(name = "device_uid", nullable = false, updatable = false)
    private String deviceUid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", updatable = false)
    private GymBranch branch;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false)
    private DeviceType deviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private DeviceDirection direction;

    @Column(name = "vendor")
    private String vendor;

    @Column(name = "model")
    private String model;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "firmware_version")
    private String firmwareVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeviceStatus status = DeviceStatus.ACTIVE;

    @Column(name = "key_version", nullable = false)
    private int keyVersion = 1;

    @Column(name = "secret_issued_at", nullable = false)
    private Instant secretIssuedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "last_seen_ip")
    private String lastSeenIp;

    @Column(name = "offline_since")
    private Instant offlineSince;

    public AccessDevice(String deviceUid, GymBranch branch, String name, DeviceType deviceType, DeviceDirection direction,
                        Instant now) {
        this.deviceUid = deviceUid;
        this.branch = branch;
        this.name = name;
        this.deviceType = deviceType;
        this.direction = direction;
        this.secretIssuedAt = now;
    }

    public void describe(String name, DeviceType type, DeviceDirection direction, String vendor, String model,
                         String serialNumber, String firmwareVersion) {
        this.name = name;
        this.deviceType = type;
        this.direction = direction;
        this.vendor = blankToNull(vendor);
        this.model = blankToNull(model);
        this.serialNumber = blankToNull(serialNumber);
        if (firmwareVersion != null && !firmwareVersion.isBlank()) {
            this.firmwareVersion = firmwareVersion.trim();
        }
    }

    public boolean isActive() {
        return status == DeviceStatus.ACTIVE;
    }

    /** Invalidates the current secret; the caller hands the newly derived one to the operator. */
    public void rotateSecret(Instant now) {
        keyVersion++;
        secretIssuedAt = now;
    }

    public void changeStatus(DeviceStatus newStatus) {
        if (status == newStatus) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Device is already " + newStatus);
        }
        status = newStatus;
        if (newStatus == DeviceStatus.DISABLED) {
            offlineSince = null;
        }
    }

    /** Records a heartbeat; returns true when the device was flagged offline and is now back. */
    public boolean heartbeat(Instant now, String ip, String firmware) {
        boolean recovered = offlineSince != null;
        lastHeartbeatAt = now;
        lastSeenIp = ip;
        offlineSince = null;
        if (firmware != null && !firmware.isBlank()) {
            firmwareVersion = firmware.trim();
        }
        return recovered;
    }

    public void markOffline(Instant now) {
        offlineSince = now;
    }

    public boolean isOnline(Instant now, java.time.Duration timeout) {
        return isActive() && lastHeartbeatAt != null && offlineSince == null
                && !lastHeartbeatAt.isBefore(now.minus(timeout));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
