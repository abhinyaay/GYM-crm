package com.gymops.classes;

import com.gymops.common.exception.BusinessRuleException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Pure booking policy decisions (no I/O), so they can be unit tested exhaustively. */
public final class ClassBookingRules {

    private static final DateTimeFormatter BLOCK_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    private ClassBookingRules() {
    }

    /**
     * Monthly class entitlement of a plan: {@code null} = unlimited, 0 = no classes, otherwise a number
     * of credits consumed by BOOKED / ATTENDED / LATE_CANCELLED / NO_SHOW bookings in that calendar month.
     */
    public static void checkEntitlement(Integer creditsPerMonth, long usedInMonth, YearMonth month) {
        if (creditsPerMonth == null) {
            return;
        }
        if (creditsPerMonth == 0) {
            throw new BusinessRuleException("Your membership plan does not include group classes");
        }
        if (usedInMonth >= creditsPerMonth) {
            throw new BusinessRuleException("All " + creditsPerMonth + " class credits for "
                    + month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear()
                    + " have been used");
        }
    }

    /** Remaining credits ({@code null} = unlimited). */
    public static Integer remaining(Integer creditsPerMonth, long used) {
        return creditsPerMonth == null ? null : (int) Math.max(0, creditsPerMonth - used);
    }

    /**
     * No-show penalty: when a member reaches {@code threshold} no-shows within a rolling {@code window},
     * booking is blocked for {@code block} from the no-show that reached the threshold.
     *
     * @param noShowTimes start times of the member's no-show classes (any order), at least the last window+block
     * @return the instant until which booking is blocked, if currently blocked
     */
    public static Optional<Instant> noShowBlockedUntil(List<Instant> noShowTimes, Instant now, int threshold,
                                                       Duration window, Duration block) {
        List<Instant> sorted = noShowTimes.stream().filter(t -> !t.isAfter(now)).sorted().toList();
        Instant trigger = null;
        for (int i = threshold - 1; i < sorted.size(); i++) {
            Instant current = sorted.get(i);
            Instant firstOfRun = sorted.get(i - threshold + 1);
            if (!current.minus(window).isAfter(firstOfRun)) {
                trigger = current;
            }
        }
        if (trigger == null) {
            return Optional.empty();
        }
        Instant until = trigger.plus(block);
        return now.isBefore(until) ? Optional.of(until) : Optional.empty();
    }

    public static void checkNoShowPenalty(Optional<Instant> blockedUntil, int threshold, Duration window, ZoneId zone) {
        blockedUntil.ifPresent(until -> {
            throw new BusinessRuleException("Class booking is blocked until " + BLOCK_FORMAT.format(until.atZone(zone))
                    + " after " + threshold + " no-shows within " + window.toDays() + " days");
        });
    }
}
