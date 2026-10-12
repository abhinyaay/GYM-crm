package com.gymops.notification;

import com.gymops.config.AppProperties;
import com.gymops.notification.preference.NotificationPreferenceService;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Dispatch-time checks for external channels: preferences may have changed since the row was enqueued (e.g. the
 * member unsubscribed while a campaign SMS waited for quiet hours to end), and retries of promotional SMS must not
 * land inside quiet hours either.
 */
@Component
public class DeliveryPolicy {

    public sealed interface Decision permits Send, Skip, Defer {
    }

    public record Send() implements Decision {
    }

    public record Skip(String reason) implements Decision {
    }

    public record Defer(Instant until) implements Decision {
    }

    private static final Send SEND = new Send();

    private final NotificationPreferenceService preferences;
    private final QuietHoursPolicy quietHours;

    public DeliveryPolicy(NotificationPreferenceService preferences, NotificationProperties props, AppProperties appProperties) {
        this.preferences = preferences;
        this.quietHours = QuietHoursPolicy.of(props.quietHours(), ZoneId.of(appProperties.businessTimezone()));
    }

    public QuietHoursPolicy quietHours() {
        return quietHours;
    }

    public Decision decide(Notification n, Instant now) {
        NotificationChannel channel = n.getChannel();
        if (channel != NotificationChannel.EMAIL && channel != NotificationChannel.SMS) {
            return SEND;
        }
        if (NotificationCategory.bypassesPreferences(n.getType())) {
            return SEND;
        }
        NotificationCategory category = NotificationCategory.of(n.getType());
        if (n.getMemberId() != null && !preferences.forMember(n.getMemberId()).allows(n.getType(), channel)) {
            return new Skip("Opted out of " + category.label() + " by " + channel);
        }
        if (channel == NotificationChannel.SMS && category.isPromotional() && quietHours.isQuiet(now)) {
            return new Defer(quietHours.nextAllowed(now));
        }
        return SEND;
    }
}
