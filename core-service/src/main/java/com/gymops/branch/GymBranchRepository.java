package com.gymops.branch;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GymBranchRepository extends JpaRepository<GymBranch, Long> {

    @Query("select count(b) > 0 from GymBranch b where upper(b.code) = upper(:code)")
    boolean existsByCode(@Param("code") String code);

    List<GymBranch> findByStatusOrderByNameAsc(BranchStatus status);

    Page<GymBranch> findByIdIn(Collection<Long> ids, Pageable pageable);

    @Query("select b from GymBranch b left join fetch b.operatingHours where b.id = :id")
    Optional<GymBranch> findWithHoursById(@Param("id") Long id);

    @Query(value = "select count(*) from members m where m.home_branch_id = :branchId and m.deleted_at is null "
            + "and m.status = 'ACTIVE'", nativeQuery = true)
    long countActiveMembers(@Param("branchId") Long branchId);
}
