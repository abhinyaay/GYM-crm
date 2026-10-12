package com.gymops.growth.referral;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.growth.GrowthProperties;
import com.gymops.growth.offer.OfferPurpose;
import com.gymops.growth.offer.PersonalOfferService;
import com.gymops.growth.offer.PersonalOfferService.IssuedOffer;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.payment.credit.CreditEntryType;
import com.gymops.payment.credit.CreditLedgerEntry;
import com.gymops.payment.credit.StoreCreditService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money side of referrals: conversion on the referee's first paid membership, the anti-fraud
 * hold, release of rewards (store credit / personal code) and reversal on refund or cancellation.
 *
 * <p>Every operation locks the member rows involved first (in id order, the codebase-wide lock
 * order), so it serialises with payments, refunds and credit postings for those members. Rewards are
 * idempotent: UNIQUE(referral_id, beneficiary) plus the referral state machine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralConversionService {

    private final ReferralRepository referrals;
    private final ReferralRewardRepository rewards;
    private final MemberRepository memberRepository;
    private final StoreCreditService storeCredit;
    private final PersonalOfferService offers;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final GrowthProperties props;
    private final Clock clock;

    /** The referee's first paid, non-trial membership (what a referral converts on). */
    public record QualifyingPurchase(Long subscriptionId, Long invoiceId, Instant activatedAt) {
    }

    // ================================================================== event entry points (own transaction)

    /** A subscription of this member was activated: link a pending referral to them and convert it if it qualifies. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMembershipActivated(Long memberId) {
        resolveForMember(memberId).ifPresent(r -> convertIfQualified(r, Instant.now(clock)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRefund(Long refundId) {
        List<Long> invoice = jdbc.queryForList("""
                select p.invoice_id from refunds r join payments p on p.id = r.payment_id where r.id = ?
                """, Long.class, refundId);
        invoice.stream().findFirst()
                .flatMap(id -> referrals.findByQualifyingInvoiceIdAndStatus(id, ReferralStatus.CONVERTED))
                .ifPresent(r -> {
                    lockMembers(r);
                    reverse(r, "Qualifying payment refunded", Instant.now(clock));
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionCancelled(Long subscriptionId) {
        referrals.findByQualifyingSubscriptionIdAndStatus(subscriptionId, ReferralStatus.CONVERTED).ifPresent(r -> {
            lockMembers(r);
            reverse(r, "Qualifying membership cancelled", Instant.now(clock));
        });
    }

    /** Job step: pays out one reward whose hold has ended. Returns true if it became AVAILABLE. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(Long rewardId) {
        Instant now = Instant.now(clock);
        ReferralReward reward = rewards.findById(rewardId).orElseThrow(() -> new ResourceNotFoundException("Reward", rewardId));
        if (!reward.isDue(now)) {
            return false;
        }
        Referral referral = referrals.findById(reward.getReferralId())
                .orElseThrow(() -> new ResourceNotFoundException("Referral", reward.getReferralId()));
        Map<Long, Member> locked = lockMembers(referral);
        if (referral.getStatus() != ReferralStatus.CONVERTED) {
            reward.reverse(now);   // referral was reversed; a held reward must not survive it
            rewards.saveAndFlush(reward);
            return false;
        }
        Optional<String> broken = qualifyingPurchaseProblem(referral);
        if (broken.isPresent()) {
            // a refund / cancellation listener missed (e.g. crash between commit and listener): reverse now
            forceReverse(referral, broken.get() + " (detected at release)", now);
            return false;
        }
        Member member = locked.get(reward.getMemberId());
        payOut(reward, referral, member, now);
        return true;
    }

    /** Job step: re-checks a pending referral whose referee is known (catches activations whose listener was missed). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reconcile(Long refereeMemberId) {
        return referrals.findByRefereeMemberId(refereeMemberId)
                .filter(r -> r.getStatus() == ReferralStatus.PENDING)
                .map(r -> convertIfQualified(r, Instant.now(clock)))
                .orElse(false);
    }

    // ================================================================== core logic (caller's transaction)

    /** Finds the member's referral, linking a pending lead / contact-matched referral to them if needed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Referral> resolveForMember(Long memberId) {
        Optional<Referral> linked = referrals.findByRefereeMemberId(memberId);
        if (linked.isPresent()) {
            return linked;
        }
        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null || member.getDeletedAt() != null) {
            return Optional.empty();
        }
        Optional<Long> candidate = referrals.findUnlinkedByLeadMember(memberId)
                .or(() -> referrals.findUnlinkedByContact(ReferralRules.normalizeEmail(member.getEmail()),
                        ReferralRules.phoneTail(member.getPhone())));
        return candidate.flatMap(referrals::findById).map(r -> {
            r.linkMember(memberId);
            referrals.saveAndFlush(r);
            audit.record(AuditEvent.of("REFERRAL", r.getId(), "MEMBER_LINKED").member(memberId).branch(r.getBranchId()));
            return r;
        });
    }

    /**
     * Converts (or rejects) a pending referral once the referee holds a paid membership. Idempotent:
     * a referral that is no longer PENDING, or whose referee has not paid yet, is left alone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean convertIfQualified(Referral referral, Instant now) {
        if (referral.getStatus() != ReferralStatus.PENDING || referral.getRefereeMemberId() == null) {
            return false;
        }
        Map<Long, Member> locked = lockMembers(referral);
        Member referee = locked.get(referral.getRefereeMemberId());
        Member referrer = locked.get(referral.getReferrerMemberId());
        if (ReferralRules.isSelfReferral(party(referrer), party(referee))) {
            reject(referral, RejectionReason.SELF_REFERRAL);
            return false;
        }
        Optional<QualifyingPurchase> purchase = firstQualifyingPurchase(referee.getId());
        if (purchase.isEmpty()) {
            return false;
        }
        GrowthProperties.Referral cfg = props.referral();
        QualifyingPurchase q = purchase.get();
        if (!ReferralRules.isNewCustomer(q.activatedAt(), referral.getCreatedAt(), cfg.attachGraceDays())) {
            reject(referral, RejectionReason.NOT_NEW);
            return false;
        }

        RewardSkipReason skipped = null;
        if (referrer.getDeletedAt() != null || !referrer.isActive()) {
            skipped = RewardSkipReason.REFERRER_INACTIVE;
        } else {
            Instant[] month = ReferralRules.monthWindow(now, clock.getZone());
            if (ReferralRules.capReached(rewards.countReferrerRewardsBetween(referrer.getId(), month[0], month[1]),
                    cfg.monthlyCap())) {
                skipped = RewardSkipReason.MONTHLY_CAP;
            }
        }
        Instant holdUntil = ReferralRules.holdUntil(now, cfg.holdDays());
        referral.convert(q.subscriptionId(), q.invoiceId(), now, holdUntil, skipped);
        referrals.saveAndFlush(referral);

        List<ReferralReward> created = new ArrayList<>();
        if (skipped == null) {
            created.add(ReferralReward.storeCredit(referral.getId(), referrer.getId(), Beneficiary.REFERRER,
                    cfg.referrerCredit(), holdUntil));
        }
        created.add(cfg.refereeRewardType() == RewardType.STORE_CREDIT
                ? ReferralReward.storeCredit(referral.getId(), referee.getId(), Beneficiary.REFEREE,
                cfg.refereeRewardValue(), holdUntil)
                : ReferralReward.discountCode(referral.getId(), referee.getId(), Beneficiary.REFEREE,
                cfg.refereeDiscountType(), cfg.refereeRewardValue(), holdUntil));
        rewards.saveAllAndFlush(created);

        audit.record(AuditEvent.of("REFERRAL", referral.getId(), "CONVERTED").member(referee.getId())
                .branch(referral.getBranchId()).with("referrerMemberId", referrer.getId())
                .with("subscriptionId", q.subscriptionId()).with("holdUntil", holdUntil)
                .with("referrerRewardSkipped", skipped));
        notifyConverted(referral, referrer, referee, skipped, holdUntil);

        if (!holdUntil.isAfter(now)) {   // hold-days = 0: pay out straight away
            created.forEach(reward -> payOut(reward, referral, locked.get(reward.getMemberId()), now));
        }
        return true;
    }

    /** Reverses a converted referral and its held rewards if still inside the hold; returns whether it did. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reverse(Referral referral, String reason, Instant now) {
        if (!referral.isInHold(now)) {
            return false;
        }
        forceReverse(referral, reason, now);
        return true;
    }

    // ================================================================== helpers

    private void forceReverse(Referral referral, String reason, Instant now) {
        referral.reverse(reason, now);
        referrals.saveAndFlush(referral);
        for (ReferralReward reward : rewards.findByReferralId(referral.getId())) {
            if (reward.getStatus() == RewardStatus.HELD) {
                reward.reverse(now);
            }
        }
        rewards.flush();
        audit.record(AuditEvent.of("REFERRAL", referral.getId(), "REVERSED").member(referral.getRefereeMemberId())
                .branch(referral.getBranchId()).with("reason", reason));
    }

    private void reject(Referral referral, RejectionReason reason) {
        referral.reject(reason);
        referrals.saveAndFlush(referral);
        audit.record(AuditEvent.of("REFERRAL", referral.getId(), "REJECTED").member(referral.getRefereeMemberId())
                .branch(referral.getBranchId()).with("reason", reason));
        log.info("referral_rejected referralId={} reason={}", referral.getId(), reason);
    }

    private void payOut(ReferralReward reward, Referral referral, Member member, Instant now) {
        String referee = referral.refereeDisplayName();
        String title;
        String body;
        if (reward.getRewardType() == RewardType.STORE_CREDIT) {
            String reason = reward.getBeneficiary() == Beneficiary.REFERRER
                    ? "Referral reward: " + referee + " joined (referral #" + referral.getId() + ")"
                    : "Referral welcome reward (referral #" + referral.getId() + ")";
            CreditLedgerEntry entry = storeCredit.post(member.getId(), CreditEntryType.ISSUED, reward.getAmount(), reason,
                    null, null);
            reward.releaseAsCredit(entry.getId(), now);
            title = reward.getBeneficiary() == Beneficiary.REFERRER ? "Referral reward added" : "Your welcome reward is ready";
            body = RewardTexts.rupees(reward.getAmount()) + " store credit was added to your account"
                    + (reward.getBeneficiary() == Beneficiary.REFERRER ? " because " + referee + " joined." : ".")
                    + " Balance: " + RewardTexts.rupees(entry.getBalanceAfter()) + ". It is used automatically "
                    + "when you pay with store credit.";
        } else {
            IssuedOffer offer = offers.issue(member.getId(), "REF", reward.getDiscountType(), reward.getAmount(),
                    props.referral().refereeCodeValidDays(), OfferPurpose.REFERRAL_WELCOME,
                    "Referral welcome reward for member " + member.getMemberCode() + " (referral #" + referral.getId() + ")");
            reward.releaseAsCode(offer.discountId(), now);
            title = "Your welcome reward is ready";
            body = "Use code " + offer.code() + " for " + RewardTexts.discount(offer.type(), offer.value())
                    + " your next membership purchase. Valid until " + RewardTexts.day(offer.validUntil())
                    + "; single use, on your account only.";
        }
        rewards.saveAndFlush(reward);
        audit.record(AuditEvent.of("REFERRAL_REWARD", reward.getId(), "RELEASED").member(member.getId())
                .branch(referral.getBranchId()).with("referralId", referral.getId())
                .with("type", reward.getRewardType()).with("amount", reward.getAmount()));
        notifications.notifyMember(member, NotificationType.REFERRAL_REWARD_EARNED, title, body,
                "referral-reward:" + reward.getId() + ":released");
    }

    private void notifyConverted(Referral referral, Member referrer, Member referee, RewardSkipReason skipped,
                                 Instant holdUntil) {
        GrowthProperties.Referral cfg = props.referral();
        String when = RewardTexts.day(holdUntil, clock.getZone());
        if (skipped == null) {
            notifications.notifyMember(referrer, NotificationType.REFERRAL_REWARD_EARNED,
                    referral.refereeDisplayName() + " joined - reward on its way",
                    "Thanks for the referral! " + RewardTexts.rupees(cfg.referrerCredit()) + " store credit will be added on "
                            + when + ", once their membership is confirmed.",
                    "referral:" + referral.getId() + ":converted:referrer");
        } else if (skipped == RewardSkipReason.MONTHLY_CAP) {
            notifications.notifyMember(referrer, NotificationType.REFERRAL_REWARD_EARNED,
                    referral.refereeDisplayName() + " joined",
                    "Thanks for the referral! You have reached this month's limit of " + cfg.monthlyCap()
                            + " referral rewards, so this one does not earn credit. New referrals earn again next month.",
                    "referral:" + referral.getId() + ":converted:referrer");
        }
        notifications.notifyMember(referee, NotificationType.REFERRAL_REWARD_EARNED, "Welcome! Your reward is on its way",
                "Thanks for joining through " + referrer.getFirstName() + "'s invitation. Your welcome reward ("
                        + RewardTexts.reward(cfg.refereeRewardType(), cfg.refereeDiscountType(), cfg.refereeRewardValue())
                        + ") will be ready on " + when + ".",
                "referral:" + referral.getId() + ":converted:referee");
    }

    public Optional<QualifyingPurchase> firstQualifyingPurchase(Long memberId) {
        return jdbc.query("""
                select s.id as subscription_id, i.id as invoice_id, s.activated_at
                from subscriptions s
                join invoices i on i.subscription_id = s.id and i.status <> 'VOID'
                where s.member_id = ? and s.activated_at is not null and i.paid_at is not null and i.amount_paid > 0
                  and not exists (select 1 from leads l where l.trial_subscription_id = s.id)
                order by s.activated_at, s.id
                limit 1
                """, (rs, n) -> new QualifyingPurchase(rs.getLong("subscription_id"), rs.getLong("invoice_id"),
                rs.getTimestamp("activated_at").toInstant()), memberId).stream().findFirst();
    }

    /** Why the referral's qualifying purchase no longer stands (refunded / cancelled), if it does not. */
    private Optional<String> qualifyingPurchaseProblem(Referral referral) {
        return jdbc.query("""
                select s.status as sub_status, coalesce(i.amount_refunded, 0) as refunded
                from subscriptions s left join invoices i on i.id = ?
                where s.id = ?
                """, (rs, n) -> {
                    if (rs.getBigDecimal("refunded").signum() > 0) {
                        return "Qualifying payment refunded";
                    }
                    return "CANCELLED".equals(rs.getString("sub_status")) ? "Qualifying membership cancelled" : null;
                }, referral.getQualifyingInvoiceId(), referral.getQualifyingSubscriptionId())
                .stream().filter(Objects::nonNull).findFirst();
    }

    /** Locks referrer and referee member rows in id order (deadlock-free with every other member-locking path). */
    private Map<Long, Member> lockMembers(Referral referral) {
        List<Long> ids = new ArrayList<>();
        ids.add(referral.getReferrerMemberId());
        if (referral.getRefereeMemberId() != null) {
            ids.add(referral.getRefereeMemberId());
        }
        Map<Long, Member> locked = new HashMap<>();
        ids.stream().distinct().sorted().forEach(id -> locked.put(id, memberRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Member", id))));
        return locked;
    }

    private static ReferralRules.Party party(Member m) {
        return new ReferralRules.Party(m.getId(), m.getEmail(), m.getPhone());
    }
}
