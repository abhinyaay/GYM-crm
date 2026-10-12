package com.gymops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class QuietHoursPolicyTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final QuietHoursPolicy policy = new QuietHoursPolicy(21, 9, IST);

    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(IST).toInstant();
    }

    @Test
    void daytimeIsNotQuiet() {
        assertThat(policy.isQuiet(at("2026-10-13T09:00:00"))).isFalse();
        assertThat(policy.isQuiet(at("2026-10-13T20:59:59"))).isFalse();
        assertThat(policy.nextAllowed(at("2026-10-13T14:30:00"))).isEqualTo(at("2026-10-13T14:30:00"));
    }

    @Test
    void lateEveningIsDeferredToNineNextMorning() {
        assertThat(policy.isQuiet(at("2026-10-13T21:00:00"))).isTrue();
        assertThat(policy.nextAllowed(at("2026-10-13T22:15:00"))).isEqualTo(at("2026-10-14T09:00:00"));
    }

    @Test
    void earlyMorningIsDeferredToNineTheSameDay() {
        assertThat(policy.isQuiet(at("2026-10-14T08:59:00"))).isTrue();
        assertThat(policy.nextAllowed(at("2026-10-14T02:00:00"))).isEqualTo(at("2026-10-14T09:00:00"));
    }

    @Test
    void equalHoursDisableQuietHoursAndNonWrappingWindowsWork() {
        assertThat(new QuietHoursPolicy(9, 9, IST).isQuiet(at("2026-10-13T23:00:00"))).isFalse();
        QuietHoursPolicy lunch = new QuietHoursPolicy(13, 14, IST);
        assertThat(lunch.isQuiet(at("2026-10-13T13:30:00"))).isTrue();
        assertThat(lunch.nextAllowed(at("2026-10-13T13:30:00"))).isEqualTo(at("2026-10-13T14:00:00"));
    }
}
