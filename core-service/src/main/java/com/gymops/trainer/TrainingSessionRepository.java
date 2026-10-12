package com.gymops.trainer;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrainingSessionRepository extends JpaRepository<TrainingSession, Long> {

    @Query("""
            select s from TrainingSession s join fetch s.member
            where s.trainer.id = :trainerId and s.startsAt >= :from and s.startsAt < :to
            order by s.startsAt
            """)
    List<TrainingSession> findTrainerSchedule(@Param("trainerId") Long trainerId, @Param("from") Instant from,
                                              @Param("to") Instant to);

    @Query("""
            select s from TrainingSession s join fetch s.trainer t join fetch t.user
            where s.member.id = :memberId and s.startsAt >= :from order by s.startsAt
            """)
    List<TrainingSession> findUpcomingForMember(@Param("memberId") Long memberId, @Param("from") Instant from);

    @Query("""
            select s.status, count(s) from TrainingSession s
            where s.trainer.id = :trainerId and s.startsAt >= :from and s.startsAt < :to
            group by s.status
            """)
    List<Object[]> countByStatus(@Param("trainerId") Long trainerId, @Param("from") Instant from, @Param("to") Instant to);
}
