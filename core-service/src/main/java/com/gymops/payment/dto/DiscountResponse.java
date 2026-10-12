package com.gymops.payment.dto;

import com.gymops.payment.Discount;
import com.gymops.payment.DiscountType;
import java.math.BigDecimal;
import java.time.LocalDate;

public record DiscountResponse(Long id, String code, String description, DiscountType discountType, BigDecimal value,
                               LocalDate validFrom, LocalDate validUntil, Integer maxRedemptions, int timesRedeemed,
                               boolean active) {

    public static DiscountResponse from(Discount d) {
        return new DiscountResponse(d.getId(), d.getCode(), d.getDescription(), d.getDiscountType(), d.getValue(),
                d.getValidFrom(), d.getValidUntil(), d.getMaxRedemptions(), d.getTimesRedeemed(), d.isActive());
    }
}
