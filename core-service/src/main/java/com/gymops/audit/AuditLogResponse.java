package com.gymops.audit;

import java.time.Instant;
import java.util.Map;

public record AuditLogResponse(Long id, String entityType, Long entityId, String action, Long actorUserId,
                               String actorEmail, Map<String, Object> details, String correlationId,
                               Instant occurredAt) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getEntityType(), log.getEntityId(), log.getAction(),
                log.getActorUserId(), log.getActorEmail(), log.getDetails(), log.getCorrelationId(), log.getOccurredAt());
    }
}
