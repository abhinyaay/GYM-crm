package com.gymops.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gymops.access.GateAuthenticator.DeviceContext;
import com.gymops.access.dto.AccessDtos.GateDecision;
import com.gymops.access.dto.AccessDtos.ScanRequest;
import com.gymops.attendance.Attendance;
import com.gymops.attendance.AttendanceService;
import com.gymops.attendance.CheckInMethod;
import com.gymops.attendance.EntryCheck;
import com.gymops.attendance.EntryDenial;
import com.gymops.branch.GymBranch;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.subscription.ChangeType;
import com.gymops.subscription.Subscription;
import com.gymops.support.Fixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Decision logic of a gate scan with the persistence and attendance rules mocked out. */
@ExtendWith(MockitoExtension.class)
class GateDecisionServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = LocalDate.of(2026, 3, 10).atTime(18, 30).atZone(IST).toInstant();
    private static final String UID = "04A1B2C3";

    @Mock AccessEventRepository events;
    @Mock AccessDeviceRepository devices;
    @Mock AccessCredentialRepository credentials;
    @Mock BiometricConsentRepository consents;
    @Mock MemberRepository members;
    @Mock AttendanceService attendance;
    @Mock QrPassService qrPasses;
    @Mock ReplayGuard replayGuard;

    private GymBranch branch;
    private Member member;
    private AccessDevice device;
    private Subscription subscription;
    private final DeviceContext ctx = new DeviceContext(9L, "gd_test", 1L, "s".repeat(64), "nonce-0123456789abcdef", "10.0.0.1");

    @BeforeEach
    void setUp() {
        branch = Fixtures.branch(1);
        member = Fixtures.member(5, branch);
        device = new AccessDevice("gd_test", branch, "Turnstile", DeviceType.TURNSTILE, DeviceDirection.BOTH, NOW);
        ReflectionTestUtils.setField(device, "id", 9L);
        subscription = Subscription.pending(member, Fixtures.monthlyPlan(), branch, LocalDate.of(2026, 3, 1), ChangeType.NEW, null);
        AccessCredential card = AccessCredential.card(member, CredentialHashes.cardHash(UID), "Card …B2C3", null, NOW);
        ReflectionTestUtils.setField(card, "id", 77L);
        lenient().when(devices.findById(9L)).thenReturn(Optional.of(device));
        lenient().when(events.findByDeviceIdAndDeviceEventId(eq(9L), anyString())).thenReturn(Optional.empty());
        lenient().when(credentials.findLiveByHash(CredentialType.RFID_CARD, CredentialHashes.cardHash(UID)))
                .thenReturn(Optional.of(card));
        lenient().when(members.findByIdForUpdate(5L)).thenReturn(Optional.of(member));
        lenient().when(events.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attendance.evaluateEntry(eq(member), eq(branch), eq(NOW), eq(EntryCheck.Options.GATE_LIVE)))
                .thenReturn(EntryCheck.allowed(subscription));
        lenient().when(attendance.recordEntry(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> visit(branch, NOW));
        lenient().when(attendance.recordExit(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private GateDecisionService service(AntiPassbackMode mode) {
        AccessProperties props = new AccessProperties(true, null, Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofSeconds(30), 1, mode, 24, Duration.ofMinutes(5), 5, Duration.ofMinutes(1));
        return new GateDecisionService(events, devices, credentials, consents, members, attendance, qrPasses, replayGuard,
                props, new SimpleMeterRegistry(), Clock.fixed(NOW, IST));
    }

    private Attendance visit(GymBranch at, Instant checkIn) {
        Attendance a = new Attendance(member, at, subscription, checkIn, CheckInMethod.GATE, null);
        ReflectionTestUtils.setField(a, "id", 500L);
        return a;
    }

    private static ScanRequest scan(Direction direction) {
        return new ScanRequest("ev-1", CredentialType.RFID_CARD, UID, direction, null, null, null);
    }

    @Test
    void entryIsGrantedAndCreatesAGateVisit() {
        when(attendance.lockOpenVisit(5L)).thenReturn(Optional.empty());
        GateDecision d = service(AntiPassbackMode.HARD).process(ctx, scan(Direction.ENTRY));
        assertThat(d.decision()).isEqualTo(Decision.GRANTED);
        assertThat(d.reasonCode()).isEqualTo(AccessReason.OK);
        assertThat(d.openGate()).isTrue();
        assertThat(d.openSeconds()).isEqualTo(5);
        assertThat(d.memberDisplayName()).isEqualTo("Test M.");
        verify(attendance).recordEntry(member, branch, subscription, NOW, CheckInMethod.GATE, null);
    }

    @Test
    void hardAntiPassbackDeniesASecondEntry() {
        when(attendance.lockOpenVisit(5L)).thenReturn(Optional.of(visit(branch, NOW.minusSeconds(3600))));
        GateDecision d = service(AntiPassbackMode.HARD).process(ctx, scan(Direction.ENTRY));
        assertThat(d.decision()).isEqualTo(Decision.DENIED);
        assertThat(d.reasonCode()).isEqualTo(AccessReason.ANTI_PASSBACK);
        assertThat(d.antiPassbackViolation()).isTrue();
        assertThat(d.openGate()).isFalse();
        verify(attendance, never()).recordEntry(any(), any(), any(), any(), any(), any());
    }

    @Test
    void softAntiPassbackOpensClosesTheStaleVisitAndStartsANewOne() {
        Attendance stale = visit(branch, NOW.minusSeconds(7200));
        when(attendance.lockOpenVisit(5L)).thenReturn(Optional.of(stale));
        GateDecision d = service(AntiPassbackMode.SOFT).process(ctx, scan(Direction.ENTRY));
        assertThat(d.decision()).isEqualTo(Decision.GRANTED);
        assertThat(d.reasonCode()).isEqualTo(AccessReason.ANTI_PASSBACK);
        assertThat(d.antiPassbackViolation()).isTrue();
        verify(attendance).recordExit(stale, NOW);
        verify(attendance).recordEntry(member, branch, subscription, NOW, CheckInMethod.GATE, null);
    }

    @Test
    void exitWithoutBeingInsideIsDeniedInHardModeAndFlaggedInSoftMode() {
        when(attendance.lockOpenVisit(5L)).thenReturn(Optional.empty());
        GateDecision hard = service(AntiPassbackMode.HARD).process(ctx, scan(Direction.EXIT));
        assertThat(hard.decision()).isEqualTo(Decision.DENIED);
        assertThat(hard.reasonCode()).isEqualTo(AccessReason.ANTI_PASSBACK);
        GateDecision soft = service(AntiPassbackMode.SOFT).process(ctx, scan(Direction.EXIT));
        assertThat(soft.decision()).isEqualTo(Decision.GRANTED);
        assertThat(soft.antiPassbackViolation()).isTrue();
        verify(attendance, never()).recordExit(any(), any());
    }

    @Test
    void exitClosesTheOpenVisitAtThisBranchButNotAnotherBranchs() {
        Attendance here = visit(branch, NOW.minusSeconds(3600));
        when(attendance.lockOpenVisit(5L)).thenReturn(Optional.of(here));
        assertThat(service(AntiPassbackMode.HARD).process(ctx, scan(Direction.EXIT)).decision()).isEqualTo(Decision.GRANTED);
        verify(attendance).recordExit(here, NOW);

        Attendance elsewhere = visit(Fixtures.branch(2), NOW.minusSeconds(3600));
        when(attendance.lockOpenVisit(5L)).thenReturn(Optional.of(elsewhere));
        GateDecision d = service(AntiPassbackMode.HARD).process(ctx, scan(Direction.EXIT));
        assertThat(d.decision()).isEqualTo(Decision.DENIED);
        assertThat(d.message()).contains("another branch");
    }

    @Test
    void sharedRuleDenialIsReportedWithTheGateReasonCode() {
        when(attendance.evaluateEntry(member, branch, NOW, EntryCheck.Options.GATE_LIVE))
                .thenReturn(EntryCheck.denied(EntryDenial.MEMBERSHIP_FROZEN, "No active membership (current state: FROZEN)"));
        GateDecision d = service(AntiPassbackMode.HARD).process(ctx, scan(Direction.ENTRY));
        assertThat(d.decision()).isEqualTo(Decision.DENIED);
        assertThat(d.reasonCode()).isEqualTo(AccessReason.FROZEN);
        verify(attendance, never()).lockOpenVisit(anyLong());
    }

    @Test
    void manuallySuspendedCardIsDeniedBeforeTheMembershipRules() {
        AccessCredential card = credentials.findLiveByHash(CredentialType.RFID_CARD, CredentialHashes.cardHash(UID)).orElseThrow();
        card.suspend(SuspensionSource.MANUAL, NOW);
        GateDecision d = service(AntiPassbackMode.HARD).process(ctx, scan(Direction.ENTRY));
        assertThat(d.reasonCode()).isEqualTo(AccessReason.CREDENTIAL_SUSPENDED);
        verify(attendance, never()).evaluateEntry(any(), any(), any(), any());
    }

    @Test
    void unknownCardIsDeniedWithoutLockingAnyone() {
        when(credentials.findLiveByHash(eq(CredentialType.RFID_CARD), anyString())).thenReturn(Optional.empty());
        when(credentials.findRevokedByHash(eq(CredentialType.RFID_CARD), anyString())).thenReturn(List.of());
        GateDecision d = service(AntiPassbackMode.HARD).process(ctx, scan(Direction.ENTRY));
        assertThat(d.reasonCode()).isEqualTo(AccessReason.UNKNOWN_CREDENTIAL);
        verify(members, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void reusedQrWindowIsDenied() {
        AccessCredential qr = AccessCredential.qrPass(member, NOW);
        ReflectionTestUtils.setField(qr, "id", 88L);
        when(qrPasses.verify("GQ1.token", NOW)).thenReturn(new QrPassService.QrCheck(5L, qr, 1234L, null));
        when(qrPasses.claimExpiry(1234L)).thenReturn(NOW.plusSeconds(90));
        when(replayGuard.claim("qr", "5:1234", NOW.plusSeconds(90))).thenReturn(false);
        GateDecision d = service(AntiPassbackMode.HARD).process(ctx,
                new ScanRequest("ev-qr", CredentialType.QR, "GQ1.token", Direction.ENTRY, null, null, null));
        assertThat(d.reasonCode()).isEqualTo(AccessReason.QR_REUSED);
    }

    @Test
    void scanOnAnEntryOnlyDeviceInTheExitDirectionIsRefused() {
        ReflectionTestUtils.setField(device, "direction", DeviceDirection.ENTRY);
        GateDecision d = service(AntiPassbackMode.HARD).process(ctx, scan(Direction.EXIT));
        assertThat(d.reasonCode()).isEqualTo(AccessReason.DIRECTION_NOT_ALLOWED);
    }
}
