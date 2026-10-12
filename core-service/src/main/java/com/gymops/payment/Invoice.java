package com.gymops.payment;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.member.Member;
import com.gymops.subscription.Subscription;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "invoices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invoice extends BaseEntity {

    @Column(name = "invoice_number", nullable = false, unique = true, updatable = false)
    private String invoiceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private GymBranch branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.ISSUED;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discount_id")
    private Discount discount;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "tax_rate", nullable = false)
    private BigDecimal taxRate;

    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "amount_refunded", nullable = false)
    private BigDecimal amountRefunded = BigDecimal.ZERO;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    // --- GST registration captured at issue time (null on invoices issued before V10) ---
    @Column(name = "supplier_gstin")
    private String supplierGstin;

    @Column(name = "supplier_state_code")
    private String supplierStateCode;

    @Column(name = "place_of_supply_code")
    private String placeOfSupplyCode;

    @Column(name = "sac_code")
    private String sacCode;

    @OrderBy("position ASC")
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceLine> lines = new ArrayList<>();

    public Invoice(String invoiceNumber, Member member, Subscription subscription, GymBranch branch, String currency,
                   PricingCalculator.Quote quote, Discount discount, LocalDate dueDate, Instant issuedAt) {
        this.invoiceNumber = invoiceNumber;
        this.member = member;
        this.subscription = subscription;
        this.branch = branch;
        this.currency = currency;
        this.subtotal = quote.subtotal();
        this.discount = discount;
        this.discountAmount = quote.discountAmount();
        this.taxRate = quote.taxRate();
        this.taxAmount = quote.taxAmount();
        this.totalAmount = quote.total();
        this.dueDate = dueDate;
        this.issuedAt = issuedAt;
        int position = 1;
        for (PricingCalculator.Line line : quote.lines()) {
            lines.add(new InvoiceLine(this, position++, line.type(), line.description(), line.amount()));
        }
    }

    /** Snapshot of the tax registration printed on the tax invoice. */
    public void recordTaxRegistration(String gstin, String supplierStateCode, String placeOfSupplyCode, String sacCode) {
        this.supplierGstin = gstin;
        this.supplierStateCode = supplierStateCode;
        this.placeOfSupplyCode = placeOfSupplyCode;
        this.sacCode = sacCode;
    }

    public boolean isPayable() {
        return status == InvoiceStatus.ISSUED;
    }

    public void assertPayable() {
        switch (status) {
            case ISSUED -> { }
            case PAID, PARTIALLY_REFUNDED, REFUNDED ->
                    throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Invoice " + invoiceNumber + " is already paid");
            case VOID -> throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Invoice " + invoiceNumber + " has been voided");
        }
    }

    public void markPaid(BigDecimal amount, Instant now) {
        assertPayable();
        this.amountPaid = amount;
        this.paidAt = now;
        this.status = InvoiceStatus.PAID;
    }

    public void voidInvoice(Instant now) {
        if (status != InvoiceStatus.ISSUED) {
            throw new InvalidStateTransitionException("invoice", status, "void");
        }
        this.status = InvoiceStatus.VOID;
        this.voidedAt = now;
    }

    public void applyRefund(BigDecimal amount) {
        this.amountRefunded = amountRefunded.add(amount);
        this.status = amountRefunded.compareTo(amountPaid) >= 0 ? InvoiceStatus.REFUNDED : InvoiceStatus.PARTIALLY_REFUNDED;
    }
}
