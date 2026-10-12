package com.gymops.access;

public enum CredentialType {
    /** Fingerprint matched on the device; GymOps stores only the device's template reference. */
    FINGERPRINT,
    /** Face matched on the device; GymOps stores only the device's template reference. */
    FACE,
    /** Contactless card / fob; GymOps stores a SHA-256 of the normalised card UID. */
    RFID_CARD,
    /** Rotating QR pass from the member app (TOTP-style, server-verified). */
    QR;

    public boolean isBiometric() {
        return this == FINGERPRINT || this == FACE;
    }
}
