package com.gymops.classes;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.classes.dto.ClassDtos.BookingRequest;
import com.gymops.classes.dto.ClassDtos.BookingResponse;
import com.gymops.classes.dto.ClassDtos.CreditsResponse;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking, cancellation, waitlist promotion and attendance.
 *
 * <p>Concurrency: every decision that depends on how many seats are taken runs while holding a
 * {@code SELECT ... FOR UPDATE} lock on the class_sessions row, so two members racing for the last
 * seat are serialised (one is BOOKED, the other WAITLISTED or rejected) and a freed seat is promoted
 * exactly once. Booking additionally locks the member row first, which serialises a member's own
 * concurrent bookings so monthly credits cannot be overspent. Lock order is always member then session.
 */
@Service
@RequiredArgsConstructor
public class ClassBookingService {

    private final ClassSessionRepository sessionRepository;
    private final ClassBookingRepository bookingRepository;
    private final MemberRepository memberRepository;
    private final ClassEntitlementService entitlement;
    private final WaitlistPromoter promoter;
    private final ClassAccess classAccess;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final ClassesProperties props;
    private final Clock clock;

    @Transactional
    public BookingResponse book(Long sessionId, BookingRequest request) {
        UserPrincipal caller = CurrentUser.get();
        Long memberId = caller.isMemberOnly() ? caller.memberId() : (request == null ? null : request.memberId());
        if (memberId == null) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "memberId is required");
        }
        if (caller.isMemberOnly() && request != null && request.memberId() != null && !request.memberId().equals(memberId)) {
            throw new ForbiddenException("Members can only book classes for themselves");
        }
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        ClassSession session = lockSession(sessionId);
        classAccess.assertCanActForMember(memberId, session);
        Instant now = Instant.now(clock);

        session.assertOpenForBooking(now);
        bookingRepository.findLive(sessionId, memberId).ifPresent(existing -> {
            throw new ConflictException("Member already has a " + existing.getStatus() + " booking for this class");
        });
        entitlement.assertCanBook(member, session, now);

        long seatsTaken = bookingRepository.countBySessionAndStatuses(sessionId, BookingStatus.SEAT_HOLDING);
        long waitlisted = bookingRepository.countBySessionAndStatuses(sessionId, EnumSet.of(BookingStatus.WAITLISTED));
        BookingStatus status;
        try {
            status = session.admissionFor(seatsTaken, waitlisted);
        } catch (ConflictException full) {
            meterRegistry.counter("gymops.classes.bookings", "result", "FULL").increment();
            throw full;
        }
        ClassBooking booking = bookingRepository.saveAndFlush(new ClassBooking(session, member, status, now, caller.userId()));
        Integer position = status == BookingStatus.WAITLISTED ? (int) waitlisted + 1 : null;
        audit.record(AuditEvent.of("CLASS_BOOKING", booking.getId(), status.name()).member(memberId)
                .branch(session.getBranch().getId()).with("sessionId", sessionId).with("waitlistPosition", position));
        events.publishEvent(status == BookingStatus.BOOKED ? new ClassEvents.ClassBooked(booking.getId())
                : new ClassEvents.ClassWaitlisted(booking.getId(), position));
        meterRegistry.counter("gymops.classes.bookings", "result", status.name()).increment();
        return BookingResponse.from(booking, position);
    }

    /**
     * Cancels a seat or waitlist place. Before the cutoff the credit is returned (CANCELLED); after it,
     * the seat is still released but the credit is used (LATE_CANCELLED). A freed seat goes to the
     * earliest waitlisted member under the same session lock.
     */
    @Transactional
    public BookingResponse cancel(Long bookingId) {
        Long sessionId = bookingRepository.findSessionIdOf(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        ClassSession session = lockSession(sessionId);
        // loaded after the lock, so its state cannot have changed since another transaction released it
        ClassBooking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new ResourceNotFoundException("Booking",
                bookingId));
        classAccess.assertCanActForMember(booking.getMember().getId(), session);
        Instant now = Instant.now(clock);
        if (!session.isScheduled()) {
            throw new InvalidStateTransitionException("booking of a class", session.getStatus(), "cancel");
        }
        if (session.hasStarted(now)) {
            throw new BusinessRuleException("The class has already started; bookings can no longer be cancelled");
        }
        boolean late = session.isLateCancellation(now);
        boolean freedSeat = booking.cancel(late, now);
        int promoted = freedSeat ? promoter.fillFreedSeats(session, now) : 0;
        audit.record(AuditEvent.of("CLASS_BOOKING", bookingId, booking.getStatus().name()).member(booking.getMember().getId())
                .branch(session.getBranch().getId()).with("sessionId", sessionId).with("promoted", promoted));
        meterRegistry.counter("gymops.classes.cancellations", "late", String.valueOf(booking.getStatus()
                == BookingStatus.LATE_CANCELLED)).increment();
        return BookingResponse.from(booking, null);
    }

    @Transactional
    public BookingResponse markAttendance(Long bookingId, BookingStatus outcome) {
        Long sessionId = bookingRepository.findSessionIdOf(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        ClassSession session = lockSession(sessionId);
        if (!classAccess.canMarkAttendance(session)) {
            throw new ForbiddenException("Only the instructor or front desk of the branch can record attendance");
        }
        if (session.getStatus() == ClassSessionStatus.CANCELLED) {
            throw new InvalidStateTransitionException("class", session.getStatus(), "record attendance for");
        }
        ClassBooking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new ResourceNotFoundException("Booking",
                bookingId));
        BookingStatus previous = booking.getStatus();
        booking.markAttendance(outcome, Instant.now(clock));
        audit.record(AuditEvent.of("CLASS_BOOKING", bookingId, "ATTENDANCE_" + outcome).member(booking.getMember().getId())
                .branch(session.getBranch().getId()).with("from", previous));
        return BookingResponse.from(booking, null);
    }

    // ------------------------------------------------------------------ member views

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> myBookings(boolean upcoming, Pageable pageable) {
        UserPrincipal caller = CurrentUser.get();
        if (caller.memberId() == null) {
            throw new ForbiddenException("No member profile is linked to this login");
        }
        return bookingsOf(caller.memberId(), upcoming, pageable);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> memberBookings(Long memberId, boolean upcoming, Pageable pageable) {
        classAccess.assertMemberAccess(memberId);
        return bookingsOf(memberId, upcoming, pageable);
    }

    @Transactional(readOnly = true)
    public CreditsResponse myCredits(YearMonth month) {
        UserPrincipal caller = CurrentUser.get();
        if (caller.memberId() == null) {
            throw new ForbiddenException("No member profile is linked to this login");
        }
        return entitlement.credits(caller.memberId(), month);
    }

    @Transactional(readOnly = true)
    public CreditsResponse memberCredits(Long memberId, YearMonth month) {
        classAccess.assertMemberAccess(memberId);
        return entitlement.credits(memberId, month);
    }

    private PageResponse<BookingResponse> bookingsOf(Long memberId, boolean upcoming, Pageable pageable) {
        Instant now = Instant.now(clock);
        Page<ClassBooking> page = upcoming ? bookingRepository.findMemberUpcoming(memberId, now, pageable)
                : bookingRepository.findMemberPast(memberId, now, pageable);
        return PageResponse.of(page, b -> BookingResponse.from(b, waitlistPosition(b)));
    }

    private Integer waitlistPosition(ClassBooking b) {
        if (b.getStatus() != BookingStatus.WAITLISTED) {
            return null;
        }
        return (int) bookingRepository.countWaitlistedAhead(b.getSession().getId(), b.getBookedAt(), b.getId()) + 1;
    }

    // ------------------------------------------------------------------ jobs

    /** Outcome of reconciling one class. */
    public record Reconciliation(boolean completed, int attended, int noShows, int waitlistExpired) {
        static final Reconciliation SKIPPED = new Reconciliation(false, 0, 0, 0);
    }

    /**
     * Completes a class that ended at least {@code reconcileAfter} ago and settles seats nobody marked:
     * a gym check-in at the branch overlapping the class window means ATTENDED, otherwise NO_SHOW.
     * Members still on the waitlist are released. Idempotent: a completed class is skipped.
     */
    @Transactional
    public Reconciliation completeAndReconcile(Long sessionId) {
        ClassSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        Instant now = Instant.now(clock);
        if (session == null || !session.isScheduled() || session.getEndsAt().plus(props.reconcileAfter()).isAfter(now)) {
            return Reconciliation.SKIPPED;
        }
        session.complete();
        int attended = 0;
        int noShows = 0;
        int expired = 0;
        Long branchId = session.getBranch().getId();
        for (ClassBooking b : bookingRepository.findBySessionAndStatuses(sessionId, BookingStatus.LIVE)) {
            if (b.getStatus() == BookingStatus.WAITLISTED) {
                b.cancelWithoutPenalty(now);
                expired++;
                continue;
            }
            Optional<Instant> checkIn = bookingRepository.findOverlappingCheckIn(b.getMember().getId(), branchId,
                    session.getStartsAt(), session.getEndsAt(), session.getStartsAt().minus(props.reconcileOpenVisitWindow()));
            b.reconcile(checkIn.isPresent(), checkIn.map(t -> t.isBefore(session.getStartsAt()) ? session.getStartsAt() : t)
                    .orElse(null), now);
            if (checkIn.isPresent()) {
                attended++;
            } else {
                noShows++;
            }
        }
        audit.record(AuditEvent.of("CLASS_SESSION", sessionId, "COMPLETED").branch(branchId)
                .with("attended", attended).with("noShows", noShows).with("waitlistExpired", expired));
        return new Reconciliation(true, attended, noShows, expired);
    }

    /** Queues reminders for booked members of classes starting within the lead time (dedup per booking). */
    @Transactional
    public int queueReminders() {
        Instant now = Instant.now(clock);
        List<Long> sessions = sessionRepository.findIdsStartingBetween(now, now.plus(props.reminderLeadTime()));
        int queued = 0;
        for (Long sessionId : sessions) {
            for (ClassBooking b : bookingRepository.findBySessionAndStatuses(sessionId, EnumSet.of(BookingStatus.BOOKED))) {
                events.publishEvent(new ClassEvents.ReminderDue(b.getId()));
                queued++;
            }
        }
        return queued;
    }

    private ClassSession lockSession(Long sessionId) {
        return sessionRepository.findByIdForUpdate(sessionId).orElseThrow(() -> new ResourceNotFoundException("Class", sessionId));
    }
}
