package com.gymops.access;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Rotating QR entry pass, TOTP style: {@code GQ1.<memberId base36>.<window base36>.<mac>} where
 * {@code window = floor(epochSeconds / qrWindow)} and {@code mac = base64url(HMAC(qrKey, "qr|memberId|credentialId|window"))}
 * truncated to 128 bits. Nothing is written per refresh; binding the MAC to the QR credential id means revoking and
 * re-issuing a member's pass invalidates every old token.
 */
public final class QrPassCodec {

    static final String PREFIX = "GQ1";
    private static final int MAC_BYTES = 16;

    public record Parsed(long memberId, long window, String mac) {
    }

    private QrPassCodec() {
    }

    public static long window(Instant at, Duration windowLength) {
        return Math.floorDiv(at.getEpochSecond(), windowLength.toSeconds());
    }

    public static Instant windowEnd(long window, Duration windowLength) {
        return Instant.ofEpochSecond((window + 1) * windowLength.toSeconds());
    }

    public static String token(AccessKeys keys, long memberId, long credentialId, long window) {
        return PREFIX + "." + Long.toString(memberId, 36) + "." + Long.toString(window, 36) + "."
                + mac(keys, memberId, credentialId, window);
    }

    /** Parses a token; null when it is not a well-formed GymOps QR pass. */
    public static Parsed parse(String token) {
        if (token == null || token.length() > 80) {
            return null;
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 4 || !PREFIX.equals(parts[0]) || parts[3].length() != 22) {
            return null;
        }
        try {
            long memberId = Long.parseLong(parts[1], 36);
            long window = Long.parseLong(parts[2], 36);
            return memberId > 0 && window > 0 ? new Parsed(memberId, window, parts[3]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean macMatches(AccessKeys keys, Parsed parsed, long credentialId) {
        return AccessKeys.constantTimeEquals(mac(keys, parsed.memberId(), credentialId, parsed.window()), parsed.mac());
    }

    /** Within the accepted window range (current ± tolerance)? */
    public static boolean inWindow(long tokenWindow, long currentWindow, int tolerance) {
        return Math.abs(currentWindow - tokenWindow) <= tolerance;
    }

    private static String mac(AccessKeys keys, long memberId, long credentialId, long window) {
        byte[] full = keys.qrMac("qr|" + memberId + "|" + credentialId + "|" + window);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, MAC_BYTES));
    }
}
