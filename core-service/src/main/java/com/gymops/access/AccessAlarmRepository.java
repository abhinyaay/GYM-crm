package com.gymops.access;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessAlarmRepository extends JpaRepository<AccessAlarm, Long> {

    Optional<AccessAlarm> findByDeviceIdAndDeviceEventId(Long deviceId, String deviceEventId);

    @Query("""
            select a from AccessAlarm a
            where (:all = true or a.branchId in :branchIds) and (:openOnly = false or a.acknowledgedAt is null)
            order by a.occurredAt desc
            """)
    List<AccessAlarm> search(@Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds,
                             @Param("openOnly") boolean openOnly, Pageable pageable);

    @Query("select count(a) from AccessAlarm a where (:all = true or a.branchId in :branchIds) and a.acknowledgedAt is null")
    long countOpen(@Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds);
}
