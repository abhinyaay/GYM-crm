package com.gymops.growth.referral;

import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One referrer -> referee relationship. Status changes only go through the intention-revealing
 * methods below, guarded by {@link ReferralStatus#canTransitionTo}; the database CHECKs mirror the
 * same invariants (converted_at iff CONVERTED/REVERSED, rejection_reason iff REJECTED ...).
 */
@Getter
@Entity
@Table(name = "referrals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Referral extends BaseEntity {

    @Column(name = "referrer_member_id", nullable = false, updatable = false)
    private Long referrerMemberId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "referee_member_id")
    private Long refereeMemberId;

    @Column(name = "referee_lead_id", updatable = false)
    private Long refereeLeadId;

    @Column(name = "referee_first_name", nullable = false)
    private String refereeFirstName;

    @Column(name = "referee_last_name")
    private String refereeLastName;

    @Column(name = "referee_email")
    private String refereeEmail;

    @Column(name = "referee_phone")
    private String refereePhone;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ReferralChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferralStatus status = ReferralStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason")
    private RejectionReason rejectionReason;

    @Column(name = "qualifying_subscription_id")
    private Long qualifyingSubscriptionId;

    @Column(name = "qualifying_invoice_id")
    private Long qualifyingInvoiceId;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @Column(name = "hold_until")
    private Instant holdUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "referrer_reward_skipped")
    private RewardSkipReason referrerRewardSkipped;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversal_reason")
    private String reversalReason;

    public record Referee(Long memberId, Long leadId, String firstName, String lastName, String email, String phone,
                          Long branchId) {
    }

    public Referral(Long referrerMemberId, String code, Referee referee, ReferralChannel channel) {
        if (referee.memberId() == null && referee.leadId() == null) {
            throw new IllegalArgumentException("A referee needs a member or a lead");
        }
        this.referrerMemberId = Objects.requireNonNull(referrerMemberId);
        this.code = Objects.requireNonNull(code);
        this.refereeMemberId = referee.memberId();
        this.refereeLeadId = referee.leadId();
        this.refereeFirstName = Objects.requireNonNull(referee.firstName()).trim();
        this.refereeLastName = referee.lastName() == null || referee.lastName().isBlank() ? null : referee.lastName().trim();
        this.refereeEmail = referee.email() == null || referee.email().isBlank() ? null : referee.email().trim().toLowerCase();
        this.refereePhone = referee.phone();
        this.branchId = Objects.requireNonNull(referee.branchId());
        this.channel = Objects.requireNonNull(channel);
    }

    /** Links the member the referee became (e.g. a website lead who later registered). */
    public void linkMember(Long memberId) {
        requireStatus(ReferralStatus.PENDING, "link a member to");
        if (refereeMemberId != null && !refereeMemberId.equals(memberId)) {
            throw new InvalidStateTransitionException("referral", status, "re-link");
        }
        this.refereeMemberId = Objects.requireNonNull(memberId);
    }

    public void convert(Long subscriptionId, Long invoiceId, Instant now, Instant holdEndsAt, RewardSkipReason skipped) {
        transition(ReferralStatus.CONVERTED);
        this.qualifyingSubscriptionId = Objects.requireNonNull(subscriptionId);
        this.qualifyingInvoiceId = invoiceId;
        this.convertedAt = Objects.requireNonNull(now);
        this.holdUntil = Objects.requireNonNull(holdEndsAt);
        this.referrerRewardSkipped = skipped;
    }

    public void reject(RejectionReason reason) {
        transition(ReferralStatus.REJECTED);
        this.rejectionReason = Objects.requireNonNull(reason);
    }

    /**
     * Reverses a conversion. Callers only do this while no reward has been paid out (inside the hold, or
     * when a missed refund is detected at release time).
     */
    public void reverse(String reason, Instant now) {
        transition(ReferralStatus.REVERSED);
        this.reversedAt = now;
        this.reversalReason = reason;
    }

    public boolean isInHold(Instant now) {
        return status == ReferralStatus.CONVERTED && holdUntil != null && now.isBefore(holdUntil);
    }

    /** "Rahul S." - what the referrer gets to see about the person they referred. */
    public String refereeDisplayName() {
        return ReferralRules.displayName(refereeFirstName, refereeLastName);
    }

    public String refereeFullName() {
        return refereeLastName == null ? refereeFirstName : refereeFirstName + " " + refereeLastName;
    }

    private void requireStatus(ReferralStatus expected, String action) {
        if (status != expected) {
            throw new InvalidStateTransitionException("referral", status, action);
        }
    }

    private void transition(ReferralStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException("referral", status, "move to " + target);
        }
        this.status = target;
    }
}
