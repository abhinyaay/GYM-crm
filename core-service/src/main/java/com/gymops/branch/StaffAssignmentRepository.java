package com.gymops.branch;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, Long> {

    @Query("select a.branch.id from StaffAssignment a where a.user.id = :userId")
    Set<Long> findBranchIdsByUserId(@Param("userId") Long userId);

    @Query("select a from StaffAssignment a join fetch a.user where a.branch.id = :branchId order by a.assignedAt")
    List<StaffAssignment> findByBranchIdWithUser(@Param("branchId") Long branchId);

    Optional<StaffAssignment> findByUserIdAndBranchId(Long userId, Long branchId);

    @Query("select a.user.id from StaffAssignment a where a.branch.id = :branchId")
    List<Long> findUserIdsByBranchId(@Param("branchId") Long branchId);
}
