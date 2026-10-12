package com.gymops.growth.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CampaignSchedulerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void dailyRunsAtTheSendHourStrictlyAfterNow() {
        // 13 Oct 2026 08:00 IST -> same day 10:00 IST
        Instant morning = Instant.parse("2026-10-13T02:30:00Z");
        assertThat(CampaignScheduler.nextRun(CampaignSchedule.DAILY, null, 10, IST, morning))
                .isEqualTo(Instant.parse("2026-10-13T04:30:00Z"));
        // exactly 10:00 IST -> tomorrow
        assertThat(CampaignScheduler.nextRun(CampaignSchedule.DAILY, null, 10, IST, Instant.parse("2026-10-13T04:30:00Z")))
                .isEqualTo(Instant.parse("2026-10-14T04:30:00Z"));
    }

    @Test
    void weeklyRunsOnTheChosenWeekday() {
        // Tuesday 13 Oct 2026 -> Monday 19 Oct 10:00 IST
        assertThat(CampaignScheduler.nextRun(CampaignSchedule.WEEKLY, 1, 10, IST, Instant.parse("2026-10-13T02:30:00Z")))
                .isEqualTo(Instant.parse("2026-10-19T04:30:00Z"));
        // Tuesday 08:00 with Tuesday schedule -> same day 10:00; after 10:00 -> next week
        assertThat(CampaignScheduler.nextRun(CampaignSchedule.WEEKLY, 2, 10, IST, Instant.parse("2026-10-13T02:30:00Z")))
                .isEqualTo(Instant.parse("2026-10-13T04:30:00Z"));
        assertThat(CampaignScheduler.nextRun(CampaignSchedule.WEEKLY, 2, 10, IST, Instant.parse("2026-10-13T06:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-20T04:30:00Z"));
    }

    @Test
    void oneOffCampaignsHaveNoNextRun() {
        assertThat(CampaignScheduler.nextRun(CampaignSchedule.NONE, null, 10, IST, Instant.now())).isNull();
    }
}
