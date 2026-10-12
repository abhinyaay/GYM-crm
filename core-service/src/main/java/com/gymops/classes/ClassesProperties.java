package com.gymops.classes;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Group-class policy knobs ({@code app.classes.*}).
 *
 * @param noShowThreshold   no-shows within {@code noShowWindow} that trigger a booking block
 * @param noShowWindow      look-back window for counting no-shows
 * @param noShowBlock       how long booking is blocked after the threshold is reached
 * @param reconcileAfter    a class is completed and reconciled this long after it ends
 * @param reconcileOpenVisitWindow a visit without check-out counts for a class only if it began at most this long before it
 * @param reminderLeadTime  booked members are reminded this long before the class starts
 * @param maxSeriesWeeks    upper bound for a recurring series
 */
@Validated
@ConfigurationProperties(prefix = "app.classes")
public record ClassesProperties(
        @DefaultValue("3") @Min(1) int noShowThreshold,
        @DefaultValue("30d") @NotNull Duration noShowWindow,
        @DefaultValue("7d") @NotNull Duration noShowBlock,
        @DefaultValue("30m") @NotNull Duration reconcileAfter,
        @DefaultValue("4h") @NotNull Duration reconcileOpenVisitWindow,
        @DefaultValue("2h") @NotNull Duration reminderLeadTime,
        @DefaultValue("26") @Min(1) @Max(52) int maxSeriesWeeks) {
}
