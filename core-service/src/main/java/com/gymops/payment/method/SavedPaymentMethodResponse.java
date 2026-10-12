package com.gymops.payment.method;

import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record SavedPaymentMethodResponse(Long id, Long memberId, PaymentProvider provider, PaymentMethod type, String label,
                                         String brand, String last4, Short expMonth, Short expYear, String vpaMasked,
                                         boolean isDefault, SavedMethodStatus status, MandateStatus mandateStatus,
                                         BigDecimal mandateMaxAmount, LocalDate mandateExpiresAt, Instant createdAt) {

    public static SavedPaymentMethodResponse from(SavedPaymentMethod m) {
        return new SavedPaymentMethodResponse(m.getId(), m.getMember().getId(), m.getProvider(), m.getType(), m.getLabel(),
                m.getBrand(), m.getLast4(), m.getExpMonth(), m.getExpYear(), m.getVpaMasked(), m.isDefaultMethod(),
                m.getStatus(), m.getMandateStatus(), m.getMandateMaxAmount(), m.getMandateExpiresAt(), m.getCreatedAt());
    }
}
