package com.gymops.access;

import com.gymops.access.GateAuthenticator.DeviceContext;
import com.gymops.access.dto.AccessDtos.GateDecision;
import com.gymops.access.dto.AccessDtos.ScanRequest;
import com.gymops.attendance.Attendance;
import com.gymops.attendance.AttendanceService;
import com.gymops.attendance.CheckInMethod;
import com.gymops.attendance.EntryCheck;
import com.gymops.branch.GymBranch;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides a gate scan: resolve the credential, lock the member row, run the SAME entry rules as front-desk
 * check-in ({@link AttendanceService#evaluateEntry}), apply anti-passback, then create / close the attendance visit
 * and append the access event, all in one transaction. A repeated {@code deviceEventId} returns the stored decision.
 *
 * <p>Buffered offline events ({@code offlineDecision} set) are <i>reconciled</i>, not decided: the person already
 * passed (or was refused) at the door, so the server records what happened at {@code occurredAt}, creates / closes
 * the visit when it can, and flags where its own rules would have disagreed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GateDecisionService {

    /** What a scan resolved to before the member-level rules. */
    record Resolution(AccessCredential credential, Long memberId, String hint, AccessReason failure, long qrWindow) {
    }

    /** The decision plus its side effects, before it is written to the event log. */
    record Outcome(Decision decision, AccessReason reason, String message, Long memberId, Long credentialId, String hint,
                   Long attendanceId, boolean antiPassbackViolation, String displayName) {

        static Outcome denied(AccessReason reason, String message, Resolution r, String displayName) {
            return new Outcome(Decision.DENIED, reason, message, r == null ? null : r.memberId(),
                    r == null || r.credential() == null ? null : r.credential().getId(), r == null ? null : r.hint(), null,
                    reason == AccessReason.ANTI_PASSBACK, displayName);
        }
    }

    private final AccessEventRepository events;
    private final AccessDeviceRepository devices;
    private final AccessCredentialRepository credentials;
    private final BiometricConsentRepository consents;
    private final MemberRepository members;
    private final AttendanceService attendance;
    private final QrPassService qrPasses;
    private final ReplayGuard replayGuard;
    private final AccessProperties props;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Transactional
    public GateDecision process(DeviceContext ctx, ScanRequest request) {
        Instant now = Instant.now(clock);
        Optional<AccessEvent> existing = events.findByDeviceIdAndDeviceEventId(ctx.deviceId(), request.deviceEventId());
        if (existing.isPresent()) {
            return replay(existing.get(), now);
        }
        AccessDevice device = devices.findById(ctx.deviceId()).orElseThrow();
        boolean offline = request.offlineDecision() != null;
        Instant occurredAt = offline ? clampToNow(request.occurredAt(), now) : now;
        Direction direction = request.direction() != null ? request.direction()
                : device.getDirection() == DeviceDirection.EXIT ? Direction.EXIT : Direction.ENTRY;

        Outcome outcome;
        if (!device.getDirection().permits(direction)) {
            outcome = Outcome.denied(AccessReason.DIRECTION_NOT_ALLOWED, "Device " + device.getName() + " only handles "
                    + device.getDirection(), null, null);
        } else {
            Resolution resolution = resolve(request.credentialType(), request.credentialValue(), occurredAt);
            Member member = resolution.memberId() == null ? null : members.findByIdForUpdate(resolution.memberId()).orElse(null);
            // Idempotency under concurrency: a duplicate submission waiting on the member lock sees the committed event.
            Optional<AccessEvent> raced = member == null ? Optional.empty()
                    : events.findByDeviceIdAndDeviceEventId(ctx.deviceId(), request.deviceEventId());
            if (raced.isPresent()) {
                return replay(raced.get(), now);
            }
            outcome = offline ? reconcile(device, direction, request, resolution, member, occurredAt)
                    : decide(device, direction, resolution, member, now);
        }
        AccessEvent event = events.saveAndFlush(AccessEvent.builder()
                .deviceId(device.getId()).branchId(device.getBranch().getId()).deviceEventId(request.deviceEventId())
                .memberId(outcome.memberId()).credentialId(outcome.credentialId())
                .credentialType(request.credentialType()).credentialHint(outcome.hint()).direction(direction)
                .decision(outcome.decision()).reasonCode(outcome.reason()).message(outcome.message())
                .attendanceId(outcome.attendanceId()).offline(offline).antiPassbackViolation(outcome.antiPassbackViolation())
                .occurredAt(occurredAt).receivedAt(now).build());
        meterRegistry.counter("gymops.access.decisions", "decision", outcome.decision().name(),
                "reason", outcome.reason() == null ? "OK" : outcome.reason().name(), "direction", direction.name(),
                "offline", String.valueOf(offline)).increment();
        log.info("gate_decision device={} event={} direction={} decision={} reason={} member={} offline={}",
                device.getDeviceUid(), event.getId(), direction, outcome.decision(), outcome.reason(), outcome.memberId(), offline);
        return toDecision(event, outcome.displayName(), false, now);
    }

    @Transactional(readOnly = true)
    public Optional<GateDecision> findExisting(Long deviceId, String deviceEventId) {
        return events.findByDeviceIdAndDeviceEventId(deviceId, deviceEventId).map(e -> replay(e, Instant.now(clock)));
    }

    // ------------------------------------------------------------------ live decision

    private Outcome decide(AccessDevice device, Direction direction, Resolution r, Member member, Instant now) {
        boolean exitWithKnownMember = direction == Direction.EXIT && member != null
                && (r.failure() == null || r.failure() == AccessReason.CREDENTIAL_REVOKED);
        if (r.failure() != null && !exitWithKnownMember) {
            return Outcome.denied(r.failure(), describe(r.failure()), r, null);
        }
        if (member == null) {
            return Outcome.denied(AccessReason.CREDENTIAL_REVOKED, "Member record no longer exists", r, null);
        }
        String name = displayName(member);
        if (r.credential().getType() == CredentialType.QR
                && !replayGuard.claim("qr", QrPassService.claimKey(member.getId(), r.qrWindow()), qrPasses.claimExpiry(r.qrWindow()))) {
            return Outcome.denied(AccessReason.QR_REUSED, "This QR code was already used; show the refreshed code", r, name);
        }
        if (direction == Direction.EXIT) {
            return exit(device, r, member, now, name);
        }
        AccessReason credentialProblem = credentialProblem(r.credential(), member);
        if (credentialProblem != null) {
            return Outcome.denied(credentialProblem, describe(credentialProblem), r, name);
        }
        GymBranch branch = device.getBranch();
        EntryCheck check = attendance.evaluateEntry(member, branch, now, EntryCheck.Options.GATE_LIVE);
        if (!check.isAllowed()) {
            return Outcome.denied(AccessReason.from(check.denial()), check.message(), r, name);
        }
        Optional<Attendance> open = attendance.lockOpenVisit(member.getId());
        boolean violation = false;
        if (open.isPresent()) {
            if (props.antiPassback() == AntiPassbackMode.HARD) {
                return Outcome.denied(AccessReason.ANTI_PASSBACK, "Already inside since " + open.get().getCheckInTime()
                        + " (branch " + open.get().getBranch().getId() + "); exit through a gate first", r, name);
            }
            // SOFT: the member evidently left without tapping out; close that visit and start a new one.
            attendance.recordExit(open.get(), now);
            violation = true;
        }
        Attendance visit = attendance.recordEntry(member, branch, check.subscription(), now, CheckInMethod.GATE, null);
        return new Outcome(Decision.GRANTED, violation ? AccessReason.ANTI_PASSBACK : AccessReason.OK,
                violation ? "Entry granted; previous visit had no exit (anti-passback, soft mode)" : "Welcome",
                member.getId(), r.credential().getId(), r.hint(), visit.getId(), violation, name);
    }

    /** Exits never strand anyone inside: a known member with an open visit here may always leave. */
    private Outcome exit(AccessDevice device, Resolution r, Member member, Instant now, String name) {
        Optional<Attendance> open = attendance.lockOpenVisit(member.getId());
        if (open.isPresent() && open.get().getBranch().getId().equals(device.getBranch().getId())) {
            Attendance visit = attendance.recordExit(open.get(), now);
            return new Outcome(Decision.GRANTED, AccessReason.OK, "Goodbye", member.getId(),
                    r.credential() == null ? null : r.credential().getId(), r.hint(), visit.getId(), false, name);
        }
        String why = open.isPresent() ? "Checked in at another branch" : "Not checked in";
        if (props.antiPassback() == AntiPassbackMode.HARD) {
            return Outcome.denied(AccessReason.ANTI_PASSBACK, why + "; exit refused (anti-passback). Staff can open the gate.",
                    r, name);
        }
        return new Outcome(Decision.GRANTED, AccessReason.ANTI_PASSBACK, why + " (anti-passback, soft mode)", member.getId(),
                r.credential() == null ? null : r.credential().getId(), r.hint(), null, true, name);
    }

    // ------------------------------------------------------------------ offline reconciliation

    private Outcome reconcile(AccessDevice device, Direction direction, ScanRequest request, Resolution r, Member member,
                              Instant occurredAt) {
        String name = member == null ? null : displayName(member);
        Long credentialId = r.credential() == null ? null : r.credential().getId();
        if (request.offlineDecision() == Decision.DENIED) {
            AccessReason reason = request.offlineReason() == null ? AccessReason.OFFLINE_DENIED : request.offlineReason();
            return new Outcome(Decision.DENIED, reason, "Refused by the device while offline", member == null ? null : member.getId(),
                    credentialId, r.hint(), null, reason == AccessReason.ANTI_PASSBACK, name);
        }
        if (member == null) {
            return new Outcome(Decision.GRANTED, r.failure() == null ? AccessReason.UNKNOWN_CREDENTIAL : r.failure(),
                    "Admitted offline by the device; credential not recognised by the server", null, null, r.hint(), null,
                    false, null);
        }
        Optional<Attendance> open = attendance.lockOpenVisit(member.getId());
        if (direction == Direction.EXIT) {
            if (open.isPresent() && !open.get().getCheckInTime().isAfter(occurredAt)
                    && open.get().getBranch().getId().equals(device.getBranch().getId())) {
                Attendance visit = attendance.recordExit(open.get(), occurredAt);
                return new Outcome(Decision.GRANTED, AccessReason.OK, "Offline exit reconciled", member.getId(), credentialId,
                        r.hint(), visit.getId(), false, name);
            }
            return new Outcome(Decision.GRANTED, AccessReason.ANTI_PASSBACK, "Offline exit without a matching open visit",
                    member.getId(), credentialId, r.hint(), null, true, name);
        }
        AccessReason policy = r.failure() != null ? r.failure() : credentialProblemAt(r.credential(), member, occurredAt);
        if (open.isPresent()) {
            return new Outcome(Decision.GRANTED, AccessReason.ANTI_PASSBACK, "Offline entry while a visit was already open",
                    member.getId(), credentialId, r.hint(), null, true, name);
        }
        EntryCheck check = attendance.evaluateEntry(member, device.getBranch(), occurredAt, EntryCheck.Options.RETROSPECTIVE);
        if (!check.isAllowed()) {
            return new Outcome(Decision.GRANTED, AccessReason.from(check.denial()),
                    "Admitted offline by the device, but server rules would have refused: " + check.message(),
                    member.getId(), credentialId, r.hint(), null, false, name);
        }
        Attendance visit = attendance.recordEntry(member, device.getBranch(), check.subscription(), occurredAt,
                CheckInMethod.GATE, null);
        return new Outcome(Decision.GRANTED, policy == null ? AccessReason.OK : policy,
                policy == null ? "Offline entry reconciled" : "Offline entry reconciled; credential issue: " + describe(policy),
                member.getId(), credentialId, r.hint(), visit.getId(), false, name);
    }

    // ------------------------------------------------------------------ credential resolution

    Resolution resolve(CredentialType type, String value, Instant at) {
        if (type == CredentialType.QR) {
            QrPassService.QrCheck qr = qrPasses.verify(value, at);
            return new Resolution(qr.credential(), qr.memberId(), CredentialHashes.hint(CredentialType.QR, null), qr.failure(),
                    qr.window());
        }
        String normalized = type == CredentialType.RFID_CARD ? CredentialHashes.normalizeCardUid(value)
                : CredentialHashes.normalizeTemplateRef(value);
        if (normalized == null) {
            return new Resolution(null, null, null, AccessReason.UNKNOWN_CREDENTIAL, 0);
        }
        String hash = type == CredentialType.RFID_CARD ? CredentialHashes.cardHash(normalized)
                : CredentialHashes.templateHash(type, normalized);
        String hint = CredentialHashes.hint(type, normalized);
        Optional<AccessCredential> live = credentials.findLiveByHash(type, hash);
        if (live.isPresent()) {
            return new Resolution(live.get(), live.get().getMember().getId(), hint, null, 0);
        }
        List<AccessCredential> revoked = credentials.findRevokedByHash(type, hash);
        if (!revoked.isEmpty()) {
            return new Resolution(revoked.get(0), revoked.get(0).getMember().getId(), hint, AccessReason.CREDENTIAL_REVOKED, 0);
        }
        return new Resolution(null, null, hint, AccessReason.UNKNOWN_CREDENTIAL, 0);
    }

    /** Credential-level refusal for an entry (member status itself is judged by the shared entry rules). */
    private AccessReason credentialProblem(AccessCredential credential, Member member) {
        switch (credential.getStatus()) {
            case PENDING:
                return AccessReason.CREDENTIAL_PENDING;
            case REVOKED:
                return AccessReason.CREDENTIAL_REVOKED;
            case SUSPENDED:
                return credential.getSuspensionSource() == SuspensionSource.MEMBER_STATUS && !member.isActive()
                        ? null   // let the shared rules report MEMBER_SUSPENDED / MEMBER_INACTIVE
                        : AccessReason.CREDENTIAL_SUSPENDED;
            default:
                break;
        }
        if (credential.getType().isBiometric() && consents.findActive(member.getId())
                .map(c -> !c.getId().equals(credential.getConsentId())).orElse(true)) {
            return AccessReason.CONSENT_WITHDRAWN;
        }
        return null;
    }

    /** For offline events: was the credential already unusable at the time the device admitted the member? */
    private AccessReason credentialProblemAt(AccessCredential credential, Member member, Instant at) {
        if (credential.getStatus() == CredentialStatus.REVOKED && credential.getRevokedAt() != null
                && credential.getRevokedAt().isBefore(at)) {
            return AccessReason.CREDENTIAL_REVOKED;
        }
        if (credential.getStatus() == CredentialStatus.SUSPENDED && credential.getSuspendedAt() != null
                && credential.getSuspendedAt().isBefore(at)) {
            return member.isActive() ? AccessReason.CREDENTIAL_SUSPENDED : AccessReason.MEMBER_SUSPENDED;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private GateDecision replay(AccessEvent event, Instant now) {
        String name = event.getMemberId() == null ? null
                : members.findById(event.getMemberId()).map(GateDecisionService::displayName).orElse(null);
        return toDecision(event, name, true, now);
    }

    private GateDecision toDecision(AccessEvent e, String displayName, boolean replayed, Instant now) {
        boolean open = e.getDecision() == Decision.GRANTED && !e.isOffline();
        return new GateDecision(e.getId(), e.getDeviceEventId(), e.getDecision(), e.getReasonCode(), e.getMessage(), open,
                open ? props.gateOpenSeconds() : 0, displayName, e.getDirection(), e.isOffline(), e.isAntiPassbackViolation(),
                replayed, now);
    }

    private Instant clampToNow(Instant occurredAt, Instant now) {
        return occurredAt.isAfter(now.plus(Duration.ofSeconds(5))) ? now : occurredAt;
    }

    /** First name and last initial only: enough for the gate display, no more. */
    static String displayName(Member member) {
        String last = member.getLastName() == null || member.getLastName().isBlank() ? "" : " " + member.getLastName().charAt(0) + ".";
        return member.getFirstName() + last;
    }

    static String describe(AccessReason reason) {
        return switch (reason) {
            case UNKNOWN_CREDENTIAL -> "Credential not recognised";
            case CREDENTIAL_PENDING -> "Enrolment not completed yet";
            case CREDENTIAL_REVOKED -> "Credential has been revoked";
            case CREDENTIAL_SUSPENDED -> "Credential is suspended";
            case CONSENT_WITHDRAWN -> "Biometric consent is not active";
            case QR_INVALID -> "Invalid QR code";
            case QR_EXPIRED -> "QR code expired; refresh the pass in the app";
            case QR_REUSED -> "QR code already used";
            default -> reason.name();
        };
    }
}
