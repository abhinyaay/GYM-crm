package com.gymops.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gymops.attendance.dto.AttendanceDtos.CheckInRequest;
import com.gymops.audit.AuditService;
import com.gymops.branch.BranchService;
import com.gymops.branch.GymBranch;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.plan.MembershipPlan;
import com.gymops.security.AccessPolicy;
import com.gymops.security.JwtServiceTest;
import com.gymops.subscription.ChangeType;
import com.gymops.subscription.MembershipQueryService;
import com.gymops.subscription.Subscription;
import com.gymops.subscription.dto.MembershipSummary;
import com.gymops.support.Fixtures;
import com.gymops.support.SecurityTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Tuesday 18:30 IST: evening peak. */
    private static final Instant NOW = LocalDate.of(2026, 3, 10).atTime(18, 30).atZone(IST).toInstant();

    @Mock AttendanceRepository repository;
    @Mock MemberRepository memberRepository;
    @Mock BranchService branchService;
    @Mock MembershipQueryService membershipQuery;
    @Mock AccessPolicy access;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;

    private AttendanceService service;
    private GymBranch home;
    private Member member;
    private MembershipPlan plan;

    @BeforeEach
    void setUp() {
        service = new AttendanceService(repository, memberRepository, branchService, membershipQuery, access, audit, events,
                new SimpleMeterRegistry(), JwtServiceTest.props(JwtServiceTest.SECRET_FOR_TESTS, "gymops-api"),
                Clock.fixed(NOW, IST));
        home = Fixtures.branch(1);
        member = Fixtures.member(5, home);
        plan = Fixtures.monthlyPlan();
        SecurityTestSupport.receptionist(1, 2);
        when(memberRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(member));
        lenient().when(branchService.load(1L)).thenReturn(home);
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private Subscription activeSubscription() {
        Subscription s = Subscription.pending(member, plan, home, LocalDate.of(2026, 3, 1), ChangeType.NEW, null);
        s.activate(LocalDate.of(2026, 3, 1), NOW);
        return s;
    }

    private static CheckInRequest checkIn(long branchId) {
        return new CheckInRequest(5L, null, branchId, CheckInMethod.FRONT_DESK);
    }

    @Test
    void validMemberIsCheckedIn() {
        when(repository.findOpenVisit(5L)).thenReturn(Optional.empty());
        when(membershipQuery.accessGrantingSubscription(5L, LocalDate.of(2026, 3, 10)))
                .thenReturn(Optional.of(activeSubscription()));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.countByMemberId(5L)).thenReturn(3L);

        var response = service.checkIn(checkIn(1));

        assertThat(response.memberId()).isEqualTo(5L);
        assertThat(response.checkInTime()).isEqualTo(NOW);
    }

    @Test
    void expiredMembershipIsDenied() {
        when(repository.findOpenVisit(5L)).thenReturn(Optional.empty());
        when(membershipQuery.accessGrantingSubscription(anyLong(), any())).thenReturn(Optional.empty());
        when(membershipQuery.currentMembership(5L)).thenReturn(MembershipSummary.none());

        assertThatThrownBy(() -> service.checkIn(checkIn(1)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.MEMBERSHIP_NOT_ACTIVE);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateCheckInIsRejected() {
        Attendance open = new Attendance(member, home, activeSubscription(), NOW.minusSeconds(600), CheckInMethod.QR_CODE, 1L);
        when(repository.findOpenVisit(5L)).thenReturn(Optional.of(open));

        assertThatThrownBy(() -> service.checkIn(checkIn(1)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.DUPLICATE_CHECK_IN);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void offPeakPlanIsDeniedDuringPeakHours() {
        plan.setAccessStartTime(LocalTime.of(10, 0));
        plan.setAccessEndTime(LocalTime.of(16, 0));
        when(repository.findOpenVisit(5L)).thenReturn(Optional.empty());
        when(membershipQuery.accessGrantingSubscription(anyLong(), any())).thenReturn(Optional.of(activeSubscription()));

        assertThatThrownBy(() -> service.checkIn(checkIn(1)))
                .isInstanceOf(ApiException.class).hasMessageContaining("allows entry only between");
    }

    @Test
    void homeBranchPlanIsDeniedAtAnotherBranch() {
        GymBranch other = Fixtures.branch(2);
        when(branchService.load(2L)).thenReturn(other);
        when(repository.findOpenVisit(5L)).thenReturn(Optional.empty());
        when(membershipQuery.accessGrantingSubscription(anyLong(), any())).thenReturn(Optional.of(activeSubscription()));

        assertThatThrownBy(() -> service.checkIn(checkIn(2)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.ACCESS_RESTRICTED);
    }

    @Test
    void branchAtCapacityIsDenied() {
        home.setCapacity(10);
        when(repository.findOpenVisit(5L)).thenReturn(Optional.empty());
        when(membershipQuery.accessGrantingSubscription(anyLong(), any())).thenReturn(Optional.of(activeSubscription()));
        when(repository.countOpenAtBranch(1L)).thenReturn(10L);

        assertThatThrownBy(() -> service.checkIn(checkIn(1))).isInstanceOf(ApiException.class)
                .hasMessageContaining("capacity");
    }
}
