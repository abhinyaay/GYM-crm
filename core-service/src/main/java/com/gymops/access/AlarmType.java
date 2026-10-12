package com.gymops.access;

public enum AlarmType {
    DOOR_FORCED,
    DOOR_HELD_OPEN,
    TAILGATING,
    TAMPER,
    /** Raised by the server when a device stops sending heartbeats. */
    DEVICE_OFFLINE
}
