package com.gymops.notification;

import java.util.Set;

/**
 * Optional knobs for {@link NotificationService#notifyMember(com.gymops.member.Member, NotificationType, String, String,
 * String, NotifyOptions)}.
 *
 * @param channels restrict delivery to these channels (null = the type's defaults). Naming SMS here requests an SMS
 *                 even for types that are not SMS-by-default; member preferences and locks still apply.
 * @param smsText  short text for the SMS (default: "title: body", cut to the configured maximum length)
 */
public record NotifyOptions(Set<NotificationChannel> channels, String smsText) {

    public static final NotifyOptions DEFAULTS = new NotifyOptions(null, null);

    public static NotifyOptions channels(NotificationChannel... channels) {
        return new NotifyOptions(Set.of(channels), null);
    }

    public NotifyOptions withSmsText(String text) {
        return new NotifyOptions(channels, text);
    }

    boolean includes(NotificationChannel channel) {
        return channels == null || channels.contains(channel);
    }
}
