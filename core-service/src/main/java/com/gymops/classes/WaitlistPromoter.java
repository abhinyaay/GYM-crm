package com.gymops.classes;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills freed seats from the waitlist, first come first served (by {@code booked_at}). Callers must
 * hold the session row lock, so two cancellations can never promote into the same seat.
 */
@Component
@RequiredArgsConstructor
public class WaitlistPromoter {

    private final ClassBookingRepository bookingRepository;
    private final ClassEntitlementService entitlement;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    /** @return number of members promoted */
    @Transactional(propagation = Propagation.MANDATORY)
    public int fillFreedSeats(ClassSession lockedSession, Instant now) {
        if (!lockedSession.isScheduled() || lockedSession.hasStarted(now)) {
            return 0;
        }
        bookingRepository.flush();
        long free = lockedSession.getCapacity()
                - bookingRepository.countBySessionAndStatuses(lockedSession.getId(), BookingStatus.SEAT_HOLDING);
        if (free <= 0) {
            return 0;
        }
        List<ClassBooking> waitlist = bookingRepository.findBySessionAndStatuses(lockedSession.getId(),
                EnumSet.of(BookingStatus.WAITLISTED));
        int promoted = 0;
        for (ClassBooking candidate : waitlist) {
            if (promoted >= free) {
                break;
            }
            // credits are consumed on promotion; a member who has used them up keeps their place in the queue
            if (!entitlement.hasCreditFor(candidate.getMember().getId(), lockedSession)) {
                continue;
            }
            candidate.promote(now);
            promoted++;
            audit.record(AuditEvent.of("CLASS_BOOKING", candidate.getId(), "PROMOTED_FROM_WAITLIST")
                    .member(candidate.getMember().getId()).branch(lockedSession.getBranch().getId())
                    .with("sessionId", lockedSession.getId()));
            events.publishEvent(new ClassEvents.WaitlistPromoted(candidate.getId()));
        }
        return promoted;
    }
}
