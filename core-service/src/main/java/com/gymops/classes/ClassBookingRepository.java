package com.gymops.classes;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClassBookingRepository extends JpaRepository<ClassBooking, Long> {

    @Query("select b.session.id from ClassBooking b where b.id = :id")
    Optional<Long> findSessionIdOf(@Param("id") Long bookingId);

    @Query("""
            select b from ClassBooking b join fetch b.member join fetch b.session s join fetch s.classType join fetch s.branch
              join fetch s.instructor i join fetch i.user
            where b.id = :id
            """)
    Optional<ClassBooking> findDetailed(@Param("id") Long id);

    @Query("select count(b) from ClassBooking b where b.session.id = :sessionId and b.status in :statuses")
    long countBySessionAndStatuses(@Param("sessionId") Long sessionId, @Param("statuses") Collection<BookingStatus> statuses);

    /** (sessionId, status, count) rows for list screens. */
    @Query("""
            select b.session.id, b.status, count(b) from ClassBooking b
            where b.session.id in :sessionIds group by b.session.id, b.status
            """)
    List<Object[]> countsBySession(@Param("sessionIds") Collection<Long> sessionIds);

    @Query("""
            select b from ClassBooking b
            where b.session.id = :sessionId and b.member.id = :memberId
              and b.status in (com.gymops.classes.BookingStatus.BOOKED, com.gymops.classes.BookingStatus.WAITLISTED)
            """)
    Optional<ClassBooking> findLive(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);

    @Query("select b from ClassBooking b join fetch b.member where b.session.id = :sessionId order by b.bookedAt, b.id")
    List<ClassBooking> findRoster(@Param("sessionId") Long sessionId);

    @Query("""
            select b from ClassBooking b join fetch b.member
            where b.session.id = :sessionId and b.status in :statuses order by b.bookedAt, b.id
            """)
    List<ClassBooking> findBySessionAndStatuses(@Param("sessionId") Long sessionId,
                                                @Param("statuses") Collection<BookingStatus> statuses);

    @Query("""
            select count(b) from ClassBooking b
            where b.session.id = :sessionId and b.status = com.gymops.classes.BookingStatus.WAITLISTED
              and (b.bookedAt < :bookedAt or (b.bookedAt = :bookedAt and b.id < :id))
            """)
    long countWaitlistedAhead(@Param("sessionId") Long sessionId, @Param("bookedAt") Instant bookedAt, @Param("id") Long id);

    /** Credits used by a member: credit-consuming bookings of classes starting in [from, to). */
    @Query("""
            select count(b) from ClassBooking b
            where b.member.id = :memberId and b.status in :statuses
              and b.session.startsAt >= :from and b.session.startsAt < :to
            """)
    long countMemberBookingsBetween(@Param("memberId") Long memberId, @Param("statuses") Collection<BookingStatus> statuses,
                                    @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select b.session.startsAt from ClassBooking b
            where b.member.id = :memberId and b.status = com.gymops.classes.BookingStatus.NO_SHOW and b.session.startsAt >= :since
            """)
    List<Instant> findNoShowTimes(@Param("memberId") Long memberId, @Param("since") Instant since);

    /** The member's latest booking per listed session (for "my status" on timetables). */
    @Query("""
            select b from ClassBooking b
            where b.member.id = :memberId and b.session.id in :sessionIds order by b.bookedAt desc, b.id desc
            """)
    List<ClassBooking> findMemberBookingsForSessions(@Param("memberId") Long memberId,
                                                     @Param("sessionIds") Collection<Long> sessionIds);

    @Query(value = """
            select b from ClassBooking b join fetch b.member join fetch b.session s join fetch s.classType join fetch s.branch
              join fetch s.instructor i join fetch i.user
            where b.member.id = :memberId and s.endsAt > :now
            order by s.startsAt asc
            """, countQuery = """
            select count(b) from ClassBooking b where b.member.id = :memberId and b.session.endsAt > :now
            """)
    Page<ClassBooking> findMemberUpcoming(@Param("memberId") Long memberId, @Param("now") Instant now, Pageable pageable);

    @Query(value = """
            select b from ClassBooking b join fetch b.member join fetch b.session s join fetch s.classType join fetch s.branch
              join fetch s.instructor i join fetch i.user
            where b.member.id = :memberId and s.endsAt <= :now
            order by s.startsAt desc
            """, countQuery = """
            select count(b) from ClassBooking b where b.member.id = :memberId and b.session.endsAt <= :now
            """)
    Page<ClassBooking> findMemberPast(@Param("memberId") Long memberId, @Param("now") Instant now, Pageable pageable);

    /**
     * Earliest gym check-in of the member at the branch overlapping the class window. Visits never
     * checked out count only if they started within {@code openSince} (the auto check-out job closes stale ones).
     */
    @Query("""
            select min(a.checkInTime) from com.gymops.attendance.Attendance a
            where a.member.id = :memberId and a.branch.id = :branchId and a.checkInTime < :endsAt
              and (a.checkOutTime > :startsAt or (a.checkOutTime is null and a.checkInTime > :openSince))
            """)
    Optional<Instant> findOverlappingCheckIn(@Param("memberId") Long memberId, @Param("branchId") Long branchId,
                                             @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt,
                                             @Param("openSince") Instant openSince);
}
