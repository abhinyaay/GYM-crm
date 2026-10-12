package com.gymops.notification.admin;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationStatus;
import com.gymops.notification.NotificationType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public final class MessagingDtos {

    private MessagingDtos() {
    }

    /** One outbox row as seen in the messaging centre. */
    public record NotificationLogEntry(Long id, NotificationType type, NotificationCategory category, NotificationChannel channel,
                                       NotificationStatus status, Long memberId, String memberName, Long recipientUserId,
                                       String destination, String title, String body, int attempts, String lastError,
                                       String provider, String providerMessageId, Integer segments, Instant nextAttemptAt,
                                       Instant createdAt, Instant sentAt, Instant readAt, Long requestedByUserId) {

        public static NotificationLogEntry from(Notification n, String memberName) {
            return new NotificationLogEntry(n.getId(), n.getType(), NotificationCategory.of(n.getType()), n.getChannel(),
                    n.getStatus(), n.getMemberId(), memberName, n.getRecipientUserId(), n.getDestination(), n.getTitle(),
                    n.getBody(), n.getAttempts(), n.getLastError(), n.getProvider(), n.getProviderMessageId(), n.getSegments(),
                    n.getNextAttemptAt(), n.getCreatedAt(), n.getSentAt(), n.getReadAt(), n.getRequestedByUserId());
        }
    }

    /**
     * Send a TEST_MESSAGE to verify provider setup: to a member (their e-mail / phone) or, for org-wide admins, to any
     * address ({@code to}).
     */
    public record TestMessageRequest(@NotNull NotificationChannel channel, Long memberId, @Size(max = 254) String to,
                                     @Size(max = 500) String message) {
    }

    /**
     * Never contains credentials; only whether they are present.
     *
     * @param live true when messages actually leave the system (not the log provider)
     */
    public record ProviderStatus(NotificationChannel channel, String provider, boolean live, boolean configured,
                                 Map<String, Object> details) {
    }

    public record ProvidersResponse(ProviderStatus email, ProviderStatus sms, String quietHours, String publicBaseUrl) {
    }
}
