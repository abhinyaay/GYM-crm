package com.gymops.payment;

import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.common.util.Hashing;
import com.gymops.member.Member;
import com.gymops.payment.credit.CreditEntryType;
import com.gymops.payment.credit.StoreCreditService;
import com.gymops.payment.gateway.PaymentGateways;
import com.gymops.payment.method.PaymentMethodService;
import com.gymops.payment.method.SavedPaymentMethod;
import java.math.BigDecimal;
import com.gymops.member.MemberRepository;
import com.gymops.payment.dto.PaymentRequest;
import com.gymops.payment.dto.PaymentResponse;
import com.gymops.payment.gateway.PaymentGateway;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct payments with exactly-once semantics per Idempotency-Key: offline (cash / bank transfer,
 * recorded by staff), store credit, a saved card / UPI handle in one click, or a simulator test
 * token. Other online payments go through hosted checkout ({@link com.gymops.payment.checkout.CheckoutService}).
 *
 * <ol>
 *   <li>Lock the member row, then the invoice row (fixed lock order, shared with subscription
 *       operations, so concurrent requests cannot deadlock).</li>
 *   <li>Under the lock, look up the Idempotency-Key: a retry with the same key and the same request
 *       returns the original result; the same key with a different request is rejected (422).</li>
 *   <li>Validate the invoice is still payable and the amount matches.</li>
 *   <li>Charge; persist SUCCEEDED or FAILED; on success mark the invoice paid and activate the
 *       subscription - all in one transaction ({@link PaymentRecorder}).</li>
 * </ol>
 * Database backstops: UNIQUE(idempotency_key) and a partial unique index allowing only one settled
 * payment per invoice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_:.-]{8,100}$");

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final MemberRepository memberRepository;
    private final PaymentGateways gateways;
    private final PaymentRecorder recorder;
    private final PaymentMethodService paymentMethods;
    private final StoreCreditService storeCredit;
    private final AccessPolicy access;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public record PaymentOutcome(PaymentResponse payment, boolean replayed) {
    }

    @Transactional
    public PaymentOutcome pay(PaymentRequest request, String idempotencyKey) {
        requireValidKey(idempotencyKey);
        String requestHash = Hashing.sha256Hex(request.invoiceId() + "|" + request.amount().stripTrailingZeros().toPlainString()
                + "|" + request.method() + (request.paymentMethodId() == null ? "" : "|pm" + request.paymentMethodId()));

        Long memberId = invoiceRepository.findMemberIdById(request.invoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", request.invoiceId()));
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        Invoice invoice = invoiceRepository.findByIdForUpdate(request.invoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", request.invoiceId()));
        authorize(invoice, request.method());

        var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment previous = existing.get();
            if (!previous.getRequestHash().equals(requestHash)) {
                throw new BusinessRuleException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was already used for a different payment request");
            }
            log.info("Idempotent replay of payment {} for key {}", previous.getId(), idempotencyKey);
            meterRegistry.counter("gymops.payments.replayed").increment();
            return new PaymentOutcome(PaymentResponse.from(previous), true);
        }

        invoice.assertPayable();
        if (request.amount().compareTo(invoice.getTotalAmount()) != 0) {
            throw new BusinessRuleException("Amount must equal the invoice total " + invoice.getTotalAmount()
                    + " (partial payments are not supported)");
        }
        Long callerId = CurrentUser.get().userId();
        Payment payment;
        if (request.paymentMethodId() != null) {
            payment = paySavedMethod(member, invoice, request, idempotencyKey, requestHash, callerId);
        } else {
            payment = switch (request.method()) {
                case CASH, BANK_TRANSFER -> recorder.succeeded(invoice, request.method(), idempotencyKey, requestHash,
                        offlineReference(request), Payment.Source.of(PaymentProvider.OFFLINE,
                                label(request.method()) + (request.externalReference() == null ? "" : " #" + request.externalReference())),
                        callerId);
                case STORE_CREDIT -> payWithStoreCredit(invoice, idempotencyKey, requestHash, callerId);
                case CARD, UPI -> payWithTestToken(invoice, request, idempotencyKey, requestHash, callerId);
                default -> throw new BusinessRuleException(label(request.method())
                        + " payments go through online checkout (POST /api/v1/payments/checkout)");
            };
        }
        return new PaymentOutcome(PaymentResponse.from(payment), false);
    }

    private Payment paySavedMethod(Member member, Invoice invoice, PaymentRequest request, String key, String hash,
                                   Long callerId) {
        SavedPaymentMethod method = paymentMethods.loadForMember(member.getId(), request.paymentMethodId());
        if (method.getType() != request.method()) {
            throw new BusinessRuleException("Saved method " + method.getLabel() + " is " + method.getType()
                    + ", not " + request.method());
        }
        if (method.getProvider() != PaymentProvider.SIMULATED) {
            // Razorpay requires the customer to confirm a saved card (CVV / UPI app) in hosted checkout
            throw new BusinessRuleException("Pay with this saved method through checkout");
        }
        if (method.isExpired(LocalDate.now(clock))) {
            throw new BusinessRuleException(method.getLabel() + " has expired");
        }
        PaymentGateway gateway = gateways.get(method.getProvider());
        PaymentGateway.TokenCharge charge = new PaymentGateway.TokenCharge(key, PaymentGateway.toMinor(invoice.getTotalAmount()),
                invoice.getCurrency(), method.getProviderCustomerId(), method.getProviderToken(), member.getEmail(),
                member.getPhone(), "Invoice " + invoice.getInvoiceNumber(), false, Instant.now(clock));
        PaymentGateway.Order order = gateway.createRecurringOrder(charge);
        PaymentGateway.ChargeResult result = gateway.chargeToken(charge, order.orderId());
        Payment.Source source = new Payment.Source(method.getProvider(), method.getLabel(), null, method.getId(), false, null);
        if (result.state() == PaymentGateway.PaymentState.CAPTURED) {
            return recorder.succeeded(invoice, method.getType(), key, hash, result.paymentId(), source, callerId);
        }
        return recorder.failed(invoice, request.amount(), method.getType(), key, hash, result.paymentId(),
                result.failureReason() == null ? "payment_pending" : result.failureReason(), source, callerId);
    }

    private Payment payWithStoreCredit(Invoice invoice, String key, String hash, Long callerId) {
        Long memberId = invoice.getMember().getId();
        BigDecimal balance = storeCredit.balance(memberId);
        if (balance.compareTo(invoice.getTotalAmount()) < 0) {
            throw new BusinessRuleException("Insufficient store credit: balance ₹" + balance + ", invoice total ₹"
                    + invoice.getTotalAmount());
        }
        Payment payment = recorder.succeeded(invoice, PaymentMethod.STORE_CREDIT, key, hash, null,
                Payment.Source.of(PaymentProvider.INTERNAL, "Store credit"), callerId);
        storeCredit.post(memberId, CreditEntryType.PAYMENT, invoice.getTotalAmount().negate(),
                "Paid invoice " + invoice.getInvoiceNumber(), payment.getId(), null);
        return payment;
    }

    /** Simulator-only direct charge with a test token ({@code tok_*}), used by local tooling and tests. */
    private Payment payWithTestToken(Invoice invoice, PaymentRequest request, String key, String hash, Long callerId) {
        if (gateways.activeProvider() != PaymentProvider.SIMULATED) {
            throw new BusinessRuleException("Card and UPI payments go through online checkout (POST /api/v1/payments/checkout)");
        }
        PaymentGateway.ChargeResult result = gateways.simulated().chargeTestToken(key,
                PaymentGateway.toMinor(request.amount()), invoice.getCurrency(), request.method(), request.paymentToken());
        Payment.Source source = Payment.Source.of(PaymentProvider.SIMULATED, label(request.method()) + " (test token)");
        if (result.state() == PaymentGateway.PaymentState.CAPTURED) {
            return recorder.succeeded(invoice, request.method(), key, hash, result.paymentId(), source, callerId);
        }
        return recorder.failed(invoice, request.amount(), request.method(), key, hash, null, result.failureReason(),
                source, callerId);
    }

    private static String offlineReference(PaymentRequest request) {
        return request.externalReference() != null && !request.externalReference().isBlank()
                ? request.externalReference()
                : request.method().name() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    static String label(PaymentMethod method) {
        return switch (method) {
            case UPI -> "UPI";
            case EMI -> "EMI";
            default -> method.name().charAt(0) + method.name().substring(1).toLowerCase().replace('_', ' ');
        };
    }

    static void requireValidKey(String idempotencyKey) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key header must be 8-100 characters [A-Za-z0-9_:.-]");
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long id) {
        Payment p = paymentRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment", id));
        access.assertMemberAccess(p.getMember().getId(), p.getInvoice().getBranch().getId());
        return PaymentResponse.from(p);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> search(Long memberId, Long branchId, PaymentStatus status, PaymentMethod method,
                                                LocalDate from, LocalDate to, Pageable pageable) {
        UserPrincipal caller = CurrentUser.get();
        Long effectiveMemberId = caller.isMemberOnly() ? caller.memberId() : memberId;
        BranchScope scope = caller.isMemberOnly() ? BranchScope.ALL : access.scope(branchId);
        Specification<Payment> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (effectiveMemberId != null) {
                p.add(cb.equal(root.get("member").get("id"), effectiveMemberId));
            }
            if (!scope.all()) {
                p.add(root.get("invoice").get("branch").get("id").in(scope.ids()));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (method != null) {
                p.add(cb.equal(root.get("method"), method));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("processedAt"), from.atStartOfDay(clock.getZone()).toInstant()));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("processedAt"), to.plusDays(1).atStartOfDay(clock.getZone()).toInstant()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return PageResponse.of(paymentRepository.findAll(spec, pageable), PaymentResponse::from);
    }

    private void authorize(Invoice invoice, PaymentMethod method) {
        UserPrincipal caller = CurrentUser.get();
        access.assertMemberAccess(invoice.getMember().getId(), invoice.getBranch().getId());
        if (caller.isMemberOnly() && PaymentMethod.OFFLINE.contains(method)) {
            throw new ForbiddenException("Members can only pay online; offline payments are recorded by staff");
        }
    }
}
