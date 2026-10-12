package com.gymops.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * E-mail / SMS channel settings ({@code app.notifications.*}; the outbox settings
 * {@code dispatch-batch-size}/{@code max-attempts} stay on {@code AppProperties}). Provider credentials come only
 * from the environment and are never returned by any API.
 *
 * @param emailProvider      {@code log} (default: logs a masked address) or {@code smtp} (uses {@code spring.mail.*})
 * @param publicBaseUrl      public URL of the web app, used for unsubscribe / preference links in e-mails
 * @param unsubscribeSecret  HMAC key for unsubscribe links; when blank a key is derived from the JWT secret
 */
@Validated
@ConfigurationProperties(prefix = "app.notifications")
public record NotificationProperties(
        @DefaultValue("log") @Pattern(regexp = "log|smtp") String emailProvider,
        @DefaultValue("http://localhost:3000") @NotBlank String publicBaseUrl,
        String unsubscribeSecret,
        @Valid @DefaultValue Email email,
        @Valid @DefaultValue Sms sms,
        @Valid @DefaultValue QuietHours quietHours) {

    /**
     * @param fromAddress envelope / header sender (must be a verified identity with SES / SendGrid)
     * @param brandColor  accent colour of the HTML layout
     */
    public record Email(
            @DefaultValue("no-reply@gymops.local") @NotBlank String fromAddress,
            @DefaultValue("GymOps") @NotBlank String fromName,
            String replyTo,
            @DefaultValue("#1c7ed6") @Pattern(regexp = "#[0-9a-fA-F]{6}") String brandColor) {
    }

    /**
     * @param provider           {@code log} (default), {@code twilio} or {@code msg91}
     * @param defaultCountryCode calling code applied to numbers stored without one (91 = India)
     * @param maxLength          SMS text is cut to this many characters (459 = three GSM-7 segments)
     */
    public record Sms(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("log") @Pattern(regexp = "log|twilio|msg91") String provider,
            @DefaultValue("91") @Pattern(regexp = "[1-9][0-9]{0,2}") String defaultCountryCode,
            @DefaultValue("459") @Min(70) @Max(1600) int maxLength,
            @DefaultValue Twilio twilio,
            @DefaultValue Msg91 msg91) {
    }

    /** Twilio Programmable Messaging; {@code fromNumber} or {@code messagingServiceSid} is required. */
    public record Twilio(String accountSid, String authToken, String fromNumber, String messagingServiceSid,
                         @DefaultValue("https://api.twilio.com") String baseUrl) {

        public boolean configured() {
            return notBlank(accountSid) && notBlank(authToken) && (notBlank(fromNumber) || notBlank(messagingServiceSid));
        }
    }

    /**
     * MSG91 Flow API (India, DLT). {@code templateId} is the MSG91 template linked to a DLT-registered template;
     * {@code templates} optionally maps a notification type to its own template. Templates receive
     * {@code ##var1##} (title) and {@code ##var2##} (message).
     */
    public record Msg91(String authKey, String senderId, String templateId, String dltTemplateId, Map<String, String> templates,
                        @DefaultValue("https://control.msg91.com") String baseUrl) {

        public boolean configured() {
            return notBlank(authKey) && notBlank(templateId);
        }

        public String templateFor(NotificationType type) {
            String specific = templates == null ? null : templates.get(type.name());
            return notBlank(specific) ? specific : templateId;
        }
    }

    /** TRAI-style quiet hours for promotional SMS, in business time: [startHour, endHour) wraps midnight. */
    public record QuietHours(
            @DefaultValue("21") @Min(0) @Max(23) int startHour,
            @DefaultValue("9") @Min(0) @Max(23) int endHour) {
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
