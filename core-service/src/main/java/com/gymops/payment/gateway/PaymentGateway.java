package com.gymops.payment.gateway;

import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProvider;
import java.time.Instant;
import java.util.Map;

/**
 * Port to an online payment provider (Razorpay in production, a deterministic simulator locally).
 * Amounts cross this boundary in the currency's minor unit (paise), exactly as providers expect.
 * No card number or UPI PIN ever reaches GymOps: the provider's hosted checkout collects them and
 * GymOps only stores the resulting token id and display metadata.
 */
public interface PaymentGateway {

    PaymentProvider provider();

    /** Public client configuration (e.g. Razorpay key id) for the browser checkout. */
    Map<String, Object> clientConfig();

    /** Creates or returns the provider-side customer used for saved tokens. */
    String ensureCustomer(Customer customer, String existingCustomerId);

    /** Creates a provider order to be paid through hosted checkout. */
    Order createOrder(OrderRequest request);

    /**
     * Verifies the checkout signature for {@code orderId|paymentId} and returns the provider's view
     * of the payment.
     *
     * @throws InvalidSignatureException if the signature does not match
     */
    ProviderPayment verifyCheckout(String orderId, String paymentId, String signature);

    ProviderPayment fetchPayment(String paymentId);

    /**
     * Creates the provider order for a token debit. For merchant-initiated debits this also registers
     * the pre-debit notification with the provider, so it must happen at least the notice period
     * before {@link #chargeToken}.
     */
    Order createRecurringOrder(TokenCharge request);

    /** Charges a saved token against an order from {@link #createRecurringOrder}. */
    ChargeResult chargeToken(TokenCharge request, String orderId);

    /** Legacy direct charge with a test token; only the simulator supports it. */
    ChargeResult chargeTestToken(String idempotencyKey, long amountMinor, String currency, PaymentMethod method, String token);

    RefundResult refund(String providerPaymentId, long amountMinor, String idempotencyKey);

    void deleteToken(String customerId, String token);

    /**
     * Verifies a webhook signature over the raw body and parses the event.
     *
     * @throws InvalidSignatureException if the signature does not match
     */
    WebhookEvent parseWebhook(String rawBody, String signature);

    // ------------------------------------------------------------------ value types

    static long toMinor(java.math.BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
    }

    static java.math.BigDecimal fromMinor(long minor) {
        return java.math.BigDecimal.valueOf(minor, 2);
    }

    record Customer(String name, String email, String contact) {
    }

    /**
     * @param recurring            create a reusable token (saved method / autopay mandate)
     * @param mandateMaxAmountMinor maximum single auto-debit for the mandate (recurring only)
     */
    record OrderRequest(long amountMinor, String currency, String receipt, PaymentMethod method, String customerId,
                        boolean recurring, long mandateMaxAmountMinor, Instant mandateExpiresAt, Map<String, String> notes) {
    }

    record Order(String orderId, long amountMinor, String currency) {
    }

    enum PaymentState { CAPTURED, PENDING, FAILED }

    /**
     * Normalised provider payment. {@code token*} fields are present when the payment created a
     * reusable token.
     */
    record ProviderPayment(String paymentId, String orderId, PaymentState state, PaymentMethod method, long amountMinor,
                           String instrument, String failureReason, String customerId, String tokenId, String tokenBrand,
                           String tokenLast4, Integer tokenExpMonth, Integer tokenExpYear, String tokenVpaMasked) {
        public boolean hasToken() {
            return tokenId != null;
        }
    }

    /**
     * @param merchantInitiated autopay (true) vs. customer clicking "pay" with a saved method (false)
     * @param chargeAfter       earliest debit time for merchant-initiated debits; the provider sends the
     *                          pre-debit notification to the customer before it (RBI e-mandate rule)
     */
    record TokenCharge(String idempotencyKey, long amountMinor, String currency, String customerId, String token,
                       String email, String contact, String description, boolean merchantInitiated, Instant chargeAfter) {
    }

    record ChargeResult(PaymentState state, String paymentId, String orderId, String instrument, String failureReason) {
        public static ChargeResult captured(String paymentId, String orderId, String instrument) {
            return new ChargeResult(PaymentState.CAPTURED, paymentId, orderId, instrument, null);
        }

        public static ChargeResult failed(String reason) {
            return new ChargeResult(PaymentState.FAILED, null, null, null, reason);
        }
    }

    record RefundResult(boolean success, String reference, String failureReason) {
    }

    /** Normalised webhook event. {@code payment} is set for payment.* events. */
    record WebhookEvent(String type, ProviderPayment payment, String tokenId, String tokenStatus) {
    }

    class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }

    class UnsupportedOperationByProvider extends RuntimeException {
        public UnsupportedOperationByProvider(String message) {
            super(message);
        }
    }
}
