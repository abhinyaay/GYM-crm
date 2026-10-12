package com.gymops.payment.checkout;

import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProvider;
import com.gymops.payment.dto.PaymentResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CheckoutDtos {

    private CheckoutDtos() {
    }

    /**
     * @param saveMethod   keep the card / UPI handle for one-click payments
     * @param setupAutopay also authorise automatic renewal debits (implies saveMethod; CARD or UPI)
     */
    public record CheckoutRequest(@NotNull Long invoiceId, @NotNull PaymentMethod method, Integer emiTenureMonths,
                                  boolean saveMethod, boolean setupAutopay) {
    }

    public record Prefill(String name, String email, String contact) {
    }

    /** Everything the browser needs to open hosted checkout for this intent. */
    public record CheckoutResponse(Long intentId, IntentStatus status, PaymentProvider provider, String orderId,
                                   long amountMinor, BigDecimal amount, String currency, PaymentMethod method,
                                   Integer emiTenureMonths, boolean saveMethod, boolean setupAutopay, String customerId,
                                   BigDecimal mandateMaxAmount, Instant expiresAt, String invoiceNumber,
                                   String description, Prefill prefill, Map<String, Object> client) {
    }

    /** What Razorpay Checkout's success handler returns ({@code razorpay_*} fields). */
    public record ConfirmRequest(@NotBlank @Size(max = 4000) String providerPaymentId,
                                 @NotBlank @Size(max = 100) String providerOrderId,
                                 @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String signature) {
    }

    /** Simulated hosted checkout page: how the "customer" completes payment. */
    public record SimulateRequest(boolean success, @Size(max = 100) String failureReason, boolean failFutureDebits,
                                  @Size(max = 20) String brand, @Pattern(regexp = "^\\d{4}$") String last4,
                                  @Size(max = 100) String vpa, @Size(max = 30) String bank) {
    }

    public record SimulatedCheckout(String providerPaymentId, String providerOrderId, String signature) {
    }

    public record CheckoutResult(Long intentId, IntentStatus status, String outcome, PaymentResponse payment,
                                 String failureReason, Long savedPaymentMethodId, boolean autopayEnabled) {
    }

    /** Payment options available to the current invoice / provider. */
    public record PaymentConfig(PaymentProvider provider, Map<String, Object> client, List<PaymentMethod> onlineMethods,
                                List<PaymentMethod> offlineMethods, List<Integer> emiTenures, BigDecimal emiMinAmount,
                                BigDecimal mandateMaxAmount, boolean testTokensEnabled) {
    }
}
