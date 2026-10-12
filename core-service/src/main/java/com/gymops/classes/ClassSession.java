package com.gymops.classes;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.trainer.Trainer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One scheduled occurrence of a class. Capacity decisions are taken while holding a row lock on
 * this session (see {@code ClassSessionRepository#findByIdForUpdate}); an instructor teaching two
 * overlapping classes is rejected by the exclusion constraint {@code ex_class_sessions_instructor_no_overlap}.
 */
@Getter
@Entity
@Table(name = "class_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_type_id")
    private ClassType classType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instructor_id")
    private Trainer instructor;

    private String room;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "waitlist_capacity", nullable = false)
    private int waitlistCapacity;

    @Column(name = "booking_opens_hours", nullable = false)
    private int bookingOpensHours;

    @Column(name = "cancel_cutoff_minutes", nullable = false)
    private int cancelCutoffMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClassSessionStatus status = ClassSessionStatus.SCHEDULED;

    @Column(name = "series_id")
    private UUID seriesId;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    /** Booking/cancellation policy of a session. */
    public record Policy(int capacity, int waitlistCapacity, int bookingOpensHours, int cancelCutoffMinutes) {
    }

    public ClassSession(ClassType classType, GymBranch branch, Trainer instructor, String room, Instant startsAt,
                        Instant endsAt, Policy policy, UUID seriesId) {
        if (!endsAt.isAfter(startsAt)) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "A class must end after it starts");
        }
        this.classType = classType;
        this.branch = branch;
        this.instructor = instructor;
        this.room = room;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.seriesId = seriesId;
        applyPolicy(policy);
    }

    // ------------------------------------------------------------------ queries

    public boolean isScheduled() {
        return status == ClassSessionStatus.SCHEDULED;
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startsAt);
    }

    public Instant bookingOpensAt() {
        return startsAt.minus(Duration.ofHours(bookingOpensHours));
    }

    /** After this instant a cancellation is a late cancellation (still counts against the member's credits). */
    public Instant cancelCutoffAt() {
        return startsAt.minus(Duration.ofMinutes(cancelCutoffMinutes));
    }

    public boolean isLateCancellation(Instant now) {
        return now.isAfter(cancelCutoffAt());
    }

    public long durationMinutes() {
        return Duration.between(startsAt, endsAt).toMinutes();
    }

    // ------------------------------------------------------------------ rules

    /** A booking may be made only while the session is scheduled and between the booking window opening and the start. */
    public void assertOpenForBooking(Instant now) {
        if (!isScheduled()) {
            throw new InvalidStateTransitionException("class", status, "book");
        }
        if (hasStarted(now)) {
            throw new BusinessRuleException("Booking has closed: the class started at " + startsAt);
        }
        if (now.isBefore(bookingOpensAt())) {
            throw new BusinessRuleException("Booking opens at " + bookingOpensAt() + " (" + bookingOpensHours
                    + "h before the class)");
        }
    }

    /**
     * Decides the status of a new booking from the current occupancy: a seat if one is free, otherwise
     * a waitlist place if the waitlist has room, otherwise the class is full. Must be called while
     * holding the session lock so the counts cannot change underneath.
     */
    public BookingStatus admissionFor(long seatsTaken, long waitlisted) {
        if (seatsTaken < capacity) {
            return BookingStatus.BOOKED;
        }
        if (waitlisted < waitlistCapacity) {
            return BookingStatus.WAITLISTED;
        }
        throw new ConflictException(ErrorCode.CLASS_FULL,
                "Class is full (" + capacity + " booked, waitlist of " + waitlistCapacity + " full)");
    }

    // ------------------------------------------------------------------ transitions

    public void cancel(String reason, Instant now) {
        if (!isScheduled()) {
            throw new InvalidStateTransitionException("class", status, "cancel");
        }
        if (!now.isBefore(endsAt)) {
            throw new BusinessRuleException("A class that has already ended cannot be cancelled");
        }
        this.status = ClassSessionStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public void complete() {
        if (!isScheduled()) {
            throw new InvalidStateTransitionException("class", status, "complete");
        }
        this.status = ClassSessionStatus.COMPLETED;
    }

    /** Changes time, instructor, room or policy of a class that has not started yet. */
    public void reschedule(Trainer instructor, String room, Instant startsAt, Instant endsAt, Policy policy,
                           long seatsTaken, Instant now) {
        if (!isScheduled()) {
            throw new InvalidStateTransitionException("class", status, "edit");
        }
        if (hasStarted(now)) {
            throw new BusinessRuleException("A class that has already started cannot be edited");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "A class must end after it starts");
        }
        if (!startsAt.isAfter(now)) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "A class cannot be moved into the past");
        }
        if (policy.capacity() < seatsTaken) {
            throw new BusinessRuleException("Capacity cannot be reduced below the " + seatsTaken + " members already booked");
        }
        this.instructor = instructor;
        this.room = room;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        applyPolicy(policy);
    }

    private void applyPolicy(Policy policy) {
        this.capacity = policy.capacity();
        this.waitlistCapacity = policy.waitlistCapacity();
        this.bookingOpensHours = policy.bookingOpensHours();
        this.cancelCutoffMinutes = policy.cancelCutoffMinutes();
    }
}
