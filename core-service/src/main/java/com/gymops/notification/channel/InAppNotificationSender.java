package com.gymops.notification.channel;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationChannel;
import org.springframework.stereotype.Component;

/** In-app notifications are delivered by being stored; the member app reads them from the inbox API. */
@Component
public class InAppNotificationSender implements NotificationSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public void send(Notification notification) {
        // nothing to push: the row itself is the delivered message
    }
}
