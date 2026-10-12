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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DPDP Act 2023 consent record for biometric processing: what the member agreed to (purpose + exact notice
 * version and its hash), when, and who captured it. Withdrawal is recorded on the same row; history is kept.
 */
@Getter
@Entity
@Table(name = "biometric_consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BiometricConsent {

    public static final String PURPOSE = "GYM_ENTRY_VERIFICATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "purpose", nullable = false, updatable = false)
    private String purpose;

    @Column(name = "consent_text_version", nullable = false, updatable = false)
    private String consentTextVersion;

    @Column(name = "consent_text_sha256", nullable = false, updatable = false)
    private String consentTextSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_method", nullable = false, updatable = false)
    private ConsentCaptureMethod captureMethod;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", updatable = false)
    private Long grantedBy;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawn_by")
    private Long withdrawnBy;

    @Column(name = "withdrawal_reason")
    private String withdrawalReason;

    public BiometricConsent(Long memberId, String textVersion, String textSha256, ConsentCaptureMethod method,
                            Instant now, Long grantedBy) {
        this.memberId = memberId;
        this.purpose = PURPOSE;
        this.consentTextVersion = textVersion;
        this.consentTextSha256 = textSha256;
        this.captureMethod = method;
        this.grantedAt = now;
        this.grantedBy = grantedBy;
    }

    public boolean isActive() {
        return withdrawnAt == null;
    }

    public void withdraw(Instant now, Long by, String reason) {
        if (!isActive()) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Consent was already withdrawn");
        }
        withdrawnAt = now.isBefore(grantedAt) ? grantedAt : now;
        withdrawnBy = by;
        withdrawalReason = reason;
    }
}
