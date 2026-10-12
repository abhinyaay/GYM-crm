package com.gymops.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Proof that a member was told, at {@code notifiedAt}, that their renewal price on a plan rises to {@code newPrice}. */
@Getter
@Entity
@Table(name = "price_change_notices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceChangeNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "price_change_id")
    private Long priceChangeId;

    @Column(name = "current_price", nullable = false)
    private BigDecimal currentPrice;

    @Column(name = "new_price", nullable = false)
    private BigDecimal newPrice;

    @Column(name = "first_debit_on")
    private LocalDate firstDebitOn;

    @Column(name = "notified_at", nullable = false)
    private Instant notifiedAt;

    public PriceChangeNotice(Long memberId, Long planId, Long subscriptionId, Long priceChangeId, BigDecimal currentPrice,
                             BigDecimal newPrice, LocalDate firstDebitOn, Instant notifiedAt) {
        this.memberId = memberId;
        this.planId = planId;
        this.subscriptionId = subscriptionId;
        this.priceChangeId = priceChangeId;
        this.currentPrice = currentPrice;
        this.newPrice = newPrice;
        this.firstDebitOn = firstDebitOn;
        this.notifiedAt = notifiedAt;
    }
}
