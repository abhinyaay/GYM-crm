package com.gymops.classes;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClassSessionRepository extends JpaRepository<ClassSession, Long> {

    /** Row lock that serialises every capacity decision (book, cancel, promote, edit) for one session. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ClassSession s where s.id = :id")
    Optional<ClassSession> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select s from ClassSession s join fetch s.classType join fetch s.branch join fetch s.instructor i join fetch i.user
            where s.id = :id
            """)
    Optional<ClassSession> findDetailed(@Param("id") Long id);

    @Query(value = """
            select s from ClassSession s join fetch s.classType join fetch s.branch join fetch s.instructor i join fetch i.user
            where (:allBranches = true or s.branch.id in :branchIds)
              and s.startsAt >= :from and s.startsAt < :to
              and (:classTypeId is null or s.classType.id = :classTypeId)
              and (:instructorId is null or s.instructor.id = :instructorId)
              and (:status is null or s.status = :status)
            """, countQuery = """
            select count(s) from ClassSession s
            where (:allBranches = true or s.branch.id in :branchIds)
              and s.startsAt >= :from and s.startsAt < :to
              and (:classTypeId is null or s.classType.id = :classTypeId)
              and (:instructorId is null or s.instructor.id = :instructorId)
              and (:status is null or s.status = :status)
            """)
    Page<ClassSession> search(@Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds,
                              @Param("from") Instant from, @Param("to") Instant to, @Param("classTypeId") Long classTypeId,
                              @Param("instructorId") Long instructorId, @Param("status") ClassSessionStatus status,
                              Pageable pageable);

    /** Scheduled classes of the instructor overlapping [start, end) (mirrors the exclusion constraint). */
    @Query("""
            select s from ClassSession s join fetch s.classType
            where s.instructor.id = :instructorId and s.status = com.gymops.classes.ClassSessionStatus.SCHEDULED
              and s.startsAt < :end and s.endsAt > :start and (:excludeId is null or s.id <> :excludeId)
            """)
    List<ClassSession> findInstructorOverlaps(@Param("instructorId") Long instructorId, @Param("start") Instant start,
                                              @Param("end") Instant end, @Param("excludeId") Long excludeId);

    @Query("""
            select s from ClassSession s join fetch s.classType
            where s.branch.id = :branchId and lower(s.room) = lower(:room)
              and s.status = com.gymops.classes.ClassSessionStatus.SCHEDULED
              and s.startsAt < :end and s.endsAt > :start and (:excludeId is null or s.id <> :excludeId)
            """)
    List<ClassSession> findRoomOverlaps(@Param("branchId") Long branchId, @Param("room") String room,
                                        @Param("start") Instant start, @Param("end") Instant end,
                                        @Param("excludeId") Long excludeId);

    @Query("""
            select s.id from ClassSession s
            where s.seriesId = :seriesId and s.status = com.gymops.classes.ClassSessionStatus.SCHEDULED
              and s.startsAt >= :from
            order by s.startsAt
            """)
    List<Long> findScheduledSeriesIdsFrom(@Param("seriesId") UUID seriesId, @Param("from") Instant from);

    @Query("select s from ClassSession s join fetch s.branch where s.seriesId = :seriesId order by s.startsAt")
    List<ClassSession> findBySeriesId(@Param("seriesId") UUID seriesId);

    /** Classes that ended at or before the cutoff but were never completed. */
    @Query("""
            select s.id from ClassSession s
            where s.status = com.gymops.classes.ClassSessionStatus.SCHEDULED and s.endsAt <= :cutoff
            order by s.endsAt
            """)
    List<Long> findIdsDueForCompletion(@Param("cutoff") Instant cutoff);

    @Query("""
            select s.id from ClassSession s
            where s.status = com.gymops.classes.ClassSessionStatus.SCHEDULED and s.startsAt > :from and s.startsAt <= :to
            """)
    List<Long> findIdsStartingBetween(@Param("from") Instant from, @Param("to") Instant to);

    long countByClassTypeId(Long classTypeId);
}
