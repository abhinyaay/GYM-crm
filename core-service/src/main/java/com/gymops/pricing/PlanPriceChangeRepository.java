package com.gymops.pricing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanPriceChangeRepository extends JpaRepository<PlanPriceChange, Long> {

    List<PlanPriceChange> findByPlanIdOrderByCreatedAtDesc(Long planId);

    Optional<PlanPriceChange> findFirstByPlanIdAndStatus(Long planId, PriceChangeStatus status);

    @Query("select c from PlanPriceChange c join fetch c.plan where c.status = :status order by c.effectiveDate asc")
    List<PlanPriceChange> findByStatusWithPlan(@Param("status") PriceChangeStatus status);

    @Query("select c.id from PlanPriceChange c where c.status = 'SCHEDULED' and c.effectiveDate <= :today")
    List<Long> findDueIds(@Param("today") LocalDate today);

    @Query("select c from PlanPriceChange c where c.status = 'SCHEDULED' and c.plan.id in :planIds")
    List<PlanPriceChange> findScheduledForPlans(@Param("planIds") Collection<Long> planIds);
}
