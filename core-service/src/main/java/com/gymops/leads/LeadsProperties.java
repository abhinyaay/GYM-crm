package com.gymops.leads;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Sales-pipeline settings ({@code app.leads.*}).
 *
 * @param trialEndingNoticeDays days before a trial's last day on which the member is reminded and the owner gets a call task
 * @param staleTrialDays        days after a trial ended without conversion before a "call to convert" task is raised
 * @param followUpHour          local hour at which automatically created follow-up tasks fall due
 * @param followUpDigestCron    when owners receive their "follow-ups due today" digest (business timezone)
 * @param trialJobsCron         when the trial-ending and stale-trial checks run (business timezone)
 */
@Validated
@ConfigurationProperties(prefix = "app.leads")
public record LeadsProperties(
        @DefaultValue("1") @Min(1) @Max(14) int trialEndingNoticeDays,
        @DefaultValue("7") @Min(1) @Max(90) int staleTrialDays,
        @DefaultValue("10") @Min(0) @Max(23) int followUpHour,
        @DefaultValue("0 0 8 * * *") String followUpDigestCron,
        @DefaultValue("0 30 8 * * *") String trialJobsCron) {
}
