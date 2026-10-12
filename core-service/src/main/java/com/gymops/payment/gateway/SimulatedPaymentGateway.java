package com.gymops.payment.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gymops.payment.PaymentMethod;
import com.gymops.payment.PaymentProperties;
import com.gymops.payment.PaymentProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for Razorpay used in local, CI and demo environments. It is stateless:
 * the outcome of a simulated checkout is encoded inside the payment id, which is HMAC-signed
 * together with the order id exactly like Razorpay signs checkout results, so it cannot be forged
 * and works across any number of application instances. Webhooks use the same JSON shape and
 * signature scheme as Razorpay.
 *
 * <ul>
 *   <li>tokens {@code simtok_fail_*}: saved method whose future charges are declined (dunning demo)</li>
 *   <li>legacy direct test tokens {@code tok_fail*} / {@code tok_insufficient*} are declined</li>
 * </ul>
 */
@Slf4j
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final String PAY_PREFIX = "sim_pay_";
    private static final String DEFAULT_SECRET = "simulated-gateway-test-secret";

    private final ObjectMapper mapper;
    private final String secret;

    public SimulatedPaymentGateway(ObjectMapper mapper, PaymentProperties props) {
        this.mapper = mapper;
        this.secret = props.simulatedSecret() == null || props.simulatedSecret().isBlank() ? DEFAULT_SECRET
                : props.simulatedSecret();
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.SIMULATED;
    }

    @Override
    public Map<String, Object> clientConfig() {
        return Map.of("mode", "simulated");
    }

    @Override
    public String ensureCustomer(Customer customer, String existingCustomerId) {
        return existingCustomerId != null ? existingCustomerId : "sim_cust_" + shortId();
    }

    @Override
    public Order createOrder(OrderRequest request) {
        return new Order("sim_order_" + shortId(), request.amountMinor(), request.currency());
    }

    /** Result of the simulated hosted checkout page: what Razorpay's checkout handler would return. */
    public record SimulatedCheckoutResult(String paymentId, String orderId, String signature) {
    }

    private static final Map<String, String> REASONS = Map.of(
            "d", "card_declined", "i", "insufficient_funds", "a", "authentication_failed", "c", "cancelled_by_user",
            "u", "upi_request_declined");
    private static final Map<String, String> BRANDS = Map.of("V", "Visa", "M", "Mastercard", "R", "RuPay", "A", "Amex");
    private static final String METHODS = "CUNWE";   // CARD, UPI, NET_BANKING, WALLET, EMI

    /**
     * Simulates the customer completing (or failing) hosted checkout. The outcome is encoded in a
     * compact payment id ({@code sim_pay_<rand>_<state><method><token><brand>_<amount36>_<detail>})
     * that the checkout signature binds to the order.
     *
     * @param issueToken       create a reusable token (save method / autopay)
     * @param failFutureDebits token whose later charges will be declined (to demonstrate dunning)
     */
    public SimulatedCheckoutResult completeCheckout(String orderId, long amountMinor, boolean success, PaymentMethod method,
                                                    String failureReason, boolean issueToken, boolean failFutureDebits,
                                                    String brand, String last4, String vpa, String bank) {
        String reasonCode = REASONS.entrySet().stream().filter(e -> e.getValue().equals(failureReason))
                .map(Map.Entry::getKey).findFirst().orElse("d");
        String brandCode = BRANDS.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(brand))
                .map(Map.Entry::getKey).findFirst().orElse("V");
        String detail = switch (method) {
            case CARD, EMI -> last4 != null && last4.matches("\\d{4}") ? last4 : "4242";
            case UPI -> RazorpayJson.maskVpa(vpa == null || !vpa.contains("@") ? "member@okaxis" : vpa);
            case NET_BANKING, WALLET -> (bank == null || bank.isBlank() ? (method == PaymentMethod.WALLET ? "PAYTM" : "HDFC")
                    : bank.replaceAll("[^A-Za-z0-9]", "")).toUpperCase();
            default -> "-";
        };
        if (detail.length() > 40) {
            detail = detail.substring(0, 40);
        }
        String flags = (success ? "c" : reasonCode)
                + METHODS.charAt(List.of(PaymentMethod.CARD, PaymentMethod.UPI, PaymentMethod.NET_BANKING,
                        PaymentMethod.WALLET, PaymentMethod.EMI).indexOf(method))
                + (!success || !issueToken ? "0" : failFutureDebits ? "2" : "1")
                + brandCode;
        String paymentId = PAY_PREFIX + shortId().substring(0, 8) + "_" + flags + "_" + Long.toString(amountMinor, 36)
                + "_" + detail;
        return new SimulatedCheckoutResult(paymentId, orderId, Hmac.sha256Hex(secret, orderId + "|" + paymentId));
    }

    @Override
    public ProviderPayment verifyCheckout(String orderId, String paymentId, String signature) {
        if (!Hmac.matches(Hmac.sha256Hex(secret, orderId + "|" + paymentId), signature)) {
            throw new InvalidSignatureException("Checkout signature mismatch");
        }
        ProviderPayment p = fetchPayment(paymentId);
        return new ProviderPayment(p.paymentId(), orderId, p.state(), p.method(), p.amountMinor(), p.instrument(),
                p.failureReason(), p.customerId(), p.tokenId(), p.tokenBrand(), p.tokenLast4(), p.tokenExpMonth(),
                p.tokenExpYear(), p.tokenVpaMasked());
    }

    @Override
    public ProviderPayment fetchPayment(String paymentId) {
        String[] parts = paymentId.startsWith(PAY_PREFIX) ? paymentId.substring(PAY_PREFIX.length()).split("_", 4) : new String[0];
        if (parts.length != 4 || parts[1].length() != 4) {
            throw new IllegalArgumentException("Unknown simulated payment " + paymentId);
        }
        String rand = parts[0];
        String flags = parts[1];
        boolean captured = flags.charAt(0) == 'c';
        PaymentMethod method = List.of(PaymentMethod.CARD, PaymentMethod.UPI, PaymentMethod.NET_BANKING,
                PaymentMethod.WALLET, PaymentMethod.EMI).get(METHODS.indexOf(flags.charAt(1)));
        char token = flags.charAt(2);
        String brand = BRANDS.getOrDefault(String.valueOf(flags.charAt(3)), "Visa");
        long amount = Long.parseLong(parts[2], 36);
        String detail = parts[3];
        String instrument = switch (method) {
            case CARD -> brand + " •••• " + detail;
            case EMI -> brand + " •••• " + detail + " (EMI)";
            case UPI -> "UPI " + detail;
            case NET_BANKING -> "Net banking " + detail;
            case WALLET -> "Wallet " + detail;
            default -> method.name();
        };
        boolean hasToken = token != '0' && (method == PaymentMethod.CARD || method == PaymentMethod.UPI);
        String tokenId = hasToken ? (token == '2' ? "simtok_fail_" : "simtok_") + rand : null;
        boolean card = method == PaymentMethod.CARD;
        return new ProviderPayment(paymentId, null,
                captured ? PaymentState.CAPTURED : PaymentState.FAILED, method, amount, instrument,
                captured ? null : REASONS.getOrDefault(String.valueOf(flags.charAt(0)), "payment_failed"),
                hasToken ? "sim_cust_" + rand : null, tokenId,
                hasToken && card ? brand : null, hasToken && card ? detail : null,
                hasToken && card ? 12 : null, hasToken && card ? 2030 : null,
                hasToken && method == PaymentMethod.UPI ? detail : null);
    }

    @Override
    public Order createRecurringOrder(TokenCharge request) {
        return new Order("sim_order_" + shortId(), request.amountMinor(), request.currency());
    }

    @Override
    public ChargeResult chargeToken(TokenCharge request, String orderId) {
        if (request.token().startsWith("simtok_fail")) {
            return new ChargeResult(PaymentState.FAILED, "sim_rec_" + shortId(), orderId, null, "card_declined");
        }
        log.info("Simulated token charge {} paise (merchantInitiated={})", request.amountMinor(), request.merchantInitiated());
        return ChargeResult.captured("sim_rec_" + shortId(), orderId, null);
    }

    @Override
    public ChargeResult chargeTestToken(String idempotencyKey, long amountMinor, String currency, PaymentMethod method,
                                        String token) {
        if (token == null || token.isBlank()) {
            return ChargeResult.failed("missing_payment_token");
        }
        if (token.startsWith("tok_fail")) {
            return ChargeResult.failed("card_declined");
        }
        if (token.startsWith("tok_insufficient")) {
            return ChargeResult.failed("insufficient_funds");
        }
        return ChargeResult.captured("SIM-" + UUID.randomUUID(), null, null);
    }

    @Override
    public RefundResult refund(String providerPaymentId, long amountMinor, String idempotencyKey) {
        return new RefundResult(true, "sim_rfnd_" + shortId(), null);
    }

    @Override
    public void deleteToken(String customerId, String token) {
        log.info("Simulated token {} deleted", token.substring(0, Math.min(12, token.length())));
    }

    @Override
    public WebhookEvent parseWebhook(String rawBody, String signature) {
        if (!Hmac.matches(Hmac.sha256Hex(secret, rawBody), signature)) {
            throw new InvalidSignatureException("Webhook signature mismatch");
        }
        try {
            return RazorpayJson.event(mapper.readTree(rawBody));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Malformed webhook body", e);
        }
    }

    /** Signs a webhook body like the provider would (used by tests and the local webhook simulator). */
    public String signWebhook(String rawBody) {
        return Hmac.sha256Hex(secret, rawBody);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}
