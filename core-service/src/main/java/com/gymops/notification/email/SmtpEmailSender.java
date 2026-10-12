package com.gymops.notification.email;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.channel.DeliveryReceipt;
import com.gymops.notification.channel.NotificationSender;
import com.gymops.notification.channel.RecipientUnusableException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real e-mail over SMTP ({@code spring.mail.*}): Mailpit locally, Amazon SES / SendGrid / Gmail SMTP in production.
 * Sends multipart/alternative (plain text + branded HTML). Marketing mail carries RFC 2369 / RFC 8058
 * {@code List-Unsubscribe} and {@code List-Unsubscribe-Post} headers so mailbox providers show one-click unsubscribe.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.email-provider", havingValue = "smtp")
public class SmtpEmailSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final EmailComposer composer;
    private final NotificationProperties props;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        deliver(notification);
    }

    @Override
    public DeliveryReceipt deliver(Notification n) {
        InternetAddress to = recipient(n.getDestination());
        EmailComposer.ComposedEmail email = composer.compose(n);
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(props.email().fromAddress(), props.email().fromName());
            helper.setTo(to);
            if (props.email().replyTo() != null && !props.email().replyTo().isBlank()) {
                helper.setReplyTo(props.email().replyTo());
            }
            helper.setSubject(email.rendered().subject());
            helper.setText(email.rendered().text(), email.rendered().html());
            if (email.oneClickUnsubscribeUrl() != null) {
                message.setHeader("List-Unsubscribe", "<" + email.oneClickUnsubscribeUrl() + ">");
                message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            }
            if (n.getId() != null) {
                message.setHeader("X-GymOps-Notification-Id", String.valueOf(n.getId()));
            }
            message.setHeader("X-GymOps-Type", n.getType().name());
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("Could not build e-mail: " + e.getMessage(), e);
        }
        try {
            mailSender.send(message);
        } catch (MailException e) {
            throw new IllegalStateException("SMTP delivery failed: " + e.getMessage(), e);
        }
        return new DeliveryReceipt("smtp", messageId(message), null);
    }

    private static InternetAddress recipient(String address) {
        if (address == null || address.isBlank()) {
            throw new RecipientUnusableException("No e-mail address");
        }
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
            return parsed;
        } catch (AddressException e) {
            throw new RecipientUnusableException("Invalid e-mail address");
        }
    }

    private static String messageId(MimeMessage message) {
        try {
            return message.getMessageID();
        } catch (MessagingException e) {
            return null;
        }
    }
}
