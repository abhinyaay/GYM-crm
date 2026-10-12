package com.gymops.payment.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only store-credit ledger. Each row carries the running balance, written under the member
 * row lock, and the database rejects any row that would take the balance below zero.
 */
@Getter
@Entity
@Table(name = "member_credit_ledger")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private CreditEntryType entryType;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "payment_id", updatable = false)
    private Long paymentId;

    @Column(name = "refund_id", updatable = false)
    private Long refundId;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    CreditLedgerEntry(Long memberId, CreditEntryType entryType, BigDecimal amount, BigDecimal balanceAfter, String reason,
                      Long paymentId, Long refundId, Long createdBy, Instant createdAt) {
        this.memberId = memberId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }
}
