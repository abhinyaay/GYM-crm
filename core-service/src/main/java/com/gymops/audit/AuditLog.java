package com.gymops.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit record of a business action. Never updated or deleted by the application. */
@Getter
@Entity
@Immutable
@Table(name = "audit_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(nullable = false)
    private String action;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "branch_id")
    private Long branchId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    AuditLog(String entityType, Long entityId, String action, Long actorUserId, String actorEmail, Long memberId,
             Long branchId, Map<String, Object> details, String correlationId, String ipAddress, Instant occurredAt) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.memberId = memberId;
        this.branchId = branchId;
        this.details = details;
        this.correlationId = correlationId;
        this.ipAddress = ipAddress;
        this.occurredAt = occurredAt;
    }
}
