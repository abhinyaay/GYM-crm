package com.gymops.payment.dto;

import com.gymops.payment.Invoice;
import com.gymops.payment.InvoiceLineType;
import com.gymops.payment.InvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record InvoiceResponse(
        Long id, String invoiceNumber, Long memberId, String memberCode, String memberName, Long subscriptionId, Long branchId,
        InvoiceStatus status, String currency, List<Line> lines, BigDecimal subtotal, String discountCode,
        BigDecimal discountAmount, BigDecimal taxRate, BigDecimal taxAmount, BigDecimal totalAmount,
        BigDecimal amountPaid, BigDecimal amountRefunded, LocalDate dueDate, boolean overdue,
        Instant issuedAt, Instant paidAt) {

    public record Line(InvoiceLineType type, String description, BigDecimal amount) {
    }

    public static InvoiceResponse from(Invoice i, LocalDate today) {
        return new InvoiceResponse(i.getId(), i.getInvoiceNumber(), i.getMember().getId(), i.getMember().getMemberCode(),
                i.getMember().fullName(),
                i.getSubscription() == null ? null : i.getSubscription().getId(), i.getBranch().getId(),
                i.getStatus(), i.getCurrency(),
                i.getLines().stream().map(l -> new Line(l.getLineType(), l.getDescription(), l.getAmount())).toList(),
                i.getSubtotal(), i.getDiscount() == null ? null : i.getDiscount().getCode(), i.getDiscountAmount(),
                i.getTaxRate(), i.getTaxAmount(), i.getTotalAmount(), i.getAmountPaid(), i.getAmountRefunded(),
                i.getDueDate(), i.isPayable() && i.getDueDate().isBefore(today), i.getIssuedAt(), i.getPaidAt());
    }
}
