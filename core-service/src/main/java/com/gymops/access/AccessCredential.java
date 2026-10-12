package com.gymops.access;

import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.member.Member;
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

/**
 * A way for a member to open a gate. Raw card UIDs and biometric data are never stored: cards are kept as a
 * SHA-256 of the normalised UID; biometrics as the device's opaque template reference (matching happens on the
 * device). Partial unique indexes keep a card / template on at most one live credential across all members.
 */
@Getter
@Entity
@Table(name = "access_credentials")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessCredential extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", updatable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, updatable = false)
    private CredentialType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CredentialStatus status;

    @Column(name = "credential_hash")
    private String credentialHash;

    @Column(name = "template_ref")
    private String templateRef;

    @Column(name = "display_hint")
    private String displayHint;

    @Column(name = "label")
    private String label;

    @Column(name = "consent_id")
    private Long consentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrolled_device_id")
    private AccessDevice enrolledDevice;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "suspension_source")
    private SuspensionSource suspensionSource;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoke_reason")
    private String revokeReason;

    private AccessCredential(Member member, CredentialType type, CredentialStatus status) {
        this.member = member;
        this.type = type;
        this.status = status;
    }

    public static AccessCredential card(Member member, String uidHash, String hint, String label, Instant now) {
        AccessCredential c = new AccessCredential(member, CredentialType.RFID_CARD, CredentialStatus.ACTIVE);
        c.credentialHash = uidHash;
        c.displayHint = hint;
        c.label = label;
        c.activatedAt = now;
        return c;
    }

    public static AccessCredential qrPass(Member member, Instant now) {
        AccessCredential c = new AccessCredential(member, CredentialType.QR, CredentialStatus.ACTIVE);
        c.displayHint = "Member app";
        c.activatedAt = now;
        return c;
    }

    /** A biometric enrolment waiting for the device to capture the template. */
    public static AccessCredential pendingBiometric(Member member, CredentialType type, Long consentId, AccessDevice device,
                                                    String label) {
        if (!type.isBiometric()) {
            throw new IllegalArgumentException(type + " is not biometric");
        }
        AccessCredential c = new AccessCredential(member, type, CredentialStatus.PENDING);
        c.consentId = consentId;
        c.enrolledDevice = device;
        c.label = label;
        return c;
    }

    /** The device captured the template and reported its reference. */
    public void completeEnrollment(String templateRef, String templateHash, Instant now) {
        if (status != CredentialStatus.PENDING) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Credential " + getId() + " is " + status);
        }
        this.templateRef = templateRef;
        this.credentialHash = templateHash;
        this.displayHint = "Template " + tail(templateRef);
        this.status = CredentialStatus.ACTIVE;
        this.activatedAt = now;
    }

    public void suspend(SuspensionSource source, Instant now) {
        if (status != CredentialStatus.ACTIVE) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Only an ACTIVE credential can be suspended");
        }
        status = CredentialStatus.SUSPENDED;
        suspensionSource = source;
        suspendedAt = now;
    }

    public void reactivate() {
        if (status != CredentialStatus.SUSPENDED) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Only a SUSPENDED credential can be reactivated");
        }
        status = CredentialStatus.ACTIVE;
        suspensionSource = null;
        suspendedAt = null;
    }

    public void revoke(Long by, String reason, Instant now) {
        if (status == CredentialStatus.REVOKED) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Credential is already revoked");
        }
        status = CredentialStatus.REVOKED;
        suspensionSource = null;
        revokedAt = now;
        revokedBy = by;
        revokeReason = reason;
    }

    public boolean isLive() {
        return status.isLive();
    }

    static String tail(String value) {
        return value == null ? "" : "…" + value.substring(Math.max(0, value.length() - 4));
    }
}
