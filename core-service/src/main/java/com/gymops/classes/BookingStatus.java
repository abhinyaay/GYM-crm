package com.gymops.classes;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a class booking.
 *
 * <pre>
 *   BOOKED ──cancel before cutoff──▶ CANCELLED
 *     │  └──cancel after cutoff───▶ LATE_CANCELLED
 *     ├──checked in / marked────▶ ATTENDED
 *     └──absent / reconciled────▶ NO_SHOW
 *   WAITLISTED ──seat freed (FIFO)─▶ BOOKED
 *     └──cancel / class ends──────▶ CANCELLED
 * </pre>
 */
public enum BookingStatus {
    BOOKED, WAITLISTED, CANCELLED, LATE_CANCELLED, ATTENDED, NO_SHOW;

    /** Statuses guarded by the partial unique index {@code ux_class_booking_live}. */
    public static final Set<BookingStatus> LIVE = EnumSet.of(BOOKED, WAITLISTED);

    /** Statuses that occupy a seat in the class. */
    public static final Set<BookingStatus> SEAT_HOLDING = EnumSet.of(BOOKED, ATTENDED, NO_SHOW);

    /** Statuses that use one of the member's monthly class credits. */
    public static final Set<BookingStatus> CREDIT_CONSUMING = EnumSet.of(BOOKED, ATTENDED, LATE_CANCELLED, NO_SHOW);

    public boolean isLive() {
        return LIVE.contains(this);
    }

    public boolean holdsSeat() {
        return SEAT_HOLDING.contains(this);
    }
}
