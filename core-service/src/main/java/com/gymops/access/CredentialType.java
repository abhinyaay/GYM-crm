package com.gymops.access;

public enum CredentialType {
    /** Fingerprint matched on the device; GymOps stores only the device's template reference. */
    FINGERPRINT,
    /** Face matched on the device; GymOps stores only the device's template reference. */
    FACE,
    /** Contactless card / fob; GymOps stores a SHA-256 of the normalised card UID. */
    RFID_CARD,
    /** Rotating QR pass from the member app (TOTP-style, server-verified). */
    QR,
    /** Not a credential: an anonymous push-button / free exit, logged on the access event only. */
    EXIT_BUTTON;

    public boolean isBiometric() {
        return this == FINGERPRINT || this == FACE;
    }
}
