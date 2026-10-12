package com.gymops.access;

import com.gymops.access.dto.AccessDtos.ConsentNotice;
import com.gymops.access.dto.AccessDtos.ConsentResponse;
import com.gymops.access.dto.AccessDtos.ConsentStatus;
import com.gymops.access.dto.AccessDtos.ConsentWithdrawal;
import com.gymops.access.dto.AccessDtos.GrantConsentRequest;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.audit.AuditService;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.settings.FeatureService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DPDP biometric consent: grant (exact notice version recorded), withdraw (revokes biometric credentials and queues
 * template deletion on every device), history. A member can grant or withdraw their own consent in the app; staff
 * can record it at the desk (staff-assisted) for members of their branch.
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final BiometricConsentRepository consents;
    private final MemberRepository members;
    private final CredentialService credentialService;
    private final AccessPolicy access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;
    private final FeatureService features;

    public ConsentNotice notice() {
        return new ConsentNotice(BiometricConsentNotice.VERSION, BiometricConsent.PURPOSE, BiometricConsentNotice.TEXT,
                BiometricConsentNotice.SHA256);
    }

    @Transactional(readOnly = true)
    public ConsentStatus status(Long memberId) {
        Member member = members.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(memberId, member.getHomeBranch().getId());
        List<ConsentResponse> history = consents.findByMemberIdOrderByGrantedAtDesc(memberId).stream()
                .map(ConsentResponse::from).toList();
        ConsentResponse current = history.stream().filter(ConsentResponse::active).findFirst().orElse(null);
        return new ConsentStatus(memberId, current != null, current, history, BiometricConsentNotice.VERSION);
    }

    @Transactional
    public ConsentResponse grant(Long memberId, GrantConsentRequest request) {
        features.requireBiometrics();
        Member member = lockAndAuthorize(memberId);
        if (!BiometricConsentNotice.VERSION.equals(request.noticeVersion())) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "The consent notice has changed (current version "
                    + BiometricConsentNotice.VERSION + "); show the current notice and ask again");
        }
        if (!member.isActive()) {
            throw new BusinessRuleException(ErrorCode.ACCESS_RESTRICTED, "Member account is " + member.getStatus());
        }
        if (consents.findActive(memberId).isPresent()) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Member already has an active biometric consent");
        }
        UserPrincipal caller = CurrentUser.get();
        ConsentCaptureMethod method = caller.isMemberOnly() ? ConsentCaptureMethod.SELF_SERVICE
                : request.captureMethod() == null ? ConsentCaptureMethod.STAFF_ASSISTED : request.captureMethod();
        BiometricConsent consent = consents.saveAndFlush(new BiometricConsent(memberId, BiometricConsentNotice.VERSION,
                BiometricConsentNotice.SHA256, method, Instant.now(clock), caller.userId()));
        audit.record(AuditEvent.of("BIOMETRIC_CONSENT", consent.getId(), "GRANTED").member(memberId)
                .branch(member.getHomeBranch().getId()).with("noticeVersion", consent.getConsentTextVersion())
                .with("captureMethod", method));
        return ConsentResponse.from(consent);
    }

    @Transactional
    public ConsentWithdrawal withdraw(Long memberId, String reason) {
        Member member = lockAndAuthorize(memberId);
        BiometricConsent consent = consents.findActive(memberId).orElseThrow(() -> new ConflictException(
                ErrorCode.INVALID_STATE_TRANSITION, "Member has no active biometric consent"));
        Instant now = Instant.now(clock);
        Long by = CurrentUser.idOrNull();
        consent.withdraw(now, by, reason == null || reason.isBlank() ? null : reason.trim());
        int[] result = credentialService.revokeBiometrics(member, by, "Biometric consent withdrawn", now);
        audit.record(AuditEvent.of("BIOMETRIC_CONSENT", consent.getId(), "WITHDRAWN").member(memberId)
                .branch(member.getHomeBranch().getId()).with("credentialsRevoked", result[0])
                .with("deleteCommandsQueued", result[1]));
        notifications.notifyMember(member, NotificationType.BIOMETRIC_CONSENT_WITHDRAWN, "Biometric consent withdrawn",
                "Your biometric entry has been switched off: " + result[0] + " credential(s) revoked and deletion of your "
                        + "templates requested from the gate devices. You can keep using your card or the QR pass.",
                "biometric-consent-withdrawn:" + consent.getId());
        return new ConsentWithdrawal(ConsentResponse.from(consent), result[0], result[1]);
    }

    private Member lockAndAuthorize(Long memberId) {
        Member member = members.findByIdForUpdate(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        UserPrincipal caller = CurrentUser.get();
        if (caller.isMemberOnly()) {
            if (!memberId.equals(caller.memberId())) {
                throw new ForbiddenException("Members can only manage their own consent");
            }
        } else {
            access.assertBranchAccess(member.getHomeBranch().getId());
        }
        return member;
    }
}
