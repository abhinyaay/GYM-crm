package com.gymops.payment;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "refunded_amount", nullable = false)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "received_by")
    private Long receivedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentProvider provider;

    /** Display text only, e.g. "Visa •••• 4242" or "UPI as***@okhdfc"; never a full instrument. */
    private String instrument;

    @Column(name = "emi_tenure_months")
    private Integer emiTenureMonths;

    @Column(name = "payment_method_id")
    private Long paymentMethodId;

    @Column(nullable = false)
    private boolean autopay;

    @Column(name = "intent_id")
    private Long intentId;

    /**
     * Where a payment came from: provider, display instrument and links to the saved method /
     * checkout intent / autopay that produced it.
     */
    public record Source(PaymentProvider provider, String instrument, Integer emiTenureMonths, Long paymentMethodId,
                         boolean autopay, Long intentId) {
        public static Source of(PaymentProvider provider, String instrument) {
            return new Source(provider, instrument, null, null, false, null);
        }
    }

    private Payment(Invoice invoice, BigDecimal amount, PaymentMethod method, String idempotencyKey,
                    String requestHash, Instant processedAt, Long receivedBy, Source source) {
        this.invoice = invoice;
        this.member = invoice.getMember();
        this.amount = amount;
        this.currency = invoice.getCurrency();
        this.method = method;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.processedAt = processedAt;
        this.receivedBy = receivedBy;
        this.provider = source.provider();
        this.instrument = source.instrument();
        this.emiTenureMonths = source.emiTenureMonths();
        this.paymentMethodId = source.paymentMethodId();
        this.autopay = source.autopay();
        this.intentId = source.intentId();
    }

    public static Payment succeeded(Invoice invoice, BigDecimal amount, PaymentMethod method, String key, String hash,
                                    String gatewayReference, Instant now, Long receivedBy, Source source) {
        Payment p = new Payment(invoice, amount, method, key, hash, now, receivedBy, source);
        p.status = PaymentStatus.SUCCEEDED;
        p.gatewayReference = gatewayReference;
        return p;
    }

    public static Payment failed(Invoice invoice, BigDecimal amount, PaymentMethod method, String key, String hash,
                                 String gatewayReference, String failureReason, Instant now, Long receivedBy,
                                 Source source) {
        Payment p = new Payment(invoice, amount, method, key, hash, now, receivedBy, source);
        p.gatewayReference = gatewayReference;
        p.status = PaymentStatus.FAILED;
        p.failureReason = failureReason;
        return p;
    }

    public BigDecimal refundableAmount() {
        return status == PaymentStatus.FAILED ? BigDecimal.ZERO : amount.subtract(refundedAmount);
    }

    public void applyRefund(BigDecimal refund) {
        if (refund.signum() <= 0 || refund.compareTo(refundableAmount()) > 0) {
            throw new BusinessRuleException("Refund amount must be between 0.01 and " + refundableAmount());
        }
        refundedAmount = refundedAmount.add(refund);
        status = refundedAmount.compareTo(amount) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }
}
