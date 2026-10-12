package com.gymops.access;

public enum CommandStatus {
    PENDING,
    /** Handed to the device at least once; re-delivered on every poll until acknowledged. */
    DELIVERED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isOpen() {
        return this == PENDING || this == DELIVERED;
    }
}
