package com.gymops.notification.preference;

import com.gymops.config.AppProperties;
import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stateless, signed unsubscribe tokens: {@code base64url(memberId:CATEGORY:CHANNEL) . base64url(HMAC-SHA256)}.
 * Validating one needs no database lookup and the link keeps working for as long as the key is unchanged
 * (RFC 8058 one-click links must not expire quickly). The key is {@code app.notifications.unsubscribe-secret}
 * or, when that is blank, an HMAC of the JWT secret with a fixed label (domain separation: a JWT key is never
 * used directly for these links).
 */
@Component
public class UnsubscribeTokens {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public record Subject(long memberId, NotificationCategory category, NotificationChannel channel) {
    }

    private final byte[] key;

    @Autowired
    public UnsubscribeTokens(NotificationProperties props, AppProperties appProperties) {
        String configured = props.unsubscribeSecret();
        this.key = configured != null && !configured.isBlank()
                ? configured.getBytes(StandardCharsets.UTF_8)
                : hmac(appProperties.security().jwt().secret().getBytes(StandardCharsets.UTF_8),
                        "gymops:unsubscribe-token:v1".getBytes(StandardCharsets.UTF_8));
    }

    UnsubscribeTokens(byte[] key) {
        this.key = key.clone();
    }

    public String create(long memberId, NotificationCategory category, NotificationChannel channel) {
        byte[] payload = (memberId + ":" + category.name() + ":" + channel.name()).getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(hmac(key, payload));
    }

    /** The token's subject, or empty when the token is malformed or its signature does not match. */
    public Optional<Subject> verify(String token) {
        if (token == null || token.length() > 512) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(token.substring(0, dot));
            byte[] signature = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(key, payload), signature)) {
                return Optional.empty();
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split(":");
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new Subject(Long.parseLong(parts[0]), NotificationCategory.valueOf(parts[1]),
                    NotificationChannel.valueOf(parts[2])));
        } catch (IllegalArgumentException e) {   // bad base64, number or enum name
            return Optional.empty();
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
