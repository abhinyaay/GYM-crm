package com.gymops.subscription.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class SubscriptionRequests {

    private SubscriptionRequests() {
    }

    /**
     * New sale. {@code customPrice} (managers and admins only) sells the plan at a negotiated price with a
     * mandatory {@code customPriceReason}; {@code keepCustomPriceOnRenewal} carries it to renewals.
     */
    public record Create(@NotNull Long memberId, @NotNull Long planId, LocalDate startDate,
                         @Size(max = 30) String discountCode,
                         @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal customPrice,
                         @Size(max = 300) String customPriceReason,
                         Boolean keepCustomPriceOnRenewal) {

        public Create(Long memberId, Long planId, LocalDate startDate, String discountCode) {
            this(memberId, planId, startDate, discountCode, null, null, null);
        }
    }

    /** Renew; planId optional (defaults to the current plan). */
    public record Renew(Long planId, @Size(max = 30) String discountCode) {
    }

    public record ChangePlan(@NotNull Long newPlanId, @Size(max = 30) String discountCode) {
    }

    public record Freeze(@Min(1) @Max(365) int days, @Size(max = 300) String reason) {
    }

    public record Cancel(@NotBlank @Size(max = 500) String reason, Boolean refund) {
    }
}
