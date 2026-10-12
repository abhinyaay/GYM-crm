package com.gymops.payment.dto;

import com.gymops.payment.DiscountType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record DiscountRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$") String code,
        @Size(max = 300) String description,
        @NotNull DiscountType discountType,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal value,
        @NotNull LocalDate validFrom,
        LocalDate validUntil,
        @Min(1) Integer maxRedemptions) {

    @AssertTrue(message = "percentage discounts cannot exceed 100")
    public boolean isPercentageValid() {
        return discountType != DiscountType.PERCENTAGE || value == null || value.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    @AssertTrue(message = "validUntil must not be before validFrom")
    public boolean isRangeValid() {
        return validUntil == null || validFrom == null || !validUntil.isBefore(validFrom);
    }
}
