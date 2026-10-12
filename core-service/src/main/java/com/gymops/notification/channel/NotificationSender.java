package com.gymops.notification.channel;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationChannel;

/**
 * Strategy per delivery channel. Integrating a provider (SMTP / SES / SendGrid for EMAIL, Twilio / MSG91 for SMS,
 * FCM for PUSH) means adding one implementation of this interface; nothing else changes.
 *
 * <p>Failure semantics: any {@link RuntimeException} is retried with backoff; {@link PermanentDeliveryException}
 * fails the row immediately; {@link RecipientUnusableException} marks it SKIPPED (no retry).
 */
public interface NotificationSender {

    NotificationChannel channel();

    /** @throws RuntimeException on delivery failure (the dispatcher retries with backoff) */
    void send(Notification notification);

    /** Delivers and reports the provider's receipt. Senders talking to a real provider override this. */
    default DeliveryReceipt deliver(Notification notification) {
        send(notification);
        return DeliveryReceipt.NONE;
    }
}
