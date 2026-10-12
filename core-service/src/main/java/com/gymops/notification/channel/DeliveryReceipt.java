package com.gymops.notification.channel;

/**
 * What the provider told us about an accepted message.
 *
 * @param provider          provider that accepted it ({@code smtp}, {@code twilio}, {@code msg91}, {@code log})
 * @param providerMessageId the provider's id (SMTP Message-ID, Twilio SID, MSG91 request id); may be null
 * @param segments          billable SMS segments; null for other channels
 */
public record DeliveryReceipt(String provider, String providerMessageId, Integer segments) {

    public static final DeliveryReceipt NONE = new DeliveryReceipt(null, null, null);
}
