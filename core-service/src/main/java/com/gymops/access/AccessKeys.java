package com.gymops.access;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Key material for the access module, all derived from one master key ({@code ACCESS_MASTER_KEY}).
 *
 * <p><b>Device secrets are derived, not stored:</b> {@code secret = HMAC-SHA256(masterKey, "device|" + uid + "|" +
 * keyVersion)} (hex). The database holds only the public device uid and the key version, so a database dump alone
 * cannot forge device requests, nothing needs decrypting at request time, and rotating a secret is just
 * {@code keyVersion + 1} (the old secret stops verifying immediately). Trade-off: whoever holds the master key can
 * recompute every device secret, exactly as with "encrypt at rest" designs; rotating the master key means
 * re-provisioning all devices.
 */
@Slf4j
@Component
public class AccessKeys {

    /** Development-only key (well known, never accepted under the prod profile). */
    static final String DEV_MASTER_KEY = "gymops-dev-access-master-key-not-for-production-use";
    private static final int MIN_KEY_LENGTH = 32;

    private final byte[] masterKey;
    private final byte[] qrKey;

    @Autowired
    public AccessKeys(AccessProperties props, Environment environment) {
        this(resolve(props.masterKey(), environment.acceptsProfiles(Profiles.of("prod"))));
    }

    AccessKeys(String masterKey) {
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8);
        this.qrKey = hmac(this.masterKey, "qr-pass|v1".getBytes(StandardCharsets.UTF_8));
    }

    static String resolve(String configured, boolean production) {
        if (configured == null || configured.isBlank()) {
            if (production) {
                throw new IllegalStateException("ACCESS_MASTER_KEY must be set (at least 32 characters) in production");
            }
            log.warn("ACCESS_MASTER_KEY is not set: using the built-in development key (never use this in production)");
            return DEV_MASTER_KEY;
        }
        if (configured.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("ACCESS_MASTER_KEY must be at least " + MIN_KEY_LENGTH + " characters (256 bits)");
        }
        return configured;
    }

    /** The shared secret of one device (hex). Returned to the operator only when the device is created / rotated. */
    public String deviceSecret(String deviceUid, int keyVersion) {
        return HexFormat.of().formatHex(hmac(masterKey, ("device|" + deviceUid + "|" + keyVersion)
                .getBytes(StandardCharsets.UTF_8)));
    }

    /** HMAC-SHA256 with the QR pass key (derived from the master key). */
    public byte[] qrMac(String message) {
        return hmac(qrKey, message.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String hmacHex(String key, String message) {
        return HexFormat.of().formatHex(hmac(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of two hex / ASCII strings. */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

}
