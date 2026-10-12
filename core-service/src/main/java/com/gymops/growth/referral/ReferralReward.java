package com.gymops.growth.referral;

import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.payment.DiscountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A reward earned through a referral: HELD until {@link #availableAt}, then AVAILABLE (store credit
 * posted / personal code issued) or REVERSED. UNIQUE(referral_id, beneficiary) makes creation idempotent.
 */
@Getter
@Entity
@Table(name = "referral_rewards")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReferralReward extends BaseEntity {

    @Column(name = "referral_id", nullable = false, updatable = false)
    private Long referralId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Beneficiary beneficiary;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", nullable = false, updatable = false)
    private RewardType rewardType;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", updatable = false)
    private DiscountType discountType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RewardStatus status = RewardStatus.HELD;

    @Column(name = "available_at", nullable = false, updatable = false)
    private Instant availableAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "credit_entry_id")
    private Long creditEntryId;

    @Column(name = "discount_id")
    private Long discountId;

    private ReferralReward(Long referralId, Long memberId, Beneficiary beneficiary, RewardType rewardType,
                           BigDecimal amount, DiscountType discountType, Instant availableAt) {
        this.referralId = Objects.requireNonNull(referralId);
        this.memberId = Objects.requireNonNull(memberId);
        this.beneficiary = Objects.requireNonNull(beneficiary);
        this.rewardType = Objects.requireNonNull(rewardType);
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Reward amount must be positive");
        }
        if ((rewardType == RewardType.DISCOUNT_CODE) != (discountType != null)) {
            throw new IllegalArgumentException("Discount type is required exactly for discount-code rewards");
        }
        this.amount = amount;
        this.discountType = discountType;
        this.availableAt = Objects.requireNonNull(availableAt);
    }

    public static ReferralReward storeCredit(Long referralId, Long memberId, Beneficiary beneficiary, BigDecimal amount,
                                             Instant availableAt) {
        return new ReferralReward(referralId, memberId, beneficiary, RewardType.STORE_CREDIT, amount, null, availableAt);
    }

    public static ReferralReward discountCode(Long referralId, Long memberId, Beneficiary beneficiary,
                                              DiscountType type, BigDecimal value, Instant availableAt) {
        return new ReferralReward(referralId, memberId, beneficiary, RewardType.DISCOUNT_CODE, value,
                Objects.requireNonNull(type), availableAt);
    }

    public boolean isDue(Instant now) {
        return status == RewardStatus.HELD && !now.isBefore(availableAt);
    }

    public void releaseAsCredit(Long ledgerEntryId, Instant now) {
        requireRelease(now, RewardType.STORE_CREDIT);
        this.creditEntryId = Objects.requireNonNull(ledgerEntryId);
        this.status = RewardStatus.AVAILABLE;
        this.releasedAt = now;
    }

    public void releaseAsCode(Long issuedDiscountId, Instant now) {
        requireRelease(now, RewardType.DISCOUNT_CODE);
        this.discountId = Objects.requireNonNull(issuedDiscountId);
        this.status = RewardStatus.AVAILABLE;
        this.releasedAt = now;
    }

    public void reverse(Instant now) {
        if (status != RewardStatus.HELD) {
            throw new InvalidStateTransitionException("reward", status, "reverse");
        }
        this.status = RewardStatus.REVERSED;
        this.reversedAt = now;
    }

    private void requireRelease(Instant now, RewardType expectedType) {
        if (rewardType != expectedType) {
            throw new IllegalStateException("Reward " + getId() + " is a " + rewardType + " reward");
        }
        if (!isDue(now)) {
            throw new InvalidStateTransitionException("reward", status, "release");
        }
    }
}
