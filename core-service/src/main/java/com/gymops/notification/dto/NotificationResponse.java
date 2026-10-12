package com.gymops.notification.dto;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationStatus;
import com.gymops.notification.NotificationType;
import java.time.Instant;

public record NotificationResponse(Long id, NotificationType type, NotificationChannel channel, String title,
                                   String body, NotificationStatus status, int attempts, String lastError,
                                   Instant createdAt, Instant sentAt, Instant readAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getChannel(), n.getTitle(), n.getBody(),
                n.getStatus(), n.getAttempts(), n.getLastError(), n.getCreatedAt(), n.getSentAt(), n.getReadAt());
    }
}
