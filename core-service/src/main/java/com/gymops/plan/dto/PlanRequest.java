package com.gymops.plan.dto;

import com.gymops.plan.BillingPeriod;
import com.gymops.plan.CancellationPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public record PlanRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,30}$") String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @NotNull BillingPeriod billingPeriod,
        @Min(1) @Max(730) Integer customDurationDays,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal joiningFee,
        boolean allBranchAccess,
        LocalTime accessStartTime,
        LocalTime accessEndTime,
        @Min(1) @Max(100) Integer maxVisitsPerMonth,
        boolean freezeAllowed,
        @Min(0) @Max(365) int minFreezeDays,
        @Min(0) @Max(365) int maxFreezeDays,
        @Min(0) @Max(12) int maxFreezeCount,
        @NotNull CancellationPolicy cancellationPolicy,
        @Min(0) @Max(60) int coolingOffDays,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal cancellationFee,
        @Min(0) @Max(60) int gracePeriodDays,
        @Size(max = 20) List<@NotBlank @Size(max = 200) String> benefits,
        // group classes per calendar month: null = unlimited, 0 = no classes
        @Min(0) @Max(100) Integer classCreditsPerMonth,
        // recorded in the plan's price history when this edit changes the price or joining fee
        @Size(max = 500) String priceChangeReason) {

    @AssertTrue(message = "customDurationDays is required for CUSTOM plans and not allowed otherwise")
    public boolean isDurationValid() {
        return (billingPeriod == BillingPeriod.CUSTOM) == (customDurationDays != null);
    }

    @AssertTrue(message = "accessStartTime and accessEndTime must both be set (start before end) or both omitted")
    public boolean isAccessWindowValid() {
        if (accessStartTime == null && accessEndTime == null) {
            return true;
        }
        return accessStartTime != null && accessEndTime != null && accessStartTime.isBefore(accessEndTime);
    }

    @AssertTrue(message = "freeze policy requires maxFreezeDays >= minFreezeDays and maxFreezeCount >= 1")
    public boolean isFreezePolicyValid() {
        return !freezeAllowed || (maxFreezeDays >= minFreezeDays && maxFreezeDays > 0 && maxFreezeCount >= 1);
    }
}
