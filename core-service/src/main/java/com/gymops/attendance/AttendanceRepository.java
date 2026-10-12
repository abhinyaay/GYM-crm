package com.gymops.attendance;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    @Query("select a from Attendance a where a.member.id = :memberId and a.checkOutTime is null")
    Optional<Attendance> findOpenVisit(@Param("memberId") Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attendance a where a.member.id = :memberId and a.checkOutTime is null")
    Optional<Attendance> findOpenVisitForUpdate(@Param("memberId") Long memberId);

    @Query("select count(a) from Attendance a where a.branch.id = :branchId and a.checkOutTime is null")
    long countOpenAtBranch(@Param("branchId") Long branchId);

    @Query("""
            select count(a) from Attendance a
            where a.member.id = :memberId and a.checkInTime >= :from and a.checkInTime < :to
            """)
    long countMemberVisitsBetween(@Param("memberId") Long memberId, @Param("from") Instant from, @Param("to") Instant to);

    long countByMemberId(Long memberId);

    @Query("""
            select a from Attendance a
            where a.member.id = :memberId and a.checkInTime >= :from and a.checkInTime < :to
            order by a.checkInTime desc
            """)
    Page<Attendance> findMemberHistory(@Param("memberId") Long memberId, @Param("from") Instant from,
                                       @Param("to") Instant to, Pageable pageable);

    @Query(value = """
            select a from Attendance a join fetch a.member
            where a.branch.id = :branchId and a.checkInTime >= :from and a.checkInTime < :to
            order by a.checkInTime desc
            """, countQuery = """
            select count(a) from Attendance a
            where a.branch.id = :branchId and a.checkInTime >= :from and a.checkInTime < :to
            """)
    Page<Attendance> findBranchVisits(@Param("branchId") Long branchId, @Param("from") Instant from,
                                      @Param("to") Instant to, Pageable pageable);

    @Query("""
            select count(a), count(distinct a.member.id) from Attendance a
            where a.branch.id = :branchId and a.checkInTime >= :from and a.checkInTime < :to
            """)
    List<Object[]> visitAndUniqueCounts(@Param("branchId") Long branchId, @Param("from") Instant from,
                                        @Param("to") Instant to);

    @Query(value = """
            select coalesce(avg(extract(epoch from (check_out_time - check_in_time)) / 60), 0)
            from attendance
            where branch_id = :branchId and check_in_time >= :from and check_in_time < :to
              and check_out_time is not null and not auto_checked_out
            """, nativeQuery = true)
    Double averageVisitMinutes(@Param("branchId") Long branchId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select count(a) from Attendance a
            where a.checkInTime >= :from and a.checkInTime < :to
              and (:allBranches = true or a.branch.id in :branchIds)
            """)
    long countCheckIns(@Param("from") Instant from, @Param("to") Instant to,
                       @Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);

    @Query("select a.id from Attendance a where a.checkOutTime is null and a.checkInTime < :cutoff")
    List<Long> findStaleOpenVisitIds(@Param("cutoff") Instant cutoff);

    @Query("select max(a.checkInTime) from Attendance a where a.member.id = :memberId")
    Optional<Instant> findLastVisit(@Param("memberId") Long memberId);
}
