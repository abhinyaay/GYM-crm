package com.gymops.payment.autopay;

import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.payment.Invoice;
import com.gymops.payment.method.SavedPaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One scheduled debit of a renewal invoice. Retries are new rows ({@code attemptNo} 2, 3, ...). */
@Getter
@Entity
@Table(name = "autopay_attempts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutopayAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private Long subscriptionId;

    @Column(name = "renewal_subscription_id", nullable = false, updatable = false)
    private Long renewalSubscriptionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", updatable = false)
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_method_id", updatable = false)
    private SavedPaymentMethod paymentMethod;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AutopayStatus status;

    @Column(name = "provider_order_id")
    private String providerOrderId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "pre_debit_notified_at")
    private Instant preDebitNotifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    AutopayAttempt(Long subscriptionId, Long renewalSubscriptionId, Invoice invoice, SavedPaymentMethod paymentMethod,
                   int attemptNo, Instant scheduledAt, Instant now) {
        this.subscriptionId = subscriptionId;
        this.renewalSubscriptionId = renewalSubscriptionId;
        this.invoice = invoice;
        this.paymentMethod = paymentMethod;
        this.attemptNo = attemptNo;
        this.scheduledAt = scheduledAt;
        this.status = AutopayStatus.SCHEDULED;
        this.createdAt = now;
    }

    void orderCreated(String orderId, Instant notifiedAt) {
        this.providerOrderId = orderId;
        this.preDebitNotifiedAt = notifiedAt;
    }

    void processing(Instant now) {
        require(EnumSet.of(AutopayStatus.SCHEDULED), "execute");
        this.status = AutopayStatus.PROCESSING;
        this.executedAt = now;
    }

    void succeeded(Long paymentId) {
        require(EnumSet.of(AutopayStatus.PROCESSING), "succeed");
        this.status = AutopayStatus.SUCCEEDED;
        this.paymentId = paymentId;
        this.failureReason = null;
    }

    void failed(Long paymentId, String reason) {
        require(EnumSet.of(AutopayStatus.PROCESSING), "fail");
        this.status = AutopayStatus.FAILED;
        this.paymentId = paymentId;
        this.failureReason = truncate(reason);
    }

    void close(AutopayStatus finalStatus, String reason, Instant now) {
        require(EnumSet.of(AutopayStatus.SCHEDULED, AutopayStatus.PROCESSING), finalStatus.name().toLowerCase());
        this.status = finalStatus;
        this.failureReason = truncate(reason);
        if (executedAt == null) {
            executedAt = now;
        }
    }

    public boolean isOpen() {
        return status == AutopayStatus.SCHEDULED || status == AutopayStatus.PROCESSING;
    }

    private void require(EnumSet<AutopayStatus> allowed, String action) {
        if (!allowed.contains(status)) {
            throw new InvalidStateTransitionException("autopay attempt", status, action);
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300);
    }
}
