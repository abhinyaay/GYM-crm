package com.gymops.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.branch.GymBranch;
import com.gymops.classes.ClassSession.Policy;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.member.Member;
import com.gymops.support.Fixtures;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Booking state machine and session admission rules (pure domain, no Spring). */
class ClassBookingStateTest {

    private static final Instant START = Instant.parse("2026-10-20T12:30:00Z");

    private ClassSession session;
    private Member member;

    @BeforeEach
    void setUp() {
        GymBranch branch = Fixtures.branch(1);
        member = Fixtures.member(5, branch);
        ClassType yoga = new ClassType("Yoga", null, ClassCategory.YOGA, ClassIntensity.LOW, 60, 20);
        // capacity 2, waitlist 1, booking opens 48h before, free cancellation until 2h before
        session = new ClassSession(yoga, branch, null, "Studio A", START, START.plus(Duration.ofHours(1)),
                new Policy(2, 1, 48, 120), null);
    }

    private ClassBooking booking(BookingStatus status) {
        return new ClassBooking(session, member, status, START.minus(Duration.ofHours(10)), null);
    }

    @Test
    void admissionGivesSeatThenWaitlistThenFull() {
        assertThat(session.admissionFor(0, 0)).isEqualTo(BookingStatus.BOOKED);
        assertThat(session.admissionFor(1, 0)).isEqualTo(BookingStatus.BOOKED);
        assertThat(session.admissionFor(2, 0)).isEqualTo(BookingStatus.WAITLISTED);
        assertThatThrownBy(() -> session.admissionFor(2, 1)).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("Class is full");
    }

    @Test
    void bookingWindowOpensBeforeAndClosesAtStart() {
        assertThatThrownBy(() -> session.assertOpenForBooking(START.minus(Duration.ofHours(49))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Booking opens at");
        session.assertOpenForBooking(START.minus(Duration.ofHours(48)));
        session.assertOpenForBooking(START.minusSeconds(1));
        assertThatThrownBy(() -> session.assertOpenForBooking(START))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Booking has closed");
    }

    @Test
    void cancelledSessionCannotBeBooked() {
        session.cancel("Pipe burst", START.minus(Duration.ofHours(5)));
        assertThatThrownBy(() -> session.assertOpenForBooking(START.minus(Duration.ofHours(4))))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(session.getCancellationReason()).isEqualTo("Pipe burst");
    }

    @Test
    void cancellationBeforeCutoffIsFreeAndAfterCutoffIsLate() {
        assertThat(session.isLateCancellation(START.minus(Duration.ofMinutes(121)))).isFalse();
        assertThat(session.isLateCancellation(START.minus(Duration.ofMinutes(120)))).isFalse();
        assertThat(session.isLateCancellation(START.minus(Duration.ofMinutes(119)))).isTrue();

        ClassBooking early = booking(BookingStatus.BOOKED);
        assertThat(early.cancel(false, START.minus(Duration.ofHours(3)))).as("seat freed").isTrue();
        assertThat(early.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(early.getCancelledAt()).isNotNull();

        ClassBooking late = booking(BookingStatus.BOOKED);
        assertThat(late.cancel(true, START.minus(Duration.ofMinutes(30)))).isTrue();
        assertThat(late.getStatus()).isEqualTo(BookingStatus.LATE_CANCELLED);
        assertThat(BookingStatus.CREDIT_CONSUMING).contains(BookingStatus.LATE_CANCELLED).doesNotContain(BookingStatus.CANCELLED);
    }

    @Test
    void leavingTheWaitlistIsNeverLateAndFreesNoSeat() {
        ClassBooking waiting = booking(BookingStatus.WAITLISTED);
        assertThat(waiting.cancel(true, START.minus(Duration.ofMinutes(10)))).isFalse();
        assertThat(waiting.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancelledBookingCannotBeCancelledAgain() {
        ClassBooking b = booking(BookingStatus.BOOKED);
        b.cancel(false, START.minus(Duration.ofHours(5)));
        assertThatThrownBy(() -> b.cancel(false, START.minus(Duration.ofHours(4))))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void promotionMovesWaitlistedToBooked() {
        ClassBooking waiting = booking(BookingStatus.WAITLISTED);
        Instant now = START.minus(Duration.ofHours(3));
        waiting.promote(now);
        assertThat(waiting.getStatus()).isEqualTo(BookingStatus.BOOKED);
        assertThat(waiting.getPromotedAt()).isEqualTo(now);
        assertThatThrownBy(() -> waiting.promote(now)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void attendanceOnlyAfterStartAndOnlyForSeatHolders() {
        ClassBooking seat = booking(BookingStatus.BOOKED);
        assertThatThrownBy(() -> seat.markAttendance(BookingStatus.ATTENDED, START.minusSeconds(1)))
                .isInstanceOf(BusinessRuleException.class);
        seat.markAttendance(BookingStatus.ATTENDED, START.plusSeconds(60));
        assertThat(seat.getStatus()).isEqualTo(BookingStatus.ATTENDED);
        assertThat(seat.getAttendedAt()).isNotNull();
        seat.markAttendance(BookingStatus.NO_SHOW, START.plusSeconds(120));   // correction
        assertThat(seat.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(seat.getAttendedAt()).isNull();

        ClassBooking waiting = booking(BookingStatus.WAITLISTED);
        assertThatThrownBy(() -> waiting.markAttendance(BookingStatus.ATTENDED, START.plusSeconds(60)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> seat.markAttendance(BookingStatus.CANCELLED, START.plusSeconds(60)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void reconciliationSettlesUnmarkedSeats() {
        ClassBooking came = booking(BookingStatus.BOOKED);
        came.reconcile(true, START.plusSeconds(30), START.plus(Duration.ofHours(2)));
        assertThat(came.getStatus()).isEqualTo(BookingStatus.ATTENDED);
        ClassBooking absent = booking(BookingStatus.BOOKED);
        absent.reconcile(false, null, START.plus(Duration.ofHours(2)));
        assertThat(absent.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
        assertThatThrownBy(() -> absent.reconcile(true, START, START)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void sessionCancellationReleasesWithoutPenalty() {
        ClassBooking seat = booking(BookingStatus.BOOKED);
        seat.cancelWithoutPenalty(START.minus(Duration.ofHours(1)));
        assertThat(seat.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThatThrownBy(() -> session.cancel("late", START.plus(Duration.ofHours(1))))
                .as("an ended class cannot be cancelled").isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void rescheduleCannotDropCapacityBelowBookedSeats() {
        Instant now = START.minus(Duration.ofDays(1));
        assertThatThrownBy(() -> session.reschedule(null, null, START, START.plus(Duration.ofHours(1)), new Policy(1, 1, 48, 120),
                2, now)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("below the 2 members");
        session.reschedule(null, "Studio B", START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(2)),
                new Policy(3, 0, 24, 60), 2, now);
        assertThat(session.getCapacity()).isEqualTo(3);
        assertThat(session.getRoom()).isEqualTo("Studio B");
        assertThat(session.cancelCutoffAt()).isEqualTo(START);
    }
}
