package com.gymops.notification.preference;

import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationType;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** A member's effective channel choices: explicit rows over category defaults; locked channels always on. */
public final class MemberPreferences {

    private final Map<String, Boolean> explicit = new HashMap<>();

    private MemberPreferences(Collection<NotificationPreference> rows) {
        rows.forEach(p -> explicit.put(key(p.getCategory(), p.getChannel()), p.isEnabled()));
    }

    public static MemberPreferences of(Collection<NotificationPreference> rows) {
        return new MemberPreferences(rows);
    }

    public static MemberPreferences defaults() {
        return new MemberPreferences(java.util.List.of());
    }

    /** The member's choice for the category (ignoring locks): explicit value or the category default. */
    public boolean chosen(NotificationCategory category, NotificationChannel channel) {
        Boolean value = explicit.get(key(category, channel));
        return value != null ? value : category.defaultEnabled(channel);
    }

    /** Whether a channel is on for the category: locked channels are always on, unoffered ones always off. */
    public boolean enabled(NotificationCategory category, NotificationChannel channel) {
        if (channel == NotificationChannel.IN_APP || category.isLocked(channel)) {
            return true;
        }
        return category.offers(channel) && chosen(category, channel);
    }

    /** Delivery decision for a notification type on a channel. */
    public boolean allows(NotificationType type, NotificationChannel channel) {
        return NotificationCategory.bypassesPreferences(type) || enabled(NotificationCategory.of(type), channel);
    }

    private static String key(NotificationCategory category, NotificationChannel channel) {
        return category.name() + ':' + channel.name();
    }
}
