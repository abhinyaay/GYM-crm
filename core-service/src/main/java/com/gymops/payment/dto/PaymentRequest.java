package com.gymops.payment.dto;

import com.gymops.payment.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Direct (non-redirect) payment. Online methods without a saved method go through hosted checkout
 * ({@code POST /api/v1/payments/checkout}) instead.
 *
 * @param paymentToken      simulator test token for CARD / UPI (local and CI only)
 * @param externalReference receipt number / bank UTR for offline methods (CASH, BANK_TRANSFER)
 * @param paymentMethodId   saved card / UPI handle to charge in one click
 */
public record PaymentRequest(
        @NotNull Long invoiceId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull PaymentMethod method,
        @Size(max = 200) String paymentToken,
        @Size(max = 100) String externalReference,
        Long paymentMethodId) {

    public PaymentRequest(Long invoiceId, BigDecimal amount, PaymentMethod method, String paymentToken,
                          String externalReference) {
        this(invoiceId, amount, method, paymentToken, externalReference, null);
    }
}
