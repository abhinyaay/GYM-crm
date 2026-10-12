package com.gymops.access;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalisation and hashing of credential identifiers. Card UIDs are not secrets (any reader can read them), so the
 * hash is about not keeping a raw identifier, not about secrecy; a gate computes the same unkeyed hash to match the
 * offline allowlist. Template references are opaque ids assigned by the device vendor's software.
 */
public final class CredentialHashes {

    private static final Pattern CARD_UID = Pattern.compile("[0-9A-Z]{4,32}");
    private static final Pattern TEMPLATE_REF = Pattern.compile("[A-Za-z0-9._:-]{1,80}");

    private CredentialHashes() {
    }

    /** Upper-cases and strips separators ("04:a1-b2 c3" → "04A1B2C3"); null when not a plausible UID. */
    public static String normalizeCardUid(String raw) {
        if (raw == null) {
            return null;
        }
        String uid = raw.replaceAll("[\\s:\\-]", "").toUpperCase(Locale.ROOT);
        return CARD_UID.matcher(uid).matches() ? uid : null;
    }

    public static String cardHash(String normalizedUid) {
        return AccessKeys.sha256Hex(("RFID_CARD:" + normalizedUid).getBytes(StandardCharsets.UTF_8));
    }

    public static String normalizeTemplateRef(String raw) {
        if (raw == null) {
            return null;
        }
        String ref = raw.trim();
        return TEMPLATE_REF.matcher(ref).matches() ? ref : null;
    }

    public static String templateHash(CredentialType type, String templateRef) {
        return AccessKeys.sha256Hex(("TEMPLATE:" + type.name() + ":" + templateRef).getBytes(StandardCharsets.UTF_8));
    }

    /** Short, non-identifying hint for screens and logs, e.g. "Card …B2C3". */
    public static String hint(CredentialType type, String normalizedValue) {
        String tail = AccessCredential.tail(normalizedValue);
        return switch (type) {
            case RFID_CARD -> "Card " + tail;
            case FINGERPRINT, FACE -> "Template " + tail;
            case QR -> "QR pass";
            case EXIT_BUTTON -> "Exit button";
        };
    }
}
