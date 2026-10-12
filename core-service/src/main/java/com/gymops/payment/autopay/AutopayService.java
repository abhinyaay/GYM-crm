package com.gymops.payment.autopay;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.common.util.Hashing;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.payment.Invoice;
import com.gymops.payment.InvoiceRepository;
import com.gymops.payment.InvoiceStatus;
import com.gymops.payment.Payment;
import com.gymops.payment.PaymentProperties;
import com.gymops.payment.PaymentRecorder;
import com.gymops.payment.autopay.AutopayDtos.AttemptResponse;
import com.gymops.payment.autopay.AutopayDtos.AutopayRequest;
import com.gymops.payment.autopay.AutopayDtos.UpcomingRenewal;
import com.gymops.payment.gateway.PaymentGateway;
import com.gymops.payment.gateway.PaymentGateway.PaymentState;
import com.gymops.payment.gateway.PaymentGateway.ProviderPayment;
import com.gymops.payment.gateway.PaymentGateways;
import com.gymops.payment.method.MandateStatus;
import com.gymops.payment.method.PaymentMethodService;
import com.gymops.payment.method.SavedMethodRevoked;
import com.gymops.payment.method.SavedPaymentMethod;
import com.gymops.payment.method.SavedPaymentMethodRepository;
import com.gymops.pricing.PricingService;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.subscription.Subscription;
import com.gymops.subscription.SubscriptionRepository;
import com.gymops.subscription.SubscriptionService;
import com.gymops.subscription.SubscriptionStatus;
import com.gymops.subscription.dto.SubscriptionResponse;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring billing. For each subscription with autopay:
 * <ol>
 *   <li>{@code leadDays} before the term ends the renewal (subscription + invoice) is raised and the
 *       first debit is scheduled {@code preDebitNotice} later; the provider order carrying the
 *       pre-debit notification is created now and the member is told amount, method and date.</li>
 *   <li>At the scheduled time the saved token is debited (merchant-initiated). Success settles the
 *       invoice through the normal payment path, which activates the renewal; the new term inherits
 *       autopay, so billing continues term after term.</li>
 *   <li>A failed debit is retried after each of {@code retryDelaysDays}; after the last failure autopay
 *       is switched off and the member is asked to pay the open invoice manually (dunning).</li>
 *   <li>Debits the provider reports asynchronously stay PROCESSING until the webhook settles them.</li>
 * </ol>
 * Every step runs in its own transaction under the member row lock and is safe to re-run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutopayService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int MAX_ATTEMPTS = 10;

    private final AutopayAttemptRepository attempts;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionService subscriptionService;
    private final MemberRepository memberRepository;
    private final InvoiceRepository invoiceRepository;
    private final SavedPaymentMethodRepository methodRepository;
    private final PaymentMethodService paymentMethods;
    private final PaymentGateways gateways;
    private final PaymentRecorder recorder;
    private final NotificationService notifications;
    private final AuditService audit;
    private final AccessPolicy access;
    private final PaymentProperties props;
    private final PricingService pricing;
    private final Clock clock;

    // ================================================================== member / staff settings

    @Transactional
    public SubscriptionResponse setAutopay(Long subscriptionId, AutopayRequest request) {
        Member member = lockMemberOfSubscription(subscriptionId);
        Subscription sub = subscriptions.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", subscriptionId));
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        if (Boolean.TRUE.equals(request.enabled())) {
            if (request.paymentMethodId() == null) {
                throw new BusinessRuleException("Choose the saved card or UPI handle to charge");
            }
            SavedPaymentMethod method = paymentMethods.loadForMember(member.getId(), request.paymentMethodId());
            requireAutopayCapable(sub, method);
            sub.enableAutopay(method.getId());
            audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "AUTOPAY_ENABLED").member(member.getId())
                    .with("paymentMethod", method.getLabel()));
        } else {
            sub.disableAutopay();
            cancelOpenAttempts(sub.getId(), "Autopay switched off");
            audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "AUTOPAY_DISABLED").member(member.getId()));
        }
        subscriptions.saveAndFlush(sub);
        return SubscriptionResponse.from(sub,
                invoiceRepository.findLiveBySubscriptionId(sub.getId()).map(Invoice::getId).orElse(null));
    }

    /** Autopay requested during checkout ("save card and renew automatically"). Caller holds the member lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enableFromCheckout(Subscription sub, SavedPaymentMethod method) {
        if (sub.isAutoRenew() && method.getId().equals(sub.getAutopayMethodId())) {
            return;
        }
        requireAutopayCapable(sub, method);
        sub.enableAutopay(method.getId());
        audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "AUTOPAY_ENABLED").member(sub.getMember().getId())
                .with("paymentMethod", method.getLabel()).with("via", "checkout"));
    }

    private void requireAutopayCapable(Subscription sub, SavedPaymentMethod method) {
        if (method.getMandateStatus() != MandateStatus.ACTIVE) {
            throw new BusinessRuleException(method.getLabel() + " is not authorised for automatic debits; pay once "
                    + "with \"renew automatically\" selected to set up the mandate");
        }
        LocalDate today = LocalDate.now(clock);
        if (!method.canAutoDebit(sub.getPrice(), today)) {
            throw new BusinessRuleException(method.getLabel() + " cannot be auto-debited for ₹" + sub.getPrice()
                    + " (expired or above the mandate limit)");
        }
        if (!sub.getPlan().isActive() || sub.getPrice().signum() <= 0) {
            throw new BusinessRuleException("Plan " + sub.getPlan().getCode() + " cannot be renewed automatically");
        }
        if (!EnumSet.of(SubscriptionStatus.PENDING_PAYMENT, SubscriptionStatus.SCHEDULED, SubscriptionStatus.ACTIVE,
                SubscriptionStatus.FROZEN).contains(sub.getStatus())) {
            throw new ConflictException("Autopay cannot be enabled on a " + sub.getStatus() + " subscription");
        }
    }

    /** A saved method was removed or its mandate cancelled at the bank: autopay relying on it stops. */
    @EventListener
    public void onMethodRevoked(SavedMethodRevoked event) {
        for (Subscription sub : subscriptions.findByAutopayMethodIdAndAutoRenewTrue(event.paymentMethodId())) {
            sub.disableAutopay();
            cancelOpenAttempts(sub.getId(), event.reason());
            notifications.notifyMember(sub.getMember(), NotificationType.AUTOPAY_DISABLED, "Autopay switched off",
                    "Automatic renewal of your " + sub.getPlan().getName() + " membership is off: " + event.reason()
                            + ". Renew manually before " + DATE.format(sub.getEndDate()) + " to keep access.",
                    "autopay-off:" + sub.getId() + ":" + event.paymentMethodId());
            audit.record(AuditEvent.of("SUBSCRIPTION", sub.getId(), "AUTOPAY_DISABLED").member(event.memberId())
                    .with("reason", event.reason()));
        }
    }

    // ================================================================== scheduling

    @Transactional(readOnly = true)
    public List<Long> dueForRenewal() {
        return subscriptions.findAutopayDue(LocalDate.now(clock).plusDays(props.autopay().leadDays()));
    }

    /** Raises the renewal for one autopay subscription and schedules its first debit. */
    @Transactional
    public boolean scheduleRenewal(Long subscriptionId) {
        Member member = lockMemberOfSubscription(subscriptionId);
        Subscription source = subscriptions.findById(subscriptionId).orElseThrow();
        LocalDate today = LocalDate.now(clock);
        if (!source.isAutoRenew() || source.getStatus() != SubscriptionStatus.ACTIVE
                || source.getEndDate().isAfter(today.plusDays(props.autopay().leadDays()))) {
            return false;
        }
        SavedPaymentMethod method = methodRepository.findById(source.getAutopayMethodId()).orElseThrow();
        if (!method.canAutoDebit(pricing.nextAutopayRenewal(source).amount(), today)) {
            switchOff(source, member, method.getLabel() + " can no longer be auto-debited (expired, removed or "
                    + "mandate inactive)");
            return false;
        }
        SubscriptionService.AutoRenewal renewal;
        try {
            renewal = subscriptionService.renewForAutopay(source);
        } catch (ConflictException e) {
            // e.g. a manual renewal / plan change is already pending: leave it to the member
            log.info("Autopay renewal for subscription {} skipped: {}", subscriptionId, e.getMessage());
            return false;
        }
        Instant debitAt = Instant.now(clock).plus(props.autopay().preDebitNotice());
        AutopayAttempt attempt = scheduleAttempt(source.getId(), renewal.subscription().getId(), renewal.invoice(), method,
                member, 1, debitAt);
        notifications.notifyMember(member, NotificationType.AUTOPAY_SCHEDULED, "Upcoming autopay debit",
                "₹%s will be debited from %s on %s to renew your %s membership (invoice %s). Turn off autopay or "
                        .formatted(renewal.invoice().getTotalAmount(), method.getLabel(), date(debitAt),
                                source.getPlan().getName(), renewal.invoice().getInvoiceNumber())
                        + "pay the invoice another way before then if you prefer.",
                "autopay-scheduled:" + attempt.getId());
        return true;
    }

    private AutopayAttempt scheduleAttempt(Long subscriptionId, Long renewalId, Invoice invoice, SavedPaymentMethod method,
                                           Member member, int attemptNo, Instant debitAt) {
        Instant now = Instant.now(clock);
        AutopayAttempt attempt = attempts.saveAndFlush(new AutopayAttempt(subscriptionId, renewalId, invoice, method,
                attemptNo, debitAt, now));
        PaymentGateway gateway = gateways.get(method.getProvider());
        PaymentGateway.Order order = gateway.createRecurringOrder(tokenCharge(attempt, invoice, member));
        attempt.orderCreated(order.orderId(), now);
        audit.record(AuditEvent.of("AUTOPAY", attempt.getId(), "SCHEDULED").member(member.getId())
                .branch(invoice.getBranch().getId()).with("invoiceNumber", invoice.getInvoiceNumber())
                .with("attemptNo", attemptNo).with("debitAt", debitAt).with("paymentMethod", method.getLabel()));
        return attempt;
    }

    // ================================================================== debits

    @Transactional(readOnly = true)
    public List<Long> dueDebits() {
        return attempts.findDueIds(Instant.now(clock));
    }

    @Transactional
    public AutopayStatus executeAttempt(Long attemptId) {
        Member member = lockMemberOfAttempt(attemptId);
        AutopayAttempt attempt = attempts.findById(attemptId).orElseThrow();
        if (attempt.getStatus() != AutopayStatus.SCHEDULED) {
            return attempt.getStatus();
        }
        Invoice invoice = invoiceRepository.findByIdForUpdate(attempt.getInvoice().getId()).orElseThrow();
        Instant now = Instant.now(clock);
        if (!invoice.isPayable()) {
            attempt.close(invoice.getStatus() == InvoiceStatus.VOID ? AutopayStatus.CANCELLED : AutopayStatus.SKIPPED,
                    invoice.getStatus() == InvoiceStatus.VOID ? "Renewal was cancelled" : "Invoice already paid", now);
            return attempt.getStatus();
        }
        Subscription source = subscriptions.findById(attempt.getSubscriptionId()).orElseThrow();
        if (!source.isAutoRenew()) {
            attempt.close(AutopayStatus.CANCELLED, "Autopay switched off", now);
            return attempt.getStatus();
        }
        SavedPaymentMethod method = attempt.getPaymentMethod();
        if (!method.canAutoDebit(invoice.getTotalAmount(), LocalDate.ofInstant(now, clock.getZone()))) {
            attempt.close(AutopayStatus.FAILED, method.getLabel() + " can no longer be auto-debited", now);
            switchOff(source, member, method.getLabel() + " can no longer be auto-debited");
            return attempt.getStatus();
        }
        return debit(attempt, invoice, member, source, now);
    }

    private AutopayStatus debit(AutopayAttempt attempt, Invoice invoice, Member member, Subscription source, Instant now) {
        attempt.processing(now);
        SavedPaymentMethod method = attempt.getPaymentMethod();
        PaymentGateway gateway = gateways.get(method.getProvider());
        PaymentGateway.TokenCharge charge = tokenCharge(attempt, invoice, member);
        if (attempt.getProviderOrderId() == null) {
            attempt.orderCreated(gateway.createRecurringOrder(charge).orderId(), attempt.getPreDebitNotifiedAt());
        }
        PaymentGateway.ChargeResult result = gateway.chargeToken(charge, attempt.getProviderOrderId());
        if (result.state() == PaymentState.CAPTURED) {
            onCaptured(attempt, invoice, member, result.paymentId());
        } else if (result.state() == PaymentState.FAILED) {
            onFailed(attempt, invoice, member, source, result.paymentId(), result.failureReason());
        } else {
            log.info("Autopay attempt {} is processing at the provider; awaiting webhook", attempt.getId());
        }
        return attempt.getStatus();
    }

    /**
     * Outcome of an asynchronous debit reported by the provider's webhook. If the member paid the
     * invoice another way in the meantime, the captured debit is refunded automatically.
     */
    @Transactional
    public void settleFromProvider(Long attemptId, ProviderPayment payment) {
        Member member = lockMemberOfAttempt(attemptId);
        AutopayAttempt attempt = attempts.findById(attemptId).orElseThrow();
        if (attempt.getStatus() != AutopayStatus.PROCESSING || payment.state() == PaymentState.PENDING) {
            return;
        }
        Invoice invoice = invoiceRepository.findByIdForUpdate(attempt.getInvoice().getId()).orElseThrow();
        if (payment.state() == PaymentState.CAPTURED) {
            if (!invoice.isPayable()) {
                gateways.get(attempt.getPaymentMethod().getProvider()).refund(payment.paymentId(), payment.amountMinor(),
                        "autopay-dup-" + attempt.getId());
                attempt.close(AutopayStatus.SKIPPED, "Invoice already paid; the debit was refunded automatically",
                        Instant.now(clock));
                audit.record(AuditEvent.of("AUTOPAY", attempt.getId(), "AUTO_REFUNDED").member(member.getId())
                        .with("providerPaymentId", payment.paymentId()));
                return;
            }
            onCaptured(attempt, invoice, member, payment.paymentId());
        } else {
            Subscription source = subscriptions.findById(attempt.getSubscriptionId()).orElseThrow();
            onFailed(attempt, invoice, member, source, payment.paymentId(), payment.failureReason());
        }
    }

    private void onCaptured(AutopayAttempt attempt, Invoice invoice, Member member, String providerPaymentId) {
        SavedPaymentMethod method = attempt.getPaymentMethod();
        Payment payment = recorder.succeeded(invoice, method.getType(), key(attempt), hash(attempt), providerPaymentId,
                source(method), null);
        attempt.succeeded(payment.getId());
        Subscription renewal = invoice.getSubscription();
        notifications.notifyMember(member, NotificationType.AUTOPAY_SUCCEEDED, "Membership renewed",
                "₹%s was debited from %s. Your %s membership now runs until %s."
                        .formatted(invoice.getTotalAmount(), method.getLabel(), renewal.getPlan().getName(),
                                DATE.format(renewal.getEndDate())),
                "autopay-ok:" + attempt.getId());
    }

    private void onFailed(AutopayAttempt attempt, Invoice invoice, Member member, Subscription source,
                          String providerPaymentId, String reason) {
        SavedPaymentMethod method = attempt.getPaymentMethod();
        String why = reason == null ? "declined" : reason;
        Payment payment = recorder.failed(invoice, invoice.getTotalAmount(), method.getType(), key(attempt), hash(attempt),
                providerPaymentId, why, source(method), null);
        attempt.failed(payment.getId(), why);
        List<Integer> delays = props.autopay().retryDelaysDays();
        if (source.isAutoRenew() && attempt.getAttemptNo() <= delays.size()) {
            Instant retryAt = Instant.now(clock).plus(Duration.ofDays(delays.get(attempt.getAttemptNo() - 1)));
            AutopayAttempt next = scheduleAttempt(attempt.getSubscriptionId(), attempt.getRenewalSubscriptionId(), invoice,
                    method, member, attempt.getAttemptNo() + 1, retryAt);
            notifications.notifyMember(member, NotificationType.AUTOPAY_FAILED, "Autopay debit failed",
                    "We couldn't debit ₹%s from %s (%s). We'll try again on %s, or you can pay invoice %s now."
                            .formatted(invoice.getTotalAmount(), method.getLabel(), why, date(retryAt),
                                    invoice.getInvoiceNumber()),
                    "autopay-failed:" + attempt.getId());
            log.info("Autopay attempt {} failed ({}); retry {} scheduled at {}", attempt.getId(), why, next.getId(), retryAt);
        } else if (source.isAutoRenew()) {
            switchOff(source, member, "the last " + attempt.getAttemptNo() + " debits from " + method.getLabel() + " failed");
        }
    }

    private void switchOff(Subscription source, Member member, String reason) {
        source.disableAutopay();
        subscriptions.saveAndFlush(source);
        Long invoiceId = null;
        String invoiceNote = "";
        var open = subscriptions.findSuccessors(source.getId(), EnumSet.of(SubscriptionStatus.PENDING_PAYMENT));
        if (!open.isEmpty()) {
            var invoice = invoiceRepository.findLiveBySubscriptionId(open.get(0).getId());
            if (invoice.isPresent() && invoice.get().isPayable()) {
                invoiceId = invoice.get().getId();
                invoiceNote = " Please pay invoice " + invoice.get().getInvoiceNumber() + " to keep your membership active.";
            }
        }
        notifications.notifyMember(member, NotificationType.AUTOPAY_DISABLED, "Autopay switched off",
                "Automatic renewal of your " + source.getPlan().getName() + " membership was switched off because "
                        + reason + "." + invoiceNote,
                "autopay-off:" + source.getId() + ":" + (invoiceId == null ? "none" : invoiceId));
        audit.record(AuditEvent.of("SUBSCRIPTION", source.getId(), "AUTOPAY_DISABLED").member(member.getId())
                .with("reason", reason));
    }

    /** Staff retry of the latest failed debit, executed immediately. */
    @Transactional
    public AttemptResponse retry(Long attemptId) {
        Member member = lockMemberOfAttempt(attemptId);
        AutopayAttempt failed = attempts.findById(attemptId).orElseThrow();
        access.assertBranchAccess(failed.getInvoice().getBranch().getId());
        if (failed.getStatus() != AutopayStatus.FAILED) {
            throw new ConflictException("Only a failed debit can be retried (status " + failed.getStatus() + ")");
        }
        Invoice invoice = invoiceRepository.findByIdForUpdate(failed.getInvoice().getId()).orElseThrow();
        int latest = attempts.maxAttemptNo(invoice.getId());
        if (latest != failed.getAttemptNo()) {
            throw new ConflictException("A newer debit attempt exists for invoice " + invoice.getInvoiceNumber());
        }
        if (latest >= MAX_ATTEMPTS) {
            throw new BusinessRuleException("Retry limit reached; collect the payment another way");
        }
        if (!invoice.isPayable()) {
            throw new ConflictException("Invoice " + invoice.getInvoiceNumber() + " is " + invoice.getStatus());
        }
        SavedPaymentMethod method = failed.getPaymentMethod();
        if (!method.canAutoDebit(invoice.getTotalAmount(), LocalDate.now(clock))) {
            throw new BusinessRuleException(method.getLabel() + " can no longer be auto-debited");
        }
        Subscription source = subscriptions.findById(failed.getSubscriptionId()).orElseThrow();
        AutopayAttempt retry = scheduleAttempt(failed.getSubscriptionId(), failed.getRenewalSubscriptionId(), invoice,
                method, member, latest + 1, Instant.now(clock));
        debit(retry, invoice, member, source, Instant.now(clock));
        audit.record(AuditEvent.of("AUTOPAY", retry.getId(), "MANUAL_RETRY").member(member.getId())
                .with("previousAttemptId", attemptId).with("status", retry.getStatus()));
        return AttemptResponse.from(retry);
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public PageResponse<AttemptResponse> search(Long memberId, Long subscriptionId, AutopayStatus status, Long branchId,
                                                Pageable pageable) {
        UserPrincipal caller = CurrentUser.get();
        Long effectiveMember = caller.isMemberOnly() ? caller.memberId() : memberId;
        BranchScope scope = caller.isMemberOnly() ? BranchScope.ALL : access.scope(branchId);
        Specification<AutopayAttempt> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (effectiveMember != null) {
                p.add(cb.equal(root.get("invoice").get("member").get("id"), effectiveMember));
            }
            if (!scope.all()) {
                p.add(root.get("invoice").get("branch").get("id").in(scope.ids()));
            }
            if (subscriptionId != null) {
                p.add(cb.or(cb.equal(root.get("subscriptionId"), subscriptionId),
                        cb.equal(root.get("renewalSubscriptionId"), subscriptionId)));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return PageResponse.of(attempts.findAll(spec, pageable), AttemptResponse::from);
    }

    @Transactional(readOnly = true)
    public List<UpcomingRenewal> upcoming(int days, Long branchId) {
        BranchScope scope = access.scope(branchId);
        LocalDate today = LocalDate.now(clock);
        return subscriptions.findAutopayUpcoming(today.plusDays(days), scope.all(), scope.ids()).stream()
                .map(s -> {
                    SavedPaymentMethod m = methodRepository.findById(s.getAutopayMethodId()).orElse(null);
                    BigDecimal amount = pricing.nextAutopayRenewal(s).amount();   // incl. protection & notice rule
                    return new UpcomingRenewal(s.getId(), s.getMember().getId(), s.getMember().getMemberCode(),
                            s.getMember().fullName(), s.getPlan().getName(), s.getEndDate(), amount,
                            m == null ? null : m.getId(), m == null ? null : m.getLabel(),
                            m != null && m.canAutoDebit(amount, today));
                }).toList();
    }

    // ================================================================== helpers

    private void cancelOpenAttempts(Long subscriptionId, String reason) {
        Instant now = Instant.now(clock);
        attempts.findBySubscriptionAndStatuses(subscriptionId, EnumSet.of(AutopayStatus.SCHEDULED))
                .forEach(a -> a.close(AutopayStatus.CANCELLED, reason, now));
    }

    private PaymentGateway.TokenCharge tokenCharge(AutopayAttempt attempt, Invoice invoice, Member member) {
        SavedPaymentMethod method = attempt.getPaymentMethod();
        return new PaymentGateway.TokenCharge(key(attempt), PaymentGateway.toMinor(invoice.getTotalAmount()),
                invoice.getCurrency(), method.getProviderCustomerId(), method.getProviderToken(), member.getEmail(),
                member.getPhone(), "Membership renewal " + invoice.getInvoiceNumber(), true, attempt.getScheduledAt());
    }

    private static Payment.Source source(SavedPaymentMethod method) {
        return new Payment.Source(method.getProvider(), method.getLabel(), null, method.getId(), true, null);
    }

    private static String key(AutopayAttempt attempt) {
        return "autopay:" + attempt.getId();
    }

    private static String hash(AutopayAttempt attempt) {
        return Hashing.sha256Hex("autopay|" + attempt.getId());
    }

    private String date(Instant instant) {
        return DATE.format(LocalDate.ofInstant(instant, clock.getZone()));
    }

    private Member lockMemberOfSubscription(Long subscriptionId) {
        Long memberId = subscriptions.findMemberIdById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", subscriptionId));
        return memberRepository.findByIdForUpdate(memberId).orElseThrow();
    }

    private Member lockMemberOfAttempt(Long attemptId) {
        Long memberId = attempts.findMemberIdById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Autopay attempt", attemptId));
        return memberRepository.findByIdForUpdate(memberId).orElseThrow();
    }

}
