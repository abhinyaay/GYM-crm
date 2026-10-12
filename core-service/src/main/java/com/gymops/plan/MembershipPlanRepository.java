package com.gymops.plan;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, Long> {

    boolean existsByCodeIgnoreCase(String code);

    List<MembershipPlan> findByStatusOrderByPriceAsc(PlanStatus status);

    Page<MembershipPlan> findByStatus(PlanStatus status, Pageable pageable);
}
