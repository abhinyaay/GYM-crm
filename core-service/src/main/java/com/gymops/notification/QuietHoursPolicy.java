package com.gymops.notification;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * TRAI-style quiet hours for promotional SMS: nothing promotional is sent between {@code startHour} (21:00) and
 * {@code endHour} (09:00) business time; such messages are deferred to the next {@code endHour}. Transactional
 * messages are never delayed.
 */
public record QuietHoursPolicy(int startHour, int endHour, ZoneId zone) {

    public static QuietHoursPolicy of(NotificationProperties.QuietHours q, ZoneId zone) {
        return new QuietHoursPolicy(q.startHour(), q.endHour(), zone);
    }

    public boolean isQuiet(Instant instant) {
        if (startHour == endHour) {
            return false;   // disabled
        }
        int hour = instant.atZone(zone).getHour();
        return startHour > endHour
                ? hour >= startHour || hour < endHour      // wraps midnight (21 -> 9)
                : hour >= startHour && hour < endHour;
    }

    /** {@code instant} itself when outside quiet hours, otherwise the moment quiet hours end. */
    public Instant nextAllowed(Instant instant) {
        if (!isQuiet(instant)) {
            return instant;
        }
        ZonedDateTime local = instant.atZone(zone);
        ZonedDateTime end = local.toLocalDate().atTime(LocalTime.of(endHour, 0)).atZone(zone);
        if (!end.isAfter(local)) {
            end = end.plusDays(1);
        }
        return end.toInstant();
    }
}
