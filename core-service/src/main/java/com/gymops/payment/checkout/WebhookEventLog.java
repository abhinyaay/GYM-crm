package com.gymops.payment.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Every verified provider webhook, unique per (provider, event id), so redeliveries are no-ops. */
@Getter
@Entity
@Table(name = "webhook_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private String status;

    private String error;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    WebhookEventLog(String provider, String eventId, String eventType, String payload, String status, String error,
                    Instant receivedAt) {
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType == null ? "unknown" : eventType;
        this.payload = payload;
        this.status = status;
        this.error = error == null || error.length() <= 500 ? error : error.substring(0, 500);
        this.receivedAt = receivedAt;
    }

    void retried(String newStatus, String newError) {
        this.status = newStatus;
        this.error = newError == null || newError.length() <= 500 ? newError : newError.substring(0, 500);
    }
}
