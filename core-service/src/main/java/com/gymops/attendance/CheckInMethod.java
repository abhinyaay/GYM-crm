package com.gymops.attendance;

public enum CheckInMethod {
    FRONT_DESK,
    QR_CODE,
    MOBILE_APP,
    /** Admitted by a gate device (turnstile / door) after a signed scan; see the access module. */
    GATE
}
