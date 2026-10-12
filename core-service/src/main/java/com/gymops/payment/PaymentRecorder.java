package com.gymops.payment;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.event.DomainEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place a payment outcome is persisted, whichever channel produced it (counter, saved
 * method, hosted checkout, webhook, autopay). A success settles the invoice and activates what it
 * paid for in the same transaction. Callers hold the member and invoice row locks.
 */
@Component
@RequiredArgsConstructor
public class PaymentRecorder {

    private final PaymentRepository paymentRepository;
    private final PaidInvoiceHandler paidInvoiceHandler;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public Payment succeeded(Invoice invoice, PaymentMethod method, String idempotencyKey, String requestHash,
                             String gatewayReference, Payment.Source source, Long receivedBy) {
        Instant now = Instant.now(clock);
        BigDecimal amount = invoice.getTotalAmount();
        Payment payment = paymentRepository.saveAndFlush(Payment.succeeded(invoice, amount, method, idempotencyKey,
                requestHash, gatewayReference, now, receivedBy, source));
        invoice.markPaid(amount, now);
        paidInvoiceHandler.onInvoicePaid(invoice);
        events.publishEvent(new DomainEvents.PaymentSucceeded(payment.getId(), invoice.getMember().getId()));
        record(payment, invoice);
        return payment;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Payment failed(Invoice invoice, BigDecimal amount, PaymentMethod method, String idempotencyKey,
                          String requestHash, String gatewayReference, String failureReason, Payment.Source source,
                          Long receivedBy) {
        Payment payment = paymentRepository.saveAndFlush(Payment.failed(invoice, amount, method, idempotencyKey,
                requestHash, gatewayReference, failureReason, Instant.now(clock), receivedBy, source));
        events.publishEvent(new DomainEvents.PaymentFailed(payment.getId(), invoice.getMember().getId()));
        record(payment, invoice);
        return payment;
    }

    private void record(Payment payment, Invoice invoice) {
        audit.record(AuditEvent.of("PAYMENT", payment.getId(), payment.getStatus().name())
                .member(invoice.getMember().getId()).branch(invoice.getBranch().getId())
                .with("invoiceNumber", invoice.getInvoiceNumber()).with("amount", payment.getAmount())
                .with("method", payment.getMethod()).with("provider", payment.getProvider())
                .with("instrument", payment.getInstrument()).with("autopay", payment.isAutopay())
                .with("failureReason", payment.getFailureReason()));
        meterRegistry.counter("gymops.payments", "status", payment.getStatus().name(),
                "method", payment.getMethod().name(), "provider", payment.getProvider().name()).increment();
    }
}
