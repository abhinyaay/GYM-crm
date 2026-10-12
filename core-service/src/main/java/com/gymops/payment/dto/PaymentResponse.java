package com.gymops.payment.dto;

import com.gymops.payment.Payment;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProvider;
import com.gymops.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(Long id, Long invoiceId, String invoiceNumber, Long memberId, String memberCode,
                              String memberName, BigDecimal amount,
                              String currency, PaymentMethod method, PaymentStatus status, String gatewayReference,
                              String failureReason, BigDecimal refundedAmount, Instant processedAt,
                              PaymentProvider provider, String instrument, Integer emiTenureMonths, Long paymentMethodId,
                              boolean autopay) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getInvoice().getId(), p.getInvoice().getInvoiceNumber(),
                p.getMember().getId(), p.getMember().getMemberCode(), p.getMember().fullName(), p.getAmount(),
                p.getCurrency(), p.getMethod(), p.getStatus(),
                p.getGatewayReference(), p.getFailureReason(), p.getRefundedAmount(), p.getProcessedAt(),
                p.getProvider(), p.getInstrument(), p.getEmiTenureMonths(), p.getPaymentMethodId(), p.isAutopay());
    }
}
