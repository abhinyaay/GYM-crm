package com.gymops.payment;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.payment.dto.RefundResponse;
import com.gymops.member.MemberRepository;
import com.gymops.payment.credit.CreditEntryType;
import com.gymops.payment.credit.StoreCreditService;
import com.gymops.payment.gateway.PaymentGateway;
import com.gymops.payment.gateway.PaymentGateways;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refunds a settled payment (fully or partially), to the original instrument or to store credit.
 * Locks follow the global order (member, then payment), so a refund can never deadlock with a
 * cancellation or payment for the same member; the payment row lock means two concurrent refunds
 * can never exceed the amount paid, and an optional idempotency key de-duplicates retries.
 */
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final MemberRepository memberRepository;
    private final PaymentGateways gateways;
    private final StoreCreditService storeCredit;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** Staff-initiated refund (goodwill, billing correction); requires access to the payment's branch. */
    @Transactional
    public RefundResponse refund(Long paymentId, BigDecimal amount, String reason, String idempotencyKey,
                                 RefundDestination destination) {
        Long memberId = paymentRepository.findMemberIdById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        memberRepository.findByIdForUpdate(memberId);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        access.assertBranchAccess(payment.getInvoice().getBranch().getId());
        return doRefund(payment, amount, reason, idempotencyKey,
                destination == null ? RefundDestination.ORIGINAL_METHOD : destination);
    }

    /** Refund computed by a business rule (e.g. cancellation policy); authorization done by the caller. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RefundResponse refundForCancellation(Long paymentId, BigDecimal amount, String reason, String idempotencyKey) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        return doRefund(payment, amount, reason, idempotencyKey, RefundDestination.ORIGINAL_METHOD);
    }

    private RefundResponse doRefund(Payment payment, BigDecimal amount, String reason, String idempotencyKey,
                                    RefundDestination requested) {
        Long paymentId = payment.getId();
        // checked under the payment row lock, so concurrent retries with the same key cannot both refund
        if (idempotencyKey != null) {
            var existing = refundRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return RefundResponse.from(existing.get());
            }
        }
        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "A failed payment cannot be refunded");
        }
        payment.applyRefund(amount);   // validates against the refundable balance

        // money paid from store credit can only go back to store credit
        RefundDestination destination = payment.getProvider() == PaymentProvider.INTERNAL
                ? RefundDestination.STORE_CREDIT : requested;
        String reference = null;
        if (destination == RefundDestination.ORIGINAL_METHOD) {
            var gateway = gateways.find(payment.getProvider());
            if (gateway.isPresent()) {
                var result = gateway.get().refund(payment.getGatewayReference(), PaymentGateway.toMinor(amount), idempotencyKey);
                if (!result.success()) {
                    // throwing rolls back applyRefund(); nothing is recorded as refunded
                    throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                            "Refund rejected by payment provider: " + result.failureReason());
                }
                reference = result.reference();
            } else {
                reference = "OFFLINE-REFUND";   // cash / bank transfer handed back at the desk
            }
        }
        payment.getInvoice().applyRefund(amount);
        Refund refund = new Refund(payment, amount, reason, RefundStatus.SUCCEEDED,
                reference, idempotencyKey, CurrentUser.idOrNull(), Instant.now(clock), destination);
        refund.assignCreditNoteNumber("CN-%d-%06d".formatted(java.time.Year.now(clock).getValue(),
                refundRepository.nextCreditNoteSequence()));
        refund = refundRepository.save(refund);
        Long memberId = payment.getMember().getId();
        if (destination == RefundDestination.STORE_CREDIT) {
            refundRepository.flush();
            storeCredit.post(memberId, CreditEntryType.REFUND, amount, "Refund: " + reason, payment.getId(), refund.getId());
        }
        events.publishEvent(new DomainEvents.RefundProcessed(refund.getId(), memberId, amount));
        audit.record(AuditEvent.of("PAYMENT", paymentId, "REFUNDED").member(memberId)
                .branch(payment.getInvoice().getBranch().getId()).with("refundId", refund.getId())
                .with("amount", amount).with("reason", reason).with("destination", destination));
        meterRegistry.counter("gymops.refunds").increment();
        return RefundResponse.from(refund);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> listForPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        access.assertMemberAccess(payment.getMember().getId(), payment.getInvoice().getBranch().getId());
        return refundRepository.findByPaymentIdOrderByProcessedAtAsc(paymentId).stream().map(RefundResponse::from).toList();
    }
}
