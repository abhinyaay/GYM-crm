package com.gymops.access;

public enum CommandType {
    /** Capture a template on the device (member present) and report the device-side template reference. */
    ENROLL_TEMPLATE,
    /** Erase a template from the device (credential revoked / consent withdrawn / member deleted). */
    DELETE_TEMPLATE
}
