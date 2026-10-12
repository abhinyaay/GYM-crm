package com.gymops.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One scan at a gate and the server's decision. Append-only: a database trigger rejects UPDATE and DELETE.
 * {@code (device_id, device_event_id)} is unique, which makes event submission idempotent.
 */
@Getter
@Entity
@Immutable
@Table(name = "access_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "device_event_id", nullable = false)
    private String deviceEventId;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "credential_id")
    private Long credentialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false)
    private CredentialType credentialType;

    @Column(name = "credential_hint")
    private String credentialHint;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    private Decision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code")
    private AccessReason reasonCode;

    @Column(name = "message")
    private String message;

    @Column(name = "attendance_id")
    private Long attendanceId;

    @Column(name = "offline", nullable = false)
    private boolean offline;

    @Column(name = "anti_passback_violation", nullable = false)
    private boolean antiPassbackViolation;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Builder
    AccessEvent(Long deviceId, Long branchId, String deviceEventId, Long memberId, Long credentialId,
                CredentialType credentialType, String credentialHint, Direction direction, Decision decision,
                AccessReason reasonCode, String message, Long attendanceId, boolean offline,
                boolean antiPassbackViolation, Instant occurredAt, Instant receivedAt) {
        this.deviceId = deviceId;
        this.branchId = branchId;
        this.deviceEventId = deviceEventId;
        this.memberId = memberId;
        this.credentialId = credentialId;
        this.credentialType = credentialType;
        this.credentialHint = credentialHint;
        this.direction = direction;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.message = message == null || message.length() <= 300 ? message : message.substring(0, 300);
        this.attendanceId = attendanceId;
        this.offline = offline;
        this.antiPassbackViolation = antiPassbackViolation;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }
}
