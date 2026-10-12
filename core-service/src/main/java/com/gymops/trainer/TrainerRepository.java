package com.gymops.trainer;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrainerRepository extends JpaRepository<Trainer, Long> {

    @Query("select t.id from Trainer t where t.user.id = :userId")
    Optional<Long> findIdByUserId(@Param("userId") Long userId);

    boolean existsByEmployeeCodeIgnoreCase(String employeeCode);

    @Query(value = """
            select distinct t from Trainer t join fetch t.user left join t.specializations s
            where (:allBranches = true or t.homeBranch.id in :branchIds)
              and (:status is null or t.status = :status)
              and (:specialization is null or s = :specialization)
            """, countQuery = """
            select count(distinct t) from Trainer t left join t.specializations s
            where (:allBranches = true or t.homeBranch.id in :branchIds)
              and (:status is null or t.status = :status)
              and (:specialization is null or s = :specialization)
            """)
    Page<Trainer> search(@Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds,
                         @Param("status") TrainerStatus status, @Param("specialization") String specialization,
                         Pageable pageable);
}
