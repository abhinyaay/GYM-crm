package com.gymops.trainer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberTrainerRepository extends JpaRepository<MemberTrainer, Long> {

    @Query("select a from MemberTrainer a join fetch a.trainer t join fetch t.user where a.member.id = :memberId and a.status = 'ACTIVE'")
    Optional<MemberTrainer> findActiveByMemberId(@Param("memberId") Long memberId);

    @Query("""
            select a from MemberTrainer a join fetch a.member
            where a.trainer.id = :trainerId and a.status = 'ACTIVE' order by a.assignedAt
            """)
    List<MemberTrainer> findActiveByTrainerId(@Param("trainerId") Long trainerId);

    @Query("select count(a) from MemberTrainer a where a.trainer.id = :trainerId and a.status = 'ACTIVE'")
    long countActiveByTrainerId(@Param("trainerId") Long trainerId);

    @Query("select count(a) > 0 from MemberTrainer a where a.trainer.id = :trainerId and a.member.id = :memberId and a.status = 'ACTIVE'")
    boolean isActiveClient(@Param("trainerId") Long trainerId, @Param("memberId") Long memberId);
}
