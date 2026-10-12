package com.gymops.access;

import com.gymops.access.dto.AccessDtos.EntryPass;
import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rotating QR entry pass. Issuing a token writes nothing (after the member's QR credential exists); verification is
 * stateless except for the single-use claim of (member, window) at the gate.
 */
@Service
@RequiredArgsConstructor
public class QrPassService {

    /** Outcome of checking a presented QR token: either a member + live credential, or a reason. */
    public record QrCheck(Long memberId, AccessCredential credential, long window, AccessReason failure) {
        boolean ok() {
            return failure == null;
        }
    }

    private final AccessCredentialRepository credentials;
    private final MemberRepository members;
    private final AccessKeys keys;
    private final AccessProperties props;
    private final AuditService audit;
    private final Clock clock;

    @Transactional
    public EntryPass issue(Long memberId) {
        Member member = members.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        if (!member.isActive()) {
            throw new BusinessRuleException(ErrorCode.ACCESS_RESTRICTED, "Your member account is " + member.getStatus()
                    + "; the entry pass is unavailable");
        }
        AccessCredential pass = livePass(memberId).orElseGet(() -> createPass(memberId));
        if (pass.getStatus() != CredentialStatus.ACTIVE) {
            throw new BusinessRuleException(ErrorCode.ACCESS_RESTRICTED, "Your entry pass is suspended; please contact the front desk");
        }
        Instant now = Instant.now(clock);
        long window = QrPassCodec.window(now, props.qrWindow());
        return new EntryPass(QrPassCodec.token(keys, memberId, pass.getId(), window), memberId, member.getMemberCode(),
                member.fullName(), now, QrPassCodec.windowEnd(window, props.qrWindow()), props.qrWindow().toSeconds(), now);
    }

    /**
     * Verifies a token presented at {@code at}: well-formed, MAC valid for the member's live QR credential and within
     * the window tolerance. Does not claim the window (the caller does, under the member lock).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public QrCheck verify(String token, Instant at) {
        QrPassCodec.Parsed parsed = QrPassCodec.parse(token);
        if (parsed == null) {
            return new QrCheck(null, null, 0, AccessReason.QR_INVALID);
        }
        List<AccessCredential> passes = credentials.findQrPasses(parsed.memberId());
        AccessCredential matching = passes.stream()
                .filter(p -> QrPassCodec.macMatches(keys, parsed, p.getId()))
                .findFirst().orElse(null);
        if (matching == null) {
            return new QrCheck(null, null, parsed.window(), AccessReason.QR_INVALID);
        }
        if (!QrPassCodec.inWindow(parsed.window(), QrPassCodec.window(at, props.qrWindow()), props.qrWindowTolerance())) {
            return new QrCheck(parsed.memberId(), matching, parsed.window(), AccessReason.QR_EXPIRED);
        }
        if (matching.getStatus() == CredentialStatus.REVOKED) {
            return new QrCheck(parsed.memberId(), matching, parsed.window(), AccessReason.CREDENTIAL_REVOKED);
        }
        return new QrCheck(parsed.memberId(), matching, parsed.window(), null);
    }

    /** Replay-guard key / expiry for a verified token: each (member, window) opens the gate at most once. */
    static String claimKey(long memberId, long window) {
        return memberId + ":" + window;
    }

    Instant claimExpiry(long window) {
        return QrPassCodec.windowEnd(window + props.qrWindowTolerance() + 1L, props.qrWindow());
    }

    private Optional<AccessCredential> livePass(Long memberId) {
        return credentials.findQrPasses(memberId).stream().filter(AccessCredential::isLive).findFirst();
    }

    private AccessCredential createPass(Long memberId) {
        // First pass for this member: lock the member row (one QR credential per member, also a partial unique index).
        Member locked = members.findByIdForUpdate(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        List<AccessCredential> passes = credentials.findQrPasses(memberId);
        Optional<AccessCredential> live = passes.stream().filter(AccessCredential::isLive).findFirst();
        if (live.isPresent()) {
            return live.get();
        }
        if (!passes.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.ACCESS_RESTRICTED,
                    "Your entry pass was revoked; please contact the front desk to re-issue it");
        }
        AccessCredential pass = credentials.saveAndFlush(AccessCredential.qrPass(locked, Instant.now(clock)));
        audit.record(AuditEvent.of("ACCESS_CREDENTIAL", pass.getId(), "QR_PASS_ISSUED").member(memberId));
        return pass;
    }
}
