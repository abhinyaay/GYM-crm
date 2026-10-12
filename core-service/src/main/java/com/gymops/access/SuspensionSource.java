package com.gymops.access;

/** Who suspended a credential: staff by hand, or automatically because the member account was suspended. */
public enum SuspensionSource {
    MANUAL,
    MEMBER_STATUS
}
