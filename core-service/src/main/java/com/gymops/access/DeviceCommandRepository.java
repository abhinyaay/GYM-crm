package com.gymops.access;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {

    @Query("""
            select c from DeviceCommand c
            where c.device.id = :deviceId
              and c.status in (com.gymops.access.CommandStatus.PENDING, com.gymops.access.CommandStatus.DELIVERED)
            order by c.id
            """)
    List<DeviceCommand> findOpen(@Param("deviceId") Long deviceId, Pageable pageable);

    @Query("""
            select count(c) from DeviceCommand c
            where c.device.id = :deviceId
              and c.status in (com.gymops.access.CommandStatus.PENDING, com.gymops.access.CommandStatus.DELIVERED)
            """)
    long countOpen(@Param("deviceId") Long deviceId);

    @Query("""
            select c from DeviceCommand c
            where c.credentialId = :credentialId
              and c.status in (com.gymops.access.CommandStatus.PENDING, com.gymops.access.CommandStatus.DELIVERED)
            """)
    List<DeviceCommand> findOpenForCredential(@Param("credentialId") Long credentialId);

    @Query("select c from DeviceCommand c join fetch c.device where c.credentialId in :credentialIds order by c.id desc")
    List<DeviceCommand> findByCredentialIds(@Param("credentialIds") java.util.Collection<Long> credentialIds);

    @Query("select c from DeviceCommand c where c.device.id = :deviceId order by c.id desc")
    List<DeviceCommand> findRecent(@Param("deviceId") Long deviceId, Pageable pageable);
}
