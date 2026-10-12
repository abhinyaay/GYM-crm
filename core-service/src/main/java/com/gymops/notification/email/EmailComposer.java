package com.gymops.notification.email;

import com.gymops.notification.Notification;
import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationProperties;
import com.gymops.notification.preference.UnsubscribeTokens;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Turns an outbox row into a rendered e-mail (layout, preference link, signed unsubscribe links for marketing). */
@Component
@RequiredArgsConstructor
public class EmailComposer {

    private final NotificationProperties props;
    private final UnsubscribeTokens tokens;

    /**
     * @param oneClickUnsubscribeUrl RFC 8058 endpoint for the {@code List-Unsubscribe} header (null unless marketing)
     */
    public record ComposedEmail(EmailTemplate.Rendered rendered, String oneClickUnsubscribeUrl, String unsubscribePageUrl) {
    }

    public ComposedEmail compose(Notification n) {
        String base = baseUrl();
        boolean member = n.getMemberId() != null;
        String pageUrl = null;
        String oneClick = null;
        if (member && NotificationCategory.of(n.getType()).isPromotional()) {
            String token = URLEncoder.encode(tokens.create(n.getMemberId(), NotificationCategory.MARKETING, NotificationChannel.EMAIL),
                    StandardCharsets.UTF_8);
            pageUrl = base + "/unsubscribe?token=" + token;
            oneClick = base + "/api/v1/public/notifications/unsubscribe?token=" + token;
        }
        var content = new EmailTemplate.Content(props.email().fromName(), props.email().brandColor(), n.getTitle(), n.getBody(),
                member ? base + "/me/notifications" : null, pageUrl);
        return new ComposedEmail(EmailTemplate.render(content), oneClick, pageUrl);
    }

    private String baseUrl() {
        String base = props.publicBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
