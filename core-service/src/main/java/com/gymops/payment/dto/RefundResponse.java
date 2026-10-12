package com.gymops.payment.dto;

import com.gymops.payment.Refund;
import com.gymops.payment.RefundDestination;
import com.gymops.payment.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponse(Long id, Long paymentId, BigDecimal amount, String reason, RefundStatus status,
                             String gatewayReference, Instant processedAt, RefundDestination destination,
                             String creditNoteNumber) {

    public static RefundResponse from(Refund r) {
        return new RefundResponse(r.getId(), r.getPayment().getId(), r.getAmount(), r.getReason(), r.getStatus(),
                r.getGatewayReference(), r.getProcessedAt(), r.getDestination(), r.getCreditNoteNumber());
    }
}
