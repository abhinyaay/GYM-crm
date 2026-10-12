package com.gymops.notification.preference;

import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An explicit member choice for one category x channel; absent rows mean "category default". */
@Getter
@Entity
@Table(name = "member_notification_preferences")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference {

    /** Who made the change. */
    public enum Source { MEMBER, STAFF, UNSUBSCRIBE_LINK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationPreference(Long memberId, NotificationCategory category, NotificationChannel channel) {
        this.memberId = memberId;
        this.category = category;
        this.channel = channel;
    }

    public void set(boolean value, Source by, Long userId, Instant now) {
        this.enabled = value;
        this.source = by;
        this.updatedBy = userId;
        this.updatedAt = now;
    }
}
