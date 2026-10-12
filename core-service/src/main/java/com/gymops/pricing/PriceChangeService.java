package com.gymops.pricing;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.config.CacheConfig;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.payment.PaymentProperties;
import com.gymops.plan.MembershipPlan;
import com.gymops.plan.MembershipPlanRepository;
import com.gymops.plan.PlanStatus;
import com.gymops.pricing.PriceResolver.Resolution;
import com.gymops.pricing.dto.PricingDtos.AffectedMember;
import com.gymops.pricing.dto.PricingDtos.PreviewResponse;
import com.gymops.pricing.dto.PricingDtos.PriceChangeRequest;
import com.gymops.pricing.dto.PricingDtos.PriceChangeResponse;
import com.gymops.pricing.dto.PricingDtos;
import com.gymops.security.CurrentUser;
import com.gymops.subscription.Subscription;
import com.gymops.subscription.SubscriptionStatus;
import com.gymops.user.User;
import com.gymops.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan price changes: scheduling (with existing-member policy and reason), cancelling, applying on the
 * effective date, recording direct edits, and telling autopay members about renewal price rises in time.
 *
 * <p>Notice rule: an autopay renewal is debited at a higher price only when the member was notified
 * {@code app.pricing.price-change-notice-days} before the debit; otherwise that renewal keeps the
 * member's current price (see {@link PriceResolver}). Notices go out when an increase is scheduled and,
 * as a safety net, from a daily sweep over autopay terms ending soon (protection or negotiated prices
 * running out, autopay switched on after the announcement, ...).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceChangeService {

    private static final int MAX_EFFECTIVE_DAYS_AHEAD = 365;
    private static final int PREVIEW_LIMIT = 200;
    private static final EnumSet<SubscriptionStatus> SWEEPABLE = EnumSet.of(SubscriptionStatus.SCHEDULED,
            SubscriptionStatus.ACTIVE, SubscriptionStatus.FROZEN);

    private final PlanPriceChangeRepository changes;
    private final PriceChangeNoticeRepository notices;
    private final PricingSubscriptionQueries subscriptions;
    private final MembershipPlanRepository plans;
    private final UserRepository users;
    private final PricingService pricing;
    private final NotificationService notifications;
    private final AuditService audit;
    private final CacheManager cacheManager;
    private final PricingProperties props;
    private final PaymentProperties paymentProps;
    private final Clock clock;

    // ================================================================== schedule / cancel

    @Transactional
    public PriceChangeResponse schedule(Long planId, PriceChangeRequest request) {
        MembershipPlan plan = loadPlan(planId);
        Proposal p = proposal(plan, request);
        changes.findFirstByPlanIdAndStatus(planId, PriceChangeStatus.SCHEDULED).ifPresent(existing -> {
            throw new ConflictException("Plan " + plan.getCode() + " already has a price change scheduled for "
                    + existing.getEffectiveDate() + "; cancel it first");
        });
        PlanPriceChange change = changes.saveAndFlush(PlanPriceChange.schedule(plan, p.newPrice(), p.newFee(),
                request.effectiveDate(), request.existingMemberPolicy(), p.protectedUntil(), request.reason().trim()));
        audit.record(AuditEvent.of("PLAN", planId, "PRICE_CHANGE_SCHEDULED").with("priceChangeId", change.getId())
                .with("oldPrice", plan.getPrice()).with("newPrice", p.newPrice())
                .with("oldJoiningFee", plan.getJoiningFee()).with("newJoiningFee", p.newFee())
                .with("effectiveDate", request.effectiveDate()).with("policy", request.existingMemberPolicy())
                .with("protectedUntil", p.protectedUntil()).with("reason", change.getReason()));
        if (change.isPriceIncrease()) {
            change.addNotified(announce(change));
        }
        if (change.isDue(today())) {
            apply(change);
        }
        return response(change);
    }

    @Transactional
    public PriceChangeResponse cancel(Long changeId, String reason) {
        PlanPriceChange change = changes.findById(changeId)
                .orElseThrow(() -> new ResourceNotFoundException("Price change", changeId));
        change.cancel(reason.trim(), CurrentUser.idOrNull(), Instant.now(clock));
        List<PriceChangeNotice> told = notices.findByPriceChangeId(changeId);
        MembershipPlan plan = change.getPlan();
        for (PriceChangeNotice n : told) {
            subscriptionWithMember(n.getSubscriptionId()).ifPresent(s -> notifications.notifyMember(s.getMember(),
                    NotificationType.PLAN_PRICE_CHANGED, "Price change cancelled",
                    "The price change announced for your " + plan.getName() + " membership will not happen. "
                            + "Your renewals continue at your current price.",
                    "price-change-cancelled:" + changeId + ":" + n.getMemberId()));
        }
        notices.deleteByPriceChange(changeId);
        audit.record(AuditEvent.of("PLAN", plan.getId(), "PRICE_CHANGE_CANCELLED").with("priceChangeId", changeId)
                .with("reason", change.getCancelReason()).with("membersInformed", told.size()));
        return response(change);
    }

    // ================================================================== apply

    /** Applies one due change (job step; own transaction). */
    @Transactional
    public boolean applyIfDue(Long changeId) {
        PlanPriceChange change = changes.findById(changeId).orElse(null);
        if (change == null || !change.isDue(today())) {
            return false;
        }
        apply(change);
        return true;
    }

    private void apply(PlanPriceChange change) {
        MembershipPlan plan = change.getPlan();
        Instant now = Instant.now(clock);
        change.apply(now);
        if (change.isPriceIncrease() && change.getPolicy() == ExistingMemberPolicy.PROTECT_EXISTING) {
            changes.flush();
            int locked = subscriptions.protectExisting(plan.getId(), change.getId(), change.getOldPrice(),
                    change.getProtectedUntil(), today(), pricing.existingCutoff(change), now);
            change.recordProtected(locked);
        }
        evictPlanCaches(plan.getId());
        audit.record(AuditEvent.of("PLAN", plan.getId(), "PRICE_CHANGED").with("priceChangeId", change.getId())
                .with("oldPrice", change.getOldPrice()).with("newPrice", change.getNewPrice())
                .with("oldJoiningFee", change.getOldJoiningFee()).with("newJoiningFee", change.getNewJoiningFee())
                .with("effectiveDate", change.getEffectiveDate()).with("policy", change.getPolicy())
                .with("protectedSubscriptions", change.getProtectedCount()));
        log.info("Plan {} price changed {} -> {} (change {})", plan.getCode(), change.getOldPrice(), change.getNewPrice(),
                change.getId());
    }

    @Transactional(readOnly = true)
    public List<Long> dueChangeIds() {
        return changes.findDueIds(today());
    }

    // ================================================================== direct edits (PUT /plans/{id})

    /**
     * Guards an immediate price edit through the plan form: not while a change is scheduled, and an
     * increase on a plan with autopay members must be scheduled (so they get notice and a policy).
     */
    @Transactional(readOnly = true)
    public void assertDirectEditAllowed(MembershipPlan plan, BigDecimal newPrice, BigDecimal newJoiningFee) {
        boolean priceChanges = plan.getPrice().compareTo(newPrice) != 0;
        boolean feeChanges = plan.getJoiningFee().compareTo(newJoiningFee) != 0;
        if (!priceChanges && !feeChanges) {
            return;
        }
        changes.findFirstByPlanIdAndStatus(plan.getId(), PriceChangeStatus.SCHEDULED).ifPresent(c -> {
            throw new ConflictException("Plan " + plan.getCode() + " has a price change scheduled for "
                    + c.getEffectiveDate() + "; cancel it before editing the price");
        });
        if (newPrice.compareTo(plan.getPrice()) > 0 && subscriptions.existsAutopayOnPlan(plan.getId())) {
            throw new BusinessRuleException("Members renew " + plan.getCode() + " by autopay: schedule the increase with "
                    + "'Change price' so they get " + props.priceChangeNoticeDays() + " days' notice");
        }
    }

    /** History entry for an immediate edit (old values captured before the edit). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordDirectEdit(MembershipPlan plan, BigDecimal oldPrice, BigDecimal oldJoiningFee, String reason) {
        if (plan.getPrice().compareTo(oldPrice) == 0 && plan.getJoiningFee().compareTo(oldJoiningFee) == 0) {
            return;
        }
        String why = reason == null || reason.isBlank() ? "Edited on the plan form" : reason.trim();
        PlanPriceChange entry = changes.save(PlanPriceChange.directEdit(plan, oldPrice, oldJoiningFee, today(), why,
                Instant.now(clock)));
        audit.record(AuditEvent.of("PLAN", plan.getId(), "PRICE_EDITED").with("priceChangeId", entry.getId())
                .with("oldPrice", oldPrice).with("newPrice", plan.getPrice()).with("oldJoiningFee", oldJoiningFee)
                .with("newJoiningFee", plan.getJoiningFee()).with("reason", why));
    }

    // ================================================================== notices

    /** Notifies every autopay member of the plan whose renewal price rises; returns how many were told. */
    private int announce(PlanPriceChange change) {
        MembershipPlan plan = change.getPlan();
        LocalDate today = today();
        int told = 0;
        for (Subscription s : subscriptions.findAutopayOnPlan(plan.getId())) {
            BigDecimal target = s.negotiatedRenewalPrice() == null ? change.getNewPrice()
                    : s.negotiatedRenewalPrice().min(change.getNewPrice());
            if (target.compareTo(s.getPrice()) <= 0
                    || notices.existsByMemberIdAndPlanIdAndNewPrice(s.getMember().getId(), plan.getId(), target)) {
                continue;
            }
            LocalDate protectedUntil = change.getPolicy() == ExistingMemberPolicy.PROTECT_EXISTING
                    ? latest(change.getProtectedUntil(), s.getPriceProtectedUntil()) : s.getPriceProtectedUntil();
            LocalDate firstDebit = projectFirstDebit(s, plan, change.getEffectiveDate(), protectedUntil, today)
                    .orElse(null);
            notices.save(new PriceChangeNotice(s.getMember().getId(), plan.getId(), s.getId(), change.getId(),
                    s.getPrice(), target, firstDebit, Instant.now(clock)));
            StringBuilder body = new StringBuilder("The price of the ").append(plan.getName())
                    .append(" membership changes from ₹").append(change.getOldPrice()).append(" to ₹").append(target)
                    .append(" (plus GST) for renewals from ").append(PricingService.DATE.format(change.getEffectiveDate()))
                    .append(". Reason: ").append(change.getReason()).append('.');
            if (change.getPolicy() == ExistingMemberPolicy.PROTECT_EXISTING) {
                body.append(" As an existing member you keep your current price for renewals starting on or before ")
                        .append(PricingService.DATE.format(protectedUntil)).append('.');
            }
            if (firstDebit != null) {
                body.append(" Autopay will first debit the new price on or after ")
                        .append(PricingService.DATE.format(firstDebit)).append('.');
            }
            body.append(" You can switch autopay off at any time from My membership.");
            notifications.notifyMember(s.getMember(), NotificationType.PLAN_PRICE_CHANGED,
                    "Your membership price is changing", body.toString(),
                    "price-change:" + change.getId() + ":" + s.getMember().getId());
            told++;
        }
        return told;
    }

    @Transactional(readOnly = true)
    public List<Long> sweepCandidates() {
        LocalDate until = today().plusDays((long) paymentProps.autopay().leadDays() + props.priceChangeNoticeDays()
                + props.noticeLookaheadDays());
        return subscriptions.findAutopayEndingBy(until);
    }

    /**
     * Safety net for one autopay term: if its next renewal will cost more than the member pays now and
     * no notice for that price exists, send one now. Returns true when a notice was sent.
     */
    @Transactional
    public boolean noticeIfRenewalPriceRises(Long subscriptionId) {
        Subscription s = subscriptions.loadWithMemberAndPlan(subscriptionId);
        if (s == null || !s.isAutoRenew() || !SWEEPABLE.contains(s.getStatus()) || !s.getPlan().isActive()) {
            return false;
        }
        LocalDate today = today();
        LocalDate invoiceDate = s.getEndDate().minusDays(paymentProps.autopay().leadDays());
        if (invoiceDate.isBefore(today)) {
            invoiceDate = today;
        }
        Resolution intended = pricing.resolveRenewal(s, s.getPlan(), invoiceDate, s.getEndDate().plusDays(1), false);
        BigDecimal price = intended.amount();
        if (price.compareTo(s.getPrice()) <= 0
                || !notices.findCovering(s.getMember().getId(), s.getPlan().getId(), price).isEmpty()) {
            return false;
        }
        int preDebitDays = PriceResolver.days(paymentProps.autopay().preDebitNotice());
        LocalDate plannedDebit = invoiceDate.plusDays(preDebitDays);
        boolean inTime = !plannedDebit.isBefore(today.plusDays(props.priceChangeNoticeDays() + 1L));
        LocalDate firstDebit = inTime ? plannedDebit
                : projectFirstDebit(s, s.getPlan(), invoiceDate, null, today).orElse(null);
        notices.save(new PriceChangeNotice(s.getMember().getId(), s.getPlan().getId(), s.getId(), null, s.getPrice(), price,
                firstDebit, Instant.now(clock)));
        String why = switch (s.getPriceBasis()) {
            case NEGOTIATED -> "your negotiated price applied to the current term only";
            case PRICE_PROTECTED -> "your existing-member price protection ends";
            case NOTICE_HELD -> "the plan's price changed and your last renewal was held at the old price";
            case LIST -> "the plan's price has changed";
        };
        String body = "Your " + s.getPlan().getName() + " membership renews at ₹" + price + " (plus GST) instead of ₹"
                + s.getPrice() + " because " + why + ". "
                + (inTime ? "Autopay will debit the new price on or after " + PricingService.DATE.format(plannedDebit) + "."
                        : "Your renewal on " + PricingService.DATE.format(s.getEndDate().plusDays(1))
                                + " stays at ₹" + s.getPrice() + "; the new price applies from the renewal after that"
                                + (firstDebit == null ? "." : " (debit on or after " + PricingService.DATE.format(firstDebit)
                                        + ")."))
                + " You can switch autopay off at any time from My membership.";
        notifications.notifyMember(s.getMember(), NotificationType.PLAN_PRICE_CHANGED, "Your renewal price is changing",
                body, "price-notice:" + s.getMember().getId() + ":" + s.getPlan().getId() + ":" + price.toPlainString());
        audit.record(AuditEvent.of("SUBSCRIPTION", s.getId(), "PRICE_RISE_NOTIFIED").member(s.getMember().getId())
                .with("currentPrice", s.getPrice()).with("newPrice", price).with("firstDebitOn", firstDebit));
        return true;
    }

    private Optional<LocalDate> projectFirstDebit(Subscription s, MembershipPlan plan, LocalDate effective,
                                                  LocalDate protectedUntil, LocalDate today) {
        return PriceResolver.projectFirstDebitAtNewPrice(s.getEndDate(), plan::endDateFor,
                paymentProps.autopay().leadDays(), PriceResolver.days(paymentProps.autopay().preDebitNotice()), today,
                effective, protectedUntil, today, props.priceChangeNoticeDays());
    }

    // ================================================================== preview & queries

    @Transactional(readOnly = true)
    public PreviewResponse preview(Long planId, PriceChangeRequest request) {
        MembershipPlan plan = loadPlan(planId);
        Proposal p = proposal(plan, request);
        LocalDate today = today();
        boolean increase = p.newPrice().compareTo(plan.getPrice()) > 0;
        List<Subscription> autopay = subscriptions.findAutopayOnPlan(planId);
        List<AffectedMember> affected = new ArrayList<>();
        for (Subscription s : autopay) {
            BigDecimal target = s.negotiatedRenewalPrice() == null ? p.newPrice() : s.negotiatedRenewalPrice().min(p.newPrice());
            if (target.compareTo(s.getPrice()) <= 0) {
                continue;
            }
            LocalDate protectedUntil = request.existingMemberPolicy() == ExistingMemberPolicy.PROTECT_EXISTING
                    ? latest(p.protectedUntil(), s.getPriceProtectedUntil()) : s.getPriceProtectedUntil();
            LocalDate firstDebit = projectFirstDebit(s, plan, request.effectiveDate(), protectedUntil, today).orElse(null);
            if (affected.size() < PREVIEW_LIMIT) {
                affected.add(new AffectedMember(s.getId(), s.getMember().getId(), s.getMember().getMemberCode(),
                        s.getMember().fullName(), s.getEndDate(), s.getPrice(), target,
                        s.negotiatedRenewalPrice() != null && target.compareTo(p.newPrice()) < 0 ? PriceBasis.NEGOTIATED
                                : PriceBasis.LIST, firstDebit));
            }
        }
        List<String> notes = new ArrayList<>();
        if (increase && !affected.isEmpty()) {
            notes.add(affected.size() + " autopay member(s) will be notified today; nobody is debited the new price "
                    + "until " + props.priceChangeNoticeDays() + " days after the notice.");
        }
        if (request.existingMemberPolicy() == ExistingMemberPolicy.PROTECT_EXISTING) {
            notes.add("Members holding the plan before " + PricingService.DATE.format(request.effectiveDate())
                    + " keep their price for renewals starting on or before " + PricingService.DATE.format(p.protectedUntil())
                    + "; new sales pay the new price from the effective date.");
        } else if (increase) {
            notes.add("Existing members pay the new price from their first renewal invoiced on or after "
                    + PricingService.DATE.format(request.effectiveDate()) + ".");
        }
        if (!increase && p.newPrice().compareTo(plan.getPrice()) < 0) {
            notes.add("Price decrease: existing members also renew at the lower price.");
        }
        return new PreviewResponse(planId, plan.getName(), plan.getPrice(), p.newPrice(), plan.getJoiningFee(), p.newFee(),
                PricingDtos.pct(plan.getPrice(), p.newPrice()), increase, request.effectiveDate(),
                request.existingMemberPolicy(), p.protectedUntil(), props.priceChangeNoticeDays(), today,
                subscriptions.countOpenOnPlan(planId), autopay.size(), affected.size(), affected, notes);
    }

    @Transactional(readOnly = true)
    public List<PriceChangeResponse> history(Long planId) {
        loadPlan(planId);
        return responses(changes.findByPlanIdOrderByCreatedAtDesc(planId));
    }

    @Transactional(readOnly = true)
    public List<PriceChangeResponse> scheduled() {
        return responses(changes.findByStatusWithPlan(PriceChangeStatus.SCHEDULED));
    }

    // ================================================================== helpers

    private record Proposal(BigDecimal newPrice, BigDecimal newFee, LocalDate protectedUntil) {
    }

    private Proposal proposal(MembershipPlan plan, PriceChangeRequest r) {
        if (plan.getStatus() == PlanStatus.ARCHIVED) {
            throw new BusinessRuleException("Plan " + plan.getCode() + " is archived");
        }
        BigDecimal newPrice = r.newPrice() == null ? plan.getPrice() : r.newPrice();
        BigDecimal newFee = r.newJoiningFee() == null ? plan.getJoiningFee() : r.newJoiningFee();
        if (newPrice.compareTo(plan.getPrice()) == 0 && newFee.compareTo(plan.getJoiningFee()) == 0) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "The new price and joining fee equal the current ones");
        }
        LocalDate today = today();
        if (r.effectiveDate().isBefore(today) || r.effectiveDate().isAfter(today.plusDays(MAX_EFFECTIVE_DAYS_AHEAD))) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "effectiveDate must be between today and " + MAX_EFFECTIVE_DAYS_AHEAD + " days ahead");
        }
        LocalDate protectedUntil = null;
        if (r.existingMemberPolicy() == ExistingMemberPolicy.PROTECT_EXISTING) {
            if (newPrice.compareTo(plan.getPrice()) <= 0) {
                throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                        "Price protection only applies to a price increase");
            }
            protectedUntil = r.protectMonths() != null
                    ? r.effectiveDate().plusMonths(r.protectMonths()).minusDays(1) : r.protectUntil();
            if (protectedUntil.isBefore(r.effectiveDate())) {
                throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "protectUntil must be on/after the effective date");
            }
        }
        return new Proposal(newPrice, newFee, protectedUntil);
    }

    private List<PriceChangeResponse> responses(List<PlanPriceChange> list) {
        Set<Long> ids = new HashSet<>();
        list.forEach(c -> {
            ids.add(c.getCreatedBy());
            ids.add(c.getCancelledBy());
        });
        ids.remove(null);
        Map<Long, String> names = users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> Objects.requireNonNullElse(u.getFullName(), u.getEmail())));
        Function<Long, String> name = id -> id == null ? null : names.getOrDefault(id, "user #" + id);
        return list.stream().map(c -> PriceChangeResponse.from(c, c.getCreatedBy() == null ? "system"
                : name.apply(c.getCreatedBy()), name.apply(c.getCancelledBy()))).toList();
    }

    private PriceChangeResponse response(PlanPriceChange change) {
        return responses(List.of(change)).get(0);
    }

    private Optional<Subscription> subscriptionWithMember(Long id) {
        return Optional.ofNullable(subscriptions.loadWithMemberAndPlan(id));
    }

    private MembershipPlan loadPlan(Long planId) {
        return plans.findById(planId).orElseThrow(() -> new ResourceNotFoundException("Plan", planId));
    }

    private void evictPlanCaches(Long planId) {
        Optional.ofNullable(cacheManager.getCache(CacheConfig.PLANS)).ifPresent(c -> c.evict(planId));
        Optional.ofNullable(cacheManager.getCache(CacheConfig.ACTIVE_PLANS)).ifPresent(Cache::clear);
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
