package com.gymops.payment;

public enum PaymentProvider {
    /** Cash / bank transfer recorded by staff. */
    OFFLINE,
    /** Store credit settled inside GymOps. */
    INTERNAL,
    /** Deterministic test gateway for local, CI and demo environments. */
    SIMULATED,
    RAZORPAY
}
