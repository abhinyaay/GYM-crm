package com.gymops.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Outbox row: written in the same transaction as the business change, delivered later by the
 * dispatcher. A notification therefore exists if and only if the change committed.
 */
@Getter
@Entity
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "member_id")
    private Long memberId;

    private String destination;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "dedup_key", unique = true)
    private String dedupKey;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Provider that accepted the message (smtp, twilio, msg91, log). */
    private String provider;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    /** Billable SMS segments reported / estimated for SMS. */
    private Integer segments;

    /** Staff user who triggered a test send or a resend (null for system notifications). */
    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    public Notification(NotificationType type, NotificationChannel channel, Long recipientUserId, Long memberId,
                        String destination, String title, String body, String dedupKey, Instant now) {
        this.type = type;
        this.channel = channel;
        this.recipientUserId = recipientUserId;
        this.memberId = memberId;
        this.destination = destination;
        this.title = title;
        this.body = body;
        this.dedupKey = dedupKey;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public void markSent(Instant now) {
        status = NotificationStatus.SENT;
        sentAt = now;
        attempts++;
        lastError = null;
    }

    public void markSent(Instant now, String provider, String providerMessageId, Integer segments) {
        markSent(now);
        this.provider = provider;
        this.providerMessageId = truncate(providerMessageId, 255);
        this.segments = segments;
    }

    /** Deliberately not delivered (opted out, no usable phone ...). Not retried. */
    public void markSkipped(String reason) {
        status = NotificationStatus.SKIPPED;
        lastError = truncate(reason == null ? "skipped" : reason, 500);
    }

    /** The provider rejected the message for good: no further attempts. */
    public void markFailedPermanently(String error) {
        attempts++;
        status = NotificationStatus.FAILED;
        lastError = truncate(error == null ? "unknown" : error, 500);
    }

    /** Postpones delivery without consuming an attempt (quiet hours). */
    public void deferUntil(Instant when) {
        nextAttemptAt = when;
    }

    /** Overrides the initial delivery time (e.g. a promotional SMS enqueued during quiet hours). */
    public Notification notBefore(Instant when) {
        if (when != null && when.isAfter(nextAttemptAt)) {
            nextAttemptAt = when;
        }
        return this;
    }

    public Notification requestedBy(Long userId) {
        this.requestedByUserId = userId;
        return this;
    }

    /** Resend: a staff member re-queues a failed notification, optionally to the member's current address. */
    public void resend(String currentDestination, Long requestedBy, Instant now) {
        if (currentDestination != null) {
            destination = currentDestination;
        }
        requestedByUserId = requestedBy;
        lastError = null;
        retry(now);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Exponential backoff (1, 2, 4, 8 ... minutes); permanently FAILED after {@code maxAttempts}. */
    public void markAttemptFailed(String error, int maxAttempts, Instant now) {
        attempts++;
        lastError = error == null ? "unknown" : error.substring(0, Math.min(500, error.length()));
        if (attempts >= maxAttempts) {
            status = NotificationStatus.FAILED;
        } else {
            nextAttemptAt = now.plus(Duration.ofMinutes(1L << Math.min(attempts - 1, 10)));
        }
    }

    public void retry(Instant now) {
        status = NotificationStatus.PENDING;
        attempts = 0;
        nextAttemptAt = now;
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }
}
