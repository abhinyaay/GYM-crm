package com.gymops.access;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessDeviceRepository extends JpaRepository<AccessDevice, Long> {

    @Query("select d from AccessDevice d join fetch d.branch where d.deviceUid = :uid")
    Optional<AccessDevice> findByDeviceUid(@Param("uid") String uid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AccessDevice d where d.id = :id")
    Optional<AccessDevice> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select d from AccessDevice d join fetch d.branch b
            where (:all = true or b.id in :branchIds)
            order by b.name, d.name
            """)
    List<AccessDevice> findScoped(@Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds);

    List<AccessDevice> findByBranchIdOrderByName(Long branchId);

    /** Active devices that have heartbeated before but have now been silent since {@code cutoff}. */
    @Query("""
            select d.id from AccessDevice d
            where d.status = com.gymops.access.DeviceStatus.ACTIVE and d.offlineSince is null
              and d.lastHeartbeatAt is not null and d.lastHeartbeatAt < :cutoff
            """)
    List<Long> findSilentSince(@Param("cutoff") Instant cutoff);
}
