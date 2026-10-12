package com.gymops.growth.campaign;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** Next run time of a recurring campaign (pure, unit tested). Runs go out at {@code sendHour}:00 business time. */
public final class CampaignScheduler {

    private CampaignScheduler() {
    }

    /** First run strictly after {@code after}; null for one-off campaigns. */
    public static Instant nextRun(CampaignSchedule schedule, Integer dayOfWeek, int sendHour, ZoneId zone, Instant after) {
        ZonedDateTime from = after.atZone(zone);
        ZonedDateTime candidate = from.toLocalDate().atTime(sendHour, 0).atZone(zone);
        return switch (schedule) {
            case NONE -> null;
            case DAILY -> (candidate.isAfter(from) ? candidate : candidate.plusDays(1)).toInstant();
            case WEEKLY -> {
                ZonedDateTime weekly = candidate.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dayOfWeek)));
                yield (weekly.isAfter(from) ? weekly : weekly.plusWeeks(1)).toInstant();
            }
        };
    }
}
