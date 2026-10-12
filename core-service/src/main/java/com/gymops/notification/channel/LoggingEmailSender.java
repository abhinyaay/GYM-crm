package com.gymops.notification.channel;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development / test e-mail channel ({@code app.notifications.email-provider=log}, the default): logs a masked
 * address instead of sending. {@code smtp} switches to {@link com.gymops.notification.email.SmtpEmailSender}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notifications.email-provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements NotificationSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(Notification n) {
        log.info("notification_email id={} type={} to={} subject=\"{}\"", n.getId(), n.getType(),
                mask(n.getDestination()), n.getTitle());
    }

    @Override
    public DeliveryReceipt deliver(Notification n) {
        send(n);
        return new DeliveryReceipt("log", "log-" + n.getId(), null);
    }

    public static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
