package com.gymops.branch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

public record OperatingHoursRequest(@NotEmpty @Size(max = 7) List<@Valid Day> days) {

    public record Day(
            @Min(1) @Max(7) int dayOfWeek,
            LocalTime openTime,
            LocalTime closeTime,
            boolean closed) {

        @AssertTrue(message = "openTime must be before closeTime unless the day is closed")
        public boolean isRangeValid() {
            return closed || (openTime != null && closeTime != null && openTime.isBefore(closeTime));
        }
    }

    @AssertTrue(message = "each day of week may appear only once")
    public boolean isDistinctDays() {
        return days == null || days.stream().map(Day::dayOfWeek).distinct().count() == days.size();
    }
}
