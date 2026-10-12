package com.gymops.access;

public enum CredentialStatus {
    /** Biometric enrolment requested; waiting for the device to capture the template. */
    PENDING,
    ACTIVE,
    SUSPENDED,
    REVOKED;

    public boolean isLive() {
        return this != REVOKED;
    }
}
