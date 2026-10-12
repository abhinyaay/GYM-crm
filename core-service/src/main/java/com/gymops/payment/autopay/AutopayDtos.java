package com.gymops.payment.autopay;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class AutopayDtos {

    private AutopayDtos() {
    }

    /** @param paymentMethodId required when enabling; ignored when disabling */
    public record AutopayRequest(@NotNull Boolean enabled, Long paymentMethodId) {
    }

    public record AttemptResponse(Long id, Long subscriptionId, Long renewalSubscriptionId, Long invoiceId,
                                  String invoiceNumber, Long memberId, String memberName, Long paymentMethodId,
                                  String paymentMethodLabel, BigDecimal amount, int attemptNo, Instant scheduledAt,
                                  Instant executedAt, AutopayStatus status, Long paymentId, String failureReason,
                                  Instant preDebitNotifiedAt) {
        static AttemptResponse from(AutopayAttempt a) {
            var invoice = a.getInvoice();
            return new AttemptResponse(a.getId(), a.getSubscriptionId(), a.getRenewalSubscriptionId(), invoice.getId(),
                    invoice.getInvoiceNumber(), invoice.getMember().getId(), invoice.getMember().fullName(),
                    a.getPaymentMethod().getId(), a.getPaymentMethod().getLabel(), invoice.getTotalAmount(),
                    a.getAttemptNo(), a.getScheduledAt(), a.getExecutedAt(), a.getStatus(), a.getPaymentId(),
                    a.getFailureReason(), a.getPreDebitNotifiedAt());
        }
    }

    public record UpcomingRenewal(Long subscriptionId, Long memberId, String memberCode, String memberName,
                                  String planName, LocalDate endDate, BigDecimal planPrice, Long paymentMethodId,
                                  String paymentMethodLabel, boolean methodUsable) {
    }

    public record RunSummary(int renewalsScheduled, int debitsAttempted, int succeeded, int failed, int failures) {
    }
}
