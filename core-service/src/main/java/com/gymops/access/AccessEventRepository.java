package com.gymops.access;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessEventRepository extends JpaRepository<AccessEvent, Long>, JpaSpecificationExecutor<AccessEvent> {

    Optional<AccessEvent> findByDeviceIdAndDeviceEventId(Long deviceId, String deviceEventId);

    @Query("""
            select e from AccessEvent e
            where (:all = true or e.branchId in :branchIds) and e.id > :afterId
            order by e.id desc
            """)
    List<AccessEvent> findFeed(@Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds,
                               @Param("afterId") long afterId, Pageable pageable);

    @Query("""
            select e.direction, e.decision, e.reasonCode, e.credentialType, count(e) from AccessEvent e
            where (:all = true or e.branchId in :branchIds) and e.occurredAt >= :from and e.occurredAt < :to
            group by e.direction, e.decision, e.reasonCode, e.credentialType
            """)
    List<Object[]> stats(@Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds,
                         @Param("from") Instant from, @Param("to") Instant to);
}
