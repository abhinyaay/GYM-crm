package com.gymops.payment.checkout;

import com.gymops.common.persistence.BaseEntity;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A hosted-checkout session for one invoice: the provider order the browser pays, plus what to do
 * once it is paid (save the method, set up autopay). A failed attempt leaves it open for another
 * try on the same order, as Razorpay Checkout allows.
 */
@Getter
@Entity
@Table(name = "payment_intents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentIntent extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentProvider provider;

    @Column(name = "provider_order_id", nullable = false, updatable = false)
    private String providerOrderId;

    @Column(name = "provider_customer_id", updatable = false)
    private String providerCustomerId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(name = "emi_tenure_months", updatable = false)
    private Integer emiTenureMonths;

    @Column(name = "save_method", nullable = false, updatable = false)
    private boolean saveMethod;

    @Column(name = "setup_autopay", nullable = false, updatable = false)
    private boolean setupAutopay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntentStatus status = IntentStatus.CREATED;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    PaymentIntent(Long invoiceId, Long memberId, PaymentProvider provider, String providerOrderId, String providerCustomerId,
                  BigDecimal amount, String currency, PaymentMethod method, Integer emiTenureMonths, boolean saveMethod,
                  boolean setupAutopay, String idempotencyKey, Instant expiresAt) {
        this.invoiceId = invoiceId;
        this.memberId = memberId;
        this.provider = provider;
        this.providerOrderId = providerOrderId;
        this.providerCustomerId = providerCustomerId;
        this.amount = amount;
        this.currency = currency;
        this.method = method;
        this.emiTenureMonths = emiTenureMonths;
        this.saveMethod = saveMethod;
        this.setupAutopay = setupAutopay;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
    }

    public boolean isOpen() {
        return status == IntentStatus.CREATED;
    }

    void succeeded(Long paymentId) {
        this.status = IntentStatus.SUCCEEDED;
        this.paymentId = paymentId;
        this.failureReason = null;
    }

    void attemptFailed(String reason) {
        this.failureReason = reason == null || reason.length() <= 300 ? reason : reason.substring(0, 300);
    }

    void cancel(String reason) {
        this.status = IntentStatus.CANCELLED;
        attemptFailed(reason);
    }

    void expire() {
        this.status = IntentStatus.EXPIRED;
    }
}
