package com.gymops.subscription;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.member.Member;
import com.gymops.plan.MembershipPlan;
import com.gymops.pricing.PriceBasis;
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
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A member's purchase of a plan for a date range. All state changes go through intention-revealing
 * methods that guard the lifecycle; services orchestrate, the entity enforces its own invariants.
 * Concurrent edits are caught by the inherited {@code @Version}.
 */
@Getter
@Entity
@Table(name = "subscriptions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id")
    private MembershipPlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_subscription_id")
    private Subscription previousSubscription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ChangeType changeType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "freeze_days_used", nullable = false)
    private int freezeDaysUsed;

    @Column(name = "freeze_count", nullable = false)
    private int freezeCount;

    @Column(name = "frozen_from")
    private LocalDate frozenFrom;

    @Column(name = "frozen_until")
    private LocalDate frozenUntil;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    /** Autopay: renew automatically by debiting {@code autopayMethodId} before the term ends. */
    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    @Column(name = "autopay_method_id")
    private Long autopayMethodId;

    // --- pricing (see com.gymops.pricing.PriceResolver) ---
    /** The plan's list price when this term was sold below it (negotiated / protected), else null. */
    @Column(name = "list_price")
    private BigDecimal listPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_basis", nullable = false)
    private PriceBasis priceBasis = PriceBasis.LIST;

    @Column(name = "price_override_reason")
    private String priceOverrideReason;

    /** Negotiated price carries over to renewals of the same plan (never above the list price). */
    @Column(name = "keep_price_on_renewal", nullable = false)
    private boolean keepPriceOnRenewal;

    /** Existing-member price protection: renewals starting on/before {@code priceProtectedUntil} cost at most this. */
    @Column(name = "locked_price")
    private BigDecimal lockedPrice;

    @Column(name = "price_protected_until")
    private LocalDate priceProtectedUntil;

    @Column(name = "price_change_id")
    private Long priceChangeId;

    private Subscription(Member member, MembershipPlan plan, GymBranch branch, LocalDate startDate,
                         ChangeType changeType, Subscription previous) {
        this.member = member;
        this.plan = plan;
        this.branch = branch;
        this.startDate = startDate;
        this.endDate = plan.endDateFor(startDate);
        this.price = plan.getPrice();
        this.changeType = changeType;
        this.previousSubscription = previous;
        this.status = SubscriptionStatus.PENDING_PAYMENT;
    }

    public static Subscription pending(Member member, MembershipPlan plan, GymBranch branch, LocalDate startDate,
                                      ChangeType changeType, Subscription previous) {
        return new Subscription(member, plan, branch, startDate, changeType, previous);
    }

    /** A term sold at {@code price}, which may differ from the plan's stored price (scheduled change, protection ...). */
    public static Subscription pending(Member member, MembershipPlan plan, GymBranch branch, LocalDate startDate,
                                      ChangeType changeType, Subscription previous, BigDecimal price) {
        Subscription sub = new Subscription(member, plan, branch, startDate, changeType, previous);
        sub.price = price;
        return sub;
    }

    // ------------------------------------------------------------------ pricing

    /** Records why this term is priced as it is; {@code listPrice} is kept only when it differs from the price. */
    public void priceAs(PriceBasis basis, BigDecimal planListPrice) {
        this.priceBasis = basis;
        this.listPrice = planListPrice != null && planListPrice.compareTo(price) != 0 ? planListPrice : null;
    }

    /** Manager-approved custom price at the point of sale. */
    public void negotiate(BigDecimal planListPrice, String reason, boolean keepOnRenewal) {
        priceAs(PriceBasis.NEGOTIATED, planListPrice);
        this.priceOverrideReason = reason;
        this.keepPriceOnRenewal = keepOnRenewal;
    }

    /** The renewal of a term whose negotiated price is kept on renewals keeps it too. */
    public void inheritNegotiationFrom(Subscription source) {
        if (source != null && source.keepPriceOnRenewal) {
            this.priceOverrideReason = source.priceOverrideReason;
            this.keepPriceOnRenewal = true;
        }
    }

    public void protectPrice(BigDecimal locked, LocalDate until, Long changeId) {
        if (locked == null || until == null) {
            return;
        }
        this.lockedPrice = locked;
        this.priceProtectedUntil = until;
        this.priceChangeId = changeId;
    }

    /** The negotiated price to apply on renewal, or null. */
    public BigDecimal negotiatedRenewalPrice() {
        return keepPriceOnRenewal ? price : null;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Payment received: the subscription becomes ACTIVE, or SCHEDULED if it starts in the future.
     * A term whose start date has passed while unpaid is moved to start today (full term kept),
     * except a renewal that directly continues its predecessor (paid during grace), which stays
     * back-dated so the membership has no gap.
     */
    public void activate(LocalDate today, Instant now) {
        requireStatus(EnumSet.of(SubscriptionStatus.PENDING_PAYMENT), "activate");
        boolean continuesPredecessor = previousSubscription != null
                && previousSubscription.getStatus() != SubscriptionStatus.SUPERSEDED
                && previousSubscription.getEndDate().plusDays(1).equals(startDate);
        if (startDate.isBefore(today) && !continuesPredecessor) {
            startDate = today;
            endDate = plan.endDateFor(today);
        }
        status = startDate.isAfter(today) ? SubscriptionStatus.SCHEDULED : SubscriptionStatus.ACTIVE;
        activatedAt = now;
    }

    public boolean startIfDue(LocalDate today) {
        if (status == SubscriptionStatus.SCHEDULED && !startDate.isAfter(today)) {
            status = SubscriptionStatus.ACTIVE;
            return true;
        }
        return false;
    }

    /**
     * Freezes the membership from today for {@code days} days, extending the end date by the same
     * number of days, subject to the plan's freeze policy.
     */
    public void freeze(LocalDate today, int days) {
        checkCanFreeze(today, days);
        frozenFrom = today;
        frozenUntil = today.plusDays(days - 1L);
        endDate = endDate.plusDays(days);
        freezeDaysUsed += days;
        freezeCount++;
        status = SubscriptionStatus.FROZEN;
    }

    /** Validates the freeze policy without changing state. */
    public void checkCanFreeze(LocalDate today, int days) {
        requireStatus(EnumSet.of(SubscriptionStatus.ACTIVE), "freeze");
        if (!plan.isFreezeAllowed()) {
            throw new BusinessRuleException("Plan " + plan.getCode() + " does not allow freezing");
        }
        if (freezeCount >= plan.getMaxFreezeCount()) {
            throw new BusinessRuleException("Freeze limit reached (" + plan.getMaxFreezeCount() + " per term)");
        }
        int remainingAllowance = plan.getMaxFreezeDays() - freezeDaysUsed;
        if (days < plan.getMinFreezeDays() || days > remainingAllowance) {
            throw new BusinessRuleException("Freeze must be between " + plan.getMinFreezeDays() + " and "
                    + remainingAllowance + " days");
        }
        if (today.isAfter(endDate)) {
            throw new BusinessRuleException("Subscription has already ended");
        }
    }

    /** Moves a not-yet-started subscription (e.g. a queued renewal) when its predecessor is frozen/resumed. */
    public void shiftDates(int days) {
        requireStatus(EnumSet.of(SubscriptionStatus.PENDING_PAYMENT, SubscriptionStatus.SCHEDULED), "reschedule");
        startDate = startDate.plusDays(days);
        endDate = endDate.plusDays(days);
    }

    /**
     * Early resume: unused freeze days are returned to the allowance and removed from the extended
     * end date.
     *
     * @return number of freeze days that were not used
     */
    public int resume(LocalDate today) {
        requireStatus(EnumSet.of(SubscriptionStatus.FROZEN), "resume");
        int unused = remainingFreezeDays(today);
        endDate = endDate.minusDays(unused);
        freezeDaysUsed -= unused;
        clearFreeze();
        return unused;
    }

    public boolean resumeIfFreezeEnded(LocalDate today) {
        if (status == SubscriptionStatus.FROZEN && frozenUntil.isBefore(today)) {
            clearFreeze();
            return true;
        }
        return false;
    }

    /**
     * Daily expiry evaluation for a subscription whose end date has passed.
     *
     * @param hasSuccessor whether a paid renewal/downgrade follows this subscription
     * @return true if the status changed
     */
    public boolean evaluateExpiry(LocalDate today, boolean hasSuccessor) {
        if (!today.isAfter(endDate)) {
            return false;
        }
        LocalDate graceEnd = endDate.plusDays(plan.getGracePeriodDays());
        if (status == SubscriptionStatus.ACTIVE) {
            status = hasSuccessor || today.isAfter(graceEnd) ? SubscriptionStatus.EXPIRED : SubscriptionStatus.GRACE;
            return true;
        }
        if (status == SubscriptionStatus.GRACE && (hasSuccessor || today.isAfter(graceEnd))) {
            status = SubscriptionStatus.EXPIRED;
            return true;
        }
        return false;
    }

    public void cancel(LocalDate today, String reason, Instant now) {
        requireStatus(EnumSet.of(SubscriptionStatus.PENDING_PAYMENT, SubscriptionStatus.SCHEDULED,
                SubscriptionStatus.ACTIVE, SubscriptionStatus.FROZEN, SubscriptionStatus.GRACE), "cancel");
        if (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.FROZEN) {
            endDate = today.isBefore(startDate) ? startDate : today;   // access ends today
        }
        clearFreezeFields();
        autoRenew = false;
        status = SubscriptionStatus.CANCELLED;
        cancelledAt = now;
        cancellationReason = reason;
    }

    /** Replaced by an upgrade that takes effect today. */
    public void supersede(LocalDate today) {
        requireStatus(EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.FROZEN, SubscriptionStatus.SCHEDULED),
                "supersede");
        LocalDate yesterday = today.minusDays(1);
        endDate = yesterday.isBefore(startDate) ? startDate : yesterday;
        clearFreezeFields();
        autoRenew = false;
        status = SubscriptionStatus.SUPERSEDED;
    }

    // ------------------------------------------------------------------ autopay

    public void enableAutopay(Long paymentMethodId) {
        requireStatus(EnumSet.of(SubscriptionStatus.PENDING_PAYMENT, SubscriptionStatus.SCHEDULED,
                SubscriptionStatus.ACTIVE, SubscriptionStatus.FROZEN, SubscriptionStatus.GRACE), "enable autopay");
        if (paymentMethodId == null) {
            throw new BusinessRuleException("Autopay needs a saved payment method");
        }
        this.autoRenew = true;
        this.autopayMethodId = paymentMethodId;
    }

    public void disableAutopay() {
        this.autoRenew = false;
    }

    /** The next term inherits autopay, so recurring billing continues term after term. */
    public void inheritAutopayFrom(Subscription previous) {
        if (previous != null && previous.autoRenew) {
            this.autoRenew = true;
            this.autopayMethodId = previous.autopayMethodId;
            previous.autoRenew = false;
        }
    }

    // ------------------------------------------------------------------ queries

    public boolean isCommitted() {
        return SubscriptionStatus.COMMITTED.contains(status);
    }

    /** Whether this subscription lets the member in on {@code date} (ignoring plan time/branch rules). */
    public boolean grantsAccessOn(LocalDate date) {
        if (date.isBefore(startDate)) {
            return false;
        }
        return switch (status) {
            case ACTIVE -> !date.isAfter(endDate);
            case GRACE -> !date.isAfter(endDate.plusDays(plan.getGracePeriodDays()));
            default -> false;
        };
    }

    /** Days actually paid for (the freeze extension is free time, not paid time). */
    public long paidDays() {
        return ChronoUnit.DAYS.between(startDate, endDate) + 1 - freezeDaysUsed;
    }

    /** Paid days not yet consumed as of {@code today} (today counts as consumed). */
    public long unusedPaidDays(LocalDate today) {
        LocalDate from = today.isBefore(startDate) ? startDate : today.plusDays(1);
        if (from.isAfter(endDate)) {
            return 0;
        }
        long remaining = ChronoUnit.DAYS.between(from, endDate) + 1 - remainingFreezeDays(from);
        return Math.max(0, Math.min(remaining, paidDays()));
    }

    public int remainingFreezeDays(LocalDate from) {
        if (status != SubscriptionStatus.FROZEN || frozenUntil.isBefore(from)) {
            return 0;
        }
        LocalDate start = from.isBefore(frozenFrom) ? frozenFrom : from;
        return (int) ChronoUnit.DAYS.between(start, frozenUntil) + 1;
    }

    public long daysUntilEnd(LocalDate today) {
        return ChronoUnit.DAYS.between(today, endDate);
    }

    // ------------------------------------------------------------------ helpers

    private void clearFreeze() {
        clearFreezeFields();
        status = SubscriptionStatus.ACTIVE;
    }

    private void clearFreezeFields() {
        frozenFrom = null;
        frozenUntil = null;
    }

    private void requireStatus(Set<SubscriptionStatus> allowed, String action) {
        if (!allowed.contains(status)) {
            throw new InvalidStateTransitionException("subscription", status, action);
        }
    }
}
