package com.gymops.classes;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.member.Member;
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

/**
 * A member's place in a class (a seat or a waitlist position). At most one live (BOOKED/WAITLISTED)
 * booking per member and session is enforced by the partial unique index {@code ux_class_booking_live}.
 * State transitions live here; the service decides <em>when</em> they happen under the session lock.
 */
@Getter
@Entity
@Table(name = "class_bookings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private ClassSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "attended_at")
    private Instant attendedAt;

    @Column(name = "booked_by")
    private Long bookedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public ClassBooking(ClassSession session, Member member, BookingStatus initialStatus, Instant now, Long bookedBy) {
        if (!initialStatus.isLive()) {
            throw new IllegalArgumentException("A new booking must be BOOKED or WAITLISTED");
        }
        this.session = session;
        this.member = member;
        this.status = initialStatus;
        this.bookedAt = now;
        this.bookedBy = bookedBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isLive() {
        return status.isLive();
    }

    /**
     * Member/staff cancellation. A seat given up after the cutoff becomes LATE_CANCELLED (the credit is
     * used); a waitlist place can always be given up without penalty.
     *
     * @return true when a seat was freed (so the waitlist should be promoted)
     */
    public boolean cancel(boolean late, Instant now) {
        boolean freedSeat = status == BookingStatus.BOOKED;
        switch (status) {
            case BOOKED -> status = late ? BookingStatus.LATE_CANCELLED : BookingStatus.CANCELLED;
            case WAITLISTED -> status = BookingStatus.CANCELLED;
            default -> throw new InvalidStateTransitionException("booking", status, "cancel");
        }
        cancelledAt = now;
        updatedAt = now;
        return freedSeat;
    }

    /** The class itself was cancelled (or ended with this member still waitlisted): no penalty. */
    public void cancelWithoutPenalty(Instant now) {
        if (!isLive()) {
            throw new InvalidStateTransitionException("booking", status, "cancel");
        }
        status = BookingStatus.CANCELLED;
        cancelledAt = now;
        updatedAt = now;
    }

    /** First-come-first-served promotion from the waitlist into a freed seat. */
    public void promote(Instant now) {
        if (status != BookingStatus.WAITLISTED) {
            throw new InvalidStateTransitionException("booking", status, "promote");
        }
        status = BookingStatus.BOOKED;
        promotedAt = now;
        updatedAt = now;
    }

    /**
     * Instructor/front-desk attendance. Allowed once the class has started, for seat holders only;
     * an earlier mark may be corrected (ATTENDED <-> NO_SHOW).
     */
    public void markAttendance(BookingStatus outcome, Instant now) {
        if (outcome != BookingStatus.ATTENDED && outcome != BookingStatus.NO_SHOW) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Attendance must be ATTENDED or NO_SHOW");
        }
        if (!status.holdsSeat()) {
            throw new InvalidStateTransitionException("booking", status, "mark " + outcome + " on");
        }
        if (!session.hasStarted(now)) {
            throw new BusinessRuleException("Attendance can only be recorded once the class has started");
        }
        status = outcome;
        attendedAt = outcome == BookingStatus.ATTENDED ? now : null;
        updatedAt = now;
    }

    /** Used by the reconciliation job for seats nobody marked: gym check-in during the class means attended. */
    public void reconcile(boolean checkedIn, Instant attendedAt, Instant now) {
        if (status != BookingStatus.BOOKED) {
            throw new InvalidStateTransitionException("booking", status, "reconcile");
        }
        status = checkedIn ? BookingStatus.ATTENDED : BookingStatus.NO_SHOW;
        this.attendedAt = checkedIn ? attendedAt : null;
        updatedAt = now;
    }
}
