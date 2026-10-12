package com.gymops.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.NotificationType;
import com.gymops.notification.channel.DeliveryReceipt;
import com.gymops.notification.channel.RecipientUnusableException;
import com.gymops.notification.preference.UnsubscribeTokens;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/** SMTP sender against a mocked JavaMailSender: the produced MIME message is inspected. */
class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private SmtpEmailSender sender;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        NotificationProperties props = new NotificationProperties("smtp", "https://gym.example/", "unit-test-unsubscribe-secret-0123456789",
                new NotificationProperties.Email("hello@gym.example", "Iron Gym", "desk@gym.example", "#123456"),
                new NotificationProperties.Sms(true, "log", "91", 459,
                        new NotificationProperties.Twilio(null, null, null, null, "https://api.twilio.com"),
                        new NotificationProperties.Msg91(null, null, null, null, Map.of(), "https://control.msg91.com")),
                new NotificationProperties.QuietHours(21, 9));
        UnsubscribeTokens tokens = new UnsubscribeTokens(props, null);
        sender = new SmtpEmailSender(mailSender, new EmailComposer(props, tokens), props);
    }

    private static Notification notification(NotificationType type, String to, Long memberId) {
        return new Notification(type, NotificationChannel.EMAIL, null, memberId, to, "Hello <Asha>", "Line one\n\nLine two",
                null, Instant.now());
    }

    @Test
    void sendsMultipartHtmlAndTextFromTheConfiguredSender() throws Exception {
        DeliveryReceipt receipt = sender.deliver(notification(NotificationType.CLASS_BOOKED, "asha@example.com", 7L));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage m = sent.getValue();
        assertThat(m.getFrom()[0].toString()).isEqualTo("Iron Gym <hello@gym.example>");
        assertThat(m.getReplyTo()[0].toString()).isEqualTo("desk@gym.example");
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("asha@example.com");
        assertThat(m.getSubject()).isEqualTo("Hello <Asha>");
        assertThat(m.getHeader("List-Unsubscribe")).as("transactional mail").isNull();
        assertThat(m.getHeader("X-GymOps-Type")[0]).isEqualTo("CLASS_BOOKED");
        m.saveChanges();
        assertThat(raw(m)).contains("multipart/alternative");
        assertThat(part(m, "text/plain")).startsWith("Hello <Asha>\n\nLine one\n\nLine two")
                .contains("https://gym.example/me/notifications");
        assertThat(part(m, "text/html")).contains("Hello &lt;Asha&gt;").contains("https://gym.example/me/notifications");
        assertThat(receipt.provider()).isEqualTo("smtp");
    }

    @Test
    void marketingMailCarriesOneClickUnsubscribeHeaders() throws Exception {
        sender.deliver(notification(NotificationType.CAMPAIGN_MESSAGE, "asha@example.com", 7L));
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage m = sent.getValue();
        assertThat(m.getHeader("List-Unsubscribe")[0])
                .startsWith("<https://gym.example/api/v1/public/notifications/unsubscribe?token=").endsWith(">");
        assertThat(m.getHeader("List-Unsubscribe-Post")[0]).isEqualTo("List-Unsubscribe=One-Click");
        m.saveChanges();
        assertThat(part(m, "text/html")).contains("https://gym.example/unsubscribe?token=");
        assertThat(part(m, "text/plain")).contains("https://gym.example/unsubscribe?token=");
    }

    @Test
    void invalidAddressIsSkippedWithoutContactingTheServer() {
        assertThatThrownBy(() -> sender.deliver(notification(NotificationType.CLASS_BOOKED, "not an address", 7L)))
                .isInstanceOf(RecipientUnusableException.class);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void smtpFailuresAreRetryable() {
        doThrow(new MailSendException("Connection refused")).when(mailSender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> sender.deliver(notification(NotificationType.CLASS_BOOKED, "asha@example.com", 7L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Connection refused");
    }

    /** Decoded content of the first body part with the given MIME type (walks nested multiparts). */
    private static String part(jakarta.mail.Part p, String type) throws Exception {
        if (p.isMimeType(type)) {
            return (String) p.getContent();
        }
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String found = part(mp.getBodyPart(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String raw(MimeMessage m) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        m.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
