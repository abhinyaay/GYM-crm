package com.gymops.notification.sms;

import com.gymops.notification.NotificationType;

/** Port to an SMS provider. Implementations: {@code log} (default), {@code twilio}, {@code msg91}. */
public interface SmsGateway {

    /** Provider name as configured ({@code app.notifications.sms.provider}). */
    String name();

    /** Whether the credentials this provider needs are present (never says which values). */
    boolean configured();

    /**
     * Sends one message.
     *
     * @throws com.gymops.notification.channel.RecipientUnusableException the number cannot receive SMS (skip)
     * @throws com.gymops.notification.channel.PermanentDeliveryException  rejected for good (no retry)
     * @throws RuntimeException                                            transient failure (retried with backoff)
     */
    SmsResult send(SmsMessage message);

    /**
     * @param to   E.164 number
     * @param type drives template selection where the provider needs registered templates (India DLT)
     */
    record SmsMessage(String to, String title, String text, NotificationType type, Long notificationId) {
    }

    record SmsResult(String providerMessageId, int segments) {
    }
}
