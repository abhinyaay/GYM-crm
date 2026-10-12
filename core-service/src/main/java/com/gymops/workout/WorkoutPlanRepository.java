package com.gymops.workout;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkoutPlanRepository extends JpaRepository<WorkoutPlan, Long> {

    @Query("select p from WorkoutPlan p where p.member.id = :memberId and p.status = 'ACTIVE'")
    Optional<WorkoutPlan> findActiveForMember(@Param("memberId") Long memberId);

    @Query("select p from WorkoutPlan p where p.member.id = :memberId order by p.createdAt desc")
    List<WorkoutPlan> findAllForMember(@Param("memberId") Long memberId);

    @Query("select p from WorkoutPlan p where p.template = true and p.status <> 'ARCHIVED' order by p.name")
    List<WorkoutPlan> findTemplates();

    @Query("select p from WorkoutPlan p where p.trainer.id = :trainerId and p.template = false and p.status = 'ACTIVE'")
    List<WorkoutPlan> findActiveMemberPlansByTrainer(@Param("trainerId") Long trainerId);
}
