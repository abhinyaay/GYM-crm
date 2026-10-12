package com.gymops.access;

/** Which way a device admits people. A BOTH device reports the direction on every scan. */
public enum DeviceDirection {
    ENTRY,
    EXIT,
    BOTH;

    public boolean permits(Direction direction) {
        return this == BOTH || name().equals(direction.name());
    }
}
