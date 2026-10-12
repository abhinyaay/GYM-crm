package com.gymops.pricing;

import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.plan.MembershipPlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One entry of a plan's price history: a scheduled change (pending until its effective date) or an
 * applied one (scheduled or a direct edit). Old values are re-captured when the change is applied,
 * so the history always shows the price that was actually replaced.
 */
@Getter
@Entity
@Table(name = "plan_price_changes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanPriceChange extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id")
    private MembershipPlan plan;

    @Column(name = "old_price", nullable = false)
    private BigDecimal oldPrice;

    @Column(name = "new_price", nullable = false)
    private BigDecimal newPrice;

    @Column(name = "old_joining_fee", nullable = false)
    private BigDecimal oldJoiningFee;

    @Column(name = "new_joining_fee", nullable = false)
    private BigDecimal newJoiningFee;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "existing_member_policy", nullable = false)
    private ExistingMemberPolicy policy;

    @Column(name = "protected_until")
    private LocalDate protectedUntil;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriceChangeSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriceChangeStatus status;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "protected_count")
    private Integer protectedCount;

    @Column(name = "notified_count", nullable = false)
    private int notifiedCount;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    private PlanPriceChange(MembershipPlan plan, BigDecimal newPrice, BigDecimal newJoiningFee, LocalDate effectiveDate,
                            ExistingMemberPolicy policy, LocalDate protectedUntil, String reason, PriceChangeSource source) {
        this.plan = plan;
        this.oldPrice = plan.getPrice();
        this.oldJoiningFee = plan.getJoiningFee();
        this.newPrice = newPrice;
        this.newJoiningFee = newJoiningFee;
        this.effectiveDate = effectiveDate;
        this.policy = policy;
        this.protectedUntil = policy == ExistingMemberPolicy.PROTECT_EXISTING ? protectedUntil : null;
        this.reason = reason;
        this.source = source;
        this.status = PriceChangeStatus.SCHEDULED;
    }

    public static PlanPriceChange schedule(MembershipPlan plan, BigDecimal newPrice, BigDecimal newJoiningFee,
                                           LocalDate effectiveDate, ExistingMemberPolicy policy, LocalDate protectedUntil,
                                           String reason) {
        return new PlanPriceChange(plan, newPrice, newJoiningFee, effectiveDate, policy, protectedUntil, reason,
                PriceChangeSource.SCHEDULED);
    }

    /** History entry for an immediate edit; the caller has already captured the old values. */
    public static PlanPriceChange directEdit(MembershipPlan plan, BigDecimal oldPrice, BigDecimal oldJoiningFee,
                                             LocalDate today, String reason, Instant now) {
        PlanPriceChange c = new PlanPriceChange(plan, plan.getPrice(), plan.getJoiningFee(), today,
                ExistingMemberPolicy.NEW_PRICE_AT_RENEWAL, null, reason, PriceChangeSource.DIRECT_EDIT);
        c.oldPrice = oldPrice;
        c.oldJoiningFee = oldJoiningFee;
        c.status = PriceChangeStatus.APPLIED;
        c.appliedAt = now;
        return c;
    }

    public boolean isPriceIncrease() {
        return newPrice.compareTo(oldPrice) > 0;
    }

    public boolean isDue(LocalDate today) {
        return status == PriceChangeStatus.SCHEDULED && !effectiveDate.isAfter(today);
    }

    /** Applies the change to the plan; old values are refreshed to what the plan held at this moment. */
    public void apply(Instant now) {
        requireScheduled("apply");
        oldPrice = plan.getPrice();
        oldJoiningFee = plan.getJoiningFee();
        plan.setPrice(newPrice);
        plan.setJoiningFee(newJoiningFee);
        status = PriceChangeStatus.APPLIED;
        appliedAt = now;
    }

    public void cancel(String why, Long by, Instant now) {
        requireScheduled("cancel");
        status = PriceChangeStatus.CANCELLED;
        cancelReason = why;
        cancelledBy = by;
        cancelledAt = now;
    }

    public void recordProtected(int count) {
        this.protectedCount = count;
    }

    public void addNotified(int count) {
        this.notifiedCount += count;
    }

    private void requireScheduled(String action) {
        if (status != PriceChangeStatus.SCHEDULED) {
            throw new InvalidStateTransitionException("price change", status, action);
        }
    }
}
