package com.gymops.notification;

public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    /** Deliberately not delivered: recipient opted out, or has no usable address/phone for the channel. */
    SKIPPED
}
