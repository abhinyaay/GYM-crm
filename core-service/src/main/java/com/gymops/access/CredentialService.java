package com.gymops.access;

import com.gymops.access.dto.AccessDtos.BiometricEnrollRequest;
import com.gymops.access.dto.AccessDtos.CardEnrollRequest;
import com.gymops.access.dto.AccessDtos.CommandView;
import com.gymops.access.dto.AccessDtos.CredentialResponse;
import com.gymops.access.dto.AccessDtos.EnrolledCredential;
import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.member.MemberStatus;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member credentials: RFID cards, biometric enrolment through a device command, rotating QR pass, suspend /
 * reactivate / revoke. Every mutation locks the member row first (same lock order as check-in and gate decisions).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    static final Set<CredentialStatus> LIVE = EnumSet.of(CredentialStatus.PENDING, CredentialStatus.ACTIVE,
            CredentialStatus.SUSPENDED);

    private final AccessCredentialRepository credentials;
    private final BiometricConsentRepository consents;
    private final DeviceCommandRepository commands;
    private final AccessDeviceRepository devices;
    private final MemberRepository members;
    private final AccessPolicy access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<CredentialResponse> list(Long memberId) {
        Member member = loadMember(memberId);
        access.assertMemberAccess(memberId, member.getHomeBranch().getId());
        List<AccessCredential> all = credentials.findByMemberIdOrderByCreatedAtDesc(memberId);
        Map<Long, List<CommandView>> byCredential = all.isEmpty() ? Map.of()
                : commands.findByCredentialIds(all.stream().map(AccessCredential::getId).toList()).stream()
                        .map(CommandView::from)
                        .collect(Collectors.groupingBy(CommandView::credentialId));
        return all.stream().map(c -> CredentialResponse.from(c, byCredential.getOrDefault(c.getId(), List.of()))).toList();
    }

    @Transactional
    public CredentialResponse enrollCard(Long memberId, CardEnrollRequest request) {
        Member member = lockMember(memberId);
        access.assertBranchAccess(member.getHomeBranch().getId());
        requireActiveMember(member);
        String uid = CredentialHashes.normalizeCardUid(request.cardUid());
        if (uid == null) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Card UID must be 4-32 hexadecimal characters");
        }
        String hash = CredentialHashes.cardHash(uid);
        credentials.findLiveByHash(CredentialType.RFID_CARD, hash).ifPresent(existing -> {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, existing.getMember().getId().equals(memberId)
                    ? "This card is already enrolled for the member" : "This card is already assigned to another member");
        });
        Instant now = Instant.now(clock);
        AccessCredential card = credentials.saveAndFlush(AccessCredential.card(member, hash,
                CredentialHashes.hint(CredentialType.RFID_CARD, uid), blankToNull(request.label()), now));
        audit.record(AuditEvent.of("ACCESS_CREDENTIAL", card.getId(), "CARD_ENROLLED").member(memberId)
                .branch(member.getHomeBranch().getId()).with("hint", card.getDisplayHint()));
        notifications.notifyMember(member, NotificationType.ACCESS_CREDENTIAL_ENROLLED, "Access card registered",
                "An RFID access card (" + card.getDisplayHint() + ") was registered for your membership. If this wasn't "
                        + "you, please contact the front desk.", "access-credential:" + card.getId());
        return CredentialResponse.from(card, List.of());
    }

    /**
     * Starts biometric enrolment: requires an active DPDP consent, creates a PENDING credential and queues an
     * ENROLL_TEMPLATE command for the chosen device. The device captures the finger / face and acknowledges with
     * its template reference ({@link #completeEnrollment}).
     */
    @Transactional
    public CredentialResponse requestBiometricEnrollment(Long memberId, BiometricEnrollRequest request) {
        Member member = lockMember(memberId);
        access.assertBranchAccess(member.getHomeBranch().getId());
        requireActiveMember(member);
        AccessDevice device = devices.findById(request.deviceId())
                .orElseThrow(() -> new ResourceNotFoundException("Device", request.deviceId()));
        access.assertBranchAccess(device.getBranch().getId());
        if (!device.isActive()) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION, "Device " + device.getName() + " is disabled");
        }
        BiometricConsent consent = consents.findActive(memberId).orElseThrow(() -> new BusinessRuleException(
                ErrorCode.BUSINESS_RULE_VIOLATION, "Biometric enrolment requires the member's recorded consent"));
        Instant now = Instant.now(clock);
        AccessCredential pending = credentials.saveAndFlush(AccessCredential.pendingBiometric(member, request.type(),
                consent.getId(), device, blankToNull(request.label())));
        DeviceCommand command = commands.saveAndFlush(new DeviceCommand(device, CommandType.ENROLL_TEMPLATE, pending.getId(),
                request.type(), null, member.getMemberCode(), now));
        audit.record(AuditEvent.of("ACCESS_CREDENTIAL", pending.getId(), "BIOMETRIC_ENROLMENT_REQUESTED").member(memberId)
                .branch(device.getBranch().getId()).with("type", request.type()).with("deviceId", device.getId())
                .with("consentId", consent.getId()));
        return CredentialResponse.from(pending, List.of(CommandView.from(command)));
    }

    /** Called when a device acknowledges an ENROLL_TEMPLATE command (inside the device API transaction). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeEnrollment(DeviceCommand command, boolean success, String templateRef, String message) {
        Instant now = Instant.now(clock);
        AccessCredential credential = command.getCredentialId() == null ? null
                : credentials.findById(command.getCredentialId()).orElse(null);
        if (credential == null) {
            command.complete(success, message, now);
            return;
        }
        Member member = lockMember(credential.getMember().getId());
        String ref = CredentialHashes.normalizeTemplateRef(templateRef);
        if (success && ref == null) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "templateRef is required for a successful enrolment");
        }
        command.complete(success, message, now);
        if (!success) {
            if (credential.getStatus() == CredentialStatus.PENDING) {
                credential.revoke(null, "Enrolment failed on device: " + (message == null ? "no reason given" : message), now);
            }
            return;
        }
        boolean consentStillActive = consents.findActive(member.getId())
                .map(c -> c.getId().equals(credential.getConsentId())).orElse(false);
        if (credential.getStatus() != CredentialStatus.PENDING || !consentStillActive || !member.isActive()) {
            // Enrolment finished after the credential was revoked / consent withdrawn: erase the new template again.
            if (credential.getStatus() == CredentialStatus.PENDING) {
                credential.revoke(null, "Consent withdrawn or member inactive before enrolment completed", now);
            }
            commands.save(new DeviceCommand(command.getDevice(), CommandType.DELETE_TEMPLATE, credential.getId(),
                    credential.getType(), ref, member.getMemberCode(), now));
            return;
        }
        String hash = CredentialHashes.templateHash(credential.getType(), ref);
        credentials.findLiveByHash(credential.getType(), hash).ifPresent(existing -> {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Template reference is already in use");
        });
        credential.completeEnrollment(ref, hash, now);
        credentials.flush();
        audit.record(AuditEvent.of("ACCESS_CREDENTIAL", credential.getId(), "BIOMETRIC_ENROLLED").member(member.getId())
                .branch(command.getDevice().getBranch().getId()).with("type", credential.getType())
                .with("deviceId", command.getDevice().getId()));
        notifications.notifyMember(member, NotificationType.ACCESS_CREDENTIAL_ENROLLED,
                label(credential.getType()) + " entry enabled",
                "Your " + label(credential.getType()).toLowerCase() + " was enrolled on a gate device. Only a reference to the "
                        + "template is stored by GymOps; you can withdraw consent at any time in the app.",
                "access-credential:" + credential.getId());
    }

    @Transactional
    public CredentialResponse revoke(Long credentialId, String reason) {
        AccessCredential credential = loadForStaff(credentialId);
        Instant now = Instant.now(clock);
        revokeInternal(credential, CurrentUser.idOrNull(), reason == null || reason.isBlank() ? "Revoked by staff" : reason, now);
        audit.record(AuditEvent.of("ACCESS_CREDENTIAL", credentialId, "REVOKED").member(credential.getMember().getId())
                .with("type", credential.getType()).with("reason", reason));
        return CredentialResponse.from(credential, List.of());
    }

    @Transactional
    public CredentialResponse suspend(Long credentialId) {
        AccessCredential credential = loadForStaff(credentialId);
        credential.suspend(SuspensionSource.MANUAL, Instant.now(clock));
        audit.record(AuditEvent.of("ACCESS_CREDENTIAL", credentialId, "SUSPENDED").member(credential.getMember().getId()));
        return CredentialResponse.from(credential, List.of());
    }

    @Transactional
    public CredentialResponse reactivate(Long credentialId) {
        AccessCredential credential = loadForStaff(credentialId);
        requireActiveMember(credential.getMember());
        if (credential.getType().isBiometric() && consents.findActive(credential.getMember().getId()).isEmpty()) {
            throw new BusinessRuleException("Biometric credentials need an active consent");
        }
        credential.reactivate();
        audit.record(AuditEvent.of("ACCESS_CREDENTIAL", credentialId, "REACTIVATED").member(credential.getMember().getId()));
        return CredentialResponse.from(credential, List.of());
    }

    /** Re-issues a member's rotating QR pass after it was revoked (staff). */
    @Transactional
    public CredentialResponse issueQrPass(Long memberId) {
        Member member = lockMember(memberId);
        access.assertBranchAccess(member.getHomeBranch().getId());
        requireActiveMember(member);
        if (credentials.findQrPasses(memberId).stream().anyMatch(AccessCredential::isLive)) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Member already has an entry pass");
        }
        AccessCredential pass = credentials.saveAndFlush(AccessCredential.qrPass(member, Instant.now(clock)));
        audit.record(AuditEvent.of("ACCESS_CREDENTIAL", pass.getId(), "QR_PASS_ISSUED").member(memberId));
        return CredentialResponse.from(pass, List.of());
    }

    /** Gate simulator support: active biometric templates / QR passes of members whose home branch is {@code branchId}. */
    @Transactional(readOnly = true)
    public List<EnrolledCredential> enrolledAtBranch(Long branchId, Set<CredentialType> types) {
        access.assertBranchAccess(branchId);
        return credentials.findActiveAtHomeBranch(branchId, types).stream()
                .map(c -> new EnrolledCredential(c.getId(), c.getMember().getId(), c.getMember().getMemberCode(),
                        c.getMember().fullName(), c.getType(), c.getTemplateRef(), c.getDisplayHint()))
                .toList();
    }

    /**
     * Revokes all live biometric credentials of a member (consent withdrawn) and queues DELETE_TEMPLATE commands to
     * every device. Caller holds the member lock. Returns {revoked, deleteCommands}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int[] revokeBiometrics(Member member, Long by, String reason, Instant now) {
        int revoked = 0;
        int deletes = 0;
        for (AccessCredential c : credentials.findByMemberAndStatuses(member.getId(), LIVE)) {
            if (c.getType().isBiometric()) {
                deletes += revokeInternal(c, by, reason, now);
                revoked++;
            }
        }
        return new int[] {revoked, deletes};
    }

    /**
     * Keeps credentials in step with the member account: suspended / inactive → credentials suspended (restored on
     * reactivation); deleted → everything revoked and templates erased from devices. Runs synchronously in the
     * transaction that changed the member (MemberChanged is published by the member, subscription and payment flows).
     */
    @EventListener
    public void onMemberChanged(DomainEvents.MemberChanged event) {
        Long memberId = event.memberId();
        if (!credentials.existsByMemberAndStatuses(memberId, LIVE)) {
            return;
        }
        Member member = entityManager.find(Member.class, memberId);
        Instant now = Instant.now(clock);
        List<AccessCredential> live = credentials.findByMemberAndStatuses(memberId, LIVE);
        if (member == null || member.getDeletedAt() != null) {
            live.forEach(c -> revokeInternal(c, CurrentUser.idOrNull(), "Member deleted", now));
            log.info("access_credentials_revoked member={} count={} reason=member_deleted", memberId, live.size());
            return;
        }
        if (member.getStatus() != MemberStatus.ACTIVE) {
            live.stream().filter(c -> c.getStatus() == CredentialStatus.ACTIVE)
                    .forEach(c -> c.suspend(SuspensionSource.MEMBER_STATUS, now));
        } else {
            live.stream().filter(c -> c.getStatus() == CredentialStatus.SUSPENDED
                            && c.getSuspensionSource() == SuspensionSource.MEMBER_STATUS)
                    .forEach(AccessCredential::reactivate);
        }
    }

    /** Revokes one credential; for biometrics cancels pending enrolment and queues template deletion. */
    private int revokeInternal(AccessCredential credential, Long by, String reason, Instant now) {
        credential.revoke(by, reason, now);
        commands.findOpenForCredential(credential.getId()).forEach(c -> c.cancel("Credential revoked", now));
        if (!credential.getType().isBiometric() || credential.getTemplateRef() == null) {
            return 0;
        }
        int queued = 0;
        for (AccessDevice device : devices.findAll()) {
            commands.save(new DeviceCommand(device, CommandType.DELETE_TEMPLATE, credential.getId(), credential.getType(),
                    credential.getTemplateRef(), credential.getMember().getMemberCode(), now));
            queued++;
        }
        return queued;
    }

    private AccessCredential loadForStaff(Long credentialId) {
        AccessCredential credential = credentials.findById(credentialId)
                .orElseThrow(() -> new ResourceNotFoundException("Credential", credentialId));
        Member member = lockMember(credential.getMember().getId());
        access.assertBranchAccess(member.getHomeBranch().getId());
        entityManager.refresh(credential);
        return credential;
    }

    private Member lockMember(Long memberId) {
        return members.findByIdForUpdate(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
    }

    private Member loadMember(Long memberId) {
        return members.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
    }

    private static void requireActiveMember(Member member) {
        if (!member.isActive()) {
            throw new BusinessRuleException(ErrorCode.ACCESS_RESTRICTED, "Member account is " + member.getStatus());
        }
    }

    private static String label(CredentialType type) {
        return type == CredentialType.FACE ? "Face" : "Fingerprint";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
