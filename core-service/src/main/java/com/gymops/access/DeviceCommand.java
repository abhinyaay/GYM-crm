package com.gymops.access;

import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A command queued for one device (polled via the signed device API, at-least-once until acknowledged). */
@Getter
@Entity
@Table(name = "access_device_commands")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", updatable = false)
    private AccessDevice device;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, updatable = false)
    private CommandType commandType;

    @Column(name = "credential_id", updatable = false)
    private Long credentialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, updatable = false)
    private CredentialType credentialType;

    @Column(name = "template_ref", updatable = false)
    private String templateRef;

    @Column(name = "member_ref", updatable = false)
    private String memberRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CommandStatus status = CommandStatus.PENDING;

    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "result_message")
    private String resultMessage;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public DeviceCommand(AccessDevice device, CommandType type, Long credentialId, CredentialType credentialType,
                         String templateRef, String memberRef, Instant now) {
        this.device = device;
        this.commandType = type;
        this.credentialId = credentialId;
        this.credentialType = credentialType;
        this.templateRef = templateRef;
        this.memberRef = memberRef;
        this.createdAt = now;
    }

    public void markDelivered(Instant now) {
        if (status.isOpen()) {
            status = CommandStatus.DELIVERED;
            deliveryCount++;
            deliveredAt = now;
        }
    }

    public void complete(boolean succeeded, String message, Instant now) {
        if (!status.isOpen()) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Command " + id + " is already " + status);
        }
        status = succeeded ? CommandStatus.SUCCEEDED : CommandStatus.FAILED;
        resultMessage = message;
        completedAt = now;
    }

    public void cancel(String reason, Instant now) {
        if (status.isOpen()) {
            status = CommandStatus.CANCELLED;
            resultMessage = reason;
            completedAt = now;
        }
    }
}
