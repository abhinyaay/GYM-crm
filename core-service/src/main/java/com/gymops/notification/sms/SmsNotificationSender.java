package com.gymops.notification.sms;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.channel.DeliveryReceipt;
import com.gymops.notification.channel.NotificationSender;
import com.gymops.notification.channel.RecipientUnusableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** SMS channel: normalises the destination to E.164 and hands the text to the configured {@link SmsGateway}. */
@Component
@RequiredArgsConstructor
public class SmsNotificationSender implements NotificationSender {

    private final SmsGateway gateway;
    private final NotificationProperties props;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void send(Notification notification) {
        deliver(notification);
    }

    @Override
    public DeliveryReceipt deliver(Notification n) {
        String to = PhoneNumbers.toE164(n.getDestination(), props.sms().defaultCountryCode())
                .orElseThrow(() -> new RecipientUnusableException("No usable mobile number for SMS"));
        SmsGateway.SmsResult result = gateway.send(new SmsGateway.SmsMessage(to, n.getTitle(), n.getBody(), n.getType(), n.getId()));
        return new DeliveryReceipt(gateway.name(), result.providerMessageId(), result.segments());
    }
}
