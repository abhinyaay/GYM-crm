package com.gymops.subscription;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.api.PageResponse;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.common.util.Money;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.payment.Invoice;
import com.gymops.payment.InvoiceLineType;
import com.gymops.payment.InvoiceRepository;
import com.gymops.payment.InvoiceService;
import com.gymops.payment.PaidInvoiceHandler;
import com.gymops.payment.Payment;
import com.gymops.payment.PaymentRepository;
import com.gymops.payment.PricingCalculator;
import com.gymops.payment.RefundService;
import com.gymops.payment.dto.RefundResponse;
import com.gymops.plan.MembershipPlan;
import com.gymops.plan.PlanService;
import com.gymops.pricing.PriceBasis;
import com.gymops.pricing.PriceResolver;
import com.gymops.pricing.PricingService;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.subscription.dto.CancellationResponse;
import com.gymops.subscription.dto.SubscriptionRequests;
import com.gymops.subscription.dto.SubscriptionResponse;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the subscription lifecycle. Concurrency strategy:
 * <ol>
 *   <li>Every mutating operation first takes a row lock on the member ({@code SELECT ... FOR UPDATE}),
 *       serialising all membership changes for one member (renew vs upgrade vs freeze vs pay).</li>
 *   <li>Subscriptions carry an optimistic {@code @Version} as a second line of defence.</li>
 *   <li>The database exclusion constraint makes overlapping committed subscriptions impossible even
 *       if application logic were bypassed.</li>
 * </ol>
 * Each public method is one transaction: subscription, invoice, discount redemption, refund, audit
 * entry and outbox notifications commit or roll back together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService implements PaidInvoiceHandler {

    private static final EnumSet<SubscriptionStatus> OPEN_SUCCESSOR = EnumSet.of(
            SubscriptionStatus.PENDING_PAYMENT, SubscriptionStatus.SCHEDULED, SubscriptionStatus.ACTIVE,
            SubscriptionStatus.FROZEN, SubscriptionStatus.GRACE);
    private static final EnumSet<SubscriptionStatus> QUEUED = EnumSet.of(
            SubscriptionStatus.PENDING_PAYMENT, SubscriptionStatus.SCHEDULED);
    private static final int MAX_ADVANCE_START_DAYS = 90;

    private final SubscriptionRepository repository;
    private final MemberRepository memberRepository;
    private final PlanService planService;
    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final PricingService pricing;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ================================================================== purchase

    @Transactional
    public SubscriptionResponse subscribe(SubscriptionRequests.Create request) {
        Member member = lockMember(request.memberId());
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        requireActiveMember(member);
        MembershipPlan plan = sellablePlan(request.planId());
        LocalDate today = today();
        PricingService.SaleQuote quote = pricing.quoteSale(plan, today, request.customPrice(), request.customPriceReason(),
                Boolean.TRUE.equals(request.keepCustomPriceOnRenewal()), request.discountCode());
        boolean free = quote.listPrice().signum() == 0 && quote.joiningFee().signum() == 0;
        if (free && com.gymops.security.CurrentUser.find().map(u -> u.isMemberOnly()).orElse(false)) {
            // free plans (trials) are granted by staff, e.g. from a lead; members can request one via the enquiry form
            throw new BusinessRuleException("Free plans are granted by our team; book a free trial from the enquiry page");
        }

        LocalDate start = request.startDate() == null ? today : request.startDate();
        if (start.isBefore(today) || start.isAfter(today.plusDays(MAX_ADVANCE_START_DAYS))) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "startDate must be between today and " + MAX_ADVANCE_START_DAYS + " days ahead");
        }
        ensureNoPending(member);
        if (repository.existsCommittedOverlap(member.getId(), start, plan.endDateFor(start))) {
            throw new ConflictException(ErrorCode.SUBSCRIPTION_OVERLAP,
                    "Member already has a membership covering this period; renew or change plan instead");
        }

        List<PricingCalculator.Line> lines = new ArrayList<>(quote.planLines());
        if (Money.isPositive(quote.joiningFee()) && !repository.hasEverActivated(member.getId())) {
            lines.add(new PricingCalculator.Line(InvoiceLineType.JOINING_FEE, "One-time joining fee", quote.joiningFee()));
        }
        Subscription sub = Subscription.pending(member, plan, member.getHomeBranch(), start, ChangeType.NEW, null,
                quote.price());
        if (quote.negotiated()) {
            sub.negotiate(quote.listPrice(), quote.reason(), quote.keepOnRenewal());
        } else {
            sub.priceAs(PriceBasis.LIST, quote.listPrice());
        }
        SubscriptionResponse created = createWithInvoice(sub, lines, request.discountCode(), "CREATED");
        if (quote.negotiated()) {
            audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "PRICE_OVERRIDDEN").member(member.getId())
                    .branch(sub.getBranch().getId()).with("plan", plan.getCode()).with("listPrice", quote.listPrice())
                    .with("price", quote.price()).with("discountPct", quote.discountPct()).with("reason", quote.reason())
                    .with("keepOnRenewal", quote.keepOnRenewal()));
        }
        return created;
    }

    /**
     * Renewal starts the day after the current term ends, keeping membership continuous (a renewal
     * paid during the grace period is back-dated to close the gap). Renewing an EXPIRED subscription
     * starts today.
     */
    @Transactional
    public SubscriptionResponse renew(Long subscriptionId, SubscriptionRequests.Renew request) {
        Member member = lockMemberOfSubscription(subscriptionId);
        Subscription source = load(subscriptionId);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        PreparedRenewal prepared = prepareRenewal(member, source, request.planId(), false);
        Subscription sub = prepared.subscription();
        List<PricingCalculator.Line> lines = pricing.renewalLines(sub.getPlan(), prepared.price(),
                sub.getPlan().getName() + " membership renewal");
        return createWithInvoice(sub, lines, request.discountCode(), "RENEWAL_CREATED");
    }

    /** Renewal raised by autopay (system actor, no caller). The caller holds the member lock. */
    public record AutoRenewal(Subscription subscription, Invoice invoice) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AutoRenewal renewForAutopay(Subscription source) {
        PreparedRenewal prepared = prepareRenewal(source.getMember(), source, null, true);
        Subscription sub = prepared.subscription();
        List<PricingCalculator.Line> lines = pricing.renewalLines(sub.getPlan(), prepared.price(),
                sub.getPlan().getName() + " membership renewal (autopay)");
        repository.saveAndFlush(sub);
        Invoice invoice = invoiceService.issueForSubscription(sub, lines, null);
        audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "RENEWAL_CREATED").member(sub.getMember().getId())
                .branch(sub.getBranch().getId()).with("plan", sub.getPlan().getCode()).with("autopay", true)
                .with("priceBasis", sub.getPriceBasis()).with("price", sub.getPrice())
                .with("startDate", sub.getStartDate()).with("invoiceNumber", invoice.getInvoiceNumber())
                .with("total", invoice.getTotalAmount()));
        events.publishEvent(new DomainEvents.MemberChanged(sub.getMember().getId()));
        return new AutoRenewal(sub, invoice);
    }

    /**
     * Renewal starts the day after the current term ends, keeping membership continuous (a renewal
     * paid during the grace period is back-dated to close the gap). Renewing an EXPIRED subscription
     * starts today.
     */
    private record PreparedRenewal(Subscription subscription, PriceResolver.Resolution price) {
    }

    private PreparedRenewal prepareRenewal(Member member, Subscription source, Long planId, boolean autopay) {
        requireActiveMember(member);
        if (!EnumSet.of(SubscriptionStatus.SCHEDULED, SubscriptionStatus.ACTIVE, SubscriptionStatus.FROZEN,
                SubscriptionStatus.GRACE, SubscriptionStatus.EXPIRED).contains(source.getStatus())) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot renew a subscription in status " + source.getStatus());
        }
        if (repository.existsSuccessorWithStatus(source.getId(), OPEN_SUCCESSOR)) {
            throw new ConflictException("This subscription has already been renewed or changed");
        }
        ensureNoPending(member);
        MembershipPlan plan = sellablePlan(planId != null ? planId : source.getPlan().getId());
        LocalDate start = source.getStatus() == SubscriptionStatus.EXPIRED ? today() : source.getEndDate().plusDays(1);
        if (repository.existsCommittedOverlap(member.getId(), start, plan.endDateFor(start))) {
            throw new ConflictException(ErrorCode.SUBSCRIPTION_OVERLAP,
                    "Member already has a membership covering the renewal period");
        }
        // price applicable on the invoice date: list price, negotiated / protected price, autopay notice rule
        PriceResolver.Resolution price = pricing.resolveRenewal(source, plan, today(), start, autopay);
        Subscription sub = Subscription.pending(member, plan, member.getHomeBranch(), start, ChangeType.RENEWAL, source,
                price.amount());
        pricing.applyRenewalPricing(sub, source, price);
        return new PreparedRenewal(sub, price);
    }

    /**
     * Upgrade (new plan has a higher contract value than the current term): effective immediately
     * once paid; the unused value of the current term is credited on the invoice and the current
     * subscription is SUPERSEDED.
     * Downgrade (lower or equal contract value): starts when the current term ends; no credit.
     */
    @Transactional
    public SubscriptionResponse changePlan(Long subscriptionId, SubscriptionRequests.ChangePlan request) {
        Member member = lockMemberOfSubscription(subscriptionId);
        Subscription source = load(subscriptionId);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        requireActiveMember(member);
        if (source.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only an ACTIVE subscription can change plan (current: " + source.getStatus() + ")");
        }
        MembershipPlan newPlan = sellablePlan(request.newPlanId());
        if (newPlan.getId().equals(source.getPlan().getId())) {
            throw new BusinessRuleException("Subscription is already on plan " + newPlan.getCode());
        }
        if (repository.existsSuccessorWithStatus(source.getId(), OPEN_SUCCESSOR)) {
            throw new ConflictException("A renewal or plan change is already queued for this subscription; cancel it first");
        }
        ensureNoPending(member);

        LocalDate today = today();
        BigDecimal newPrice = pricing.listPrice(newPlan, today);
        boolean upgrade = newPrice.compareTo(source.getPrice()) > 0;

        List<PricingCalculator.Line> lines = new ArrayList<>();
        lines.add(new PricingCalculator.Line(InvoiceLineType.PLAN_FEE, newPlan.getName() + " membership", newPrice));
        Subscription sub;
        if (upgrade) {
            BigDecimal credit = CancellationRefundCalculator.upgradeCredit(source, today);
            if (Money.isPositive(credit)) {
                lines.add(new PricingCalculator.Line(InvoiceLineType.PRORATION_CREDIT,
                        "Credit for unused " + source.getPlan().getName() + " days", credit.negate()));
            }
            sub = Subscription.pending(member, newPlan, member.getHomeBranch(), today, ChangeType.UPGRADE, source, newPrice);
        } else {
            sub = Subscription.pending(member, newPlan, member.getHomeBranch(), source.getEndDate().plusDays(1),
                    ChangeType.DOWNGRADE, source, newPrice);
        }
        return createWithInvoice(sub, lines, request.discountCode(), upgrade ? "UPGRADE_CREATED" : "DOWNGRADE_CREATED");
    }

    /** Invoked by the payment transaction once the subscription's invoice is settled. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onInvoicePaid(Invoice invoice) {
        Subscription sub = invoice.getSubscription();
        if (sub == null) {
            return;
        }
        if (sub.getStatus() != SubscriptionStatus.PENDING_PAYMENT) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Subscription " + sub.getId() + " is " + sub.getStatus() + " and cannot be activated");
        }
        LocalDate today = today();
        Subscription previous = sub.getPreviousSubscription();
        if (previous != null) {
            sub.inheritAutopayFrom(previous);   // recurring billing carries over to the next term / new plan
        }
        if (sub.getChangeType() == ChangeType.UPGRADE && previous != null
                && EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.FROZEN).contains(previous.getStatus())) {
            previous.supersede(today);
            repository.saveAndFlush(previous);   // must hit the DB before the new term becomes committed
            audit.record(AuditEvent.of("SUBSCRIPTION", previous.getId(), "SUPERSEDED")
                    .member(sub.getMember().getId()).with("bySubscriptionId", sub.getId()));
        }
        sub.activate(today, Instant.now(clock));
        repository.saveAndFlush(sub);            // exclusion constraint is checked here
        audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "ACTIVATED").member(sub.getMember().getId())
                .branch(sub.getBranch().getId()).with("status", sub.getStatus())
                .with("startDate", sub.getStartDate()).with("endDate", sub.getEndDate()));
        events.publishEvent(new DomainEvents.SubscriptionActivated(sub.getId(), sub.getMember().getId()));
        events.publishEvent(new DomainEvents.MemberChanged(sub.getMember().getId()));
    }

    // ================================================================== freeze / resume

    @Transactional
    public SubscriptionResponse freeze(Long subscriptionId, SubscriptionRequests.Freeze request) {
        Member member = lockMemberOfSubscription(subscriptionId);
        Subscription sub = load(subscriptionId);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        LocalDate today = today();
        sub.checkCanFreeze(today, request.days());

        // queued renewals move back by the freeze length; latest first so no intermediate overlap
        List<Subscription> chain = queuedSuccessors(sub);
        for (int i = chain.size() - 1; i >= 0; i--) {
            chain.get(i).shiftDates(request.days());
            repository.saveAndFlush(chain.get(i));
        }
        sub.freeze(today, request.days());
        repository.saveAndFlush(sub);
        audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "FROZEN").member(member.getId())
                .with("days", request.days()).with("frozenUntil", sub.getFrozenUntil())
                .with("newEndDate", sub.getEndDate()).with("reason", request.reason()));
        events.publishEvent(new DomainEvents.SubscriptionFrozen(sub.getId(), member.getId(), sub.getFrozenUntil()));
        events.publishEvent(new DomainEvents.MemberChanged(member.getId()));
        return response(sub);
    }

    @Transactional
    public SubscriptionResponse resume(Long subscriptionId) {
        Member member = lockMemberOfSubscription(subscriptionId);
        Subscription sub = load(subscriptionId);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        int unused = sub.resume(today());
        repository.saveAndFlush(sub);
        for (Subscription next : queuedSuccessors(sub)) {   // earliest first after shrinking
            next.shiftDates(-unused);
            repository.saveAndFlush(next);
        }
        audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "RESUMED").member(member.getId())
                .with("unusedFreezeDaysReturned", unused).with("newEndDate", sub.getEndDate()));
        events.publishEvent(new DomainEvents.SubscriptionResumed(sub.getId(), member.getId()));
        events.publishEvent(new DomainEvents.MemberChanged(member.getId()));
        return response(sub);
    }

    // ================================================================== cancellation

    @Transactional
    public CancellationResponse cancel(Long subscriptionId, SubscriptionRequests.Cancel request) {
        Member member = lockMemberOfSubscription(subscriptionId);
        Subscription sub = load(subscriptionId);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        LocalDate today = today();

        BigDecimal refundAmount = Money.ZERO;
        Payment payment = null;
        if (sub.getStatus() == SubscriptionStatus.PENDING_PAYMENT) {
            invoiceRepository.findLiveBySubscriptionId(sub.getId())
                    .ifPresent(invoice -> invoiceService.voidInvoice(invoice, "Subscription cancelled before payment"));
        } else {
            var invoice = invoiceRepository.findLiveBySubscriptionId(sub.getId());
            payment = invoice.flatMap(i -> paymentRepository.findSettledByInvoiceId(i.getId())).orElse(null);
            boolean refundRequested = request.refund() == null || request.refund();
            if (payment != null && refundRequested) {
                // computed BEFORE cancel(), which truncates the end date
                refundAmount = CancellationRefundCalculator.refundFor(sub, payment.getAmount(),
                        payment.refundableAmount(), today, clock.getZone());
            }
        }
        sub.cancel(today, request.reason(), Instant.now(clock));
        repository.saveAndFlush(sub);

        RefundResponse refund = null;
        if (payment != null && Money.isPositive(refundAmount)) {
            refund = refundService.refundForCancellation(payment.getId(), refundAmount,
                    "Cancellation of subscription " + sub.getId() + ": " + request.reason(),
                    "cancel-subscription-" + sub.getId());
        }
        audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "CANCELLED").member(member.getId())
                .branch(sub.getBranch().getId()).with("reason", request.reason())
                .with("policy", sub.getPlan().getCancellationPolicy()).with("refundAmount", refundAmount));
        events.publishEvent(new DomainEvents.SubscriptionCancelled(sub.getId(), member.getId(), refundAmount));
        events.publishEvent(new DomainEvents.MemberChanged(member.getId()));
        return new CancellationResponse(response(sub), refundAmount, refund);
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public SubscriptionResponse get(Long id) {
        Subscription sub = load(id);
        access.assertMemberAccess(sub.getMember().getId(), sub.getMember().getHomeBranch().getId());
        return response(sub);
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionResponse> history(Long memberId, Pageable pageable) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        return PageResponse.of(repository.findByMemberIdOrderByStartDateDesc(memberId, pageable), this::response);
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionResponse> search(Long branchId, SubscriptionStatus status, Long planId,
                                                     LocalDate endingFrom, LocalDate endingTo, Pageable pageable) {
        BranchScope scope = access.scope(branchId);
        Specification<Subscription> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (!scope.all()) {
                p.add(root.get("branch").get("id").in(scope.ids()));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (planId != null) {
                p.add(cb.equal(root.get("plan").get("id"), planId));
            }
            if (endingFrom != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("endDate"), endingFrom));
            }
            if (endingTo != null) {
                p.add(cb.lessThanOrEqualTo(root.get("endDate"), endingTo));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return PageResponse.of(repository.findAll(spec, pageable), this::response);
    }

    // ================================================================== lifecycle (one item per transaction)

    @Transactional
    public boolean startIfDue(Long id) {
        Subscription sub = lockAndLoad(id);
        if (sub.startIfDue(today())) {
            audit.record(AuditEvent.of("SUBSCRIPTION", id, "STARTED").member(sub.getMember().getId()));
            events.publishEvent(new DomainEvents.MemberChanged(sub.getMember().getId()));
            return true;
        }
        return false;
    }

    @Transactional
    public boolean evaluateExpiry(Long id) {
        Subscription sub = lockAndLoad(id);
        SubscriptionStatus before = sub.getStatus();
        boolean hasSuccessor = repository.existsSuccessorWithStatus(id,
                EnumSet.of(SubscriptionStatus.SCHEDULED, SubscriptionStatus.ACTIVE, SubscriptionStatus.FROZEN));
        if (!sub.evaluateExpiry(today(), hasSuccessor)) {
            return false;
        }
        audit.record(AuditEvent.of("SUBSCRIPTION", id, sub.getStatus() == SubscriptionStatus.GRACE ? "GRACE_STARTED" : "EXPIRED")
                .member(sub.getMember().getId()).with("from", before).with("renewed", hasSuccessor));
        if (sub.getStatus() == SubscriptionStatus.EXPIRED && !hasSuccessor) {
            events.publishEvent(new DomainEvents.SubscriptionExpired(id, sub.getMember().getId()));
        }
        events.publishEvent(new DomainEvents.MemberChanged(sub.getMember().getId()));
        return true;
    }

    @Transactional
    public boolean resumeIfFreezeEnded(Long id) {
        Subscription sub = lockAndLoad(id);
        if (sub.resumeIfFreezeEnded(today())) {
            audit.record(AuditEvent.of("SUBSCRIPTION", id, "AUTO_RESUMED").member(sub.getMember().getId()));
            events.publishEvent(new DomainEvents.SubscriptionResumed(id, sub.getMember().getId()));
            events.publishEvent(new DomainEvents.MemberChanged(sub.getMember().getId()));
            return true;
        }
        return false;
    }

    /** Unpaid subscriptions are released after the configured period so they stop blocking new purchases. */
    @Transactional
    public boolean cancelStalePending(Long id) {
        Subscription sub = lockAndLoad(id);
        if (sub.getStatus() != SubscriptionStatus.PENDING_PAYMENT) {
            return false;
        }
        invoiceRepository.findLiveBySubscriptionId(id)
                .ifPresent(invoice -> invoiceService.voidInvoice(invoice, "Unpaid - expired automatically"));
        sub.cancel(today(), "Not paid within the payment window", Instant.now(clock));
        audit.record(AuditEvent.of("SUBSCRIPTION", id, "PENDING_EXPIRED").member(sub.getMember().getId()));
        events.publishEvent(new DomainEvents.MemberChanged(sub.getMember().getId()));
        return true;
    }

    @Transactional
    public int queueRenewalReminders(LocalDate endDate, int daysLeft) {
        List<Subscription> ending = repository.findEndingOnWithoutRenewal(endDate);
        ending.forEach(s -> events.publishEvent(new DomainEvents.RenewalReminderDue(s.getId(), s.getMember().getId(),
                s.getEndDate(), daysLeft)));
        return ending.size();
    }

    // ================================================================== helpers

    /** A plan change that increases the committed contract value is an upgrade (e.g. Monthly -> Annual). */
    static boolean isUpgrade(Subscription current, MembershipPlan newPlan) {
        return newPlan.getPrice().compareTo(current.getPrice()) > 0;
    }

    private SubscriptionResponse createWithInvoice(Subscription sub, List<PricingCalculator.Line> lines,
                                                   String discountCode, String auditAction) {
        repository.saveAndFlush(sub);
        Invoice invoice = invoiceService.issueForSubscription(sub, lines, discountCode);
        audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), auditAction).member(sub.getMember().getId())
                .branch(sub.getBranch().getId()).with("plan", sub.getPlan().getCode())
                .with("changeType", sub.getChangeType()).with("startDate", sub.getStartDate())
                .with("invoiceNumber", invoice.getInvoiceNumber()).with("total", invoice.getTotalAmount()));
        if (invoice.getTotalAmount().signum() == 0) {
            invoiceService.settleZeroAmount(invoice);
            onInvoicePaid(invoice);
        }
        events.publishEvent(new DomainEvents.MemberChanged(sub.getMember().getId()));
        return SubscriptionResponse.from(sub, invoice.getId());
    }

    private List<Subscription> queuedSuccessors(Subscription sub) {
        List<Subscription> chain = new ArrayList<>();
        Subscription current = sub;
        while (true) {
            List<Subscription> next = repository.findSuccessors(current.getId(), QUEUED);
            if (next.isEmpty()) {
                return chain;
            }
            current = next.get(0);
            chain.add(current);
        }
    }

    private SubscriptionResponse response(Subscription sub) {
        Long invoiceId = invoiceRepository.findLiveBySubscriptionId(sub.getId()).map(Invoice::getId).orElse(null);
        return SubscriptionResponse.from(sub, invoiceId);
    }

    private Member lockMember(Long memberId) {
        return memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
    }

    private Member lockMemberOfSubscription(Long subscriptionId) {
        Long memberId = repository.findMemberIdById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", subscriptionId));
        return lockMember(memberId);
    }

    private Subscription lockAndLoad(Long subscriptionId) {
        lockMemberOfSubscription(subscriptionId);
        return load(subscriptionId);
    }

    private Subscription load(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Subscription", id));
    }

    private MembershipPlan sellablePlan(Long planId) {
        MembershipPlan plan = planService.load(planId);
        if (!plan.isActive()) {
            throw new BusinessRuleException("Plan " + plan.getCode() + " is not available for sale");
        }
        return plan;
    }

    private void ensureNoPending(Member member) {
        repository.findFirstByMemberIdAndStatus(member.getId(), SubscriptionStatus.PENDING_PAYMENT).ifPresent(p -> {
            throw new ConflictException("Member already has subscription " + p.getId()
                    + " awaiting payment; pay or cancel it first");
        });
    }

    private static void requireActiveMember(Member member) {
        if (!member.isActive()) {
            throw new BusinessRuleException("Member account is " + member.getStatus());
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
