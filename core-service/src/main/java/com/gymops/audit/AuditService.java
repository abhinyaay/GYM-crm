package com.gymops.audit;

import com.gymops.common.web.ClientIp;
import com.gymops.common.web.CorrelationIdFilter;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes audit entries inside the caller's transaction: if the business change rolls back, so
 * does its audit entry, and a committed change always has its audit entry.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        Optional<UserPrincipal> actor = CurrentUser.find();
        String ip = Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(a -> ClientIp.of(((ServletRequestAttributes) a).getRequest()))
                .orElse(null);
        repository.save(new AuditLog(
                event.entityType(), event.entityId(), event.action(),
                actor.map(UserPrincipal::userId).orElse(null),
                actor.map(UserPrincipal::email).orElse("system"),
                event.memberId(), event.branchId(), event.details(),
                MDC.get(CorrelationIdFilter.MDC_KEY), ip, Instant.now(clock)));
    }

    /** Fluent builder for audit events. */
    public record AuditEvent(String entityType, Long entityId, String action, Long memberId, Long branchId,
                             Map<String, Object> details) {

        public static AuditEvent of(String entityType, Long entityId, String action) {
            return new AuditEvent(entityType, entityId, action, null, null, new LinkedHashMap<>());
        }

        public AuditEvent member(Long id) {
            return new AuditEvent(entityType, entityId, action, id, branchId, details);
        }

        public AuditEvent branch(Long id) {
            return new AuditEvent(entityType, entityId, action, memberId, id, details);
        }

        public AuditEvent with(String key, Object value) {
            if (value != null) {
                details.put(key, value instanceof Enum<?> e ? e.name() : value);
            }
            return this;
        }
    }
}
