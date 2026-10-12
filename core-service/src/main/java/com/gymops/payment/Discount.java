package com.gymops.payment;

import com.gymops.common.persistence.BaseEntity;
import com.gymops.common.util.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "discounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Discount extends BaseEntity {

    @Column(nullable = false)
    private String code;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(nullable = false)
    private BigDecimal value;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    @Setter(AccessLevel.NONE)
    @Column(name = "times_redeemed", nullable = false)
    private int timesRedeemed;

    @Column(nullable = false)
    private boolean active = true;

    public Discount(String code, DiscountType type, BigDecimal value, LocalDate validFrom) {
        this.code = code.trim().toUpperCase();
        this.discountType = type;
        this.value = value;
        this.validFrom = validFrom;
    }

    public boolean isUsableOn(LocalDate date) {
        return active
                && !date.isBefore(validFrom)
                && (validUntil == null || !date.isAfter(validUntil))
                && (maxRedemptions == null || timesRedeemed < maxRedemptions);
    }

    /** Discount amount for a given (pre-tax) subtotal, never more than the subtotal. */
    public BigDecimal amountFor(BigDecimal subtotal) {
        BigDecimal raw = discountType == DiscountType.PERCENTAGE
                ? subtotal.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : value;
        return Money.of(Money.min(raw, subtotal));
    }
}
