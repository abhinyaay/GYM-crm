package com.gymops.access;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * The device request signature scheme (pure functions, unit tested).
 *
 * <pre>
 * X-Signature = hex(HMAC-SHA256(deviceSecret, METHOD + "|" + PATH + "|" + X-Timestamp + "|" + X-Nonce + "|" + hex(SHA-256(body))))
 * </pre>
 *
 * PATH is the request path including the query string (e.g. {@code /api/v1/access/gate/allowlist?hours=12}); the
 * timestamp is Unix epoch seconds. Responses carry {@code X-Signature = hex(HMAC(deviceSecret, "response|" + nonce +
 * "|" + hex(SHA-256(responseBody))))} so the gate can trust a GRANTED decision / an allowlist it received.
 */
public final class GateSignature {

    public static final String HEADER_DEVICE = "X-Device-Id";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";

    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Pattern HEX_SIGNATURE = Pattern.compile("[0-9a-fA-F]{64}");

    private GateSignature() {
    }

    public static String canonical(String method, String pathAndQuery, String timestamp, String nonce, byte[] body) {
        return method.toUpperCase() + "|" + pathAndQuery + "|" + timestamp + "|" + nonce + "|"
                + AccessKeys.sha256Hex(body == null ? new byte[0] : body);
    }

    public static String sign(String secret, String method, String pathAndQuery, String timestamp, String nonce, byte[] body) {
        return AccessKeys.hmacHex(secret, canonical(method, pathAndQuery, timestamp, nonce, body));
    }

    public static String signResponse(String secret, String nonce, byte[] body) {
        return AccessKeys.hmacHex(secret, "response|" + nonce + "|" + AccessKeys.sha256Hex(body));
    }

    public static boolean validNonce(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    public static boolean wellFormedSignature(String signature) {
        return signature != null && HEX_SIGNATURE.matcher(signature).matches();
    }

    /** Parses X-Timestamp (epoch seconds); null when malformed. */
    public static Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.length() > 12 || !timestamp.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Instant.ofEpochSecond(Long.parseLong(timestamp));
    }

    public static boolean withinSkew(Instant timestamp, Instant now, Duration skew) {
        return timestamp != null && Duration.between(timestamp, now).abs().compareTo(skew) <= 0;
    }

    public static boolean verify(String secret, String method, String pathAndQuery, String timestamp, String nonce,
                                 byte[] body, String signature) {
        if (!wellFormedSignature(signature)) {
            return false;
        }
        String expected = sign(secret, method, pathAndQuery, timestamp, nonce, body);
        return AccessKeys.constantTimeEquals(expected, signature.toLowerCase());
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
