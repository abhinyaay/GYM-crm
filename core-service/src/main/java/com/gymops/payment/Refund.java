package com.gymops.payment;

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
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "refunds")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundDestination destination;

    /** GST credit note issued for this refund (CN-yyyy-nnnnnn). */
    @Column(name = "credit_note_number", unique = true, updatable = false)
    private String creditNoteNumber;

    public Refund(Payment payment, BigDecimal amount, String reason, RefundStatus status, String gatewayReference,
                  String idempotencyKey, Long processedBy, Instant processedAt, RefundDestination destination) {
        this.payment = payment;
        this.amount = amount;
        this.reason = reason;
        this.status = status;
        this.gatewayReference = gatewayReference;
        this.idempotencyKey = idempotencyKey;
        this.processedBy = processedBy;
        this.processedAt = processedAt;
        this.destination = destination;
    }

    public void assignCreditNoteNumber(String number) {
        if (this.creditNoteNumber == null) {
            this.creditNoteNumber = number;
        }
    }
}
