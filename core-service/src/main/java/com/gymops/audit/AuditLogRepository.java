package com.gymops.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, Long entityId, Pageable pageable);

    Page<AuditLog> findByMemberIdOrderByOccurredAtDesc(Long memberId, Pageable pageable);
}
