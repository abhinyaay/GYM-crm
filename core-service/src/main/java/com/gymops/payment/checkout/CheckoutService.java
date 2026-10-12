package com.gymops.payment.checkout;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.common.util.Hashing;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.payment.Invoice;
import com.gymops.payment.InvoiceRepository;
import com.gymops.payment.Payment;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProperties;
import com.gymops.payment.PaymentProvider;
import com.gymops.payment.PaymentRecorder;
import com.gymops.payment.PaymentRepository;
import com.gymops.payment.autopay.AutopayService;
import com.gymops.payment.checkout.CheckoutDtos.CheckoutRequest;
import com.gymops.payment.checkout.CheckoutDtos.CheckoutResponse;
import com.gymops.payment.checkout.CheckoutDtos.CheckoutResult;
import com.gymops.payment.checkout.CheckoutDtos.ConfirmRequest;
import com.gymops.payment.checkout.CheckoutDtos.PaymentConfig;
import com.gymops.payment.checkout.CheckoutDtos.Prefill;
import com.gymops.payment.checkout.CheckoutDtos.SimulateRequest;
import com.gymops.payment.checkout.CheckoutDtos.SimulatedCheckout;
import com.gymops.payment.dto.PaymentResponse;
import com.gymops.payment.gateway.PaymentGateway;
import com.gymops.payment.gateway.PaymentGateway.InvalidSignatureException;
import com.gymops.payment.gateway.PaymentGateway.PaymentState;
import com.gymops.payment.gateway.PaymentGateway.ProviderPayment;
import com.gymops.payment.gateway.PaymentGateways;
import com.gymops.payment.method.PaymentMethodService;
import com.gymops.payment.method.SavedPaymentMethod;
import com.gymops.payment.method.SavedPaymentMethodRepository;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.subscription.Subscription;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hosted checkout (Razorpay Checkout, or the simulator locally):
 * <ol>
 *   <li>{@link #start}: create a provider order for the invoice and a {@link PaymentIntent}; the
 *       browser opens checkout with it. Card details / UPI PIN go only to the provider.</li>
 *   <li>{@link #confirm}: the browser posts back {@code payment_id, order_id, signature}; the HMAC
 *       signature is verified and the payment fetched from the provider before anything is recorded.</li>
 *   <li>The provider's webhook ({@link #settle}) reaches the same code path, so whichever arrives
 *       first records the payment and the other is a no-op (member lock + intent status +
 *       unique (provider, gateway_reference)).</li>
 * </ol>
 * If an invoice is paid twice (e.g. two tabs), the later capture is refunded automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_:.-]{8,100}$");

    private final PaymentIntentRepository intents;
    private final InvoiceRepository invoiceRepository;
    private final MemberRepository memberRepository;
    private final PaymentRepository paymentRepository;
    private final SavedPaymentMethodRepository methodRepository;
    private final PaymentMethodService paymentMethods;
    private final AutopayService autopay;
    private final PaymentGateways gateways;
    private final PaymentRecorder recorder;
    private final AccessPolicy access;
    private final AuditService audit;
    private final PaymentProperties props;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PaymentConfig config() {
        boolean staff = !CurrentUser.get().isMemberOnly();
        return new PaymentConfig(gateways.activeProvider(), gateways.active().clientConfig(),
                List.copyOf(PaymentMethod.ONLINE), staff ? List.of(PaymentMethod.CASH, PaymentMethod.BANK_TRANSFER) : List.of(),
                props.emiTenures(), props.emiMinAmount(), props.mandateMaxAmount(),
                gateways.activeProvider() == PaymentProvider.SIMULATED);
    }

    @Transactional
    public CheckoutResponse start(CheckoutRequest request, String idempotencyKey) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key header must be 8-100 characters [A-Za-z0-9_:.-]");
        }
        Long memberId = invoiceRepository.findMemberIdById(request.invoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", request.invoiceId()));
        Member member = memberRepository.findByIdForUpdate(memberId).orElseThrow();
        Invoice invoice = invoiceRepository.findByIdForUpdate(request.invoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", request.invoiceId()));
        access.assertMemberAccess(member.getId(), invoice.getBranch().getId());

        var existing = intents.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PaymentIntent previous = existing.get();
            if (!previous.getInvoiceId().equals(request.invoiceId()) || previous.getMethod() != request.method()) {
                throw new BusinessRuleException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key was already used for a different checkout");
            }
            return response(previous, invoice, member);
        }

        invoice.assertPayable();
        boolean setupAutopay = request.setupAutopay();
        boolean saveMethod = request.saveMethod() || setupAutopay;
        validateOptions(request, invoice, saveMethod, setupAutopay);

        PaymentGateway gateway = gateways.active();
        // an existing provider customer lets hosted checkout offer the member's saved cards / UPI handles
        String customerId = methodRepository.findCustomerId(member.getId(), gateway.provider()).orElse(null);
        if (saveMethod) {
            customerId = gateway.ensureCustomer(new PaymentGateway.Customer(member.fullName(), member.getEmail(),
                    member.getPhone()), customerId);
        }
        Instant mandateExpiry = LocalDate.now(clock).plusYears(5).atStartOfDay().toInstant(ZoneOffset.UTC);
        PaymentGateway.Order order = gateway.createOrder(new PaymentGateway.OrderRequest(
                PaymentGateway.toMinor(invoice.getTotalAmount()), invoice.getCurrency(), invoice.getInvoiceNumber(),
                request.method(), customerId, saveMethod, PaymentGateway.toMinor(props.mandateMaxAmount()), mandateExpiry,
                Map.of("invoice", invoice.getInvoiceNumber(), "member", member.getMemberCode())));
        PaymentIntent intent = intents.saveAndFlush(new PaymentIntent(invoice.getId(), member.getId(), gateway.provider(),
                order.orderId(), customerId, invoice.getTotalAmount(), invoice.getCurrency(), request.method(),
                request.method() == PaymentMethod.EMI ? request.emiTenureMonths() : null, saveMethod, setupAutopay,
                idempotencyKey, Instant.now(clock).plus(props.checkoutExpiry())));
        audit.record(AuditEvent.of("PAYMENT_INTENT", intent.getId(), "CREATED").member(member.getId())
                .branch(invoice.getBranch().getId()).with("invoiceNumber", invoice.getInvoiceNumber())
                .with("method", request.method()).with("provider", gateway.provider())
                .with("saveMethod", saveMethod).with("setupAutopay", setupAutopay));
        return response(intent, invoice, member);
    }

    private void validateOptions(CheckoutRequest request, Invoice invoice, boolean saveMethod, boolean setupAutopay) {
        PaymentMethod method = request.method();
        if (!PaymentMethod.ONLINE.contains(method)) {
            throw new BusinessRuleException(method + " is not an online payment method");
        }
        if (method == PaymentMethod.EMI) {
            if (request.emiTenureMonths() == null || !props.emiTenures().contains(request.emiTenureMonths())) {
                throw new BusinessRuleException("Choose an EMI tenure: " + props.emiTenures() + " months");
            }
            if (invoice.getTotalAmount().compareTo(props.emiMinAmount()) < 0) {
                throw new BusinessRuleException("EMI is available on invoices of ₹" + props.emiMinAmount() + " or more");
            }
        } else if (request.emiTenureMonths() != null) {
            throw new BusinessRuleException("emiTenureMonths applies only to EMI");
        }
        if (saveMethod && !PaymentMethod.TOKENISABLE.contains(method)) {
            throw new BusinessRuleException("Only cards and UPI can be saved or used for autopay");
        }
        if (setupAutopay) {
            if (invoice.getSubscription() == null) {
                throw new BusinessRuleException("Autopay applies to membership invoices only");
            }
            if (invoice.getTotalAmount().compareTo(props.mandateMaxAmount()) > 0) {
                throw new BusinessRuleException("Autopay is limited to ₹" + props.mandateMaxAmount() + " per debit");
            }
        }
    }

    /** Local / CI only: plays the part of the hosted checkout page and returns what it would post back. */
    @Transactional(readOnly = true)
    public SimulatedCheckout simulate(Long intentId, SimulateRequest request) {
        PaymentIntent intent = load(intentId);
        if (intent.getProvider() != PaymentProvider.SIMULATED) {
            throw new ForbiddenException("Simulated checkout is only available for simulated payments");
        }
        if (!intent.isOpen() || Instant.now(clock).isAfter(intent.getExpiresAt())) {
            throw new BusinessRuleException("This checkout is " + (intent.isOpen() ? "expired" : intent.getStatus())
                    + "; start a new one");
        }
        var result = gateways.simulated().completeCheckout(intent.getProviderOrderId(),
                PaymentGateway.toMinor(intent.getAmount()), request.success(), intent.getMethod(), request.failureReason(),
                intent.isSaveMethod(), request.failFutureDebits(), request.brand(), request.last4(), request.vpa(),
                request.bank());
        return new SimulatedCheckout(result.paymentId(), result.orderId(), result.signature());
    }

    @Transactional
    public CheckoutResult confirm(Long intentId, ConfirmRequest request) {
        PaymentIntent unlocked = load(intentId);
        if (!unlocked.getProviderOrderId().equals(request.providerOrderId())) {
            throw new BusinessRuleException("Order id does not match this checkout");
        }
        ProviderPayment payment;
        try {
            payment = gateways.get(unlocked.getProvider()).verifyCheckout(request.providerOrderId(),
                    request.providerPaymentId(), request.signature());
        } catch (InvalidSignatureException | IllegalArgumentException e) {
            log.warn("Checkout {} verification failed: {}", intentId, e.getMessage());
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Payment verification failed");
        }
        return settle(intentId, payment);
    }

    /**
     * Records the provider's outcome for a checkout (idempotent). Used by both the browser callback
     * and the webhook.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public CheckoutResult settle(Long intentId, ProviderPayment payment) {
        Long memberId = intents.findById(intentId).map(PaymentIntent::getMemberId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout", intentId));
        Member member = memberRepository.findByIdForUpdate(memberId).orElseThrow();
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        Invoice invoice = invoiceRepository.findByIdForUpdate(intent.getInvoiceId()).orElseThrow();

        var recorded = paymentRepository.findByProviderAndGatewayReference(intent.getProvider(), payment.paymentId());
        if (intent.getStatus() == IntentStatus.SUCCEEDED || intent.getStatus() == IntentStatus.CANCELLED
                || recorded.isPresent()) {
            return result(intent, recorded.or(() -> intent.getPaymentId() == null ? java.util.Optional.empty()
                    : paymentRepository.findById(intent.getPaymentId())).orElse(null), "ALREADY_PROCESSED");
        }
        if (payment.amountMinor() != PaymentGateway.toMinor(intent.getAmount())) {
            throw new BusinessRuleException("Paid amount does not match the checkout amount");
        }
        String key = "checkout:" + intent.getId() + ":" + Hashing.sha256Hex(payment.paymentId()).substring(0, 20);
        String hash = Hashing.sha256Hex("checkout|" + intent.getId() + "|" + payment.paymentId());
        PaymentMethod method = payment.method() == null ? intent.getMethod() : payment.method();
        Long callerId = CurrentUser.idOrNull();

        if (payment.state() == PaymentState.PENDING) {
            return result(intent, null, "PENDING");
        }
        if (payment.state() == PaymentState.FAILED) {
            Payment failed = recorder.failed(invoice, intent.getAmount(), method, key, hash, payment.paymentId(),
                    payment.failureReason() == null ? "payment_failed" : payment.failureReason(),
                    new Payment.Source(intent.getProvider(), payment.instrument(), intent.getEmiTenureMonths(), null,
                            false, intent.getId()), callerId);
            intent.attemptFailed(failed.getFailureReason());
            return result(intent, failed, "FAILED");
        }

        // captured
        if (!invoice.isPayable()) {
            var refund = gateways.get(intent.getProvider()).refund(payment.paymentId(), payment.amountMinor(),
                    "dup-" + intent.getId());
            intent.cancel("Invoice was already settled; this payment was refunded automatically");
            audit.record(AuditEvent.of("PAYMENT_INTENT", intent.getId(), "DUPLICATE_REFUNDED").member(memberId)
                    .with("providerPaymentId", payment.paymentId()).with("refunded", refund.success()));
            return result(intent, null, "REFUNDED_DUPLICATE");
        }
        SavedPaymentMethod saved = null;
        if (intent.isSaveMethod() && payment.hasToken()) {
            saved = paymentMethods.saveFromCheckout(member, intent.getProvider(), payment,
                    intent.isSetupAutopay() ? props.mandateMaxAmount() : null,
                    intent.isSetupAutopay() ? LocalDate.now(clock).plusYears(5) : null);
        }
        Payment paid = recorder.succeeded(invoice, method, key, hash, payment.paymentId(),
                new Payment.Source(intent.getProvider(), payment.instrument(), intent.getEmiTenureMonths(),
                        saved == null ? null : saved.getId(), false, intent.getId()), callerId);
        intent.succeeded(paid.getId());
        boolean autopayOn = false;
        Subscription sub = invoice.getSubscription();
        if (intent.isSetupAutopay() && saved != null && sub != null) {
            autopay.enableFromCheckout(sub, saved);
            autopayOn = true;
        }
        return new CheckoutResult(intent.getId(), intent.getStatus(), "SUCCEEDED", PaymentResponse.from(paid), null,
                saved == null ? null : saved.getId(), autopayOn);
    }

    @Transactional(readOnly = true)
    public CheckoutResponse get(Long intentId) {
        PaymentIntent intent = load(intentId);
        Invoice invoice = invoiceRepository.findById(intent.getInvoiceId()).orElseThrow();
        return response(intent, invoice, invoice.getMember());
    }

    private CheckoutResult result(PaymentIntent intent, Payment payment, String outcome) {
        return new CheckoutResult(intent.getId(), intent.getStatus(), outcome,
                payment == null ? null : PaymentResponse.from(payment), intent.getFailureReason(),
                payment == null ? null : payment.getPaymentMethodId(), false);
    }

    private PaymentIntent load(Long intentId) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow(() -> new ResourceNotFoundException("Checkout", intentId));
        Invoice invoice = invoiceRepository.findById(intent.getInvoiceId()).orElseThrow();
        access.assertMemberAccess(intent.getMemberId(), invoice.getBranch().getId());
        return intent;
    }

    private CheckoutResponse response(PaymentIntent intent, Invoice invoice, Member member) {
        return new CheckoutResponse(intent.getId(), intent.getStatus(), intent.getProvider(), intent.getProviderOrderId(),
                PaymentGateway.toMinor(intent.getAmount()), intent.getAmount(), intent.getCurrency(), intent.getMethod(),
                intent.getEmiTenureMonths(), intent.isSaveMethod(), intent.isSetupAutopay(), intent.getProviderCustomerId(),
                intent.isSetupAutopay() ? props.mandateMaxAmount() : null, intent.getExpiresAt(),
                invoice.getInvoiceNumber(), "Invoice " + invoice.getInvoiceNumber(),
                new Prefill(member.fullName(), member.getEmail(), member.getPhone()),
                gateways.get(intent.getProvider()).clientConfig());
    }
}
